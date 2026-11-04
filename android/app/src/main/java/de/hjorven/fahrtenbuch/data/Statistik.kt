package de.hjorven.fahrtenbuch.data

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min

/**
 * Verbrauch und Kostenaufteilung, wie in docs/PLAN.md beschrieben.
 * Reine Funktionen ohne Android-Abhängigkeit, damit sie per Unit-Test prüfbar sind.
 */
object Statistik {

    data class Verbrauch(val literPro100km: Double, val kostenProKm: Double, val km: Double)

    /**
     * Gleitend über die letzten [anzahlVolle] Abschnitte zwischen vollen Tankungen.
     * Gezählt werden alle Tankungen nach der ersten vollen bis einschließlich der letzten vollen.
     * Laden zuhause zählt 0 Euro (siehe [Tankvorgang.kosten]).
     */
    fun verbrauch(tankungen: List<Tankvorgang>, anzahlVolle: Int = 3, elektrisch: Boolean = false): Verbrauch? {
        val sortiert = tankungen.filter { !it.geloescht }.sortedBy { it.kmStand }
        // E-Autos werden selten voll geladen: dort zählt jeder Ladevorgang, gleitend über die letzten 5.
        val volleIdx = if (elektrisch) sortiert.indices.toList() else sortiert.indices.filter { sortiert[it].vollgetankt }
        val anzahl = if (elektrisch) 5 else anzahlVolle
        if (volleIdx.size < 2) return null
        val bisIdx = volleIdx.last()
        val vonIdx = volleIdx[maxOf(0, volleIdx.size - 1 - anzahl)]
        val km = sortiert[bisIdx].kmStand - sortiert[vonIdx].kmStand
        if (km <= 0) return null
        val abschnitt = sortiert.subList(vonIdx + 1, bisIdx + 1)
        val liter = abschnitt.sumOf { it.liter }
        val kosten = abschnitt.sumOf { it.kosten }
        return Verbrauch(liter / km * 100.0, kosten / km, km)
    }

    data class VerbrauchsPunkt(val datum: Long, val wert: Double)

    /** Verbrauch pro Abschnitt zwischen zwei vollen Tankungen (E-Auto: zwischen zwei Ladungen), für den Verlauf. */
    fun verbrauchVerlauf(tankungen: List<Tankvorgang>, elektrisch: Boolean = false): List<VerbrauchsPunkt> {
        val sortiert = tankungen.filter { !it.geloescht }.sortedBy { it.kmStand }
        val aus = ArrayList<VerbrauchsPunkt>()
        var letzteVolle: Tankvorgang? = null
        var menge = 0.0
        for (t in sortiert) {
            if (letzteVolle != null) menge += t.liter
            if (elektrisch || t.vollgetankt) {
                val v = letzteVolle
                if (v != null && t.kmStand > v.kmStand) {
                    val wert = menge / (t.kmStand - v.kmStand) * 100.0
                    // Ausreißer (falsch getippter km-Stand) nicht in den Verlauf.
                    if (wert in 1.0..50.0) aus.add(VerbrauchsPunkt(t.datum, wert))
                }
                letzteVolle = t
                menge = 0.0
            }
        }
        return aus
    }

    /** Fester Preis pro km am Fahrzeug überschreibt den berechneten Wert. */
    fun kostenProKm(fahrzeug: Fahrzeug, tankungen: List<Tankvorgang>): Double? =
        if (fahrzeug.preisProKm > 0) fahrzeug.preisProKm
        else verbrauch(tankungen, elektrisch = fahrzeug.elektrisch)?.kostenProKm

    data class Saldo(
        val fahrerId: String,
        val km: Double,
        val anteil: Double,
        val bezahlt: Double,
        /** Ausgleich: + selbst überwiesen, - bekommen. */
        val ausgleich: Double = 0.0,
    ) {
        /** positiv = bekommt Geld zurück, negativ = muss zahlen */
        val saldo: Double get() = bezahlt + ausgleich - anteil
    }

    /**
     * Abrechnung für einen Zeitraum über alle Autos.
     * [kostenProKm] je Fahrzeug-ID; Autos ohne Wert zählen nur km, keinen Anteil.
     * [ausgleiche] sind die Zahlungen, die für diesen Zeitraum schon geleistet wurden.
     */
    fun abrechnung(
        fahrten: List<Fahrt>,
        tankungen: List<Tankvorgang>,
        kostenProKm: Map<String, Double>,
        von: Long,
        bis: Long,
        ausgleiche: List<Ausgleich> = emptyList(),
    ): List<Saldo> {
        // Vorläufige Fahrten zählen erst nach Bestätigung (Roadmap Nr. 6).
        val f = fahrten.filter { !it.geloescht && !it.vorlaeufig && it.ende != null && it.start in von until bis }
        val t = tankungen.filter { !it.geloescht && it.datum in von until bis }
        val a = ausgleiche.filter { !it.geloescht }
        val fahrer = (f.flatMap { listOf(it.fahrerId) + it.mitfahrerIds } + t.map { it.fahrerId } + a.flatMap { listOf(it.von, it.an) }).toSortedSet()
        return fahrer.map { id ->
            val eigene = f.filter { it.fahrerId == id }
            val km = eigene.sumOf { it.km }
            // Mit Mitfahrern teilen sich alle den Anteil gleichmäßig, die km zählen nur beim Fahrer.
            val anteil = f.filter { it.fahrerId == id || id in it.mitfahrerIds }
                .sumOf { it.km * (kostenProKm[it.fahrzeugId] ?: 0.0) / (1 + it.mitfahrerIds.size) }
            val bezahlt = t.filter { it.fahrerId == id }.sumOf { it.kosten }
            val ausgleich = a.filter { it.von == id }.sumOf { it.betrag } - a.filter { it.an == id }.sumOf { it.betrag }
            Saldo(id, km, anteil, bezahlt, ausgleich)
        }
    }

    data class Ueberweisung(val von: String, val an: String, val betrag: Double)

    /** Wer muss wem wie viel zahlen, damit alle bei 0 sind (wenige Überweisungen, größte zuerst). */
    fun ueberweisungen(salden: List<Saldo>): List<Ueberweisung> {
        class Offen(val id: String, var betrag: Double)
        val schuldner = salden.filter { it.saldo < -0.005 }.map { Offen(it.fahrerId, -it.saldo) }.sortedByDescending { it.betrag }
        val glaeubiger = salden.filter { it.saldo > 0.005 }.map { Offen(it.fahrerId, it.saldo) }.sortedByDescending { it.betrag }
        val aus = ArrayList<Ueberweisung>()
        var i = 0
        var j = 0
        while (i < schuldner.size && j < glaeubiger.size) {
            val s = schuldner[i]
            val g = glaeubiger[j]
            val betrag = min(s.betrag, g.betrag)
            if (betrag > 0.005) aus.add(Ueberweisung(s.id, g.id, Math.round(betrag * 100) / 100.0))
            s.betrag -= betrag
            g.betrag -= betrag
            if (s.betrag <= 0.005) i++
            if (g.betrag <= 0.005) j++
        }
        return aus
    }

    /** "2026-10" für den Monat, in dem [millis] liegt (lokale Zeit). */
    fun zeitraum(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(java.util.Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    /** km pro Monat (Index 0 = Januar) im Jahr [jahr] für ein Auto oder alle (null). */
    fun kmProMonat(fahrten: List<Fahrt>, jahr: Int, fahrzeugId: String? = null): DoubleArray {
        val aus = DoubleArray(12)
        val c = Calendar.getInstance()
        for (f in fahrten) {
            if (f.geloescht || f.ende == null) continue
            if (fahrzeugId != null && f.fahrzeugId != fahrzeugId) continue
            c.timeInMillis = f.start
            if (c.get(Calendar.YEAR) == jahr) aus[c.get(Calendar.MONTH)] += f.km
        }
        return aus
    }

    data class TachoAbweichung(val fahrten: Int, val gpsKm: Double, val tachoKm: Double) {
        /** positiv = GPS zählt zu viel */
        val prozent: Double get() = if (tachoKm > 0) (gpsKm - tachoKm) / tachoKm * 100.0 else 0.0
    }

    /** Fahrten, deren km per Tacho korrigiert wurden: Wie weit liegt GPS daneben? */
    fun tachoAbweichung(fahrten: List<Fahrt>): TachoAbweichung? {
        val korrigiert = fahrten.filter {
            !it.geloescht && it.ende != null && it.streckeKm > 0.5 && it.kmStart > 0 && it.kmEnde > it.kmStart &&
                abs((it.kmEnde - it.kmStart) - it.streckeKm) > 0.05
        }
        if (korrigiert.isEmpty()) return null
        return TachoAbweichung(korrigiert.size, korrigiert.sumOf { it.streckeKm }, korrigiert.sumOf { it.kmEnde - it.kmStart })
    }
}
