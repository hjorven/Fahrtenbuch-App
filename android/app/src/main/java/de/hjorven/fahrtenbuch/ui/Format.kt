package de.hjorven.fahrtenbuch.ui

import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DE = Locale.GERMANY

fun km(v: Double): String = String.format(DE, "%.1f km", v)
fun euro(v: Double): String = String.format(DE, "%.2f €", v)
fun zahl(v: Double, stellen: Int = 1): String = String.format(DE, "%.${stellen}f", v)
fun kmStand(v: Double): String = String.format(DE, "%,.0f km", v)

fun datum(millis: Long): String = SimpleDateFormat("EEE, d. MMM yyyy", DE).format(Date(millis))
fun datumKurz(millis: Long): String = SimpleDateFormat("dd.MM.yyyy", DE).format(Date(millis))
fun uhrzeit(millis: Long): String = SimpleDateFormat("HH:mm", DE).format(Date(millis))
fun tagKurz(millis: Long): String = SimpleDateFormat("EE d.", DE).format(Date(millis))
fun monatKurz(millis: Long): String = SimpleDateFormat("MMM", DE).format(Date(millis))
fun monat(millis: Long): String = SimpleDateFormat("MMMM yyyy", DE).format(Date(millis))

fun dauer(ms: Long): String {
    val min = ms / 60_000
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

fun dauerUhr(ms: Long): String {
    val s = ms / 1000
    return String.format(DE, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
}

fun farbe(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color(0xFF1565C0)
}

/** Akzeptiert Komma und Punkt als Dezimaltrenner. */
fun String.alsZahl(): Double? = trim().replace(',', '.').toDoubleOrNull()

val ZWECKE = listOf("privat" to "Privat", "geschaeftlich" to "Geschäftlich", "sonstiges" to "Sonstiges")
