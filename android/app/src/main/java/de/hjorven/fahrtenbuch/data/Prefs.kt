package de.hjorven.fahrtenbuch.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Anmeldung(
    val serverUrl: String,
    val token: String,
    val userId: String,
    val name: String,
    val email: String,
)

/** Einfache Einstellungen in SharedPreferences, Anmeldung zusätzlich als Flow für die Oberfläche. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("fahrtenbuch", Context.MODE_PRIVATE)

    private val _anmeldung = MutableStateFlow(leseAnmeldung())
    val anmeldung: StateFlow<Anmeldung?> = _anmeldung

    private fun leseAnmeldung(): Anmeldung? {
        val token = sp.getString("token", null) ?: return null
        return Anmeldung(
            serverUrl = sp.getString("serverUrl", "") ?: "",
            token = token,
            userId = sp.getString("userId", "") ?: "",
            name = sp.getString("name", "") ?: "",
            email = sp.getString("email", "") ?: "",
        )
    }

    fun speichereAnmeldung(a: Anmeldung) {
        sp.edit()
            .putString("serverUrl", a.serverUrl)
            .putString("token", a.token)
            .putString("userId", a.userId)
            .putString("name", a.name)
            .putString("email", a.email)
            .apply()
        _anmeldung.value = a
    }

    fun abmelden() {
        sp.edit().remove("token").remove("userId").remove("passwortAendern").apply()
        _passwortAendern.value = false
        listOf("fahrzeug", "fahrt", "tankvorgang", "users", "ausgleich").forEach { sp.edit().remove("sync_$it").apply() }
        _anmeldung.value = null
    }

    /** Eigene Benutzer-ID: vom Server, oder "lokal" solange nicht verbunden. */
    val userId: String get() = _anmeldung.value?.userId ?: LOKAL

    /** Name im lokalen Modus (ohne Server). */
    var lokalerName: String
        get() = sp.getString("lokalerName", "Ich") ?: "Ich"
        set(v) = sp.edit().putString("lokalerName", v).apply()

    /** Nach der ersten Anmeldung: lokale Daten dem Server-Konto zuordnen. */
    var zusammenfuehren: Boolean
        get() = sp.getBoolean("zusammenfuehren", false)
        set(v) = sp.edit().putBoolean("zusammenfuehren", v).apply()

    /** Zuletzt benutzte Server-Adresse, als Vorschlag im Login. */
    val letzteServerUrl: String get() = sp.getString("serverUrl", "") ?: ""
    val letzteEmail: String get() = sp.getString("email", "") ?: ""

    /** Zweite Adresse (z. B. Tailscale), wird versucht, wenn die Hauptadresse nicht antwortet. */
    var tailscaleUrl: String
        get() = sp.getString("tailscaleUrl", "") ?: ""
        set(v) = sp.edit().putString("tailscaleUrl", v).apply()

    /**
     * IP-Adresse, unter der eine .local-Adresse zuletzt erreichbar war. mDNS (fahrtenbuch.local)
     * löst auf manchen Handys nur zeitweise auf; dann nimmt die App diese Adresse.
     */
    /** Zeitpunkt des letzten Zwischenspeicherns einer laufenden Fahrt (Ende, falls sie abbricht). */
    var zwischenstandUm: Long
        get() = sp.getLong("zwischenstandUm", 0)
        set(v) = sp.edit().putLong("zwischenstandUm", v).apply()

    var geraetGemeldetVersion: String
        get() = sp.getString("geraetGemeldetVersion", "") ?: ""
        set(v) = sp.edit().putString("geraetGemeldetVersion", v).apply()
    var geraetGemeldetUm: Long
        get() = sp.getLong("geraetGemeldetUm", 0)
        set(v) = sp.edit().putLong("geraetGemeldetUm", v).apply()

    /** Seit wann Android "im Auto" meldet (Roadmap Nr. 7), 0 = nicht. */
    var eingestiegenUm: Long
        get() = sp.getLong("eingestiegenUm", 0)
        set(v) = sp.edit().putLong("eingestiegenUm", v).apply()

    /** Zeitraum ("2026-10"), für den die Monatsabrechnung schon gemeldet wurde (Roadmap Nr. 10). */
    var abrechnungGemeldet: String
        get() = sp.getString("abrechnungGemeldet", "") ?: ""
        set(v) = sp.edit().putString("abrechnungGemeldet", v).apply()

    /** Roadmap Nr. 6.3: beim Einsteigen automatisch vorläufig aufzeichnen. Aus ab Werk (Datenschutz, Akku). */
    var autoVorlaeufig: Boolean
        get() = sp.getBoolean("autoVorlaeufig", false)
        set(v) = sp.edit().putBoolean("autoVorlaeufig", v).apply()

    /** Bluetooth im Auto (Roadmap Nr. 6): "Adresse=FahrzeugId;..." (siehe location/AutoBluetooth). */
    var bluetoothAutos: String
        get() = sp.getString("bluetoothAutos", "") ?: ""
        set(v) = sp.edit().putString("bluetoothAutos", v).apply()

    var letzteIpUrl: String
        get() = sp.getString("letzteIpUrl", "") ?: ""
        set(v) = sp.edit().putString("letzteIpUrl", v).apply()

    /** Hauptadresse ändern, ohne neu anzumelden. */
    fun serverUrlAendern(url: String) {
        val a = _anmeldung.value ?: return
        speichereAnmeldung(a.copy(serverUrl = url))
    }

    var autoStopMinuten: Int
        get() = sp.getInt("autoStopMinuten", 10)
        set(v) = sp.edit().putInt("autoStopMinuten", v).apply()

    /** Einrichtung (Berechtigungen) beim ersten Start schon durchlaufen? */
    var einrichtungFertig: Boolean
        get() = sp.getBoolean("einrichtungFertig", false)
        set(v) = sp.edit().putBoolean("einrichtungFertig", v).apply()

    /** Konto hat noch das Start-Passwort: die App bittet dann, ein eigenes festzulegen. */
    private val _passwortAendern = MutableStateFlow(sp.getBoolean("passwortAendern", false))
    val passwortAendern: StateFlow<Boolean> = _passwortAendern

    fun setPasswortAendern(v: Boolean) {
        sp.edit().putBoolean("passwortAendern", v).apply()
        _passwortAendern.value = v
    }

    /** Welche Fassung der Einrichtung zuletzt durchlaufen wurde (siehe HauptViewModel). */
    var einrichtungStand: Int
        get() = sp.getInt("einrichtungStand", 0)
        set(v) = sp.edit().putInt("einrichtungStand", v).apply()

    /** Test-Modus: neue Fahrten und Tankungen werden als Test markiert (zählen nirgends mit). */
    var testModus: Boolean
        get() = sp.getBoolean("testModus", false)
        set(v) = sp.edit().putBoolean("testModus", v).apply()

    /** Nur im Test-Modus: GPS-Punkt höchstens alle so viele Meter (0 = normal, so oft wie möglich). */
    var testAufnahmeMeter: Int
        get() = sp.getInt("testAufnahmeMeter", 0)
        set(v) = sp.edit().putInt("testAufnahmeMeter", v).apply()

    var gewaehltesFahrzeug: String?
        get() = sp.getString("fahrzeug", null)
        set(v) = sp.edit().putString("fahrzeug", v).apply()

    var letzterSync: Long
        get() = sp.getLong("letzterSync", 0)
        set(v) = sp.edit().putLong("letzterSync", v).apply()

    /** Update-Prüfung (siehe Update.kt): wann zuletzt, und welche neue Version gefunden wurde. */
    var updateGeprueft: Long
        get() = sp.getLong("updateGeprueft", 0)
        set(v) = sp.edit().putLong("updateGeprueft", v).apply()

    fun updateMerken(v: NeueVersion?) {
        sp.edit()
            .putInt("updateNummer", v?.nummer ?: 0)
            .putString("updateName", v?.name ?: "")
            .putString("updateApk", v?.apkUrl ?: "")
            .putString("updateSha", v?.sha256 ?: "")
            .apply()
    }

    fun updateBekannt(): NeueVersion? {
        val n = sp.getInt("updateNummer", 0)
        if (n <= 0) return null
        return NeueVersion(n, sp.getString("updateName", "") ?: "", sp.getString("updateApk", "") ?: "",
            sp.getString("updateSha", "") ?: "")
    }

    /** Hinweis für diese Version weggetippt. */
    var updateAusgeblendet: Int
        get() = sp.getInt("updateAusgeblendet", 0)
        set(v) = sp.edit().putInt("updateAusgeblendet", v).apply()

    fun syncStand(collection: String): String = sp.getString("sync_$collection", "") ?: ""
    fun setSyncStand(collection: String, updated: String) =
        sp.edit().putString("sync_$collection", updated).apply()

    companion object {
        /** "192.168.1.5:8090" -> "http://192.168.1.5:8090", leer bleibt leer. */
        fun normalisieren(eingabe: String): String {
            val t = eingabe.trim().trimEnd('/')
            if (t.isEmpty()) return ""
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
        }

        const val LOKAL = "lokal"
    }
}
