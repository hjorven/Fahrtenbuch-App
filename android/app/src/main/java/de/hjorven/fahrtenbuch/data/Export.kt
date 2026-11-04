package de.hjorven.fahrtenbuch.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fahrten als CSV (Roadmap Nr. 22) für Steuer oder Arbeitgeber. Semikolon und Dezimalkomma,
 * damit Excel und LibreOffice auf Deutsch die Spalten richtig erkennen. Test-Fahrten und
 * gelöschte Fahrten fehlen. Reine Funktion, getestet in LogikTest.
 */
object Export {
    private val ZWECKE = mapOf("privat" to "Privat", "geschaeftlich" to "Geschäftlich", "sonstiges" to "Sonstiges")

    private fun feld(text: String): String =
        if (text.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text

    private fun zahl(v: Double, stellen: Int): String = String.format(Locale.GERMANY, "%.${stellen}f", v)

    fun fahrtenCsv(fahrten: List<Fahrt>, namen: Map<String, String>, autos: Map<String, String>): String {
        val tag = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
        val zeit = SimpleDateFormat("HH:mm", Locale.GERMANY)
        val zeilen = mutableListOf("Datum;Beginn;Ende;Auto;Fahrer;Zweck;km;km-Stand Beginn;km-Stand Ende;Von;Nach;Notiz")
        for (f in fahrten.filter { !it.test && !it.geloescht && it.ende != null }.sortedBy { it.start }) {
            zeilen += listOf(
                tag.format(Date(f.start)), zeit.format(Date(f.start)), zeit.format(Date(f.ende!!)),
                autos[f.fahrzeugId] ?: "", namen[f.fahrerId] ?: "", ZWECKE[f.zweck] ?: f.zweck,
                zahl(f.km, 1), if (f.kmStart > 0) zahl(f.kmStart, 0) else "", if (f.kmEnde > 0) zahl(f.kmEnde, 0) else "",
                f.startOrt, f.zielOrt, f.notiz,
            ).joinToString(";") { feld(it) }
        }
        // BOM, damit Excel die Umlaute als UTF-8 liest
        return "\uFEFF" + zeilen.joinToString("\r\n") + "\r\n"
    }
}
