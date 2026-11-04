package de.hjorven.fahrtenbuch.ui.screens

import androidx.compose.ui.text.style.TextAlign

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hjorven.fahrtenbuch.data.FahrtBearbeitung
import de.hjorven.fahrtenbuch.data.Polyline
import de.hjorven.fahrtenbuch.ui.HauptViewModel
import de.hjorven.fahrtenbuch.ui.farbe
import de.hjorven.fahrtenbuch.ui.km
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.ln
import kotlin.math.max

/** Alle Fahrten auf einer Karte: als Linien je Auto oder als Heatmap (wo am häufigsten gefahren wird). */
@Composable
fun KarteScreen(vm: HauptViewModel) {
    val fahrten by vm.echteFahrten.collectAsStateWithLifecycle()
    val fahrzeuge by vm.alleFahrzeuge.collectAsStateWithLifecycle()
    var autoFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var zeitraum by rememberSaveable { mutableStateOf("jahr") }
    var heatmap by rememberSaveable { mutableStateOf(false) }

    val grenzen = zeitraumGrenzen(zeitraum)
    val auswahl = fahrten.filter {
        it.ende != null && it.route.isNotEmpty() &&
            (autoFilter == null || it.fahrzeugId == autoFilter) &&
            (grenzen == null || it.start in grenzen.first until grenzen.second)
    }
    // Einmal dekodieren und ausdünnen (Punkte mindestens 40 m auseinander), sonst wird das Zeichnen zäh.
    val routen = remember(auswahl.map { it.id to it.route }) {
        auswahl.map { f -> f.fahrzeugId to ausduennen(Polyline.decode(f.route), 40.0) }.filter { it.second.size >= 2 }
    }
    val farben = fahrzeuge.associate { it.id to farbe(it.farbe).toArgb() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZEITRAEUME.forEach { (wert, text) ->
                FilterChip(selected = zeitraum == wert, onClick = { zeitraum = wert }, label = { Text(text) })
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = autoFilter == null, onClick = { autoFilter = null }, label = { Text("Alle Autos") })
            fahrzeuge.filter { it.aktiv }.forEach { f ->
                FilterChip(
                    selected = autoFilter == f.id,
                    onClick = { autoFilter = if (autoFilter == f.id) null else f.id },
                    label = { Text(f.name) },
                )
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            listOf(false to "Fahrten", true to "Heatmap").forEachIndexed { i, (wert, text) ->
                SegmentedButton(
                    selected = heatmap == wert,
                    onClick = { heatmap = wert },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(text) }
            }
        }
        Text(
            "${auswahl.size} Fahrten mit Strecke · ${km(auswahl.sumOf { it.km })}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
            if (routen.isEmpty()) {
                Text(
                    "Keine aufgezeichneten Strecken in diesem Zeitraum",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                )
            } else {
                GesamtKarte(routen, farben, heatmap)
            }
        }
    }
}

@Composable
private fun GesamtKarte(routen: List<Pair<String, List<Polyline.Punkt>>>, farben: Map<String, Int>, heatmap: Boolean) {
    val context = LocalContext.current
    val karte = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        }
    }
    DisposableEffect(Unit) {
        karte.onResume()
        onDispose { karte.onPause(); karte.onDetach() }
    }
    LaunchedEffect(routen, heatmap) {
        karte.overlays.clear()
        if (heatmap) {
            karte.overlays.add(HeatmapOverlay(routen.map { it.second }))
        } else {
            routen.forEach { (fzId, punkte) ->
                karte.overlays.add(
                    org.osmdroid.views.overlay.Polyline(karte).apply {
                        setPoints(punkte.map { GeoPoint(it.lat, it.lon) })
                        outlinePaint.color = farben[fzId] ?: 0xFF1565C0.toInt()
                        outlinePaint.alpha = 170
                        outlinePaint.strokeWidth = 7f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                    }
                )
            }
        }
        val alle = routen.flatMap { it.second }.map { GeoPoint(it.lat, it.lon) }
        if (alle.size >= 2) {
            val box = BoundingBox.fromGeoPoints(alle).increaseByScale(1.15f)
            if (karte.width > 0) karte.zoomToBoundingBox(box, false)
            else karte.addOnFirstLayoutListener { _, _, _, _, _ -> karte.zoomToBoundingBox(box, false) }
        }
        karte.invalidate()
    }
    AndroidView(factory = { karte }, modifier = Modifier.fillMaxSize())
}

/** Nur Punkte behalten, die mindestens [meter] vom zuletzt behaltenen entfernt sind (letzter Punkt bleibt). */
fun ausduennen(punkte: List<Polyline.Punkt>, meter: Double): List<Polyline.Punkt> {
    if (punkte.size <= 2) return punkte
    val aus = ArrayList<Polyline.Punkt>()
    aus.add(punkte.first())
    for (p in punkte.drop(1)) if (FahrtBearbeitung.meter(aus.last(), p) >= meter) aus.add(p)
    if (aus.last() != punkte.last()) aus.add(punkte.last())
    return aus
}

/**
 * Heatmap: Bildschirm in Zellen teilen und zählen, wie viele Fahrten durch jede Zelle gehen
 * (jede Fahrt zählt pro Zelle höchstens einmal). Häufige Strecken leuchten rot, seltene blau.
 */
private class HeatmapOverlay(private val routen: List<List<Polyline.Punkt>>) : Overlay() {
    private val zelle = 14
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val punkt = android.graphics.Point()

    override fun draw(c: Canvas, karte: MapView, schatten: Boolean) {
        if (schatten) return
        val proj = karte.projection
        val zaehler = HashMap<Long, Int>()
        val gesehen = HashSet<Long>()
        for (route in routen) {
            gesehen.clear()
            var vorher: android.graphics.Point? = null
            for (p in route) {
                proj.toPixels(GeoPoint(p.lat, p.lon), punkt)
                val jetzt = android.graphics.Point(punkt.x, punkt.y)
                // Zwischen zwei Punkten auffüllen, damit Linien beim Hineinzoomen nicht zerfallen.
                val v = vorher
                val schritte = if (v == null) 1 else max(1, max(Math.abs(jetzt.x - v.x), Math.abs(jetzt.y - v.y)) / zelle)
                for (s in 1..schritte) {
                    val x = if (v == null) jetzt.x else v.x + (jetzt.x - v.x) * s / schritte
                    val y = if (v == null) jetzt.y else v.y + (jetzt.y - v.y) * s / schritte
                    if (x < -zelle || y < -zelle || x > c.width + zelle || y > c.height + zelle) continue
                    val schluessel = (Math.floorDiv(x, zelle).toLong() shl 32) or (Math.floorDiv(y, zelle).toLong() and 0xffffffffL)
                    if (gesehen.add(schluessel)) zaehler[schluessel] = (zaehler[schluessel] ?: 0) + 1
                }
                vorher = jetzt
            }
        }
        if (zaehler.isEmpty()) return
        val maxWert = ln((zaehler.values.maxOrNull() ?: 1).toDouble() + 1.0)
        for ((schluessel, n) in zaehler) {
            val x = (schluessel shr 32).toInt() * zelle + zelle / 2f
            val y = (schluessel and 0xffffffffL).toInt() * zelle + zelle / 2f
            val anteil = (ln(n + 1.0) / maxWert).toFloat().coerceIn(0f, 1f)
            paint.color = farbverlauf(anteil)
            c.drawCircle(x, y, zelle * 0.8f, paint)
        }
    }

    /** blau (selten) -> gelb -> rot (oft), halb durchsichtig */
    private fun farbverlauf(t: Float): Int {
        val (r, g, b) = if (t < 0.5f) {
            val u = t / 0.5f
            Triple((30 + u * 225).toInt(), (120 + u * 100).toInt(), (220 - u * 190).toInt())
        } else {
            val u = (t - 0.5f) / 0.5f
            Triple(255, (220 - u * 190).toInt(), 30)
        }
        return android.graphics.Color.argb((110 + t * 120).toInt(), r, g, b)
    }
}
