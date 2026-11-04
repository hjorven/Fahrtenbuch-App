package de.hjorven.fahrtenbuch.ui.screens

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hjorven.fahrtenbuch.data.Ausgleich
import de.hjorven.fahrtenbuch.data.Fahrt
import de.hjorven.fahrtenbuch.data.Fahrzeug
import de.hjorven.fahrtenbuch.data.Statistik
import de.hjorven.fahrtenbuch.data.Tankvorgang
import de.hjorven.fahrtenbuch.data.einheit
import de.hjorven.fahrtenbuch.data.elektrisch
import de.hjorven.fahrtenbuch.ui.HauptViewModel
import de.hjorven.fahrtenbuch.ui.alsZahl
import de.hjorven.fahrtenbuch.ui.datumKurz
import de.hjorven.fahrtenbuch.ui.euro
import de.hjorven.fahrtenbuch.ui.farbe
import de.hjorven.fahrtenbuch.ui.km
import de.hjorven.fahrtenbuch.ui.monat
import de.hjorven.fahrtenbuch.ui.zahl
import java.util.Calendar

@Composable
fun StatistikScreen(vm: HauptViewModel) {
    val fahrzeuge by vm.alleFahrzeuge.collectAsStateWithLifecycle()
    val fahrten by vm.echteFahrten.collectAsStateWithLifecycle()
    val tankungen by vm.echteTankungen.collectAsStateWithLifecycle()
    val ausgleiche by vm.ausgleiche.collectAsStateWithLifecycle()
    val namen by vm.namen.collectAsStateWithLifecycle()
    var jahrAnsicht by rememberSaveable { mutableStateOf(false) }
    var versatz by rememberSaveable { mutableIntStateOf(0) }

    val kostenProKm = fahrzeuge.mapNotNull { fz ->
        Statistik.kostenProKm(fz, tankungen.filter { it.fahrzeugId == fz.id })?.let { fz.id to it }
    }.toMap()

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "Monat", true to "Jahr").forEachIndexed { i, (wert, text) ->
                SegmentedButton(
                    selected = jahrAnsicht == wert,
                    onClick = { if (jahrAnsicht != wert) { jahrAnsicht = wert; versatz = 0 } },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(text) }
            }
        }
        val (von, bis) = if (jahrAnsicht) jahresGrenzen(versatz) else monatsGrenzen(versatz)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { versatz-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Zurück") }
            Text(
                if (jahrAnsicht) jahr(von).toString() else monat(von),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { versatz++ }, enabled = versatz < 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Weiter")
            }
        }

        if (jahrAnsicht) {
            Jahr(fahrzeuge.filter { it.aktiv }, fahrten, tankungen, kostenProKm, von, bis)
        } else {
            Monat(vm, fahrzeuge.filter { it.aktiv }, fahrten, tankungen, ausgleiche, kostenProKm, namen, von, bis)
        }
    }
}

// ---------------------------------------------------------------------------
// Monat
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Monat(
    vm: HauptViewModel,
    fahrzeuge: List<Fahrzeug>,
    fahrten: List<Fahrt>,
    tankungen: List<Tankvorgang>,
    ausgleiche: List<Ausgleich>,
    kostenProKm: Map<String, Double>,
    namen: Map<String, String>,
    von: Long,
    bis: Long,
) {
    val zeitraum = Statistik.zeitraum(von)
    val ausgleichMonat = ausgleiche.filter { it.zeitraum == zeitraum }
    val abrechnung = Statistik.abrechnung(fahrten, tankungen, kostenProKm, von, bis, ausgleichMonat)
    val vorschlaege = Statistik.ueberweisungen(abrechnung)
    var eintragen by remember { mutableStateOf<Statistik.Ueberweisung?>(null) }
    var loeschen by remember { mutableStateOf<Ausgleich?>(null) }

    fahrzeuge.forEach { fz ->
        val eigeneTank = tankungen.filter { it.fahrzeugId == fz.id }
        val verbrauch = Statistik.verbrauch(eigeneTank, elektrisch = fz.elektrisch)
        val kmMonat = fahrten.filter { it.fahrzeugId == fz.id && it.ende != null && it.start in von until bis }.sumOf { it.km }
        val tankMonat = eigeneTank.filter { it.datum in von until bis }
        AutoKarte(fz) {
            Zeile("Gefahren im Monat", km(kmMonat))
            Zeile(if (fz.elektrisch) "Geladen im Monat" else "Getankt im Monat", euro(tankMonat.sumOf { it.kosten }))
            if (fz.elektrisch) {
                val zuhause = tankMonat.filter { it.ladeort == "zuhause" }.sumOf { it.liter }
                val unterwegs = tankMonat.filter { it.ladeort != "zuhause" }
                if (zuhause > 0) Zeile("Zuhause geladen", "${zahl(zuhause)} kWh, kostenlos")
                if (unterwegs.isNotEmpty()) {
                    Zeile("Öffentlich geladen", "${zahl(unterwegs.sumOf { it.liter })} kWh, ${euro(unterwegs.sumOf { it.kosten })}")
                }
            }
            Zeile(
                "Verbrauch",
                verbrauch?.let { "${zahl(it.literPro100km)} ${fz.einheit}/100 km" }
                    ?: if (fz.elektrisch) "zu wenig Ladedaten" else "zu wenig Tankdaten",
            )
            Zeile(
                if (fz.preisProKm > 0) "Preis pro km (fest)" else "Kosten pro km",
                kostenProKm[fz.id]?.let { "${zahl(it, 3)} €" } ?: "–",
            )
        }
    }

    Text("Abrechnung", style = MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (abrechnung.isEmpty()) {
                Text("Keine Fahrten in diesem Monat", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            abrechnung.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(namen[s.fahrerId] ?: "Fahrer", fontWeight = FontWeight.Medium)
                        val ausgleich = when {
                            s.ausgleich > 0.005 -> " · überwiesen ${euro(s.ausgleich)}"
                            s.ausgleich < -0.005 -> " · erhalten ${euro(-s.ausgleich)}"
                            else -> ""
                        }
                        Text(
                            "${km(s.km)} · Anteil ${euro(s.anteil)} · bezahlt ${euro(s.bezahlt)}$ausgleich",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val saldo = s.saldo
                    Text(
                        when {
                            saldo > 0.005 -> "bekommt ${euro(saldo)}"
                            saldo < -0.005 -> "zahlt ${euro(-saldo)}"
                            else -> "ausgeglichen"
                        },
                        color = if (saldo < -0.005) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    if (vorschlaege.isNotEmpty()) {
        Text("Ausgleich", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vorschlaege.forEach { u ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${namen[u.von] ?: "?"} zahlt ${namen[u.an] ?: "?"} ${euro(u.betrag)}",
                            modifier = Modifier.weight(1f),
                        )
                        // Eintragen darf nur, wer beteiligt ist (Server-Regel).
                        if (u.von == vm.userId || u.an == vm.userId) {
                            FilledTonalButton(onClick = { eintragen = u }) { Text("Bezahlt") }
                        }
                    }
                }
            }
        }
    }
    if (ausgleichMonat.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Schon bezahlt", style = MaterialTheme.typography.labelLarge)
                ausgleichMonat.forEach { a ->
                    Text(
                        "${datumKurz(a.datum)}: ${namen[a.von] ?: "?"} an ${namen[a.an] ?: "?"}, ${euro(a.betrag)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { if (a.von == vm.userId || a.an == vm.userId) loeschen = a },
                        ),
                    )
                }
            }
        }
    }
    Text(
        "Anteil = eigene km mal Kosten pro km des Autos. Kosten pro km kommen aus den letzten drei vollen " +
            "Tankungen oder dem festen Preis beim Auto in den Einstellungen. Laden zuhause kostet nichts.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    eintragen?.let { u ->
        var betrag by remember(u) { mutableStateOf(zahl(u.betrag, 2)) }
        val wert = betrag.alsZahl()
        AlertDialog(
            onDismissRequest = { eintragen = null },
            title = { Text("Ausgleich eintragen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${namen[u.von] ?: "?"} hat ${namen[u.an] ?: "?"} für ${monat(von)} bezahlt:")
                    OutlinedTextField(
                        betrag, { betrag = it },
                        label = { Text("Betrag (€)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.ausgleichEintragen(u.von, u.an, wert!!, zeitraum); eintragen = null },
                    enabled = wert != null && wert > 0,
                ) { Text("Eintragen") }
            },
            dismissButton = { TextButton(onClick = { eintragen = null }) { Text("Abbrechen") } },
        )
    }
    loeschen?.let { a ->
        AlertDialog(
            onDismissRequest = { loeschen = null },
            title = { Text("Ausgleich löschen?") },
            text = { Text("${namen[a.von] ?: "?"} an ${namen[a.an] ?: "?"}, ${euro(a.betrag)}") },
            confirmButton = { TextButton(onClick = { vm.ausgleichLoeschen(a); loeschen = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { loeschen = null }) { Text("Abbrechen") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Jahr
// ---------------------------------------------------------------------------

@Composable
private fun Jahr(
    fahrzeuge: List<Fahrzeug>,
    fahrten: List<Fahrt>,
    tankungen: List<Tankvorgang>,
    kostenProKm: Map<String, Double>,
    von: Long,
    bis: Long,
) {
    val jahr = jahr(von)
    val proAuto = fahrzeuge.map { it to Statistik.kmProMonat(fahrten, jahr, it.id) }
    val gesamt = proAuto.sumOf { it.second.sum() }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${km(gesamt)} im Jahr", style = MaterialTheme.typography.titleMedium)
            Balken(proAuto.map { (fz, werte) -> farbe(fz.farbe) to werte })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                fahrzeuge.forEach { fz ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(farbe(fz.farbe), CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(fz.name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    proAuto.forEach { (fz, werte) ->
        val eigeneTank = tankungen.filter { it.fahrzeugId == fz.id }
        val tankJahr = eigeneTank.filter { it.datum in von until bis }
        val verlauf = Statistik.verbrauchVerlauf(eigeneTank, fz.elektrisch).filter { it.datum in von until bis }
        val tacho = Statistik.tachoAbweichung(fahrten.filter { it.fahrzeugId == fz.id && it.start in von until bis })
        AutoKarte(fz) {
            Zeile("Gefahren im Jahr", km(werte.sum()))
            Zeile(if (fz.elektrisch) "Laden im Jahr" else "Tanken im Jahr", euro(tankJahr.sumOf { it.kosten }))
            if (fz.elektrisch) {
                val kwh = tankJahr.sumOf { it.liter }
                val zuhause = tankJahr.filter { it.ladeort == "zuhause" }.sumOf { it.liter }
                if (kwh > 0) Zeile("Zuhause geladen", "${zahl(zuhause / kwh * 100, 0)} % der kWh")
            }
            if (verlauf.size >= 2) {
                Text("Verbrauch je Tankung (${fz.einheit}/100 km)", style = MaterialTheme.typography.labelMedium)
                Linie(verlauf.map { it.wert }, farbe(fz.farbe))
                Zeile("Spanne", "${zahl(verlauf.minOf { it.wert })} bis ${zahl(verlauf.maxOf { it.wert })}")
            }
            tacho?.let { t ->
                Zeile(
                    "GPS gegen Tacho",
                    "${if (t.prozent >= 0) "+" else ""}${zahl(t.prozent, 1)} % (${t.fahrten} Fahrten)",
                )
            }
        }
    }

    // Kosten pro km im Vergleich, günstigstes Auto zuerst.
    val vergleich = fahrzeuge.mapNotNull { fz -> kostenProKm[fz.id]?.let { fz to it } }.sortedBy { it.second }
    if (vergleich.size >= 2) {
        Text("Kosten pro km im Vergleich", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val max = vergleich.maxOf { it.second }.coerceAtLeast(0.001)
                vergleich.forEach { (fz, wert) ->
                    Text("${fz.name}: ${zahl(wert, 3)} €/km", style = MaterialTheme.typography.bodyMedium)
                    Box(
                        Modifier
                            .fillMaxWidth((wert / max).toFloat().coerceIn(0.02f, 1f))
                            .height(8.dp)
                            .background(farbe(fz.farbe), CircleShape)
                    )
                }
                Text(
                    "Nur Sprit bzw. Strom (Laden zuhause kostenlos) oder der feste Preis pro km.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Gestapelte Balken: km pro Monat, je Auto eine Farbe. */
@Composable
private fun Balken(reihen: List<Pair<Color, DoubleArray>>) {
    val summen = DoubleArray(12) { m -> reihen.sumOf { it.second[m] } }
    val max = (summen.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val linie = MaterialTheme.colorScheme.outlineVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val breite = size.width / 12f
            drawLine(linie, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
            for (m in 0 until 12) {
                var oben = size.height
                for ((farbe, werte) in reihen) {
                    val h = (werte[m] / max * size.height).toFloat()
                    if (h <= 0f) continue
                    drawRect(farbe, topLeft = Offset(m * breite + breite * 0.15f, oben - h), size = Size(breite * 0.7f, h))
                    oben -= h
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D").forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** Einfache Linie über die Werte (Verbrauchsverlauf). */
@Composable
private fun Linie(werte: List<Double>, farbe: Color) {
    val min = werte.minOrNull() ?: 0.0
    val max = werte.maxOrNull() ?: 1.0
    val spanne = (max - min).coerceAtLeast(0.5)
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        if (werte.size < 2) return@Canvas
        val dx = size.width / (werte.size - 1)
        val pfad = Path()
        werte.forEachIndexed { i, w ->
            val x = i * dx
            val y = (size.height - ((w - min) / spanne * (size.height - 8f)) - 4f).toFloat()
            if (i == 0) pfad.moveTo(x, y) else pfad.lineTo(x, y)
            drawCircle(farbe, radius = 4f, center = Offset(x, y))
        }
        drawPath(pfad, farbe, style = Stroke(width = 4f))
    }
}

@Composable
private fun AutoKarte(fz: Fahrzeug, inhalt: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(farbe(fz.farbe), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(fz.name, style = MaterialTheme.typography.titleMedium)
            }
            inhalt()
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

private fun jahr(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)

private fun monatsGrenzen(versatz: Int): Pair<Long, Long> {
    val c = Calendar.getInstance()
    c.set(Calendar.DAY_OF_MONTH, 1)
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    c.add(Calendar.MONTH, versatz)
    val von = c.timeInMillis
    c.add(Calendar.MONTH, 1)
    return von to c.timeInMillis
}

private fun jahresGrenzen(versatz: Int): Pair<Long, Long> {
    val c = Calendar.getInstance()
    c.set(Calendar.DAY_OF_YEAR, 1)
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    c.add(Calendar.YEAR, versatz)
    val von = c.timeInMillis
    c.add(Calendar.YEAR, 1)
    return von to c.timeInMillis
}
