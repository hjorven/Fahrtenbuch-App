package de.hjorven.fahrtenbuch.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.hjorven.fahrtenbuch.data.Fahrt
import de.hjorven.fahrtenbuch.data.Fahrzeug
import de.hjorven.fahrtenbuch.data.Plausibel
import de.hjorven.fahrtenbuch.ui.ZWECKE
import de.hjorven.fahrtenbuch.ui.TachoScanKnopf
import de.hjorven.fahrtenbuch.ui.alsZahl
import de.hjorven.fahrtenbuch.ui.datumKurz
import de.hjorven.fahrtenbuch.ui.uhrzeit
import de.hjorven.fahrtenbuch.ui.zahl
import java.util.Calendar
import java.util.TimeZone

/**
 * Fahrt bearbeiten oder nachtragen ([vorlage] = null).
 * Bei Fahrten mit GPS bleibt die GPS-Strecke stehen, km_start/km_ende sind die Tacho-Stände.
 * Bei nachgetragenen Fahrten ergibt sich die Strecke aus km_ende - km_start.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FahrtFormular(
    fahrzeuge: List<Fahrzeug>,
    vorlage: Fahrt?,
    vorauswahl: String?,
    speichern: (Fahrt) -> Unit,
) {
    val jetzt = remember { System.currentTimeMillis() }
    var fzId by remember { mutableStateOf(vorlage?.fahrzeugId ?: vorauswahl ?: fahrzeuge.firstOrNull()?.id) }
    val fz = fahrzeuge.firstOrNull { it.id == fzId }
    var start by remember { mutableLongStateOf(vorlage?.start ?: (jetzt - 30 * 60_000L)) }
    var ende by remember { mutableLongStateOf(vorlage?.ende ?: jetzt) }
    var kmStart by remember(fzId) {
        mutableStateOf((vorlage?.kmStart ?: fz?.kmStand)?.takeIf { it > 0 }?.let { zahl(it, 0) } ?: "")
    }
    var kmEnde by remember { mutableStateOf(vorlage?.kmEnde?.takeIf { it > 0 }?.let { zahl(it, 0) } ?: "") }
    var zweck by remember { mutableStateOf(vorlage?.zweck ?: "privat") }
    var startOrt by remember { mutableStateOf(vorlage?.startOrt ?: "") }
    var zielOrt by remember { mutableStateOf(vorlage?.zielOrt ?: "") }
    var notiz by remember { mutableStateOf(vorlage?.notiz ?: "") }
    var waehlen by remember { mutableStateOf<String?>(null) }

    val mitGps = vorlage != null && vorlage.route.isNotEmpty()
    val kS = kmStart.replace(".", "").alsZahl() ?: 0.0
    val kE = kmEnde.replace(".", "").alsZahl() ?: 0.0
    val fehler = when {
        fz == null -> "Auto wählen"
        !mitGps && (kE <= 0 || kE <= kS) -> "km-Stand am Ende eintragen (größer als am Start)"
        else -> Plausibel.fahrt(start, ende, kS, kE)
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (vorlage == null) "Fahrt nachtragen" else "Fahrt bearbeiten", style = MaterialTheme.typography.titleLarge)
        // Umbrechen statt seitlich scrollen: sonst wird der letzte Autoname abgeschnitten ("VW Kombi 4 Ko").
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fahrzeuge.forEach { f ->
                FilterChip(selected = f.id == fzId, onClick = { fzId = f.id }, label = { Text(f.name, maxLines = 1) })
            }
        }
        // Feste Breiten-Anteile und kleinerer Innenabstand, damit "bis 21:40" nicht zweizeilig wird.
        val knopfInnen = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { waehlen = "datum" }, contentPadding = knopfInnen, modifier = Modifier.weight(1.2f)) {
                Text(datumKurz(start), maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = { waehlen = "start" }, contentPadding = knopfInnen, modifier = Modifier.weight(1f)) {
                Text("ab ${uhrzeit(start)}", maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = { waehlen = "ende" }, contentPadding = knopfInnen, modifier = Modifier.weight(1f)) {
                Text("bis ${uhrzeit(ende)}", maxLines = 1, softWrap = false)
            }
        }
        val zahlTastatur = KeyboardOptions(keyboardType = KeyboardType.Number)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(kmStart, { kmStart = it }, label = { Text("km-Stand Start") }, singleLine = true,
                keyboardOptions = zahlTastatur, modifier = Modifier.weight(1f))
            OutlinedTextField(kmEnde, { kmEnde = it }, label = { Text("km-Stand Ende") }, singleLine = true,
                keyboardOptions = zahlTastatur, modifier = Modifier.weight(1f))
        }
        // Foto vom Tacho füllt den km-Stand am Ende aus.
        TachoScanKnopf(bisher = if (kS > 0) kS else fz?.kmStand ?: 0.0, modifier = Modifier.fillMaxWidth()) {
            kmEnde = it.toString()
        }
        if (mitGps && vorlage != null) {
            Text(
                "GPS-Strecke: ${zahl(vorlage.streckeKm, 1)} km. Mit den Tacho-Ständen zählen die Tacho-km.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ZWECKE.forEachIndexed { i, (wert, text) ->
                SegmentedButton(
                    selected = zweck == wert,
                    onClick = { zweck = wert },
                    shape = SegmentedButtonDefaults.itemShape(i, ZWECKE.size),
                ) { Text(text) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(startOrt, { startOrt = it }, label = { Text("Von") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(zielOrt, { zielOrt = it }, label = { Text("Nach") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(notiz, { notiz = it }, label = { Text("Notiz") }, modifier = Modifier.fillMaxWidth())
        fehler?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                val dauerH = (ende - start) / 3_600_000.0
                val basis = vorlage ?: Fahrt(id = "", fahrzeugId = fz!!.id, fahrerId = "", start = start)
                val strecke = if (mitGps) basis.streckeKm else (kE - kS).coerceAtLeast(0.0)
                speichern(
                    basis.copy(
                        fahrzeugId = fz!!.id,
                        start = start,
                        ende = ende,
                        kmStart = kS,
                        kmEnde = kE,
                        streckeKm = strecke,
                        schnittKmh = if (dauerH > 0) (if (kE > kS) kE - kS else strecke) / dauerH else 0.0,
                        zweck = zweck,
                        startOrt = startOrt.trim(),
                        zielOrt = zielOrt.trim(),
                        notiz = notiz,
                    )
                )
            },
            enabled = fehler == null,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Speichern") }
        Spacer(Modifier.height(16.dp))
    }

    when (waehlen) {
        "datum" -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = alsUtcTag(start))
            DatePickerDialog(
                onDismissRequest = { waehlen = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { tag ->
                            val dauer = ende - start
                            start = tagSetzen(start, tag)
                            ende = start + dauer
                        }
                        waehlen = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { waehlen = null }) { Text("Abbrechen") } },
            ) { DatePicker(state) }
        }
        "start", "ende" -> {
            val wert = if (waehlen == "start") start else ende
            val c = Calendar.getInstance().apply { timeInMillis = wert }
            val state = rememberTimePickerState(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), is24Hour = true)
            AlertDialog(
                onDismissRequest = { waehlen = null },
                title = { Text(if (waehlen == "start") "Abfahrt" else "Ankunft") },
                text = { TimePicker(state) },
                confirmButton = {
                    TextButton(onClick = {
                        if (waehlen == "start") {
                            val dauer = ende - start
                            start = zeitSetzen(start, state.hour, state.minute)
                            ende = start + dauer
                        } else {
                            var neu = zeitSetzen(start, state.hour, state.minute)
                            // Ankunft vor Abfahrt: Fahrt ging über Mitternacht.
                            if (neu < start) neu += 24 * 3_600_000L
                            ende = neu
                        }
                        waehlen = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { waehlen = null }) { Text("Abbrechen") } },
            )
        }
    }
}

/** Lokales Datum von [millis] als UTC-Mitternacht (so erwartet es der DatePicker). */
internal fun alsUtcTag(millis: Long): Long {
    val lokal = Calendar.getInstance().apply { timeInMillis = millis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(lokal.get(Calendar.YEAR), lokal.get(Calendar.MONTH), lokal.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** Datum aus dem DatePicker übernehmen, Uhrzeit von [millis] behalten. */
internal fun tagSetzen(millis: Long, utcTag: Long): Long {
    val tag = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcTag }
    return Calendar.getInstance().apply {
        timeInMillis = millis
        set(tag.get(Calendar.YEAR), tag.get(Calendar.MONTH), tag.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun zeitSetzen(millis: Long, stunde: Int, minute: Int): Long =
    Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, stunde)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
