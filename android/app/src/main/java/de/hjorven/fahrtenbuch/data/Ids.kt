package de.hjorven.fahrtenbuch.data

import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val zufall = SecureRandom()
private const val ZEICHEN = "abcdefghijklmnopqrstuvwxyz0123456789"

/** PocketBase-kompatible ID (15 Zeichen, a-z0-9), auf dem Gerät erzeugt. */
fun neueId(): String = buildString(15) { repeat(15) { append(ZEICHEN[zufall.nextInt(ZEICHEN.length)]) } }

/** Datumsformat von PocketBase: "2026-11-02 07:00:00.000Z" (immer UTC). */
object PbDatum {
    private fun format() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun aus(millis: Long?): String = if (millis == null) "" else format().format(Date(millis))

    fun parse(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            format().parse(text)?.time
        } catch (_: Exception) {
            null
        }
    }
}
