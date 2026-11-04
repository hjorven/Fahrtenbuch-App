package de.hjorven.fahrtenbuch.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GpsPunkt(
    val zeit: Long,
    val lat: Double,
    val lon: Double,
    /** m/s */
    val speed: Double,
    val genauigkeit: Float = 0f,
)

fun distanzMeter(a: GpsPunkt, b: GpsPunkt): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

/**
 * Erkennt das automatische Fahrtende (docs/PLAN.md, "Automatisches Fahrtende"):
 * Geschwindigkeit unter 3 km/h und Position innerhalb von 100 m für [dauerMs].
 * Das Fahrtende ist der Beginn des Stillstands.
 */
class StillstandErkennung(
    private val dauerMs: Long,
    private val maxSpeedMps: Double = 3.0 / 3.6,
    private val radiusM: Double = 100.0,
) {
    private var anker: GpsPunkt? = null

    /** Zeitpunkt, seit dem das Auto steht, oder null. */
    val stehtSeit: Long? get() = anker?.zeit

    fun punkt(p: GpsPunkt) {
        val a = anker
        if (p.speed >= maxSpeedMps) {
            anker = null
        } else if (a == null || distanzMeter(a, p) > radiusM) {
            anker = p
        }
    }

    /**
     * Prüft zu einem beliebigen Zeitpunkt (auch ohne neuen GPS-Punkt), ob die Fahrt enden soll.
     * [imFahrzeug] = die Bewegungserkennung meldet gerade "im Fahrzeug" (Stau): dann läuft die
     * Fahrt weiter, bis jemand aussteigt (docs/PLAN.md, "Stau").
     */
    fun sollEnden(jetzt: Long, imFahrzeug: Boolean = false): Boolean {
        if (imFahrzeug) return false
        val seit = stehtSeit ?: return false
        return jetzt - seit >= dauerMs
    }

    /**
     * Lange Lücke zwischen zwei Punkten, in der das Auto kaum vorwärtsgekommen ist (App gedrosselt oder
     * kein Empfang): das war ein Halt. Ein Tunnel bei normaler Fahrt hat einen viel höheren Schnitt.
     */
    fun lueckeIstHalt(vorher: GpsPunkt?, neu: GpsPunkt): Boolean {
        if (vorher == null) return false
        val ms = neu.zeit - vorher.zeit
        if (ms < dauerMs) return false
        return distanzMeter(vorher, neu) / (ms / 1000.0) < LUECKE_MAX_MPS
    }

    /** Gar kein Standort mehr seit der doppelten Stillstandszeit (z. B. Tiefgarage): als Halt werten. */
    fun keinSignal(letzter: GpsPunkt?, jetzt: Long): Boolean {
        if (letzter == null) return false
        return jetzt - letzter.zeit >= 2 * dauerMs
    }

    companion object {
        /** 10 km/h im Schnitt über die Lücke. */
        const val LUECKE_MAX_MPS = 10.0 / 3.6
    }
}

/** Sammelt Punkte und rechnet Strecke, Höchst- und Durchschnittsgeschwindigkeit. */
class Streckenrechner {
    val punkte = ArrayList<GpsPunkt>()
    /** Verworfene Sprünge in Folge; passen [SPRUNG_BESTAETIGT] davon zueinander, war der alte Ort falsch. */
    private val spruenge = ArrayList<GpsPunkt>()
    var meter = 0.0
        private set
    var maxMps = 0.0
        private set

    fun hinzufuegen(p: GpsPunkt): Boolean {
        // Ungenaue Punkte (Tiefgarage, Tunnel) verwerfen.
        if (p.genauigkeit > MAX_GENAUIGKEIT_M) return false
        val letzter = punkte.lastOrNull()
        if (letzter != null) {
            val d = distanzMeter(letzter, p)
            // GPS-Zittern im Stand nicht als Strecke zählen.
            if (d < 5.0 && p.speed < 1.0) return false
            if (istSprung(letzter, p)) {
                // Ein Punkt weit daneben (Mehrwegeempfang, falscher Fix) ergäbe Fantasie-km und -km/h.
                val vorher = spruenge.lastOrNull()
                if (vorher != null && !istSprung(vorher, p)) spruenge.add(p) else { spruenge.clear(); spruenge.add(p) }
                if (spruenge.size < SPRUNG_BESTAETIGT) return false
                // Mehrere Punkte passen zueinander: der alte Ort war falsch. Ohne die Sprung-Strecke weiter;
                // waren es nur ein, zwei falsche Punkte am Anfang (erster Fix daneben), fliegen sie raus.
                if (punkte.size < SPRUNG_BESTAETIGT) {
                    punkte.clear()
                    meter = 0.0
                }
                for (i in 1 until spruenge.size) meter += distanzMeter(spruenge[i - 1], spruenge[i])
                punkte.addAll(spruenge)
                spruenge.clear()
                return true
            }
            meter += d
        }
        spruenge.clear()
        // Gemeldete Geschwindigkeit über 250 km/h ist ein Messfehler (Server setzt sie ebenfalls auf 0).
        if (p.speed > maxMps && p.speed <= MAX_SPRUNG_MPS) maxMps = p.speed
        punkte.add(p)
        return true
    }

    private fun istSprung(a: GpsPunkt, b: GpsPunkt): Boolean {
        val d = distanzMeter(a, b)
        val sek = maxOf((b.zeit - a.zeit) / 1000.0, 1.0)
        return d > SPRUNG_MIN_M && d / sek > MAX_SPRUNG_MPS
    }

    /** Alle Punkte nach [zeit] entfernen (Stillstand am Ende abschneiden). */
    fun abschneidenNach(zeit: Long) {
        while (punkte.size > 1 && punkte.last().zeit > zeit) {
            val weg = punkte.removeAt(punkte.size - 1)
            meter -= distanzMeter(punkte.last(), weg)
        }
        if (meter < 0) meter = 0.0
    }

    fun schnittKmh(start: Long, ende: Long): Double {
        val h = (ende - start) / 3_600_000.0
        return if (h > 0) meter / 1000.0 / h else 0.0
    }

    companion object {
        /** Ungenauere Punkte (Tiefgarage, Tunnel) werden verworfen. */
        const val MAX_GENAUIGKEIT_M = 35f
        /** 250 km/h: schneller fährt keins der Autos, das ist ein GPS-Sprung. */
        const val MAX_SPRUNG_MPS = 250.0 / 3.6
        /** Kleinere Abweichungen sind normales GPS-Rauschen, die fängt die Genauigkeit ab. */
        const val SPRUNG_MIN_M = 200.0
        const val SPRUNG_BESTAETIGT = 3
    }
}
