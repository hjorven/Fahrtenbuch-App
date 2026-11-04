package de.hjorven.fahrtenbuch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import de.hjorven.fahrtenbuch.data.Anmeldung
import de.hjorven.fahrtenbuch.data.ApiFehler
import de.hjorven.fahrtenbuch.data.Ausgleich
import de.hjorven.fahrtenbuch.data.PbDatum
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import de.hjorven.fahrtenbuch.data.Verbinden
import de.hjorven.fahrtenbuch.data.Plausibel
import de.hjorven.fahrtenbuch.data.Fahrt
import de.hjorven.fahrtenbuch.data.FahrtBearbeitung
import de.hjorven.fahrtenbuch.data.NeueVersion
import de.hjorven.fahrtenbuch.data.Update
import de.hjorven.fahrtenbuch.data.Fahrzeug
import de.hjorven.fahrtenbuch.data.Prefs
import de.hjorven.fahrtenbuch.data.SyncWorker
import de.hjorven.fahrtenbuch.data.Tankvorgang
import de.hjorven.fahrtenbuch.data.neueId
import de.hjorven.fahrtenbuch.location.FahrtStatus
import de.hjorven.fahrtenbuch.location.TripService
import de.hjorven.fahrtenbuch.widget.widgetsAktualisieren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Erhöhen, wenn die Einrichtung neue Schritte bekommt: dann sieht jeder sie einmal. 2 = "Updates installieren". */
private const val EINRICHTUNG_STAND = 2

class HauptViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FahrtenbuchApp
    private val db = app.db
    val prefs = app.prefs

    private fun <T> kotlinx.coroutines.flow.Flow<T>.halten(start: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), start)

    val anmeldung: StateFlow<Anmeldung?> = prefs.anmeldung
    val fahrzeuge = db.fahrzeugDao().aktive().halten(emptyList())
    val alleFahrzeuge = db.fahrzeugDao().alle().halten(emptyList())
    /** Alle Fahrten und Tankungen, auch Test-Einträge (für die Listen, dort markiert). */
    val fahrten = db.fahrtDao().alle().halten(emptyList())
    val tankungen = db.tankDao().alle().halten(emptyList())
    /** Ohne Test-Einträge: für Statistik, Karte, Abrechnung und Vorschläge. */
    val echteFahrten = db.fahrtDao().alle().map { l -> l.filter { !it.test && !it.vorlaeufig } }.halten(emptyList())
    val echteTankungen = db.tankDao().alle().map { l -> l.filter { !it.test } }.halten(emptyList())
    val ausgleiche = db.ausgleichDao().alle().halten(emptyList())
    val laufend = FahrtStatus.aktiv
    val syncStatus = app.sync.status

    private val _lokalerName = MutableStateFlow(prefs.lokalerName)
    val lokalerName: StateFlow<String> = _lokalerName

    fun setLokalerName(name: String) {
        prefs.lokalerName = name.trim().ifBlank { "Ich" }
        _lokalerName.value = prefs.lokalerName
    }

    /** Eigene ID (Server oder "lokal"). */
    val userId: String get() = prefs.userId

    /** Benutzer-ID -> Anzeigename. Der eigene Name kommt aus der Anmeldung bzw. dem lokalen Namen. */
    val namen: StateFlow<Map<String, String>> =
        combine(db.benutzerDao().alle(), prefs.anmeldung, _lokalerName) { liste, a, lokal ->
        val m = liste.associate { it.id to it.name }.toMutableMap()
        if (a != null && a.name.isNotBlank()) m[a.userId] = a.name
        m[Prefs.LOKAL] = lokal
        m.toMap()
    }.halten(emptyMap())

    /**
     * Einrichtung zeigen: beim ersten Start, einmal nach einem Update mit neuen Schritten
     * ([EINRICHTUNG_STAND]) und immer, solange der Standort fehlt (ohne ihn geht nichts).
     */
    private val _einrichtung = MutableStateFlow(
        !prefs.einrichtungFertig || prefs.einrichtungStand < EINRICHTUNG_STAND ||
            androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    )
    val einrichtungZeigen: StateFlow<Boolean> = _einrichtung

    fun einrichtung(zeigen: Boolean) {
        prefs.einrichtungFertig = !zeigen
        if (!zeigen) prefs.einrichtungStand = EINRICHTUNG_STAND
        _einrichtung.value = zeigen
    }

    private val _gewaehlt = MutableStateFlow(prefs.gewaehltesFahrzeug)
    val gewaehlt: StateFlow<String?> = _gewaehlt

    private val _autoStop = MutableStateFlow(prefs.autoStopMinuten)
    val autoStop: StateFlow<Int> = _autoStop

    private val _testModus = MutableStateFlow(prefs.testModus)
    val testModus: StateFlow<Boolean> = _testModus

    fun setTestModus(an: Boolean) {
        prefs.testModus = an
        _testModus.value = an
    }

    private val _testAufnahme = MutableStateFlow(prefs.testAufnahmeMeter)
    val testAufnahme: StateFlow<Int> = _testAufnahme

    fun setTestAufnahme(meter: Int) {
        prefs.testAufnahmeMeter = meter
        _testAufnahme.value = meter
    }

    /** Eigene Test-Fahrten und Test-Tankungen weich löschen. Gibt die Anzahl zurück. */
    suspend fun alleTestLoeschen(): Int {
        val n = db.fahrtDao().testLoeschen(prefs.userId) + db.tankDao().testLoeschen(prefs.userId)
        SyncWorker.jetzt(app)
        return n
    }

    val letzterSync: StateFlow<Long> = app.sync.letzterSync

    private val _update = MutableStateFlow<NeueVersion?>(null)
    /** Neuere APK auf dem Pi, solange der Hinweis nicht weggetippt wurde. */
    val update: StateFlow<NeueVersion?> = _update

    fun updatePruefen(erzwingen: Boolean = false) = viewModelScope.launch {
        val neu = Update.pruefen(app, prefs, erzwingen)
        _update.value = neu
        if (erzwingen) _updateGeprueft.value = if (neu == null) "Die App ist aktuell." else null
    }

    private val _updateGeprueft = MutableStateFlow<String?>(null)
    val updateMeldung: StateFlow<String?> = _updateGeprueft

    /** "Später": nur bis zum nächsten Start der App ausblenden, damit das Update nicht vergessen wird. */
    fun updateAusblenden() {
        _update.value = null
    }

    /** Ein-Klick-Update: null = nichts los, sonst Fortschritt, Hinweis oder Fehler. */
    private val _updateLauf = MutableStateFlow<UpdateLauf?>(null)
    val updateLauf: StateFlow<UpdateLauf?> = _updateLauf

    /** APK laden, prüfen, Installer öffnen. Fehlt die Erlaubnis, wird erst danach gefragt. */
    fun updateInstallieren() {
        val neu = _update.value ?: prefs.updateBekannt() ?: return
        if (_updateLauf.value is UpdateLauf.Laedt) return
        if (laufend.value != null) {
            _updateLauf.value = UpdateLauf.Fehler("Während einer Fahrt geht das nicht. Bitte nach der Fahrt aktualisieren.")
            return
        }
        viewModelScope.launch {
            _updateLauf.value = UpdateLauf.Laedt(0f)
            try {
                val apk = Update.herunterladen(app, neu) { _updateLauf.value = UpdateLauf.Laedt(it) }
                if (!Update.installierenErlaubt(app)) {
                    _updateLauf.value = UpdateLauf.ErlaubnisNoetig
                    return@launch
                }
                _updateLauf.value = null
                Update.installerOeffnen(app, apk)
            } catch (e: Exception) {
                _updateLauf.value = UpdateLauf.Fehler(e.message ?: "Update fehlgeschlagen.")
            }
        }
    }

    /** Zurück aus den Einstellungen: Ist die Erlaubnis jetzt da, gleich weitermachen. */
    fun updateFortsetzen() {
        if (_updateLauf.value != UpdateLauf.ErlaubnisNoetig) return
        if (!Update.installierenErlaubt(app)) return
        val apk = Update.geladen(app)
        _updateLauf.value = null
        if (apk != null) Update.installerOeffnen(app, apk) else updateInstallieren()
    }

    fun updateLaufSchliessen() {
        _updateLauf.value = null
    }

    init {
        updatePruefen()
        if (prefs.anmeldung.value != null) syncJetzt()
        // Offene Fahrt in der Datenbank, aber kein Dienst (App wurde beendet oder ist abgestürzt):
        // Aufzeichnung fortsetzen, damit sie wieder sichtbar ist und beendet werden kann.
        viewModelScope.launch {
            if (FahrtStatus.aktiv.value == null && db.fahrtDao().laufende(prefs.userId) != null) {
                TripService.starten(app)
            }
        }
    }

    // ---------- Anmeldung ----------

    val aktiveAdresse = app.sync.aktiveAdresse

    /**
     * Anmeldung über die Hauptadresse, sonst über die Tailscale-Adresse.
     * Gespeichert wird die Hauptadresse; beim Abgleich wird jedes Mal neu gewählt.
     */
    suspend fun login(server: String, tailscale: String, email: String, passwort: String): String? {
        val haupt = Prefs.normalisieren(server)
        val zweit = Prefs.normalisieren(tailscale)
        val url = listOf(haupt, zweit).filter { it.isNotEmpty() }.distinct()
            .firstOrNull { app.api.erreichbar(it) }
            ?: return "Server nicht erreichbar. Adressen und Verbindung prüfen."
        return try {
            val r = app.api.login(url, email.trim(), passwort)
            prefs.zusammenfuehren = true
            prefs.tailscaleUrl = zweit
            prefs.speichereAnmeldung(Anmeldung(haupt.ifEmpty { url }, r.token, r.userId, r.name, email.trim()))
            prefs.setPasswortAendern(r.passwortAendern)
            SyncWorker.planen(app)
            syncJetzt()
            null
        } catch (e: ApiFehler) {
            if (e.status == 400) "E-Mail oder Passwort falsch. Was tun: Groß- und Kleinschreibung prüfen, sonst am Display \"Passwort vergessen\"." else de.hjorven.fahrtenbuch.data.Fehlertext.aus(e, "Anmelden")
        } catch (e: Exception) {
            "Server nicht erreichbar. Adresse und WLAN prüfen."
        }
    }

    /**
     * Konto mit Einladungscode anlegen bzw. Passwort mit Reset-Code setzen (Roadmap Nr. 1/2,
     * Nachtplan 5c). Danach angemeldet wie nach dem normalen Login. null = ok, sonst Fehlertext.
     */
    suspend fun mitCode(
        server: String, tailscale: String, code: String, reset: Boolean,
        name: String, email: String, passwort: String,
    ): String? {
        val haupt = Prefs.normalisieren(server)
        val zweit = Prefs.normalisieren(tailscale)
        val url = listOf(haupt, zweit, prefs.letzteIpUrl).map { Prefs.normalisieren(it) }.filter { it.isNotEmpty() }.distinct()
            .firstOrNull { app.api.erreichbar(it) }
            ?: return "Server nicht erreichbar. Adressen und Verbindung prüfen."
        return try {
            val r = if (reset) app.api.zuruecksetzen(url, code, passwort)
            else app.api.beitreten(url, code, name.trim(), email.trim(), passwort)
            prefs.zusammenfuehren = true
            prefs.tailscaleUrl = zweit
            val mail = if (reset) prefs.letzteEmail else email.trim()
            prefs.speichereAnmeldung(Anmeldung(haupt.ifEmpty { url }, r.token, r.userId, r.name, mail))
            prefs.setPasswortAendern(false)
            SyncWorker.planen(app)
            syncJetzt()
            null
        } catch (e: ApiFehler) {
            // Texte vom Server: "Code ungültig.", "Code wurde schon benutzt.", "Code ist abgelaufen.", Passwort zu kurz ...
            if (e.status == 400) (e.message ?: "Code oder Eingaben ungültig") else de.hjorven.fahrtenbuch.data.Fehlertext.aus(e, "Beitreten")
        } catch (e: Exception) {
            "Server nicht erreichbar. Adresse und WLAN prüfen."
        }
    }

    /** Einladungs-QR für ein Familienmitglied: Text für den QR-Code, null bei Fehler. */
    suspend fun einladungsText(): String? {
        val a = prefs.anmeldung.value ?: return null
        val url = app.sync.adresseFinden(a) ?: return null
        return try {
            val code = app.api.einladungErstellen(url, a.token)
            Verbinden.bauen(Verbinden.Daten(
                heim = a.serverUrl, tail = prefs.tailscaleUrl,
                dl = Update.quellen(listOf(a.serverUrl)).first().trimEnd('/'), einladung = code,
            ))
        } catch (e: Exception) {
            null
        }
    }

    /** "Problem melden" (Roadmap Nr. 21): mit der Diagnose der letzten Test-Fahrt, falls vorhanden. */
    suspend fun problemMelden(text: String): String? {
        val a = prefs.anmeldung.value ?: return "Nicht angemeldet."
        val url = app.sync.adresseFinden(a) ?: return "Server nicht erreichbar."
        val diagnose = db.fahrtDao().letzteMitDiagnose()?.diagnose.orEmpty()
        return try {
            app.api.berichtSenden(url, a.token, a.userId, text, app.sync.appVersion, app.sync.geraet, diagnose)
            null
        } catch (e: Exception) {
            de.hjorven.fahrtenbuch.data.Fehlertext.aus(e, "Senden")
        }
    }

    // ---------- Termine (Roadmap Nr. 16, nur online) ----------

    suspend fun termineLaden(): Pair<List<Termin>?, String?> {
        val a = prefs.anmeldung.value ?: return null to "Termine gibt es nur mit Server-Anmeldung."
        val url = app.sync.adresseFinden(a) ?: return null to "Server nicht erreichbar, Termine später."
        return try {
            fun JsonObject.s(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            app.api.liste(url, a.token, "termin", "")
                .filter { it.s("erledigt") != "true" && it.s("geloescht") != "true" }
                .map { Termin(it.s("id"), it.s("fahrzeug"), it.s("art"), PbDatum.parse(it.s("faellig")) ?: 0L) }
                .sortedBy { it.faellig } to null
        } catch (e: Exception) {
            null to "Termine nicht lesbar (älterer Server?)."
        }
    }

    suspend fun terminAnlegen(fahrzeug: String, art: String, faellig: Long): String? {
        val a = prefs.anmeldung.value ?: return "Nicht angemeldet."
        val url = app.sync.adresseFinden(a) ?: return "Server nicht erreichbar."
        return try {
            app.api.anlegen(url, a.token, "termin", buildJsonObject {
                put("fahrzeug", fahrzeug); put("art", art); put("faellig", PbDatum.aus(faellig))
            })
            null
        } catch (e: Exception) {
            de.hjorven.fahrtenbuch.data.Fehlertext.aus(e, "Speichern")
        }
    }

    suspend fun terminErledigt(id: String) {
        val a = prefs.anmeldung.value ?: return
        val url = app.sync.adresseFinden(a) ?: return
        try { app.api.aendern(url, a.token, "termin", id, buildJsonObject { put("erledigt", true) }) } catch (_: Exception) {}
    }

    // ---------- Sonstige Kosten (Roadmap Nr. 25, nur online) ----------

    suspend fun kostenLaden(): Pair<List<Kosten>?, String?> {
        val a = prefs.anmeldung.value ?: return null to "Sonstige Kosten gibt es nur mit Server-Anmeldung."
        val url = app.sync.adresseFinden(a) ?: return null to "Server nicht erreichbar, Kosten später."
        return try {
            fun JsonObject.s(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            app.api.liste(url, a.token, "kosten", "")
                .filter { it.s("geloescht") != "true" }
                .map {
                    Kosten(it.s("id"), it.s("fahrzeug"), it.s("art"), PbDatum.parse(it.s("datum")) ?: 0L,
                        it.s("betrag").toDoubleOrNull() ?: 0.0, it.s("notiz"), it.s("test") == "true")
                }
                .sortedByDescending { it.datum } to null
        } catch (e: Exception) {
            null to "Kosten nicht lesbar (älterer Server?)."
        }
    }

    suspend fun kostenAnlegen(fahrzeug: String, art: String, datum: Long, betrag: Double, notiz: String): String? {
        val a = prefs.anmeldung.value ?: return "Nicht angemeldet."
        val url = app.sync.adresseFinden(a) ?: return "Server nicht erreichbar."
        return try {
            app.api.anlegen(url, a.token, "kosten", buildJsonObject {
                put("fahrzeug", fahrzeug); put("art", art); put("datum", PbDatum.aus(datum)); put("betrag", betrag)
                put("notiz", notiz); put("erfasst_von", a.userId); put("bezahlt_von", a.userId); put("test", prefs.testModus)
            })
            null
        } catch (e: Exception) {
            de.hjorven.fahrtenbuch.data.Fehlertext.aus(e, "Speichern")
        }
    }

    suspend fun kostenLoeschen(id: String) {
        val a = prefs.anmeldung.value ?: return
        val url = app.sync.adresseFinden(a) ?: return
        try { app.api.aendern(url, a.token, "kosten", id, buildJsonObject { put("geloescht", true) }) } catch (_: Exception) {}
    }

    // ---------- Reservierungen (Roadmap Nr. 17, nur online) ----------

    suspend fun reservierungenLaden(): Pair<List<Reservierung>?, String?> {
        val a = prefs.anmeldung.value ?: return null to "Reservieren geht nur mit Server-Anmeldung."
        val url = app.sync.adresseFinden(a) ?: return null to "Server nicht erreichbar."
        return try {
            fun JsonObject.s(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val jetzt = System.currentTimeMillis()
            app.api.liste(url, a.token, "reservierung", "")
                .filter { it.s("geloescht") != "true" }
                .map { Reservierung(it.s("id"), it.s("fahrzeug"), it.s("wer"), PbDatum.parse(it.s("von")) ?: 0L, PbDatum.parse(it.s("bis")) ?: 0L) }
                .filter { it.bis > jetzt }
                .sortedBy { it.von } to null
        } catch (e: Exception) {
            null to "Reservierungen nicht lesbar (älterer Server?)."
        }
    }

    suspend fun reservierungAnlegen(fahrzeug: String, von: Long, bis: Long): String? {
        val a = prefs.anmeldung.value ?: return "Nicht angemeldet."
        val url = app.sync.adresseFinden(a) ?: return "Server nicht erreichbar."
        return try {
            app.api.anlegen(url, a.token, "reservierung", buildJsonObject {
                put("fahrzeug", fahrzeug); put("wer", a.userId); put("von", PbDatum.aus(von)); put("bis", PbDatum.aus(bis))
            })
            null
        } catch (e: de.hjorven.fahrtenbuch.data.ApiFehler) {
            e.message ?: "Reservieren ging nicht."
        } catch (e: Exception) {
            "Reservieren ging nicht."
        }
    }

    suspend fun reservierungLoeschen(id: String) {
        val a = prefs.anmeldung.value ?: return
        val url = app.sync.adresseFinden(a) ?: return
        try { app.api.aendern(url, a.token, "reservierung", id, buildJsonObject { put("geloescht", true) }) } catch (_: Exception) {}
    }

    fun abmelden() = prefs.abmelden()

    /** Noch nicht hochgeladene Fahrten und Tankungen (Rückfrage vor dem Abmelden). */
    suspend fun offeneAenderungen(): Int = db.fahrtDao().anzahlDirty() + db.tankDao().anzahlDirty()

    // ---------- Passwort ----------

    /** Hinweis "eigenes Passwort festlegen", solange das Konto noch das Start-Passwort hat. */
    val passwortAendernNoetig = prefs.passwortAendern

    private val _passwortDialog = MutableStateFlow(false)
    val passwortDialog: StateFlow<Boolean> = _passwortDialog
    /** "Später" gilt bis zum nächsten Start der App. */
    private var passwortSpaeter = false

    fun passwortDialog(zeigen: Boolean, spaeter: Boolean = false) {
        if (spaeter) passwortSpaeter = true
        _passwortDialog.value = zeigen
    }

    val passwortHinweisZeigen: Boolean get() = !passwortSpaeter

    /** null = geklappt, sonst Fehlertext für den Dialog. */
    suspend fun passwortSetzen(alt: String, neu: String, wiederholung: String): String? {
        val a = prefs.anmeldung.value ?: return "Nicht mit dem Server verbunden."
        if (neu.length < 8) return "Das neue Passwort braucht mindestens 8 Zeichen."
        if (neu != wiederholung) return "Die beiden neuen Passwörter sind nicht gleich."
        if (neu == alt) return "Das neue Passwort muss sich vom bisherigen unterscheiden."
        val url = app.sync.adresseFinden(a) ?: return "Keine Verbindung zum Heim-Server. Bitte zuhause im WLAN versuchen."
        return try {
            app.api.passwortAendern(url, a.token, a.userId, alt, neu)
            // Das alte Token gilt nach der Änderung nicht mehr: gleich mit dem neuen Passwort anmelden.
            val r = app.api.login(url, a.email, neu)
            prefs.speichereAnmeldung(a.copy(token = r.token))
            prefs.setPasswortAendern(false)
            _passwortDialog.value = false
            null
        } catch (e: ApiFehler) {
            if (e.status == 400) "Das bisherige Passwort stimmt nicht." else de.hjorven.fahrtenbuch.data.Fehlertext.aus(e, "Passwort ändern")
        } catch (e: Exception) {
            "Keine Verbindung zum Heim-Server. Bitte zuhause im WLAN versuchen."
        }
    }

    /** Beide Server-Adressen in den Einstellungen ändern und gleich neu abgleichen. */
    fun adressenSpeichern(haupt: String, tailscale: String) {
        Prefs.normalisieren(haupt).takeIf { it.isNotEmpty() }?.let { prefs.serverUrlAendern(it) }
        prefs.tailscaleUrl = Prefs.normalisieren(tailscale)
        syncJetzt()
    }

    fun syncJetzt() {
        viewModelScope.launch {
            app.sync.sync()
        }
    }

    // ---------- Fahrt ----------

    /** Antippen wählt das Auto, erneutes Antippen hebt die Auswahl wieder auf. */
    fun waehle(fahrzeugId: String) {
        val neu = if (_gewaehlt.value == fahrzeugId) null else fahrzeugId
        prefs.gewaehltesFahrzeug = neu
        _gewaehlt.value = neu
        widgetsAktualisieren(app)
    }

    fun fahrtStarten() {
        val id = _gewaehlt.value ?: return
        TripService.starten(app, id)
    }

    fun fahrtStoppen() = TripService.stoppen(app)

    fun setAutoStop(min: Int) {
        prefs.autoStopMinuten = min
        _autoStop.value = min
    }

    fun fahrtAendern(f: Fahrt, zweck: String, notiz: String) = viewModelScope.launch {
        // Aktuellen Stand lesen: beim automatischen Speichern nach "Zurück" kann f veraltet sein
        // (z. B. gerade gelöscht oder per Tacho geändert), das darf nicht überschrieben werden.
        val jetzt = db.fahrtDao().get(f.id) ?: return@launch
        if (jetzt.geloescht || (jetzt.zweck == zweck && jetzt.notiz == notiz.trim())) return@launch
        db.fahrtDao().upsert(jetzt.copy(zweck = zweck, notiz = notiz.trim(), dirty = true))
        SyncWorker.jetzt(app)
    }

    /**
     * Bearbeitete oder nachgetragene Fahrt speichern. Steigt dadurch der km-Stand des Autos,
     * wird er mitgezogen (sinken kann er über die App nicht, siehe Server-Hook km_stand).
     */
    fun fahrtSpeichern(f: Fahrt) = viewModelScope.launch {
        db.fahrtDao().upsert(f.copy(notiz = f.notiz.trim(), dirty = true))
        if (!f.test) kmStandMitziehen(f.fahrzeugId, f.kmEnde)
        SyncWorker.jetzt(app)
    }

    private suspend fun kmStandMitziehen(fahrzeugId: String, km: Double) {
        val fz = db.fahrzeugDao().get(fahrzeugId) ?: return
        if (km > fz.kmStand) db.fahrzeugDao().upsert(fz.copy(kmStand = km, dirty = true))
    }

    /** Neue Fahrt ohne GPS (vergessen, Handy leer). */
    fun fahrtNachtragen(f: Fahrt) =
        fahrtSpeichern(f.copy(id = neueId(), fahrerId = prefs.userId, aufServer = false, test = prefs.testModus))

    /** Tacho-Stand am Ende der Fahrt eintragen: km_ende wird ersetzt, die GPS-Strecke bleibt zum Vergleich. */
    fun fahrtTacho(f: Fahrt, kmEnde: Double) =
        fahrtSpeichern(f.copy(kmStart = Plausibel.startAusTacho(f.kmStart, kmEnde, f.streckeKm), kmEnde = kmEnde))

    /** Nächste eigene Fahrt mit demselben Auto, die sich anhängen lässt. */
    suspend fun naechsteFahrt(f: Fahrt): Fahrt? {
        val ende = f.ende ?: return null
        return db.fahrtDao().naechste(f.fahrerId, f.fahrzeugId, ende, f.id, f.test)
    }

    fun zusammenlegen(a: Fahrt, b: Fahrt) = viewModelScope.launch {
        db.fahrtDao().upsert(FahrtBearbeitung.zusammenlegen(a, b))
        db.fahrtDao().upsert(b.copy(geloescht = true, dirty = true))
        SyncWorker.jetzt(app)
    }

    /** Teilt die Fahrt, gibt die ID des zweiten Teils zurück. */
    suspend fun teilen(f: Fahrt, anteil: Double, punktIndex: Int?): String {
        val (eins, zwei) = FahrtBearbeitung.teilen(f, anteil, punktIndex, neueId())
        db.fahrtDao().upsert(eins)
        db.fahrtDao().upsert(zwei)
        SyncWorker.jetzt(app)
        return zwei.id
    }

    fun fahrtLoeschen(f: Fahrt) = viewModelScope.launch {
        db.fahrtDao().upsert(f.copy(geloescht = true, dirty = true))
        SyncWorker.jetzt(app)
    }

    fun fahrtBeobachten(id: String) = db.fahrtDao().beobachten(id)

    /** "Ich fahre mit" bzw. austragen (Roadmap Nr. 6, nur online). Gibt einen Fehlertext zurück oder null. */
    suspend fun mitfahren(f: Fahrt, raus: Boolean): String? {
        val a = prefs.anmeldung.value ?: return "Mitfahren geht nur mit Server-Anmeldung."
        val url = app.sync.adresseFinden(a) ?: return de.hjorven.fahrtenbuch.data.Fehlertext.OFFLINE
        return try {
            val liste = app.api.mitfahren(url, a.token, f.id, raus)
            db.fahrtDao().get(f.id)?.let { db.fahrtDao().upsert(it.copy(mitfahrer = liste.joinToString(","))) }
            null
        } catch (e: Exception) {
            de.hjorven.fahrtenbuch.data.Fehlertext.aus(e, "Mitfahren")
        }
    }

    /** Ganze Fahrt an eine andere Person geben (Roadmap Nr. 6). Bestätigt eine vorläufige Fahrt mit. */
    fun fahrerAendern(f: Fahrt, neu: String) = viewModelScope.launch {
        val jetzt = db.fahrtDao().get(f.id) ?: return@launch
        if (!jetzt.aufServer || jetzt.fahrerId != userId) return@launch
        db.fahrtDao().upsert(jetzt.copy(fahrerId = neu, vorlaeufig = false, dirty = true))
        SyncWorker.jetzt(app)
    }

    /** Vorläufige Fahrt bestätigen: zählt ab jetzt mit. */
    fun vorlaeufigBestaetigen(f: Fahrt) = viewModelScope.launch {
        val jetzt = db.fahrtDao().get(f.id) ?: return@launch
        db.fahrtDao().upsert(jetzt.copy(vorlaeufig = false, dirty = true))
        SyncWorker.jetzt(app)
    }

    // ---------- Tanken ----------

    /** Neuer Eintrag ([bisher] = null) oder Änderung eines eigenen Eintrags. */
    fun tankSpeichern(
        fahrzeug: Fahrzeug,
        kmStand: Double,
        liter: Double,
        preisProLiter: Double?,
        gesamt: Double?,
        voll: Boolean,
        tankstelle: String,
        ladeort: String = "",
        bisher: Tankvorgang? = null,
        /** Datum vom Beleg, sonst jetzt (neu) bzw. unverändert. */
        datum: Long? = null,
        /** Neues Belegfoto aus dem Scan (verkleinertes JPEG im Cache). */
        belegFoto: File? = null,
    ) = viewModelScope.launch {
        // Laden zuhause kostet nichts.
        val kostenlos = ladeort == "zuhause"
        val ppl = if (kostenlos) 0.0 else preisProLiter ?: gesamt?.let { it / liter } ?: 0.0
        val sum = if (kostenlos) 0.0 else gesamt ?: (liter * ppl)
        val id = bisher?.id ?: neueId()
        val mitFoto = belegFoto?.takeIf { it.exists() }?.let { f ->
            withContext(Dispatchers.IO) { app.belege.uebernehmen(id, f); f.delete() }
            true
        } ?: false
        db.tankDao().upsert(
            Tankvorgang(
                id = id,
                fahrzeugId = fahrzeug.id,
                fahrerId = bisher?.fahrerId ?: prefs.userId,
                datum = datum ?: bisher?.datum ?: System.currentTimeMillis(),
                kmStand = kmStand,
                liter = liter,
                preisProLiter = ppl,
                gesamtpreis = sum,
                vollgetankt = voll,
                tankstelle = tankstelle.trim(),
                ladeort = ladeort,
                notiz = bisher?.notiz ?: "",
                test = bisher?.test ?: prefs.testModus,
                beleg = bisher?.beleg ?: "",
                belegOffen = mitFoto || (bisher?.belegOffen ?: false),
                aufServer = bisher?.aufServer ?: false,
            )
        )
        val test = bisher?.test ?: prefs.testModus
        if (!test && kmStand > fahrzeug.kmStand) db.fahrzeugDao().upsert(fahrzeug.copy(kmStand = kmStand, dirty = true))
        SyncWorker.jetzt(app)
    }

    /** Belegfoto zum Anzeigen: lokal oder vom Server geladen. */
    suspend fun belegHolen(t: Tankvorgang): File? = app.sync.belegHolen(t)

    /** Gibt es zu diesem Eintrag ein Foto (hier oder auf dem Server)? */
    fun hatBeleg(t: Tankvorgang): Boolean = t.beleg.isNotEmpty() || app.belege.datei(t.id).exists()

    fun tankLoeschen(t: Tankvorgang) = viewModelScope.launch {
        db.tankDao().upsert(t.copy(geloescht = true, dirty = true))
        SyncWorker.jetzt(app)
    }

    // ---------- Ausgleich ----------

    fun ausgleichEintragen(von: String, an: String, betrag: Double, zeitraum: String) = viewModelScope.launch {
        db.ausgleichDao().upsert(
            Ausgleich(neueId(), von, an, Math.round(betrag * 100) / 100.0, zeitraum, System.currentTimeMillis())
        )
        SyncWorker.jetzt(app)
    }

    fun ausgleichLoeschen(a: Ausgleich) = viewModelScope.launch {
        db.ausgleichDao().upsert(a.copy(geloescht = true, dirty = true))
        SyncWorker.jetzt(app)
    }

    // ---------- Fahrzeuge ----------

    fun fahrzeugSpeichern(f: Fahrzeug) = viewModelScope.launch {
        db.fahrzeugDao().upsert(f.copy(dirty = true))
        SyncWorker.jetzt(app)
    }
}

sealed interface UpdateLauf {
    data class Laedt(val anteil: Float) : UpdateLauf
    data object ErlaubnisNoetig : UpdateLauf
    data class Fehler(val text: String) : UpdateLauf
}
