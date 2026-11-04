package de.hjorven.fahrtenbuch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.hjorven.fahrtenbuch.data.Fahrzeug
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/** Eine Reservierung vom Server (Roadmap Nr. 17), nur online. */
data class Reservierung(val id: String, val fahrzeug: String, val wer: String, val von: Long, val bis: Long)

/** Tag aus dem DatePicker (UTC-Mitternacht) plus lokale Uhrzeit. */
internal fun tagUndZeit(utcTag: Long, stunde: Int, minute: Int): Long {
    val t = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcTag }
    return Calendar.getInstance().apply {
        clear()
        set(t.get(Calendar.YEAR), t.get(Calendar.MONTH), t.get(Calendar.DAY_OF_MONTH), stunde, minute)
    }.timeInMillis
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReservierungenKarte(vm: HauptViewModel, fahrzeuge: List<Fahrzeug>, namen: Map<String, String>) {
    val scope = rememberCoroutineScope()
    var neuLaden by remember { mutableIntStateOf(0) }
    var liste by remember { mutableStateOf<List<Reservierung>?>(null) }
    var fehler by remember { mutableStateOf<String?>(null) }
    var neu by remember { mutableStateOf(false) }
    LaunchedEffect(neuLaden) {
        val r = vm.reservierungenLaden()
        liste = r.first
        fehler = r.second
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            fehler != null -> Text(fehler!!, color = MaterialTheme.colorScheme.error)
            liste == null -> Text("Lädt ...")
            liste!!.isEmpty() -> Text("Keine Reservierungen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        liste?.forEach { r ->
            val auto = fahrzeuge.firstOrNull { it.id == r.fahrzeug }?.name ?: "Auto"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$auto · ${namen[r.wer] ?: "jemand"}")
                    Text("${datumKurz(r.von)} ${uhrzeit(r.von)}–" + (if (datumKurz(r.von) == datumKurz(r.bis)) uhrzeit(r.bis) else "${datumKurz(r.bis)} ${uhrzeit(r.bis)}"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (r.wer == vm.userId) TextButton(onClick = { scope.launch { vm.reservierungLoeschen(r.id); neuLaden++ } }) { Text("Löschen") }
            }
        }
        OutlinedButton(onClick = { neu = true }, enabled = fehler == null, modifier = Modifier.fillMaxWidth()) { Text("Auto reservieren") }
    }
    if (neu) {
        var autoId by remember { mutableStateOf(fahrzeuge.firstOrNull()?.id) }
        val tag = rememberDatePickerState()
        val von = rememberTimePickerState(9, 0, true)
        val bis = rememberTimePickerState(12, 0, true)
        var waehlen by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { neu = false },
            title = { Text("Auto reservieren") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        fahrzeuge.forEach { f -> FilterChip(f.id == autoId, { autoId = f.id }, { Text(f.name, maxLines = 1) }) }
                    }
                    OutlinedButton(onClick = { waehlen = "tag" }) { Text(tag.selectedDateMillis?.let { "Am ${datumKurz(it)}" } ?: "Tag wählen") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { waehlen = "von" }) { Text("ab %02d:%02d".format(von.hour, von.minute)) }
                        OutlinedButton(onClick = { waehlen = "bis" }) { Text("bis %02d:%02d".format(bis.hour, bis.minute)) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = autoId != null && tag.selectedDateMillis != null, onClick = {
                    val d = tag.selectedDateMillis!!
                    val start = tagUndZeit(d, von.hour, von.minute)
                    val ende = tagUndZeit(d, bis.hour, bis.minute)
                    val f = autoId!!
                    neu = false
                    // Server prüft Überschneidung und Reihenfolge; dessen Text wird angezeigt.
                    scope.launch { fehler = vm.reservierungAnlegen(f, start, ende); neuLaden++ }
                }) { Text("Reservieren") }
            },
            dismissButton = { TextButton(onClick = { neu = false }) { Text("Abbrechen") } },
        )
        when (waehlen) {
            "tag" -> DatePickerDialog(onDismissRequest = { waehlen = null },
                confirmButton = { TextButton(onClick = { waehlen = null }) { Text("OK") } }) { DatePicker(tag) }
            "von", "bis" -> AlertDialog(
                onDismissRequest = { waehlen = null },
                confirmButton = { TextButton(onClick = { waehlen = null }) { Text("OK") } },
                text = { TimePicker(if (waehlen == "von") von else bis) },
            )
        }
    }
}
