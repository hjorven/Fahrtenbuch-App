package de.hjorven.fahrtenbuch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.rememberDatePickerState
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
import de.hjorven.fahrtenbuch.data.PbDatum
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Ein Termin vom Server (Roadmap Nr. 16), nur online. */
data class Termin(val id: String, val fahrzeug: String, val art: String, val faellig: Long)

val TERMIN_ARTEN = listOf("tuev" to "TÜV", "inspektion" to "Inspektion", "reifen" to "Reifen", "versicherung" to "Versicherung", "sonstiges" to "Sonstiges")

/**
 * Termine pro Auto (TÜV, Inspektion, Reifen, Versicherung). Liegen nur auf dem Server, das
 * Display erinnert 30 Tage vorher. Ohne Verbindung steht ein Hinweis da.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TermineKarte(vm: HauptViewModel, fahrzeuge: List<Fahrzeug>) {
    val scope = rememberCoroutineScope()
    var neuLaden by remember { mutableIntStateOf(0) }
    var termine by remember { mutableStateOf<List<Termin>?>(null) }
    var fehler by remember { mutableStateOf<String?>(null) }
    var neu by remember { mutableStateOf(false) }
    LaunchedEffect(neuLaden) {
        val r = vm.termineLaden()
        termine = r.first
        fehler = r.second
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            fehler != null -> Text(fehler!!, color = MaterialTheme.colorScheme.error)
            termine == null -> Text("Lädt ...")
            termine!!.isEmpty() -> Text("Noch keine Termine.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        termine?.forEach { t ->
            val auto = fahrzeuge.firstOrNull { it.id == t.fahrzeug }?.name ?: "Auto"
            val art = TERMIN_ARTEN.firstOrNull { it.first == t.art }?.second ?: "Termin"
            val tage = ((t.faellig - System.currentTimeMillis()) / 86_400_000L).toInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$art · $auto")
                    Text(
                        "${datumKurz(t.faellig)} · " + when {
                            tage < 0 -> "seit ${-tage} Tagen fällig"
                            tage == 0 -> "heute"
                            else -> "in $tage Tagen"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tage <= 30) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { scope.launch { vm.terminErledigt(t.id); neuLaden++ } }) { Text("Erledigt") }
            }
        }
        OutlinedButton(onClick = { neu = true }, enabled = fehler == null, modifier = Modifier.fillMaxWidth()) { Text("Termin eintragen") }
    }
    if (neu) {
        var autoId by remember { mutableStateOf(fahrzeuge.firstOrNull()?.id) }
        var art by remember { mutableStateOf("tuev") }
        val datum = rememberDatePickerState()
        var waehlen by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { neu = false },
            title = { Text("Termin eintragen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        fahrzeuge.forEach { f -> FilterChip(f.id == autoId, { autoId = f.id }, { Text(f.name, maxLines = 1) }) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TERMIN_ARTEN.forEach { (w, n) -> FilterChip(art == w, { art = w }, { Text(n) }) }
                    }
                    OutlinedButton(onClick = { waehlen = true }) {
                        Text(datum.selectedDateMillis?.let { "Fällig am ${datumKurz(it)}" } ?: "Datum wählen")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = autoId != null && datum.selectedDateMillis != null, onClick = {
                    val f = autoId!!
                    val d = datum.selectedDateMillis!!
                    neu = false
                    scope.launch { fehler = vm.terminAnlegen(f, art, d); neuLaden++ }
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { neu = false }) { Text("Abbrechen") } },
        )
        if (waehlen) DatePickerDialog(
            onDismissRequest = { waehlen = false },
            confirmButton = { TextButton(onClick = { waehlen = false }) { Text("OK") } },
        ) { DatePicker(datum) }
    }
}
