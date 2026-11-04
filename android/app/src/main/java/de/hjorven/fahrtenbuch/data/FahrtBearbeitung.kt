package de.hjorven.fahrtenbuch.data

import kotlin.math.roundToLong

/** Zwei Fahrten zusammenlegen und eine Fahrt teilen (reine Funktionen, getestet in LogikTest). */
object FahrtBearbeitung {

    /** [b] wird an [a] angehängt. Ergebnis: neue Fassung von [a]; [b] wird danach gelöscht. */
    fun zusammenlegen(a: Fahrt, b: Fahrt): Fahrt {
        val erste = if (a.start <= b.start) a else b
        val zweite = if (erste === a) b else a
        val strecke = erste.streckeKm + zweite.streckeKm
        val ende = zweite.ende ?: erste.ende
        val dauerH = if (ende != null && ende > erste.start) (ende - erste.start) / 3_600_000.0 else 0.0
        val route = Polyline.encode(Polyline.decode(erste.route) + Polyline.decode(zweite.route))
        return erste.copy(
            ende = ende,
            kmEnde = maxOf(erste.kmEnde, zweite.kmEnde),
            streckeKm = strecke,
            maxKmh = maxOf(erste.maxKmh, zweite.maxKmh),
            schnittKmh = if (dauerH > 0) strecke / dauerH else 0.0,
            zielOrt = zweite.zielOrt.ifBlank { erste.zielOrt },
            route = route,
            notiz = listOf(erste.notiz, zweite.notiz).filter { it.isNotBlank() }.joinToString("\n"),
            dirty = true,
        )
    }

    /** Strecke vom Start bis zu Punkt [index] der Route in km. */
    fun kmBisPunkt(punkte: List<Polyline.Punkt>, index: Int): Double {
        var m = 0.0
        for (i in 1..index.coerceAtMost(punkte.size - 1)) m += meter(punkte[i - 1], punkte[i])
        return m / 1000.0
    }

    fun meter(a: Polyline.Punkt, b: Polyline.Punkt): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    /**
     * Teilt [f] beim Anteil [anteil] (0..1) der Strecke. Mit Route wird an Punkt [punktIndex] geteilt.
     * Die Uhrzeit des Teilpunkts wird anteilig geschätzt (Route hat keine Zeitstempel),
     * man kann sie danach beim Bearbeiten korrigieren.
     * Ergebnis: (erster Teil = alte ID, zweiter Teil mit [neueId]).
     */
    fun teilen(f: Fahrt, anteil: Double, punktIndex: Int?, neueId: String): Pair<Fahrt, Fahrt> {
        val ende = f.ende ?: f.start
        val punkte = Polyline.decode(f.route)
        val q = anteil.coerceIn(0.0, 1.0)
        val teilZeit = f.start + ((ende - f.start) * q).roundToLong()
        val km1 = f.streckeKm * q
        val km2 = f.streckeKm - km1
        val kmTeil = if (f.kmEnde > f.kmStart) f.kmStart + (f.kmEnde - f.kmStart) * q else f.kmStart + km1
        val (route1, route2) = if (punkte.size >= 2 && punktIndex != null) {
            val i = punktIndex.coerceIn(0, punkte.size - 1)
            Polyline.encode(punkte.subList(0, i + 1)) to Polyline.encode(punkte.subList(i, punkte.size))
        } else {
            "" to ""
        }
        fun schnitt(km: Double, von: Long, bis: Long) = if (bis > von) km / ((bis - von) / 3_600_000.0) else 0.0
        val erster = f.copy(
            ende = teilZeit,
            kmEnde = kmTeil,
            streckeKm = km1,
            schnittKmh = schnitt(km1, f.start, teilZeit),
            zielOrt = "",
            route = route1,
            dirty = true,
        )
        val zweiter = f.copy(
            id = neueId,
            start = teilZeit,
            kmStart = kmTeil,
            streckeKm = km2,
            schnittKmh = schnitt(km2, teilZeit, ende),
            startOrt = "",
            route = route2,
            dirty = true,
            aufServer = false,
        )
        return erster to zweiter
    }

    /** Anteil der Strecke bis Punkt [index] (0..1). */
    fun anteilBeiPunkt(punkte: List<Polyline.Punkt>, index: Int): Double {
        val gesamt = kmBisPunkt(punkte, punkte.size - 1)
        return if (gesamt > 0) kmBisPunkt(punkte, index) / gesamt else 0.0
    }
}
