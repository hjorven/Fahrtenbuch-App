package de.hjorven.fahrtenbuch.data

/**
 * Dieselben Grenzen wie im Server-Hook server/pb_hooks/plausibel.js. Die App prüft vor dem
 * Speichern und zeigt den Fehler an, statt dass der Server den Datensatz später ablehnt.
 */
object Plausibel {
    const val MAX_MENGE = 200.0
    const val MAX_PREIS_EINHEIT = 5.0
    const val MAX_KM_STAND = 2_000_000.0
    /** Kürzere Fahrten sind Fehlstarts und werden verworfen. */
    const val MIN_FAHRT_KM = 0.3
    /** Schneller im Schnitt (Tacho-km durch Fahrzeit) ist ein Tippfehler im km-Stand. */
    const val MAX_SCHNITT_KMH = 250.0

    /** null = in Ordnung, sonst der Fehlertext. */
    fun tank(menge: Double?, preisProEinheit: Double?, kmStand: Double?): String? = when {
        // 0 lehnt der Server ab (Pflichtfeld), die Tankung bliebe sonst im Sync hängen.
        kmStand == null || kmStand == 0.0 -> "Kilometerstand fehlt"
        kmStand < 0 || kmStand > MAX_KM_STAND -> "Kilometerstand ist unplausibel"
        menge == null || menge <= 0 -> "Menge fehlt"
        menge > MAX_MENGE -> "Mehr als ${MAX_MENGE.toInt()} auf einmal ist unplausibel"
        preisProEinheit != null && preisProEinheit > MAX_PREIS_EINHEIT ->
            "Mehr als ${MAX_PREIS_EINHEIT.toInt()} € pro Einheit ist unplausibel"
        else -> null
    }

    /** Spielraum für Handys, deren Uhr etwas vorgeht. */
    const val ZUKUNFT_SPIELRAUM_MS = 10 * 60_000L

    fun fahrt(
        start: Long, ende: Long?, kmStart: Double, kmEnde: Double,
        jetzt: Long = System.currentTimeMillis(),
    ): String? = when {
        ende != null && ende < start -> "Das Ende liegt vor dem Start"
        start > jetzt + ZUKUNFT_SPIELRAUM_MS || (ende ?: 0) > jetzt + ZUKUNFT_SPIELRAUM_MS -> "Die Fahrt liegt in der Zukunft"
        kmStart < 0 || kmEnde < 0 -> "Kilometer dürfen nicht negativ sein"
        kmEnde > 0 && kmEnde < kmStart -> "km am Ende kleiner als am Start"
        kmEnde > MAX_KM_STAND -> "Kilometerstand ist unplausibel"
        zuSchnell(start, ende, kmStart, kmEnde) ->
            "${(kmEnde - kmStart).toLong()} km in dieser Zeit sind unplausibel, bitte km-Stand oder Uhrzeit prüfen"
        else -> null
    }

    /** Nur wenn beide Tacho-Stände bekannt sind: Strecke durch Dauer (mindestens 1 Minute). */
    private fun zuSchnell(start: Long, ende: Long?, kmStart: Double, kmEnde: Double): Boolean {
        if (ende == null || kmStart <= 0 || kmEnde <= kmStart) return false
        val stunden = maxOf(ende - start, 60_000L) / 3_600_000.0
        return (kmEnde - kmStart) / stunden > MAX_SCHNITT_KMH
    }

    /**
     * Erster Tacho-Stand einer Fahrt ohne bekannten Start: der Start ergibt sich aus der
     * GPS-Strecke, damit die Fahrt nicht plötzlich den ganzen Tacho-Stand als Strecke zählt.
     */
    fun startAusTacho(kmStart: Double, kmEnde: Double, gpsKm: Double): Double =
        if (kmStart > 0) kmStart else maxOf(0.0, kmEnde - gpsKm)
}
