package de.hjorven.fahrtenbuch.data

import kotlin.math.roundToLong

/** Encoded Polyline (Google-Format, Präzision 5), wie im Datenmodell festgelegt. */
object Polyline {
    data class Punkt(val lat: Double, val lon: Double)

    fun encode(punkte: List<Punkt>): String {
        val sb = StringBuilder()
        var lastLat = 0L
        var lastLon = 0L
        for (p in punkte) {
            val lat = (p.lat * 1e5).roundToLong()
            val lon = (p.lon * 1e5).roundToLong()
            wert(lat - lastLat, sb)
            wert(lon - lastLon, sb)
            lastLat = lat
            lastLon = lon
        }
        return sb.toString()
    }

    private fun wert(v: Long, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else (v shl 1)
        while (x >= 0x20) {
            sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
            x = x shr 5
        }
        sb.append((x + 63).toInt().toChar())
    }

    fun decode(text: String): List<Punkt> {
        val result = ArrayList<Punkt>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < text.length) {
            val (dLat, i1) = lese(text, index)
            if (i1 > text.length) break
            val (dLon, i2) = lese(text, i1)
            index = i2
            lat += dLat
            lon += dLon
            result.add(Punkt(lat / 1e5, lon / 1e5))
        }
        return result
    }

    private fun lese(text: String, start: Int): Pair<Long, Int> {
        var index = start
        var shift = 0
        var result = 0L
        while (index < text.length) {
            val b = text[index++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val v = if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)
        return v to index
    }
}
