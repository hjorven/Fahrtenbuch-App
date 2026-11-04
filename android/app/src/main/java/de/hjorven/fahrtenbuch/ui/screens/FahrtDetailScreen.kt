package de.hjorven.fahrtenbuch.ui.screens

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.rememberUpdatedState
import android.widget.Toast
import android.content.Intent
import android.graphics.Paint
import androidx.compose.material.icons.filled.Share
import de.hjorven.fahrtenbuch.location.Diagnose
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Speed
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import de.hjorven.fahrtenbuch.data.Fahrt
import de.hjorven.fahrtenbuch.data.FahrtBearbeitung
import de.hjorven.fahrtenbuch.data.Plausibel
import de.hjorven.fahrtenbuch.ui.alsZahl
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hjorven.fahrtenbuch.data.Polyline
import de.hjorven.fahrtenbuch.ui.HauptViewModel
import de.hjorven.fahrtenbuch.ui.TachoScanKnopf
import de.hjorven.fahrtenbuch.ui.ZWECKE
import de.hjorven.fahrtenbuch.ui.datum
import de.hjorven.fahrtenbuch.ui.dauer
import de.hjorven.fahrtenbuch.ui.farbe
import de.hjorven.fahrtenbuch.ui.km
import de.hjorven.fahrtenbuch.ui.kmStand
import de.hjorven.fahrtenbuch.ui.uhrzeit
import de.hjorven.fahrtenbuch.ui.zahl
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FahrtDetailScreen(vm: HauptViewModel, id: String, zurueck: () -> Unit, oeffnen: (String) -> Unit) {
    val fahrt by remember(id) { vm.fahrtBeobachten(id) }.collectAsState(initial = null)
    val fahrzeuge by vm.alleFahrzeuge.collectAsStateWithLifecycle()
    val aktive by vm.fahrzeuge.collectAsStateWithLifecycle()
    val namen by vm.namen.collectAsStateWithLifecycle()
    val f = fahrt ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val fz = fahrzeuge.firstOrNull { it.id == f.fahrzeugId }
    val eigene = f.fahrerId == vm.userId
    val beendet = f.ende != null
    var zweck by remember(f.id) { mutableStateOf(f.zweck) }
    var notiz by remember(f.id) { mutableStateOf(f.notiz) }
    var dialog by remember { mutableStateOf<String?>(null) }
    // Zurück ohne Speichern verliert sonst still die Änderungen an Zweck und Notiz:
    // beim Verlassen der Ansicht automatisch speichern und kurz bestätigen.
    val stand by rememberUpdatedState(Triple(f, zweck, notiz))
    val geloescht = remember(f.id) { booleanArrayOf(false) }
    DisposableEffect(f.id) {
        onDispose {
            val (alt, z, n) = stand
            if (alt.fahrerId == vm.userId && !alt.geloescht && !geloescht[0] && (z != alt.zweck || n.trim() != alt.notiz)) {
                vm.fahrtAendern(alt, z, n)
                Toast.makeText(context, "Zweck und Notiz gespeichert", Toast.LENGTH_SHORT).show()
            }
        }
    }
    var naechste by remember(f.id, f.ende) { mutableStateOf<Fahrt?>(null) }
    LaunchedEffect(f.id, f.ende) { naechste = vm.naechsteFahrt(f) }

    val punkte = remember(f.route) { Polyline.decode(f.route) }
    // Teilen: gewählter Punkt auf der Route bzw. Anteil der Strecke ohne Route.
    var teilPunkt by remember(f.id) { mutableIntStateOf(punkte.size / 2) }
    var teilAnteil by remember(f.id) { mutableFloatStateOf(0.5f) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (punkte.size >= 2) {
            Card(Modifier.fillMaxWidth().height(280.dp)) {
                Karte(punkte, farbe(fz?.farbe ?: "").toArgb(), markierung = if (dialog == "teilen") teilPunkt else null)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${if (f.test) "TEST · " else ""}${fz?.name ?: "Auto"} · ${km(f.km)}", style = MaterialTheme.typography.titleLarge)
                Text("${namen[f.fahrerId] ?: "Fahrer"} · ${datum(f.start)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Zeile("Zeit", f.ende?.let { "${uhrzeit(f.start)}–${uhrzeit(it)} (${dauer(it - f.start)})" } ?: "läuft seit ${uhrzeit(f.start)}")
                if (f.startOrt.isNotBlank() || f.zielOrt.isNotBlank()) Zeile("Strecke", "${f.startOrt} → ${f.zielOrt}")
                Zeile("Kilometerstand", "${kmStand(f.kmStart)} → ${kmStand(f.kmEnde)}")
                if (f.route.isNotEmpty() && kotlin.math.abs(f.km - f.streckeKm) > 0.05) Zeile("Laut GPS", km(f.streckeKm))
                // Eine live gestartete Fahrt ohne GPS-Empfang hat ebenfalls keine Route; nur mit Diagnose
                // (Test-Modus) lässt sie sich sicher von einer nachgetragenen unterscheiden.
                if (f.route.isEmpty() && beendet) Zeile(
                    "Erfasst",
                    if (f.diagnose.isNotEmpty()) "live, aber ohne GPS-Empfang" else "ohne GPS-Strecke (nachgetragen oder kein Empfang)",
                )
                Zeile("Durchschnitt", "${zahl(f.schnittKmh, 0)} km/h")
                if (f.maxKmh > 0) Zeile("Höchstgeschwindigkeit", "${zahl(f.maxKmh, 0)} km/h")
            }
        }

        // Roadmap Nr. 6: vorläufige Fahrt bestätigen, Mitfahrer eintragen.
        if (eigene && f.vorlaeufig && beendet) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("War das deine Fahrt?", style = MaterialTheme.typography.titleMedium)
                    Text("Automatisch aufgezeichnet. Sie zählt erst, wenn du sie bestätigst.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.vorlaeufigBestaetigen(f) }) { Text("Ja, meine Fahrt") }
                        OutlinedButton(onClick = { dialog = "loeschen" }) { Text("Löschen") }
                    }
                    Text("Ist jemand anderes gefahren? Unten \"Fahrer ändern\".",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (beendet && !f.test) {
            var mitFehler by remember(f.id) { mutableStateOf<String?>(null) }
            val ichDabei = vm.userId in f.mitfahrerIds
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Zeile("Mitfahrer", f.mitfahrerIds.joinToString(", ") { namen[it] ?: "jemand" }.ifEmpty { "niemand" })
                    if (f.mitfahrerIds.isNotEmpty()) Text(
                        "Die Kosten der Fahrt teilen sich ${1 + f.mitfahrerIds.size} Personen, die km zählen beim Fahrer.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!eigene) OutlinedButton(onClick = { scope.launch { mitFehler = vm.mitfahren(f, raus = ichDabei) } }) {
                        Text(if (ichDabei) "Ich war doch nicht dabei" else "Ich bin mitgefahren")
                    }
                    mitFehler?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        if (f.test) {
            val diagnose = remember(f.diagnose) { Diagnose.ausJson(f.diagnose) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Test-Fahrt", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Zählt nicht in Statistik, km-Stand und Display.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    diagnose?.zeilen()?.forEach { (titel, wert) -> Zeile(titel, wert) }
                        ?: Text("Keine Diagnose gespeichert.")
                    OutlinedButton(onClick = {
                        val text = buildString {
                            appendLine("Test-Fahrt ${fz?.name ?: ""}, ${datum(f.start)}, ${km(f.km)}")
                            diagnose?.zeilen()?.forEach { (t, w) -> appendLine("$t: $w") }
                            appendLine()
                            append(f.diagnose)
                        }
                        val senden = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Fahrtenbuch Diagnose")
                            .putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(senden, "Diagnose exportieren"))
                    }) {
                        Icon(Icons.Filled.Share, null)
                        Text(" Diagnose exportieren")
                    }
                }
            }
        }

        Text("Zweck", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ZWECKE.forEachIndexed { i, (wert, text) ->
                SegmentedButton(
                    selected = zweck == wert,
                    onClick = { if (eigene) zweck = wert },
                    enabled = eigene,
                    shape = SegmentedButtonDefaults.itemShape(i, ZWECKE.size),
                ) { Text(text) }
            }
        }
        OutlinedTextField(
            notiz, { notiz = it },
            label = { Text("Notiz") },
            enabled = eigene,
            modifier = Modifier.fillMaxWidth(),
        )
        if (eigene) {
            if (zweck != f.zweck || notiz.trim() != f.notiz) {
                Button(onClick = {
                    vm.fahrtAendern(f, zweck, notiz)
                    Toast.makeText(context, "Gespeichert", Toast.LENGTH_SHORT).show()
                }) { Text("Zweck und Notiz speichern") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (beendet) {
                    OutlinedButton(onClick = { dialog = "tacho" }) {
                        Icon(Icons.Filled.Speed, null)
                        Text(" Tacho-Stand")
                    }
                    OutlinedButton(onClick = { dialog = "bearbeiten" }) {
                        Icon(Icons.Filled.Edit, null)
                        Text(" Bearbeiten")
                    }
                    if (f.streckeKm > 0.2 || punkte.size >= 3) {
                        OutlinedButton(onClick = { dialog = "teilen" }) {
                            Icon(Icons.Filled.ContentCut, null)
                            Text(" Teilen")
                        }
                    }
                    naechste?.let { n ->
                        OutlinedButton(onClick = { dialog = "zusammen" }) {
                            Icon(Icons.Filled.MergeType, null)
                            Text(" Mit ${uhrzeit(n.start)} zusammenlegen")
                        }
                    }
                }
                // Roadmap Nr. 6: Die ganze Fahrt an jemand anderen geben (nur wenn sie schon auf dem Server ist).
                if (beendet && f.aufServer && !f.test) OutlinedButton(onClick = { dialog = "fahrer" }) { Text("Fahrer ändern") }
                OutlinedButton(onClick = { dialog = "loeschen" }) {
                    Icon(Icons.Filled.Delete, null)
                    Text(" Löschen")
                }
            }
        } else {
            Text(
                "Nur ${namen[f.fahrerId] ?: "der Fahrer"} kann diese Fahrt bearbeiten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    when (dialog) {
        "fahrer" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Wer ist gefahren?") },
            text = {
                val andere = namen.filterKeys { it != vm.userId && it != de.hjorven.fahrtenbuch.data.Prefs.LOKAL }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (andere.isEmpty()) Text("Noch keine anderen Personen bekannt. Einmal synchronisieren.")
                    andere.forEach { (id, name) ->
                        TextButton(onClick = { geloescht[0] = true; vm.fahrerAendern(f, id); dialog = null; zurueck() }) { Text(name) }
                    }
                    Text("Die Fahrt gehört dann ihr bzw. ihm und zählt dort. Zurück geht es nur über diese Person.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Abbrechen") } },
        )
        "loeschen" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Fahrt löschen?") },
            text = { Text("Die Fahrt verschwindet auf allen Geräten.") },
            confirmButton = {
                TextButton(onClick = { geloescht[0] = true; vm.fahrtLoeschen(f); dialog = null; zurueck() }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Abbrechen") } },
        )
        "tacho" -> {
            var eingabe by remember { mutableStateOf(zahl(f.kmEnde, 0)) }
            val wert = eingabe.replace(".", "").alsZahl()
            val fehler = when {
                wert == null -> "Zahl eingeben"
                f.kmStart > 0 && wert <= f.kmStart -> "Muss größer als der Start (${kmStand(f.kmStart)}) sein"
                else -> Plausibel.fahrt(f.start, f.ende, Plausibel.startAusTacho(f.kmStart, wert, f.streckeKm), wert)
            }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Tacho-Stand am Ende") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Was zeigt der Tacho nach dieser Fahrt? Die GPS-Strecke bleibt zum Vergleich gespeichert.")
                        TachoScanKnopf(bisher = f.kmStart, modifier = Modifier.fillMaxWidth()) { eingabe = it.toString() }
                        OutlinedTextField(
                            eingabe, { eingabe = it },
                            label = { Text("km-Stand") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = fehler != null,
                            supportingText = { fehler?.let { Text(it) } },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.fahrtTacho(f, wert!!); dialog = null }, enabled = fehler == null) { Text("Speichern") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Abbrechen") } },
            )
        }
        "bearbeiten" -> {
            val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = { dialog = null }, sheetState = sheet) {
                FahrtFormular(aktive.ifEmpty { fahrzeuge }, f, f.fahrzeugId) { neu ->
                    vm.fahrtSpeichern(neu)
                    zweck = neu.zweck
                    notiz = neu.notiz
                    dialog = null
                }
            }
        }
        "zusammen" -> naechste?.let { n ->
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Fahrten zusammenlegen?") },
                text = {
                    Text(
                        "Diese Fahrt (${uhrzeit(f.start)}, ${km(f.km)}) und die nächste " +
                            "(${uhrzeit(n.start)}, ${km(n.km)}) werden eine Fahrt bis ${n.ende?.let { uhrzeit(it) } ?: ""}."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.zusammenlegen(f, n); dialog = null }) { Text("Zusammenlegen") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Abbrechen") } },
            )
        }
        "teilen" -> {
            val mitRoute = punkte.size >= 3
            val anteil = if (mitRoute) FahrtBearbeitung.anteilBeiPunkt(punkte, teilPunkt) else teilAnteil.toDouble()
            val ende = f.ende ?: f.start
            val zeit = f.start + ((ende - f.start) * anteil).toLong()
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Fahrt teilen") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (mitRoute) "Punkt auf der Strecke wählen, die Karte zeigt ihn."
                            else "Anteil der Strecke wählen."
                        )
                        if (mitRoute) {
                            Slider(
                                value = teilPunkt.toFloat(),
                                onValueChange = { teilPunkt = it.toInt() },
                                valueRange = 1f..(punkte.size - 2).toFloat().coerceAtLeast(1f),
                            )
                        } else {
                            Slider(value = teilAnteil, onValueChange = { teilAnteil = it }, valueRange = 0.05f..0.95f)
                        }
                        Text("Teil 1: ${km(f.streckeKm * anteil)} bis etwa ${uhrzeit(zeit)}")
                        Text("Teil 2: ${km(f.streckeKm * (1 - anteil))} ab etwa ${uhrzeit(zeit)}")
                        Text(
                            "Die Uhrzeit ist geschätzt, sie lässt sich danach unter Bearbeiten anpassen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        scope.launch { oeffnen(vm.teilen(f, anteil, if (mitRoute) teilPunkt else null)) }
                    }) { Text("Teilen") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Abbrechen") } },
            )
        }
    }
}

@Composable
private fun Zeile(titel: String, wert: String) {
    // Abstand und rechtsbündiger Wert: bei langen, umbrechenden Werten liefen Titel und Wert sonst ineinander.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(titel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(wert, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Karte(punkte: List<Polyline.Punkt>, farbe: Int, markierung: Int? = null) {
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
    LaunchedEffect(punkte, markierung) {
        val geo = punkte.map { GeoPoint(it.lat, it.lon) }
        karte.overlays.clear()
        karte.overlays.add(
            org.osmdroid.views.overlay.Polyline(karte).apply {
                setPoints(geo)
                outlinePaint.color = farbe
                outlinePaint.strokeWidth = 10f
                outlinePaint.strokeCap = Paint.Cap.ROUND
            }
        )
        markierung?.let { i -> punkte.getOrNull(i) }?.let { p ->
            karte.overlays.add(
                org.osmdroid.views.overlay.Marker(karte).apply {
                    position = GeoPoint(p.lat, p.lon)
                    setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                    title = "Hier teilen"
                }
            )
        }
        val box = BoundingBox.fromGeoPoints(geo)
        if (karte.width > 0) {
            karte.zoomToBoundingBox(box.increaseByScale(1.3f), false)
        } else {
            karte.addOnFirstLayoutListener { _, _, _, _, _ -> karte.zoomToBoundingBox(box.increaseByScale(1.3f), false) }
        }
        karte.invalidate()
    }
    AndroidView(factory = { karte }, modifier = Modifier.fillMaxWidth().height(280.dp))
}
