package de.hjorven.fahrtenbuch.data

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * QR-Code zum Verbinden (Roadmap Nr. 1/2):
 * `fahrtenbuch://verbinden?heim=<url>&tail=<url>&dl=<url>[&einladung=<code>|&reset=<code>]`, URL-kodiert.
 * Zweiter Weg (Nachtplan 5c): Adressen und Code lassen sich im Login auch abtippen.
 */
object Verbinden {
    data class Daten(
        val heim: String = "",
        val tail: String = "",
        val dl: String = "",
        val einladung: String = "",
        val reset: String = "",
    )

    private const val ANFANG = "fahrtenbuch://verbinden"

    /** null, wenn es kein Fahrtenbuch-Code ist (z. B. ein fremder QR-Code). */
    fun lesen(text: String): Daten? {
        val t = text.trim()
        if (!t.startsWith(ANFANG)) return null
        val werte = t.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }
        return Daten(
            heim = werte["heim"].orEmpty(),
            tail = werte["tail"].orEmpty(),
            dl = werte["dl"].orEmpty(),
            einladung = code(werte["einladung"].orEmpty()),
            reset = code(werte["reset"].orEmpty()),
        )
    }

    fun bauen(d: Daten): String = buildString {
        append(ANFANG)
        val teile = listOf("heim" to d.heim, "tail" to d.tail, "dl" to d.dl, "einladung" to d.einladung, "reset" to d.reset)
            .filter { it.second.isNotBlank() }
        if (teile.isNotEmpty()) append('?')
        append(teile.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") })
    }

    /** Abgetippter Code: Leerzeichen und Bindestriche weg, Großbuchstaben, 12 Zeichen. */
    fun code(eingabe: String): String {
        val c = eingabe.uppercase().filter { it.isLetterOrDigit() }
        return if (c.length == 12) c else ""
    }
}
