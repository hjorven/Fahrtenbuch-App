package de.hjorven.fahrtenbuch.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hjorven.fahrtenbuch.data.Fahrzeug
import de.hjorven.fahrtenbuch.location.LaufendeFahrt
import de.hjorven.fahrtenbuch.ui.HauptViewModel
import de.hjorven.fahrtenbuch.ui.dauer
import de.hjorven.fahrtenbuch.ui.dauerUhr
import de.hjorven.fahrtenbuch.ui.farbe
import de.hjorven.fahrtenbuch.ui.km
import de.hjorven.fahrtenbuch.ui.kmStand
import de.hjorven.fahrtenbuch.ui.uhrzeit
import de.hjorven.fahrtenbuch.ui.zahl
import kotlinx.coroutines.delay

@Composable
fun FahrtScreen(vm: HauptViewModel, oeffnen: (String) -> Unit) {
    val laufend by vm.laufend.collectAsStateWithLifecycle()
    val fahrzeuge by vm.fahrzeuge.collectAsStateWithLifecycle()
    val gewaehlt by vm.gewaehlt.collectAsStateWithLifecycle()
    val fahrten by vm.fahrten.collectAsStateWithLifecycle()
    val namen by vm.namen.collectAsStateWithLifecycle()
    val autoStop by vm.autoStop.collectAsStateWithLifecycle()
    var hinweis by remember { mutableStateOf<String?>(null) }
    var einstellungenZeigen by remember { mutableStateOf(false) }
    var beendenFragen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val berechtigungen = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        // Optional: Stau-Erkennung. Ohne diese Erlaubnis läuft alles wie bisher.
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
    }.toTypedArray()
    val anfrage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            hinweis = null
            vm.fahrtStarten()
        } else {
            hinweis = "Ohne Standort kann keine Fahrt aufgezeichnet werden. Bitte in den Einstellungen bei \"Standort\" " +
                "\"Immer zulassen\" bzw. \"Bei Nutzung der App\" und \"Genauer Standort\" wählen."
            einstellungenZeigen = true
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val l = laufend
        // Energiesparmodus drosselt GPS im Hintergrund: Hinweis, statt still eine ungenaue Strecke aufzuzeichnen.
        val energiesparen = remember(l != null) {
            context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        }
        if (l != null && energiesparen) {
            Text(
                "Energiesparmodus ist an: Android kann die Aufzeichnung drosseln, die Strecke wird dann ungenauer. " +
                    "Am besten für die Fahrt ausschalten.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (l != null) {
            LaufendeKarte(l, autoStop)
            Button(
                onClick = { beendenFragen = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) {
                Icon(Icons.Filled.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("Fahrt beenden", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Text("Auto wählen", style = MaterialTheme.typography.titleMedium)
            if (fahrzeuge.isEmpty()) {
                Text(
                    // Ohne Anmeldung gibt es kein Sync-Symbol (im Oberflächentest aufgefallen).
                    if (vm.prefs.anmeldung.collectAsStateWithLifecycle().value != null) "Noch keine Autos. Bitte synchronisieren (Symbol oben rechts)."
                    else "Noch keine Autos. Unter Einstellungen mit dem Server verbinden.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Wer gerade mit welchem Auto unterwegs ist (laufende Fahrten der anderen, höchstens 12 Stunden alt).
            val jetzt = System.currentTimeMillis()
            val unterwegs = fahrten.filter {
                it.ende == null && it.fahrerId != vm.userId && jetzt - it.start < 12 * 3_600_000L
            }.associateBy { it.fahrzeugId }
            fahrzeuge.forEach { f ->
                val u = unterwegs[f.id]
                FahrzeugKarte(f, f.id == gewaehlt, u?.let { "unterwegs mit ${namen[it.fahrerId] ?: "jemandem"} seit ${uhrzeit(it.start)}" }) {
                    vm.waehle(f.id)
                }
            }
            hinweis?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
            if (einstellungenZeigen) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text("Einstellungen öffnen") }
            }
            val autoGewaehlt = gewaehlt != null && fahrzeuge.any { it.id == gewaehlt }
            if (!autoGewaehlt && fahrzeuge.isNotEmpty()) {
                Text(
                    "Zuerst oben das Auto antippen, mit dem du fährst.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Button(
                onClick = { anfrage.launch(berechtigungen) },
                enabled = autoGewaehlt,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Fahrt starten", style = MaterialTheme.typography.titleMedium)
            }
        }

        if (beendenFragen) {
            AlertDialog(
                onDismissRequest = { beendenFragen = false },
                title = { Text("Fahrt beenden?") },
                text = { Text("Die Fahrt wird gespeichert.", style = MaterialTheme.typography.bodyLarge) },
                confirmButton = {
                    Button(
                        onClick = { beendenFragen = false; vm.fahrtStoppen() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text("Ja, beenden") }
                },
                dismissButton = { TextButton(onClick = { beendenFragen = false }) { Text("Weiterfahren") } },
            )
        }

        val letzte = fahrten.filter { it.ende != null }.take(3)
        if (letzte.isNotEmpty()) {
            Text("Letzte Fahrten", style = MaterialTheme.typography.titleMedium)
            Card {
                letzte.forEach { f ->
                    val fz = fahrzeuge.firstOrNull { it.id == f.fahrzeugId }
                    FahrtZeile(f, fz, namen[f.fahrerId], onClick = { oeffnen(f.id) })
                }
            }
        }
    }
}

@Composable
private fun LaufendeKarte(l: LaufendeFahrt, autoStop: Int) {
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(l.fahrtId) {
        while (true) {
            jetzt = System.currentTimeMillis()
            delay(1000)
        }
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(l.fahrzeugName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(zahl(l.kmh, 0), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text("km/h", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Wert("Strecke", km(l.km))
                Wert("Dauer", dauerUhr(jetzt - l.start))
                Wert("Max", "${zahl(l.maxKmh, 0)} km/h")
            }
            l.stehtSeit?.let { seit ->
                val steht = jetzt - seit
                if (steht > 60_000) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Steht seit ${dauer(steht)}, endet automatisch nach $autoStop min",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Wert(titel: String, wert: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(wert, style = MaterialTheme.typography.titleLarge)
        Text(titel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FahrzeugKarte(f: Fahrzeug, gewaehlt: Boolean, unterwegs: String?, onClick: () -> Unit) {
    val inhalt: @Composable () -> Unit = {
        ListItem(
            headlineContent = { Text(f.name, style = MaterialTheme.typography.titleMedium) },
            supportingContent = {
                Column {
                    Text(kmStand(f.kmStand))
                    unterwegs?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                }
            },
            leadingContent = { Box(Modifier.size(16.dp).background(farbe(f.farbe), CircleShape)) },
            trailingContent = {
                if (gewaehlt) Icon(Icons.Filled.CheckCircle, "gewählt", tint = MaterialTheme.colorScheme.primary)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    if (gewaehlt) {
        Card(
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) { inhalt() }
    } else {
        OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) { inhalt() }
    }
}
