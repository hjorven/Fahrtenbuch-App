package de.hjorven.fahrtenbuch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.hjorven.fahrtenbuch.data.Fahrzeug
import kotlinx.coroutines.launch

/** Sonstige Kosten vom Server (Roadmap Nr. 25), nur online. */
data class Kosten(val id: String, val fahrzeug: String, val art: String, val datum: Long, val betrag: Double, val notiz: String, val test: Boolean)

val KOSTEN_ARTEN = listOf(
    "werkstatt" to "Werkstatt", "versicherung" to "Versicherung", "steuer" to "Steuer",
    "waesche" to "Wäsche", "parken" to "Parken", "sonstiges" to "Sonstiges",
)

/** Betrag aus der Eingabe ("189,90", "1.200,50", "45") oder null, wenn unsinnig (wie der Server: 0 bis 50 000). */
fun betragLesen(text: String): Double? {
    val t = text.trim().replace("€", "").trim()
    val normal = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
    val v = normal.toDoubleOrNull() ?: return null
    return if (v > 0 && v <= 50_000) Math.round(v * 100) / 100.0 else null
}

/**
 * Werkstatt, Versicherung, Steuer, Parken ... pro Auto. Liegen nur auf dem Server, das Display
 * zählt sie bei den Kosten mit (Test-Einträge nicht).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun KostenKarte(vm: HauptViewModel, fahrzeuge: List<Fahrzeug>) {
    val scope = rememberCoroutineScope()
    var neuLaden by remember { mutableIntStateOf(0) }
    var liste by remember { mutableStateOf<List<Kosten>?>(null) }
    var fehler by remember { mutableStateOf<String?>(null) }
    var neu by remember { mutableStateOf(false) }
    LaunchedEffect(neuLaden) {
        val r = vm.kostenLaden()
        liste = r.first
        fehler = r.second
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            fehler != null -> Text(fehler!!, color = MaterialTheme.colorScheme.error)
            liste == null -> Text("Lädt ...")
            liste!!.isEmpty() -> Text("Noch keine sonstigen Kosten.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        liste?.take(10)?.forEach { k ->
            val auto = fahrzeuge.firstOrNull { it.id == k.fahrzeug }?.name ?: "Auto"
            val art = KOSTEN_ARTEN.firstOrNull { it.first == k.art }?.second ?: "Kosten"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${if (k.test) "TEST · " else ""}$art · $auto")
                    Text(
                        datumKurz(k.datum) + if (k.notiz.isNotBlank()) " · ${k.notiz}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(euro(k.betrag))
                TextButton(onClick = { scope.launch { vm.kostenLoeschen(k.id); neuLaden++ } }) { Text("Löschen") }
            }
        }
        OutlinedButton(onClick = { neu = true }, enabled = fehler == null, modifier = Modifier.fillMaxWidth()) { Text("Kosten eintragen") }
    }
    if (neu) {
        var autoId by remember { mutableStateOf(fahrzeuge.firstOrNull()?.id) }
        var art by remember { mutableStateOf("werkstatt") }
        var betrag by remember { mutableStateOf("") }
        var notiz by remember { mutableStateOf("") }
        val datum = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        var waehlen by remember { mutableStateOf(false) }
        val wert = betragLesen(betrag)
        val zukunft = (datum.selectedDateMillis ?: 0L) > System.currentTimeMillis() + 3_600_000L
        AlertDialog(
            onDismissRequest = { neu = false },
            title = { Text("Kosten eintragen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        fahrzeuge.forEach { f -> FilterChip(f.id == autoId, { autoId = f.id }, { Text(f.name, maxLines = 1) }) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        KOSTEN_ARTEN.forEach { (w, n) -> FilterChip(art == w, { art = w }, { Text(n) }) }
                    }
                    OutlinedTextField(
                        betrag, { betrag = it }, label = { Text("Betrag in €") }, singleLine = true,
                        isError = betrag.isNotBlank() && wert == null,
                        supportingText = { if (betrag.isNotBlank() && wert == null) Text("Bitte einen Betrag zwischen 0 und 50 000 € eingeben") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(notiz, { notiz = it.take(200) }, label = { Text("Notiz (optional)") }, singleLine = true)
                    OutlinedButton(onClick = { waehlen = true }) {
                        Text(datum.selectedDateMillis?.let { "Am ${datumKurz(it)}" } ?: "Datum wählen")
                    }
                    if (zukunft) Text("Das Datum liegt in der Zukunft.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = autoId != null && wert != null && datum.selectedDateMillis != null && !zukunft, onClick = {
                    val f = autoId!!
                    val d = datum.selectedDateMillis!!
                    neu = false
                    scope.launch { fehler = vm.kostenAnlegen(f, art, d, wert!!, notiz.trim()); neuLaden++ }
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
