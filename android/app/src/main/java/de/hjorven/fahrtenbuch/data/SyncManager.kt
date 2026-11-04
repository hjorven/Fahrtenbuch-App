package de.hjorven.fahrtenbuch.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface SyncStatus {
    data object Bereit : SyncStatus
    data object Laeuft : SyncStatus
    /** [abgelaufen]: Server lehnt das Token ab (z. B. Passwort woanders geändert), neu anmelden. */
    data class Fehler(val text: String, val abgelaufen: Boolean = false) : SyncStatus
    /** Weder Haupt- noch Tailscale-Adresse antworten. */
    data object Offline : SyncStatus
}

/**
 * Offline-first-Abgleich mit PocketBase.
 *
 * 1. Hochladen: alle lokal geänderten Datensätze (dirty = true).
 * 2. Abholen: alles, was auf dem Server seit dem letzten Stand geändert wurde.
 *
 * Lokal noch nicht hochgeladene Änderungen werden beim Abholen nicht überschrieben.
 */
class SyncManager(
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val api: PocketBaseApi,
    private val belege: Belege,
) {
    private val mutex = Mutex()
    /** App-Version und Gerät für die Geräte-Übersicht (Roadmap Nr. 15), setzt FahrtenbuchApp. */
    var appVersion: String = ""
    var geraet: String = ""

    /** Gründe, aus denen der Server Einträge im laufenden Abgleich abgelehnt hat (HTTP 400). */
    private val abgelehnt = mutableListOf<String>()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Bereit)
    val status: StateFlow<SyncStatus> = _status

    /** Zeitpunkt des letzten erfolgreichen Abgleichs, auch aus dem Hintergrund (SyncWorker). */
    private val _letzterSync = MutableStateFlow(prefs.letzterSync)
    val letzterSync: StateFlow<Long> = _letzterSync

    /** Adresse, über die der letzte Abgleich lief (Hauptadresse oder Tailscale). */
    private val _aktiveAdresse = MutableStateFlow<String?>(null)
    val aktiveAdresse: StateFlow<String?> = _aktiveAdresse

    /** Erst die Hauptadresse (Heimnetz), dann Tailscale. Null, wenn keine antwortet. */
    suspend fun adresseFinden(a: Anmeldung): String? {
        val kandidaten = listOf(a.serverUrl, prefs.letzteIpUrl, prefs.tailscaleUrl)
            .map { Prefs.normalisieren(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        val url = kandidaten.firstOrNull { api.erreichbar(it) } ?: return null
        if (url == Prefs.normalisieren(a.serverUrl)) ipMerken(url)
        return url
    }

    /**
     * Fehlende Ersatzadressen vom Server übernehmen (Tailscale für unterwegs, IP falls .local hakt).
     * Eingetragene Adressen bleiben, wie sie sind. Ältere Server ohne den Hook: einfach nichts.
     */
    private suspend fun adressenErgaenzen(url: String, token: String) {
        if (prefs.tailscaleUrl.isNotBlank() && prefs.letzteIpUrl.isNotBlank()) return
        try {
            val i = api.info(url, token)
            fun feld(n: String) = (i[n] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            if (prefs.tailscaleUrl.isBlank()) {
                val t = feld("tailscale_ip").ifBlank { feld("tailscale") }
                if (t.isNotBlank()) prefs.tailscaleUrl = Prefs.normalisieren(t)
            }
            if (prefs.letzteIpUrl.isBlank() && feld("heimnetz_ip").isNotBlank()) {
                prefs.letzteIpUrl = Prefs.normalisieren(feld("heimnetz_ip"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Version, Gerät und Sync-Zeit am eigenen Konto eintragen (Roadmap Nr. 15), höchstens einmal
     * am Tag oder nach einem App-Update. Ältere Server ohne die Felder: einfach nichts.
     */
    private suspend fun geraetMelden(url: String, token: String, userId: String) {
        if (appVersion.isEmpty() || userId == Prefs.LOKAL) return
        val jetzt = System.currentTimeMillis()
        if (prefs.geraetGemeldetVersion == appVersion && jetzt - prefs.geraetGemeldetUm < 24 * 3600_000L) return
        try {
            api.aendern(url, token, "users", userId, buildJsonObject {
                put("app_version", appVersion)
                put("geraet", geraet)
                put("letzter_sync", PbDatum.aus(jetzt))
            })
            prefs.geraetGemeldetVersion = appVersion
            prefs.geraetGemeldetUm = jetzt
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Zu einer erreichbaren .local-Adresse die IP merken (Ersatz, wenn mDNS mal nicht auflöst). */
    private suspend fun ipMerken(url: String) = withContext(Dispatchers.IO) {
        try {
            val u = java.net.URI(url)
            if (u.host?.endsWith(".local") != true) return@withContext
            val ip = java.net.InetAddress.getByName(u.host) as? java.net.Inet4Address ?: return@withContext
            val neu = "${u.scheme}://${ip.hostAddress}${if (u.port > 0) ":${u.port}" else ""}"
            if (neu != prefs.letzteIpUrl) prefs.letzteIpUrl = neu
        } catch (_: Exception) {
        }
    }

    suspend fun sync(): Boolean = mutex.withLock {
        val a = prefs.anmeldung.value ?: return false
        _status.value = SyncStatus.Laeuft
        val url = adresseFinden(a)
        _aktiveAdresse.value = url
        if (url == null) {
            _status.value = SyncStatus.Offline
            return false
        }
        abgelehnt.clear()
        return try {
            val token = tokenPruefen(a, url)
            adressenErgaenzen(url, token)
            if (prefs.zusammenfuehren) lokaleUebernehmen(url, token, a.userId)
            hochladen(url, token)
            abholen(url, token)
            prefs.letzterSync = System.currentTimeMillis()
            _letzterSync.value = prefs.letzterSync
            geraetMelden(url, token, a.userId)
            _status.value = if (abgelehnt.isEmpty()) SyncStatus.Bereit else SyncStatus.Fehler(
                "${abgelehnt.size} ${if (abgelehnt.size == 1) "Eintrag" else "Einträge"} vom Server abgelehnt: " +
                    "${abgelehnt.first()} Bitte den Eintrag prüfen, er bleibt auf dem Handy."
            )
            true
        } catch (e: ApiFehler) {
            if (e.status == 401) {
                _status.value = SyncStatus.Fehler("Anmeldung abgelaufen. Tippen, um dich neu anzumelden.", abgelaufen = true)
            } else {
                _status.value = SyncStatus.Fehler(Fehlertext.aus(e, "Abgleichen"))
            }
            false
        } catch (e: CancellationException) {
            _status.value = SyncStatus.Bereit
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync fehlgeschlagen", e)
            _status.value = SyncStatus.Offline
            false
        }
    }

    /**
     * Belegfoto eines Tankvorgangs: lokal, sonst vom Server laden (z. B. Foto vom Handy des Bens).
     * Null, wenn es keins gibt oder der Server nicht erreichbar ist.
     */
    suspend fun belegHolen(t: Tankvorgang): java.io.File? {
        val datei = belege.datei(t.id)
        if (datei.exists()) return datei
        if (t.beleg.isEmpty()) return null
        val a = prefs.anmeldung.value ?: return null
        val url = adresseFinden(a) ?: return null
        return try {
            api.belegLaden(url, a.token, t.id, t.beleg, datei)
            datei.takeIf { it.exists() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Beleg ${t.id} nicht geladen", e)
            null
        }
    }

    private suspend fun tokenPruefen(a: Anmeldung, url: String): String {
        return try {
            val (neu, aendern) = api.tokenErneuern(url, a.token)
            if (neu != a.token) prefs.speichereAnmeldung(a.copy(token = neu))
            prefs.setPasswortAendern(aendern)
            neu
        } catch (e: ApiFehler) {
            if (e.status == 401) throw e
            a.token
        }
    }

    /**
     * Erste Verbindung nach dem lokalen Modus: eigene Fahrten und Tankvorgänge dem Konto zuordnen
     * und die lokal angelegten Autos mit den gleichnamigen Autos auf dem Server zusammenlegen.
     */
    private suspend fun lokaleUebernehmen(url: String, token: String, userId: String) {
        db.fahrtDao().fahrerTauschen(Prefs.LOKAL, userId)
        db.tankDao().fahrerTauschen(Prefs.LOKAL, userId)
        db.ausgleichDao().vonTauschen(Prefs.LOKAL, userId)
        db.ausgleichDao().anTauschen(Prefs.LOKAL, userId)
        val server = api.liste(url, token, "fahrzeug", "").map { it.toFahrzeug() }
        for (lokal in db.fahrzeugDao().lokale()) {
            val schluessel = Antrieb.namensSchluessel(lokal.name)
            val treffer = server.firstOrNull { Antrieb.namensSchluessel(it.name) == schluessel }
            if (treffer == null) {
                val benutzt = db.fahrtDao().anzahlMitAuto(lokal.id) + db.tankDao().anzahlMitAuto(lokal.id)
                if (Antrieb.unbenutztesStartauto(lokal.name, lokal.kmStand, benutzt, server.isNotEmpty())) {
                    // Unbenutztes Startauto ("Kombi", "Bus", "E-Auto"): der Server hat die echten Autos, nicht doppelt anlegen.
                    db.fahrzeugDao().loeschen(lokal.id)
                    if (prefs.gewaehltesFahrzeug == lokal.id) prefs.gewaehltesFahrzeug = server.firstOrNull { it.aktiv }?.id
                } else {
                    // Gibt es auf dem Server nicht: wird beim Hochladen neu angelegt.
                    db.fahrzeugDao().upsert(lokal.copy(dirty = true))
                }
                continue
            }
            db.fahrtDao().fahrzeugTauschen(lokal.id, treffer.id)
            db.tankDao().fahrzeugTauschen(lokal.id, treffer.id)
            val km = maxOf(lokal.kmStand, treffer.kmStand)
            db.fahrzeugDao().upsert(treffer.copy(kmStand = km, dirty = km > treffer.kmStand))
            db.fahrzeugDao().loeschen(lokal.id)
            if (prefs.gewaehltesFahrzeug == lokal.id) prefs.gewaehltesFahrzeug = treffer.id
        }
        prefs.zusammenfuehren = false
    }

    // ---------- Hochladen ----------

    private suspend fun hochladen(url: String, token: String) {
        for (f in db.fahrzeugDao().dirty()) {
            if (push(url, token, "fahrzeug", f.id, f.aufServer, f.toJson())) {
                // Z. B. km-Stand durch ein Fahrtende während des Uploads geändert: nicht überschreiben.
                val aktuell = db.fahrzeugDao().get(f.id)
                if (aktuell == f) db.fahrzeugDao().upsert(f.copy(dirty = false, aufServer = true))
                else if (aktuell != null) db.fahrzeugDao().upsert(aktuell.copy(aufServer = true))
            }
        }
        for (f in db.fahrtDao().dirty()) {
            if (push(url, token, "fahrt", f.id, f.aufServer, f.toJson())) {
                // Nur sauber markieren, wenn sich der Datensatz während des Uploads nicht geändert hat.
                val aktuell = db.fahrtDao().get(f.id)
                if (aktuell == f) db.fahrtDao().upsert(f.copy(dirty = false, aufServer = true))
                else if (aktuell != null) db.fahrtDao().upsert(aktuell.copy(aufServer = true))
            }
        }
        for (t in db.tankDao().dirty()) {
            if (push(url, token, "tankvorgang", t.id, t.aufServer, t.toJson())) {
                val aktuell = db.tankDao().get(t.id)
                if (aktuell == t) db.tankDao().upsert(t.copy(dirty = false, aufServer = true))
                else if (aktuell != null) db.tankDao().upsert(aktuell.copy(aufServer = true))
            }
        }
        belegeHochladen(url, token)
        for (a in db.ausgleichDao().dirty()) {
            // Ausgleich mit jemandem, der nur lokal existiert, kann der Server nicht zuordnen.
            if (a.von == Prefs.LOKAL || a.an == Prefs.LOKAL) continue
            if (push(url, token, "ausgleich", a.id, a.aufServer, a.toJson())) {
                val aktuell = db.ausgleichDao().get(a.id)
                if (aktuell == a) db.ausgleichDao().upsert(a.copy(dirty = false, aufServer = true))
                else if (aktuell != null) db.ausgleichDao().upsert(aktuell.copy(aufServer = true))
            }
        }
    }

    /** Belegfotos nach dem Datensatz hochladen. Fehler hier halten den restlichen Abgleich nicht auf. */
    private suspend fun belegeHochladen(url: String, token: String) {
        for (t in db.tankDao().belegOffen()) {
            val datei = belege.datei(t.id)
            if (!datei.exists()) {
                db.tankDao().belegHochgeladen(t.id, t.beleg)
                continue
            }
            try {
                val antwort = api.belegHochladen(url, token, t.id, datei)
                // Server ohne Migration 1762600000_beleg kennt das Feld nicht: offen lassen, später nochmal.
                val name = antwort.s("beleg")
                if (name.isNotEmpty()) db.tankDao().belegHochgeladen(t.id, name)
            } catch (e: ApiFehler) {
                if (e.status == 401) throw e
                Log.w(TAG, "Beleg ${t.id} nicht hochgeladen: ${e.message}")
                // Datensatz gehört jemand anderem oder gibt es nicht: nicht ewig wiederholen.
                if (e.status in listOf(403, 404)) db.tankDao().belegHochgeladen(t.id, t.beleg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Beleg ${t.id} nicht hochgeladen", e)
            }
        }
    }

    /** true = erledigt (hochgeladen oder dauerhaft abgelehnt), false = später nochmal versuchen. */
    private suspend fun push(url: String, token: String, col: String, id: String, aufServer: Boolean, body: JsonObject): Boolean {
        return try {
            if (aufServer) {
                api.aendern(url, token, col, id, body)
            } else {
                try {
                    api.anlegen(url, token, col, buildJsonObject {
                        put("id", id)
                        body.forEach { (k, v) -> put(k, v) }
                    })
                } catch (e: ApiFehler) {
                    // Existiert evtl. schon (Antwort beim letzten Mal verloren gegangen).
                    if (e.status != 400) throw e
                    try {
                        api.aendern(url, token, col, id, body)
                    } catch (e2: ApiFehler) {
                        // Gibt es nicht und Anlegen ging auch nicht, z. B. weil das Auto noch nicht hochgeladen ist.
                        // Nicht als erledigt markieren, sonst kommt der Datensatz nie auf den Server.
                        if (e2.status == 404) return false
                        throw e2
                    }
                }
            }
            true
        } catch (e: ApiFehler) {
            when (e.status) {
                401 -> throw e
                400 -> {
                    // Nicht still als erledigt markieren (sonst fehlt der Eintrag unbemerkt auf dem Server):
                    // bleibt zum Ändern auf dem Handy, der Grund steht im Sync-Status.
                    Log.w(TAG, "$col/$id vom Server abgelehnt: ${e.message}")
                    abgelehnt += e.message ?: "ungültige Werte"
                    false
                }
                403, 404 -> {
                    Log.w(TAG, "$col/$id vom Server abgelehnt: ${e.message}")
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Abholen ----------

    private suspend fun abholen(url: String, token: String) {
        abholenCollection(url, token, "users") { o ->
            db.benutzerDao().upsert(Benutzer(o.s("id"), o.s("name").ifBlank { o.s("email") }))
        }
        abholenCollection(url, token, "fahrzeug") { o ->
            val lokal = db.fahrzeugDao().get(o.s("id"))
            if (lokal?.dirty != true) db.fahrzeugDao().upsert(o.toFahrzeug())
        }
        abholenCollection(url, token, "fahrt") { o ->
            val lokal = db.fahrtDao().get(o.s("id"))
            if (lokal?.dirty != true) db.fahrtDao().upsert(o.toFahrt())
        }
        abholenCollection(url, token, "tankvorgang") { o ->
            val lokal = db.tankDao().get(o.s("id"))
            if (lokal?.dirty != true) {
                val neu = o.toTank()
                // Ein noch nicht hochgeladenes Foto bleibt offen, ein ersetztes Foto wird neu geladen.
                if (lokal != null && lokal.beleg != neu.beleg && !lokal.belegOffen) belege.datei(neu.id).delete()
                db.tankDao().upsert(neu.copy(belegOffen = lokal?.belegOffen ?: false))
            }
        }
        abholenCollection(url, token, "ausgleich") { o ->
            val lokal = db.ausgleichDao().get(o.s("id"))
            if (lokal?.dirty != true) db.ausgleichDao().upsert(o.toAusgleich())
        }
    }

    private suspend fun abholenCollection(url: String, token: String, col: String, speichern: suspend (JsonObject) -> Unit) {
        val seit = prefs.syncStand(col)
        val items = try {
            api.liste(url, token, col, seit)
        } catch (e: ApiFehler) {
            // users: ohne angepasste Regel sieht jeder nur sich selbst. Kein Grund abzubrechen.
            // ausgleich: Server ohne die neue Migration kennt die Tabelle noch nicht.
            if (col in listOf("users", "ausgleich") && e.status in listOf(400, 403, 404)) return else throw e
        }
        var maxUpdated = seit
        for (o in items) {
            speichern(o)
            val u = o.s("updated")
            if (u > maxUpdated) maxUpdated = u
        }
        if (maxUpdated != seit) prefs.setSyncStand(col, maxUpdated)
    }

    companion object {
        private const val TAG = "Sync"
    }
}

// ---------- Umwandlung JSON <-> Entities ----------

private fun JsonObject.s(key: String): String = this[key]?.jsonPrimitive?.content ?: ""
private fun JsonObject.d(key: String): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
private fun JsonObject.b(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false

/**
 * Auf die Feldlänge des Servers kürzen (in Zeichen wie PocketBase, Emojis nicht zerschneiden).
 * Ein zu langer Text ließe sonst den ganzen Datensatz mit 400 abprallen.
 */
internal fun String.hoechstens(n: Int): String =
    if (codePointCount(0, length) <= n) this else substring(0, offsetByCodePoints(0, n))

private fun Fahrzeug.toJson() = buildJsonObject {
    put("name", name.hoechstens(60))
    put("kennzeichen", kennzeichen.hoechstens(15))
    if (kraftstoff.isNotEmpty()) put("kraftstoff", kraftstoff)
    put("km_stand", kmStand)
    put("preis_pro_km", preisProKm)
    put("farbe", farbe)
    put("aktiv", aktiv)
}

private fun JsonObject.toFahrzeug() = Fahrzeug(
    id = s("id"),
    name = s("name"),
    kennzeichen = s("kennzeichen"),
    kraftstoff = s("kraftstoff"),
    kmStand = d("km_stand"),
    preisProKm = d("preis_pro_km"),
    farbe = s("farbe").ifBlank { "#1565C0" },
    aktiv = b("aktiv"),
    dirty = false,
    aufServer = true,
)

private fun Fahrt.toJson() = buildJsonObject {
    put("fahrzeug", fahrzeugId)
    put("fahrer", fahrerId)
    put("start", PbDatum.aus(start))
    put("ende", PbDatum.aus(ende))
    put("km_start", kmStart)
    put("km_ende", kmEnde)
    put("strecke_km", streckeKm)
    put("max_kmh", maxKmh)
    put("schnitt_kmh", schnittKmh)
    put("zweck", zweck)
    put("start_ort", startOrt.hoechstens(200))
    put("ziel_ort", zielOrt.hoechstens(200))
    put("route", route)
    put("notiz", notiz.hoechstens(2000))
    put("geloescht", geloescht)
    put("test", test)
    // mitfahrer nie mitschicken: das setzt nur der Server-Hook, sonst überschreibt ein alter Stand.
    put("vorlaeufig", vorlaeufig)
    if (diagnose.isNotBlank()) {
        try { put("diagnose", Json.parseToJsonElement(diagnose)) } catch (_: Exception) {}
    }
}

private fun JsonObject.toFahrt() = Fahrt(
    id = s("id"),
    fahrzeugId = s("fahrzeug"),
    fahrerId = s("fahrer"),
    start = PbDatum.parse(s("start")) ?: 0L,
    ende = PbDatum.parse(s("ende")),
    kmStart = d("km_start"),
    kmEnde = d("km_ende"),
    streckeKm = d("strecke_km"),
    maxKmh = d("max_kmh"),
    schnittKmh = d("schnitt_kmh"),
    zweck = s("zweck").ifBlank { "privat" },
    startOrt = s("start_ort"),
    zielOrt = s("ziel_ort"),
    route = s("route"),
    notiz = s("notiz"),
    geloescht = b("geloescht"),
    test = b("test"),
    diagnose = (this["diagnose"] as? JsonObject)?.toString() ?: "",
    dirty = false,
    aufServer = true,
    mitfahrer = (this["mitfahrer"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.content.takeIf { c -> c.isNotBlank() } }?.joinToString(",") ?: "",
    vorlaeufig = b("vorlaeufig"),
)

private fun Tankvorgang.toJson() = buildJsonObject {
    put("fahrzeug", fahrzeugId)
    put("fahrer", fahrerId)
    put("datum", PbDatum.aus(datum))
    put("km_stand", kmStand)
    put("liter", liter)
    put("preis_pro_liter", preisProLiter)
    put("gesamtpreis", gesamtpreis)
    put("vollgetankt", vollgetankt)
    put("tankstelle", tankstelle.hoechstens(200))
    if (ladeort.isNotEmpty()) put("ladeort", ladeort)
    put("notiz", notiz.hoechstens(2000))
    put("geloescht", geloescht)
    put("test", test)
}

private fun JsonObject.toTank() = Tankvorgang(
    id = s("id"),
    fahrzeugId = s("fahrzeug"),
    fahrerId = s("fahrer"),
    datum = PbDatum.parse(s("datum")) ?: 0L,
    kmStand = d("km_stand"),
    liter = d("liter"),
    preisProLiter = d("preis_pro_liter"),
    gesamtpreis = d("gesamtpreis"),
    vollgetankt = b("vollgetankt"),
    tankstelle = s("tankstelle"),
    ladeort = s("ladeort"),
    notiz = s("notiz"),
    geloescht = b("geloescht"),
    test = b("test"),
    beleg = s("beleg"),
    dirty = false,
    aufServer = true,
)

private fun Ausgleich.toJson() = buildJsonObject {
    put("von", von)
    put("an", an)
    put("betrag", betrag)
    put("zeitraum", zeitraum)
    put("datum", PbDatum.aus(datum))
    put("notiz", notiz.hoechstens(2000))
    put("geloescht", geloescht)
}

private fun JsonObject.toAusgleich() = Ausgleich(
    id = s("id"),
    von = s("von"),
    an = s("an"),
    betrag = d("betrag"),
    zeitraum = s("zeitraum"),
    datum = PbDatum.parse(s("datum")) ?: 0L,
    notiz = s("notiz"),
    geloescht = b("geloescht"),
    dirty = false,
    aufServer = true,
)
