package de.hjorven.fahrtenbuch.ui.screens

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePicker
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hjorven.fahrtenbuch.data.Fahrzeug
import de.hjorven.fahrtenbuch.data.Plausibel
import de.hjorven.fahrtenbuch.data.Tankvorgang
import de.hjorven.fahrtenbuch.data.einheit
import de.hjorven.fahrtenbuch.data.elektrisch
import de.hjorven.fahrtenbuch.ui.BelegBild
import de.hjorven.fahrtenbuch.ui.BelegScan
import de.hjorven.fahrtenbuch.ui.BelegScanKnoepfe
import de.hjorven.fahrtenbuch.ui.HauptViewModel
import de.hjorven.fahrtenbuch.ui.TachoScanKnopf
import java.io.File
import de.hjorven.fahrtenbuch.ui.alsZahl
import de.hjorven.fahrtenbuch.ui.datumKurz
import de.hjorven.fahrtenbuch.ui.euro
import de.hjorven.fahrtenbuch.ui.farbe
import de.hjorven.fahrtenbuch.ui.kmStand
import de.hjorven.fahrtenbuch.ui.zahl

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TankenScreen(vm: HauptViewModel) {
    var mitKamera by remember { mutableStateOf(true) }
    val testModus by vm.testModus.collectAsStateWithLifecycle()
    val tankungen by vm.tankungen.collectAsStateWithLifecycle()
    val echte by vm.echteTankungen.collectAsStateWithLifecycle()
    val fahrzeuge by vm.fahrzeuge.collectAsStateWithLifecycle()
    val alle by vm.alleFahrzeuge.collectAsStateWithLifecycle()
    val namen by vm.namen.collectAsStateWithLifecycle()
    val gewaehlt by vm.gewaehlt.collectAsStateWithLifecycle()
    var formular by remember { mutableStateOf(false) }
    var bearbeiten by remember { mutableStateOf<Tankvorgang?>(null) }
    var loeschen by remember { mutableStateOf<Tankvorgang?>(null) }
    var ansehen by remember { mutableStateOf<Tankvorgang?>(null) }
    // Roadmap Nr. 14: über "Teilen" geschicktes Bild -> Formular öffnen und das Bild lesen.
    val geteilt by de.hjorven.fahrtenbuch.ui.Geteilt.bild.collectAsStateWithLifecycle()
    var startBild by remember { mutableStateOf<android.net.Uri?>(null) }
    LaunchedEffect(geteilt) {
        geteilt?.let { startBild = it; de.hjorven.fahrtenbuch.ui.Geteilt.bild.value = null; bearbeiten = null; formular = true }
    }

    Box(Modifier.fillMaxSize()) {
        if (tankungen.isEmpty()) {
            Text(
                "Noch nichts getankt oder geladen",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(tankungen, key = { it.id }) { t ->
                val fz = alle.firstOrNull { it.id == t.fahrzeugId }
                ListItem(
                    headlineContent = {
                        val preis = if (t.ladeort == "zuhause") "kostenlos" else euro(t.gesamtpreis)
                        Text("${if (t.test) "TEST · " else ""}${fz?.name ?: "Auto"} · ${zahl(t.liter, 2)} ${fz?.einheit ?: "L"} · $preis")
                    },
                    supportingContent = {
                        val art = when {
                            fz?.elektrisch == true -> LADEORTE.firstOrNull { it.first == t.ladeort }?.second ?: "geladen"
                            t.vollgetankt -> "voll"
                            else -> "teilweise"
                        }
                        Text("${datumKurz(t.datum)} · ${kmStand(t.kmStand)} · ${namen[t.fahrerId] ?: "Fahrer"} · $art")
                    },
                    leadingContent = { Box(Modifier.size(12.dp).background(farbe(fz?.farbe ?: ""), CircleShape)) },
                    trailingContent = {
                        if (vm.hatBeleg(t)) IconButton(onClick = { ansehen = t }) { Icon(Icons.Filled.Receipt, "Beleg ansehen") }
                    },
                    // Antippen = bearbeiten (eigene) bzw. Beleg ansehen (andere), lange drücken = löschen (nur eigene).
                    modifier = Modifier.combinedClickable(
                        onClick = { if (t.fahrerId == vm.userId) bearbeiten = t else if (vm.hatBeleg(t)) ansehen = t },
                        onLongClick = { if (t.fahrerId == vm.userId) loeschen = t },
                    ),
                )
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        val elektroGewaehlt = fahrzeuge.firstOrNull { it.id == gewaehlt }?.elektrisch == true
        // Roadmap Nr. 9 / Nachtplan 5c: Hauptweg mit Kamera, "Ohne Beleg" immer daneben sichtbar.
        Row(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Kleiner Knopf passt keinen Text: zweiter, schlichter Knopf in Sekundärfarbe.
            ExtendedFloatingActionButton(
                onClick = { mitKamera = false; formular = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) { Text("Ohne Beleg") }
            ExtendedFloatingActionButton(
                onClick = { mitKamera = true; formular = true },
                icon = { Icon(if (elektroGewaehlt) Icons.Filled.EvStation else Icons.Filled.LocalGasStation, null) },
                text = { Text(if (elektroGewaehlt) "Geladen" else "Getankt") },
            )
        }
    }

    ansehen?.let { t ->
        // Roadmap Nr. 13: Belege ansehen, auch die der anderen (Foto kommt bei Bedarf vom Server).
        val datei by produceState<File?>(null, t.id) { value = vm.belegHolen(t) }
        var geladen by remember(t.id) { mutableStateOf(false) }
        LaunchedEffect(t.id) { kotlinx.coroutines.delay(15_000); geladen = true }
        Dialog(onDismissRequest = { ansehen = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val fz = alle.firstOrNull { it.id == t.fahrzeugId }
                    Text("${fz?.name ?: "Auto"} · ${datumKurz(t.datum)} · ${namen[t.fahrerId] ?: "Fahrer"}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val d = datei
                    when {
                        d != null -> BelegBild(d, Modifier.fillMaxWidth().weight(1f))
                        geladen -> Text("Beleg nicht geladen. Ohne Verbindung zum Server gibt es nur eigene Fotos.", Modifier.weight(1f))
                        else -> Text("Beleg wird geladen ...", Modifier.weight(1f))
                    }
                    Button(onClick = { ansehen = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Schließen") }
                }
            }
        }
    }

    if (formular || bearbeiten != null) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val vorlage = bearbeiten
        // Vorhandenes Belegfoto (eigenes oder vom Server) beim Bearbeiten anzeigen.
        val vorhanden by produceState<File?>(null, vorlage?.id) {
            value = vorlage?.let { vm.belegHolen(it) }
        }
        ModalBottomSheet(onDismissRequest = { formular = false; bearbeiten = null; startBild = null }, sheetState = sheet) {
            TankFormular(
                fahrzeuge.ifEmpty { alle },
                vorlage?.fahrzeugId ?: gewaehlt,
                echte,
                vorlage,
                vorhanden,
                test = vorlage?.test ?: testModus,
                sofortKamera = vorlage == null && mitKamera && startBild == null,
                startBild = startBild.takeIf { vorlage == null },
            ) { fz, km, liter, ppl, gesamt, voll, tankstelle, ladeort, beleg ->
                vm.tankSpeichern(
                    fz, km, liter, ppl, gesamt, voll, tankstelle, ladeort,
                    bisher = vorlage, datum = beleg.datum, belegFoto = beleg.foto,
                )
                formular = false; startBild = null
                bearbeiten = null
            }
        }
    }

    loeschen?.let { t ->
        val einheit = alle.firstOrNull { it.id == t.fahrzeugId }?.einheit ?: "L"
        AlertDialog(
            onDismissRequest = { loeschen = null },
            title = { Text("Eintrag löschen?") },
            text = { Text("${zahl(t.liter, 2)} $einheit am ${datumKurz(t.datum)}") },
            confirmButton = { TextButton(onClick = { vm.tankLoeschen(t); loeschen = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { loeschen = null }) { Text("Abbrechen") } },
        )
    }
}

/** Was der Beleg-Scan zum Speichern beiträgt: Foto und ggf. Datum vom Beleg. */
private class BelegDaten(val foto: File?, val datum: Long?)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TankFormular(
    fahrzeuge: List<Fahrzeug>,
    vorauswahl: String?,
    bisher: List<Tankvorgang>,
    vorlage: Tankvorgang?,
    vorhandenerBeleg: File?,
    test: Boolean,
    sofortKamera: Boolean = false,
    startBild: android.net.Uri? = null,
    speichern: (Fahrzeug, Double, Double, Double?, Double?, Boolean, String, String, BelegDaten) -> Unit,
) {
    // Geteilter Screenshot (Roadmap Nr. 14) kommt meist aus der Lade-App: dann das E-Auto vorwählen.
    var fzId by remember {
        mutableStateOf(
            (if (startBild != null && fahrzeuge.firstOrNull { it.id == vorauswahl }?.elektrisch != true) fahrzeuge.firstOrNull { it.elektrisch }?.id else null)
                ?: vorauswahl ?: fahrzeuge.firstOrNull()?.id
        )
    }
    val fz = fahrzeuge.firstOrNull { it.id == fzId }
    var km by remember(fzId) {
        mutableStateOf((vorlage?.kmStand ?: fz?.kmStand)?.takeIf { it > 0 }?.let { zahl(it, 0) } ?: "")
    }
    var liter by remember { mutableStateOf(vorlage?.liter?.let { zahl(it, 2) } ?: "") }
    var ppl by remember { mutableStateOf(vorlage?.preisProLiter?.takeIf { it > 0 }?.let { zahl(it, 3) } ?: "") }
    var gesamt by remember { mutableStateOf(vorlage?.gesamtpreis?.takeIf { it > 0 }?.let { zahl(it, 2) } ?: "") }
    var voll by remember { mutableStateOf(vorlage?.vollgetankt ?: true) }
    var tankstelle by remember { mutableStateOf(vorlage?.tankstelle ?: "") }
    val elektro = fz?.elektrisch == true
    var scan by remember { mutableStateOf<BelegScan?>(null) }
    var belegDatum by remember { mutableStateOf<Long?>(null) }
    var scanHinweis by remember { mutableStateOf<String?>(null) }
    var ladeort by remember { mutableStateOf(vorlage?.ladeort?.ifBlank { null } ?: "zuhause") }
    // Laden zuhause kostet nichts: keine Preisfelder.
    val kostenlos = elektro && ladeort == "zuhause"
    // Strompreis wiederholt sich (vor allem zuhause): letzten Preis für diesen Ladeort vorschlagen.
    val letzterPreis = bisher
        .filter { it.fahrzeugId == fzId && !it.geloescht && (!elektro || it.ladeort == ladeort) && it.preisProLiter > 0 }
        .maxByOrNull { it.datum }?.preisProLiter

    val l = liter.alsZahl()
    val p = ppl.alsZahl() ?: if (elektro) letzterPreis else null
    val g = gesamt.alsZahl()
    val kmWert = km.replace(".", "").alsZahl()
    val pruefung = Plausibel.tank(l, p ?: if (l != null && g != null && l > 0) g / l else null, kmWert)
    val ok = fz != null && pruefung == null && (kostenlos || p != null || g != null)

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            (if (elektro) "Ladevorgang" else "Tankvorgang") + if (vorlage != null) " bearbeiten" else "",
            style = MaterialTheme.typography.titleLarge,
        )
        if (test) Text(
            "Test-Modus: wird als Test gespeichert und zählt nirgends mit.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
        // Umbrechen statt seitlich scrollen: sonst wird der letzte Autoname abgeschnitten ("VW Kombi 4 Ko").
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fahrzeuge.forEach { f ->
                FilterChip(selected = f.id == fzId, onClick = { fzId = f.id }, label = { Text(f.name, maxLines = 1) })
            }
        }
        // Beleg zuerst: ein Foto füllt Menge, Preis, Tankstelle und Datum aus.
        BelegScanKnoepfe(elektro, sofortKamera = sofortKamera, startBild = startBild) { s ->
            scan?.foto?.delete()
            scan = s
            val e = s.erkannt
            e.menge?.let { liter = zahl(it, 2) }
            e.preisProEinheit?.let { ppl = zahl(it, 3) }
            e.gesamt?.let { gesamt = zahl(it, 2) }
            e.tankstelle?.let { tankstelle = it }
            // Falsch gelesenes Datum in der Zukunft nicht übernehmen.
            belegDatum = e.datum?.takeIf { datumKurz(it) != datumKurz(System.currentTimeMillis()) && it <= System.currentTimeMillis() }
            if (elektro && e.gesamt != null && e.gesamt > 0) ladeort = "oeffentlich"
            scanHinweis = if (e.leer) {
                "Auf dem Foto war nichts lesbar. Das Foto wird trotzdem als Beleg gespeichert. " +
                    "Bitte Menge und Preis selbst eintippen."
            } else {
                "Vom Beleg übernommen. Bitte die Zahlen kurz mit dem Beleg vergleichen."
            }
        }
        val foto = scan?.foto ?: vorhandenerBeleg
        if (foto != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BelegBild(foto, Modifier.size(72.dp))
            Column(Modifier.weight(1f)) {
                Text(if (scan != null) "Neues Belegfoto" else "Belegfoto", style = MaterialTheme.typography.titleSmall)
                Text("Antippen zum Vergrößern", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                belegDatum?.let { Text("Datum vom Beleg: ${datumKurz(it)}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        scanHinweis?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
        // Datum immer von Hand änderbar (Nachtplan 5c), nicht nur über das Belegfoto.
        var datumWaehlen by remember { mutableStateOf(false) }
        val datumAnzeige = belegDatum ?: vorlage?.datum ?: System.currentTimeMillis()
        OutlinedButton(onClick = { datumWaehlen = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (belegDatum == null && vorlage == null) "Datum: heute (${datumKurz(datumAnzeige)}), ändern"
                else "Datum: ${datumKurz(datumAnzeige)}, ändern"
            )
        }
        if (datumWaehlen) {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = alsUtcTag(datumAnzeige),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= alsUtcTag(System.currentTimeMillis())
                },
            )
            DatePickerDialog(
                onDismissRequest = { datumWaehlen = false },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { tag -> belegDatum = tagSetzen(datumAnzeige, tag) }
                        datumWaehlen = false
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { datumWaehlen = false }) { Text("Abbrechen") } },
            ) { DatePicker(state) }
        }
        val zahlTastatur = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        OutlinedTextField(km, { km = it }, label = { Text("Kilometerstand") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        TachoScanKnopf(bisher = fz?.kmStand ?: 0.0, modifier = Modifier.fillMaxWidth()) { km = it.toString() }
        if (elektro) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LADEORTE.forEach { (wert, text) ->
                    FilterChip(selected = ladeort == wert, onClick = { ladeort = wert }, label = { Text(text) })
                }
            }
        }
        OutlinedTextField(liter, { liter = it }, label = { Text(if (elektro) "Geladen (kWh)" else "Liter") }, singleLine = true,
            keyboardOptions = zahlTastatur, modifier = Modifier.fillMaxWidth())
        if (kostenlos) Text(
            "Laden zuhause ist kostenlos, ein Preis ist nicht nötig.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!kostenlos) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(ppl, { ppl = it }, label = { Text(if (elektro) "Preis pro kWh" else "Preis pro Liter") },
                singleLine = true, keyboardOptions = zahlTastatur, modifier = Modifier.weight(1f),
                placeholder = {
                    when {
                        l != null && g != null -> Text(zahl(g / l, 3))
                        elektro && letzterPreis != null -> Text(zahl(letzterPreis, 3))
                    }
                },
                supportingText = { if (ppl.isBlank() && l != null && g != null && l > 0) Text("= ${zahl(g / l, 3)} €") })
            OutlinedTextField(gesamt, { gesamt = it }, label = { Text("Gesamt (€)") }, singleLine = true,
                keyboardOptions = zahlTastatur, modifier = Modifier.weight(1f),
                placeholder = { if (l != null && p != null) Text(zahl(l * p, 2)) },
                // Ausgerechneter Betrag gut sichtbar, nicht nur als blasser Platzhalter.
                supportingText = { if (gesamt.isBlank() && l != null && p != null) Text("= ${zahl(l * p, 2)} €") })
        }
        // Zuhause gibt es keine Ladesäule zu benennen.
        if (!kostenlos) OutlinedTextField(tankstelle, { tankstelle = it },
            label = { Text(if (elektro) "Ladesäule (optional)" else "Tankstelle (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        // Bei E-Autos zählt jeder Ladevorgang für den Verbrauch, deshalb kein Schalter.
        if (!elektro) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Vollgetankt")
                Text(
                    "Nur volle Tankungen zählen für den Verbrauch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(voll, { voll = it })
        }
        // Sagen, was noch fehlt, statt nur den Knopf grau zu lassen.
        val fehlt = when {
            fz == null -> "Bitte oben ein Auto wählen."
            pruefung != null -> "$pruefung."
            !(kostenlos || p != null || g != null) -> "Preis pro ${if (elektro) "kWh" else "Liter"} oder Gesamtbetrag fehlt."
            else -> null
        }
        if (fehlt != null && (liter.isNotBlank() || km.isNotBlank())) {
            Text(fehlt, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }
        Button(
            onClick = {
                speichern(
                    fz!!, kmWert!!, l!!, p, g, voll || elektro, tankstelle, if (elektro) ladeort else "",
                    BelegDaten(scan?.foto, belegDatum),
                )
            },
            enabled = ok,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Speichern", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(16.dp))
    }
}

private val LADEORTE = listOf("zuhause" to "Zuhause", "oeffentlich" to "Öffentlich")
