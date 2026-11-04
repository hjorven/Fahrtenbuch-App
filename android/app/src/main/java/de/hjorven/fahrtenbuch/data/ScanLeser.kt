package de.hjorven.fahrtenbuch.data

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.round

/**
 * Wertet den Text aus, den die Texterkennung (ML Kit, auf dem Handy) auf einem Foto gefunden hat.
 * Reine Logik ohne Android, damit sie getestet werden kann. Dieselben Regeln stehen in
 * iOS Daten/ScanLeser.swift.
 */
object ScanLeser {

    /** Was auf einem Tank- oder Ladebeleg erkannt wurde. Alles optional. */
    data class Beleg(
        val menge: Double? = null,
        /** "L" oder "kWh", je nachdem was auf dem Beleg stand. */
        val einheit: String? = null,
        val preisProEinheit: Double? = null,
        val gesamt: Double? = null,
        /** Datum (Mittag, lokale Zeit) in Millisekunden. */
        val datum: Long? = null,
        val tankstelle: String? = null,
    ) {
        val leer: Boolean get() = menge == null && preisProEinheit == null && gesamt == null
    }

    // ---------- Beleg ----------

    private val ZAHL = Regex("""(?<![\d.,])(\d{1,4})\s?[.,]\s?(\d{1,3})(?![\d])""")
    private val MENGE_LITER = Regex("""(\d{1,3}[.,]\d{1,3})\s*(?:l|ltr|liter|litre)\b""", RegexOption.IGNORE_CASE)
    private val MENGE_KWH = Regex("""(\d{1,3}[.,]\d{1,4})\s*kwh\b""", RegexOption.IGNORE_CASE)
    private val PREIS_EINHEIT = Regex(
        """(\d[.,]\d{2,3})\s*(?:eur|€)?\s*/\s*(l|ltr|liter|kwh)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MAL_PREIS = Regex("""(?:x|\*|à|@)\s*(\d[.,]\d{2,3})""", RegexOption.IGNORE_CASE)
    private val DATUM_DE = Regex("""(?<!\d)(\d{1,2})\.(\d{1,2})\.(\d{4}|\d{2})(?!\d)""")
    private val DATUM_ISO = Regex("""(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)""")
    private val SUMME_WORTE = listOf("zu zahlen", "summe", "gesamtbetrag", "gesamt", "total", "betrag")
    private val ZAHL_WORTE = listOf("girocard", "ec-karte", "ec karte", "kartenzahlung", "bar", "visa", "mastercard", "gegeben")
    private val NICHT_SUMME = listOf("mwst", "ust", "netto", "steuer", "rückgeld", "rueckgeld", "zurück")

    private val MARKEN = listOf(
        "TotalEnergies", "Aral", "Shell", "Esso", "Total", "JET", "Avia", "Agip", "Eni", "BFT", "Star",
        "Orlen", "OMV", "HEM", "Westfalen", "Raiffeisen", "Tamoil", "Hoyer", "Classic", "Sprint", "Q1",
        "Globus", "Score", "Freie Tankstelle", "Ionity", "EnBW", "Allego", "Tesla", "Fastned", "EWE Go",
        "Mer", "Pfalzwerke", "E.ON", "Maingau", "Lidl", "Kaufland", "Aldi",
    )

    private fun dezimal(s: String): Double? = s.replace(" ", "").replace(',', '.').toDoubleOrNull()

    private fun runde(x: Double, stellen: Int): Double {
        var f = 1.0
        repeat(stellen) { f *= 10 }
        return round(x * f) / f
    }

    /**
     * [zeilen]: Textzeilen von oben nach unten, nebeneinander stehende Teile schon zu einer Zeile
     * zusammengefügt. [elektro]: bei E-Autos wird nach kWh gesucht, sonst nach Litern.
     */
    fun beleg(zeilen: List<String>, elektro: Boolean, heute: Long = System.currentTimeMillis()): Beleg {
        val text = zeilen.map { it.replace(' ', ' ').trim() }.filter { it.isNotEmpty() }
        val einheit = if (elektro) "kWh" else "L"
        val mengeRegex = if (elektro) MENGE_KWH else MENGE_LITER

        var menge = text.firstNotNullOfOrNull { z ->
            mengeRegex.findAll(z).mapNotNull { dezimal(it.groupValues[1]) }.firstOrNull { it in 0.5..Plausibel.MAX_MENGE }
        }
        var preis = text.firstNotNullOfOrNull { z ->
            PREIS_EINHEIT.findAll(z)
                .filter { (it.groupValues[2].lowercase() == "kwh") == elektro }
                .mapNotNull { dezimal(it.groupValues[1]) }
                .firstOrNull { it in 0.05..Plausibel.MAX_PREIS_EINHEIT }
        } ?: text.firstNotNullOfOrNull { z ->
            if (mengeRegex.containsMatchIn(z)) {
                MAL_PREIS.findAll(z).mapNotNull { dezimal(it.groupValues[1]) }.firstOrNull { it in 0.05..Plausibel.MAX_PREIS_EINHEIT }
            } else null
        }
        var gesamt = summe(text)

        // Probe: Menge mal Preis muss die Summe ergeben. Passt etwas nicht oder fehlt etwas,
        // unter allen Zahlen auf dem Beleg ein Trio suchen, das aufgeht.
        val passt = menge != null && preis != null && gesamt != null && abs(menge * preis - gesamt) <= 0.06
        if (!passt) {
            trio(text, elektro, menge, preis, gesamt)?.let { (m, p, g) ->
                menge = m; preis = p; gesamt = g
            }
        }
        // Fehlendes aus den anderen beiden berechnen.
        val m = menge; val p = preis; val g = gesamt
        if (m == null && p != null && g != null && p > 0) menge = runde(g / p, 2).takeIf { it in 0.5..Plausibel.MAX_MENGE }
        if (p == null && m != null && g != null && m > 0) preis = runde(g / m, 3).takeIf { it in 0.05..Plausibel.MAX_PREIS_EINHEIT }
        if (g == null && m != null && p != null) gesamt = runde(m * p, 2)

        return Beleg(
            menge = menge,
            einheit = if (menge != null) einheit else null,
            preisProEinheit = preis,
            gesamt = gesamt,
            datum = datum(text, heute),
            tankstelle = tankstelle(text),
        )
    }

    private fun zweiStellen(z: String): List<Double> =
        ZAHL.findAll(z).filter { it.groupValues[2].length == 2 }.mapNotNull { dezimal(it.value) }.toList()

    /** Der Betrag in der Zeile mit "Summe", "zu zahlen" usw., ersatzweise in der Zahlungszeile. */
    private fun summe(text: List<String>): Double? {
        for (worte in listOf(SUMME_WORTE, ZAHL_WORTE)) {
            for ((i, z) in text.withIndex()) {
                val klein = z.lowercase()
                if (worte.none { klein.contains(it) } || NICHT_SUMME.any { klein.contains(it) }) continue
                // Betrag steht in derselben Zeile oder (bei zweispaltigen Bons) in der nächsten.
                val betrag = zweiStellen(z).lastOrNull()
                    ?: text.getOrNull(i + 1)?.takeIf { n -> n.count { it.isLetter() } <= 4 }?.let { zweiStellen(it).lastOrNull() }
                if (betrag != null && betrag > 0 && betrag < 1000) return betrag
            }
        }
        return null
    }

    private fun trio(text: List<String>, elektro: Boolean, menge: Double?, preis: Double?, gesamt: Double?): Triple<Double, Double, Double>? {
        val zahlen = text.flatMap { z -> ZAHL.findAll(z).mapNotNull { dezimal(it.value) }.toList() }.distinct()
        val mengen = menge?.let { listOf(it) } ?: zahlen.filter { it in 0.5..Plausibel.MAX_MENGE }
        val preise = preis?.let { listOf(it) } ?: zahlen.filter { it in 0.05..Plausibel.MAX_PREIS_EINHEIT }
        val summen = gesamt?.let { listOf(it) + zahlen } ?: zahlen
        var bestes: Triple<Double, Double, Double>? = null
        var besteGuete = Double.MAX_VALUE
        for (m in mengen) for (p in preise) {
            if (m == p) continue
            val soll = m * p
            for (g in summen) {
                if (g == m || g == p || g < 1) continue
                val abw = abs(soll - g)
                if (abw > 0.06) continue
                // Die schon erkannte Summe bevorzugen, dann die genaueste Übereinstimmung.
                val guete = abw + (if (gesamt != null && g == gesamt) -1.0 else 0.0)
                if (guete < besteGuete) {
                    besteGuete = guete
                    bestes = Triple(m, p, g)
                }
            }
        }
        // Bei Diesel liegen Preise zwischen etwa 1 und 3 Euro; ein Trio mit Preis 0,19 (MwSt-Satz) ist Unsinn.
        return bestes?.takeIf { elektro || it.second >= 0.5 }
    }

    private fun datum(text: List<String>, heute: Long): Long? {
        val cal = Calendar.getInstance()
        for (z in text) {
            val treffer = DATUM_DE.find(z)?.let { t ->
                val (tag, monat, jahr) = t.destructured
                Triple(tag.toInt(), monat.toInt(), jahr.toInt().let { if (it < 100) 2000 + it else it })
            } ?: DATUM_ISO.find(z)?.let { t ->
                val (jahr, monat, tag) = t.destructured
                Triple(tag.toInt(), monat.toInt(), jahr.toInt())
            } ?: continue
            val (tag, monat, jahr) = treffer
            if (tag !in 1..31 || monat !in 1..12) continue
            cal.clear()
            cal.set(jahr, monat - 1, tag, 12, 0, 0)
            val ms = cal.timeInMillis
            // Nur plausible Daten: nicht in der Zukunft, höchstens ein Jahr alt.
            if (ms <= heute + 86_400_000L && ms >= heute - 366L * 86_400_000L) return ms
        }
        return null
    }

    private fun tankstelle(text: List<String>): String? {
        // Nur der Kopf des Bons und nur Zeilen ohne Beträge, sonst wird "TOTAL 80,92" zur Marke Total.
        val oben = text.take(6).filter { !ZAHL.containsMatchIn(it) }
        for (marke in MARKEN) {
            val r = Regex("""(?<![A-Za-zÄÖÜäöü])${Regex.escape(marke)}(?![A-Za-zÄÖÜäöü])""", RegexOption.IGNORE_CASE)
            if (oben.any { r.containsMatchIn(it) }) return marke
        }
        return null
    }

    // ---------- Tacho ----------

    /** Ein Textstück vom Tacho-Foto mit seiner Höhe (größer = auffälliger). */
    data class TachoText(val text: String, val hoehe: Float = 1f)

    private val ZIFFERN = Regex("""\d{1,3}(?:[ .'’]\d{3})+|\d{3,7}""")

    /**
     * Mögliche km-Stände, bester zuerst (höchstens drei). [bisher] = letzter bekannter
     * km-Stand des Autos (0 = unbekannt): Der Tacho kann nur höher stehen, und meist nur wenig.
     */
    fun tacho(texte: List<TachoText>, bisher: Double): List<Int> {
        data class Kandidat(val km: Int, val hoehe: Float, val mitKm: Boolean)
        val kandidaten = ArrayList<Kandidat>()
        for (t in texte) {
            val roh = t.text
                .replace('O', '0').replace('o', '0')
                .replace('I', '1').replace('|', '1')
            for (m in ZIFFERN.findAll(roh)) {
                val nach = roh.substring(m.range.last + 1)
                val vor = roh.substring(0, m.range.first)
                // Tageskilometer (123,4), Uhrzeiten (12:34) und Temperaturen fallen raus.
                if (Regex("""^\s?[.,]\d(?!\d{2})""").containsMatchIn(nach)) continue
                if (Regex("""^\s?:\d""").containsMatchIn(nach) || vor.endsWith(":")) continue
                if (Regex("""^\s?(°|km/h|kmh|mph|u/min|rpm|%)""", RegexOption.IGNORE_CASE).containsMatchIn(nach)) continue
                val km = m.value.filter { it.isDigit() }.toIntOrNull() ?: continue
                if (km < 10 || km > Plausibel.MAX_KM_STAND) continue
                val mitKm = Regex("""^\s?km\b""", RegexOption.IGNORE_CASE).containsMatchIn(nach)
                kandidaten.add(Kandidat(km, t.hoehe, mitKm))
            }
        }
        if (kandidaten.isEmpty()) return emptyList()
        val maxHoehe = kandidaten.maxOf { it.hoehe }.coerceAtLeast(0.0001f)

        fun punkte(k: Kandidat): Double {
            var p = (k.hoehe / maxHoehe).toDouble() + (if (k.mitKm) 1.0 else 0.0)
            if (bisher > 0) {
                val diff = k.km - bisher
                p += when {
                    diff < -5 -> -10.0                       // Tacho läuft nicht rückwärts
                    diff <= 3000 -> 3.0 - diff / 3000.0      // das Übliche: kurz seit dem letzten Eintrag
                    diff <= 30000 -> 0.5
                    else -> -5.0
                }
            } else {
                p += k.km.toString().length * 0.4            // ohne Vorwissen: längere Zahlen sind eher der Tacho
            }
            return p
        }

        return kandidaten
            .groupBy { it.km }
            .map { (km, liste) -> km to liste.maxOf { punkte(it) } }
            .filter { it.second > -5 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(3)
    }
}
