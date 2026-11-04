package de.hjorven.fahrtenbuch.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Einheitliche Fehlermeldungen mit "Was tun?" (Roadmap Nr. 11). Statt "HTTP 502" oder einer
 * Java-Meldung steht da, was passiert ist und was hilft. Ausführlicher in der Hilfe (Einstellungen).
 */
object Fehlertext {
    const val OFFLINE = "Server nicht erreichbar. Was tun: Zuhause ins WLAN, unterwegs Tailscale einschalten. Es geht nichts verloren, die App lädt später hoch."

    /** [was] = was gerade nicht ging, z. B. "Speichern". */
    fun aus(e: Throwable, was: String = "Das"): String = when (e) {
        is ApiFehler -> when {
            e.status == 401 -> "Anmeldung abgelaufen. Was tun: In den Einstellungen neu anmelden."
            e.status == 403 -> "$was ist nur für eigene Einträge erlaubt."
            e.status == 404 -> "$was gibt es auf dem Server noch nicht. Was tun: Server aktualisieren (Pi), dann erneut."
            e.status == 400 -> serverText(e.message) ?: "$was ging nicht, der Server hat die Werte abgelehnt. Was tun: Eingaben prüfen."
            e.status == 413 -> "Die Datei ist zu groß. Was tun: Foto erneut aufnehmen."
            e.status in 500..599 -> "Der Server hat gerade ein Problem. Was tun: Später erneut, sonst unter Einstellungen \"Problem melden\"."
            else -> "$was ging nicht (Fehler ${e.status}). Was tun: Später erneut, sonst \"Problem melden\"."
        }
        is UnknownHostException, is ConnectException, is SocketTimeoutException -> OFFLINE
        is IOException -> OFFLINE
        else -> "$was ging nicht. Was tun: Später erneut, sonst unter Einstellungen \"Problem melden\"."
    }

    /** Deutsche Meldung vom Server übernehmen (plausibel.js), technische ("HTTP 400", englisch) nicht. */
    private fun serverText(m: String?): String? {
        val t = m?.trim().orEmpty()
        if (t.isEmpty() || t.startsWith("HTTP ") || t.startsWith("Failed") || t.startsWith("Something")) return null
        return t
    }
}
