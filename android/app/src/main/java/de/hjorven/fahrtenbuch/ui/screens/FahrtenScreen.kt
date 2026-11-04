package de.hjorven.fahrtenbuch.ui.screens

import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Map
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.remember
import de.hjorven.fahrtenbuch.ui.ZWECKE
import de.hjorven.fahrtenbuch.ui.monat
import de.hjorven.fahrtenbuch.ui.tagKurz
import java.util.Calendar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hjorven.fahrtenbuch.data.Fahrt
import de.hjorven.fahrtenbuch.data.Fahrzeug
import de.hjorven.fahrtenbuch.ui.HauptViewModel
import de.hjorven.fahrtenbuch.ui.farbe
import de.hjorven.fahrtenbuch.ui.km
import de.hjorven.fahrtenbuch.ui.uhrzeit

/** Zeitraum-Filter der Fahrtenliste und der Karte. */
val ZEITRAEUME = listOf("alle" to "Alle", "monat" to "Dieser Monat", "vormonat" to "Letzter Monat", "jahr" to "Dieses Jahr")

/** [von, bis) für einen Zeitraum-Filter, null = keine Grenze. */
fun zeitraumGrenzen(zeitraum: String): Pair<Long, Long>? {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    c.set(Calendar.DAY_OF_MONTH, 1)
    return when (zeitraum) {
        "monat" -> c.timeInMillis to c.apply { add(Calendar.MONTH, 1) }.timeInMillis
        "vormonat" -> { c.add(Calendar.MONTH, -1); c.timeInMillis to c.apply { add(Calendar.MONTH, 1) }.timeInMillis }
        "jahr" -> { c.set(Calendar.MONTH, 0); c.timeInMillis to c.apply { add(Calendar.YEAR, 1) }.timeInMillis }
        else -> null
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FahrtenScreen(vm: HauptViewModel, oeffnen: (String) -> Unit, karte: () -> Unit) {
    val fahrten by vm.fahrten.collectAsStateWithLifecycle()
    val fahrzeuge by vm.alleFahrzeuge.collectAsStateWithLifecycle()
    val aktive by vm.fahrzeuge.collectAsStateWithLifecycle()
    val gewaehlt by vm.gewaehlt.collectAsStateWithLifecycle()
    val namen by vm.namen.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var fahrerFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var zweckFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var zeitraum by rememberSaveable { mutableStateOf("alle") }
    var mehrFilter by rememberSaveable { mutableStateOf(false) }
    var nachtragen by remember { mutableStateOf(false) }

    val grenzen = zeitraumGrenzen(zeitraum)
    val liste = fahrten.filter {
        (filter == null || it.fahrzeugId == filter) &&
            (fahrerFilter == null || it.fahrerId == fahrerFilter) &&
            (zweckFilter == null || it.zweck == zweckFilter) &&
            (grenzen == null || it.start in grenzen.first until grenzen.second)
    }
    val nachMonat = liste.groupBy { monat(it.start) }
    val fahrer = fahrten.map { it.fahrerId }.distinct().sortedBy { namen[it] ?: it }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(onClick = karte, label = { Text("Karte") }, leadingIcon = { Icon(Icons.Filled.Map, null) })
            // Roadmap Nr. 22: die gerade gefilterten Fahrten als CSV teilen (Mail, Drive, ...)
            val context = androidx.compose.ui.platform.LocalContext.current
            AssistChip(onClick = {
                val autos = fahrzeuge.associate { it.id to it.name }
                val csv = de.hjorven.fahrtenbuch.data.Export.fahrtenCsv(liste, namen, autos)
                val ordner = java.io.File(context.cacheDir, "export").apply { mkdirs() }
                val datei = java.io.File(ordner, "fahrtenbuch-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMANY).format(java.util.Date()) + ".csv")
                datei.writeText(csv)
                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".updates", datei)
                val teilen = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Fahrtenbuch")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(teilen, "Fahrten als CSV teilen"))
            }, label = { Text("Export") }, leadingIcon = { Icon(Icons.Filled.Share, null) })
            FilterChip(selected = mehrFilter, onClick = { mehrFilter = !mehrFilter }, label = { Text("Filter") },
                leadingIcon = { Icon(Icons.Filled.FilterList, null) })
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Alle") })
            fahrzeuge.filter { it.aktiv }.forEach { f ->
                FilterChip(
                    selected = filter == f.id,
                    onClick = { filter = if (filter == f.id) null else f.id },
                    label = { Text(f.name) },
                    leadingIcon = { Icon(Icons.Filled.FiberManualRecord, null, tint = farbe(f.farbe), modifier = Modifier.size(12.dp)) },
                )
            }
        }
        if (mehrFilter) {
            ChipZeile {
                ZEITRAEUME.forEach { (wert, text) ->
                    FilterChip(selected = zeitraum == wert, onClick = { zeitraum = wert }, label = { Text(text) })
                }
            }
            ChipZeile {
                fahrer.forEach { id ->
                    FilterChip(
                        selected = fahrerFilter == id,
                        onClick = { fahrerFilter = if (fahrerFilter == id) null else id },
                        label = { Text(namen[id] ?: "Fahrer") },
                    )
                }
                ZWECKE.forEach { (wert, text) ->
                    FilterChip(
                        selected = zweckFilter == wert,
                        onClick = { zweckFilter = if (zweckFilter == wert) null else wert },
                        label = { Text(text) },
                    )
                }
            }
        }
        if (liste.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (fahrten.isEmpty()) "Noch keine Fahrten" else "Keine Fahrten für diesen Filter",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            nachMonat.forEach { (titel, eintraege) ->
                stickyHeader(key = titel) {
                    Surface(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                titel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                // Test-Fahrten zählen nicht mit.
                                // Test-Fahrten getrennt nennen, sonst steht "0 Fahrten" über einer sichtbaren Fahrt.
                                eintraege.count { it.test }.let { tests ->
                                    val echte = eintraege.filter { !it.test }
                                    "${echte.size} ${if (echte.size == 1) "Fahrt" else "Fahrten"} · ${km(echte.sumOf { it.km })}" +
                                        if (tests > 0) " · $tests Test" else ""
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(eintraege, key = { it.id }) { f ->
                    FahrtZeile(f, fahrzeuge.firstOrNull { it.id == f.fahrzeugId }, namen[f.fahrerId], mitTag = true) { oeffnen(f.id) }
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
        ExtendedFloatingActionButton(
            onClick = { nachtragen = true },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Nachtragen") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (nachtragen) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { nachtragen = false }, sheetState = sheet) {
            FahrtFormular(aktive, null, gewaehlt) { neu ->
                vm.fahrtNachtragen(neu)
                nachtragen = false
            }
        }
    }
}

@Composable
private fun ChipZeile(inhalt: @Composable () -> Unit) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { inhalt() }
}

@Composable
fun FahrtZeile(f: Fahrt, fahrzeug: Fahrzeug?, fahrer: String?, mitTag: Boolean = false, onClick: () -> Unit) {
    val zeit = if (f.ende == null) "seit ${uhrzeit(f.start)}, läuft" else "${uhrzeit(f.start)}–${uhrzeit(f.ende)}"
    val tag = if (mitTag) "${tagKurz(f.start)}, " else ""
    val ziel = f.zielOrt.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
    ListItem(
        headlineContent = { Text("${if (f.test) "TEST · " else ""}${fahrzeug?.name ?: "Auto"} · ${km(f.km)}") },
        supportingContent = { Text("${fahrer ?: "Fahrer"} · $tag$zeit$ziel", maxLines = 1) },
        leadingContent = {
            Box(Modifier.size(12.dp).background(farbe(fahrzeug?.farbe ?: ""), CircleShape))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
