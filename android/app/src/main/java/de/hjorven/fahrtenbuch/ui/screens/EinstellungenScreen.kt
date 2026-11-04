package de.hjorven.fahrtenbuch.ui.screens

import androidx.compose.material.icons.filled.BugReport
import androidx.compose.ui.Alignment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.core.content.ContextCompat
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import de.hjorven.fahrtenbuch.data.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hjorven.fahrtenbuch.data.Antrieb
import de.hjorven.fahrtenbuch.data.Fahrzeug
import de.hjorven.fahrtenbuch.data.SyncStatus
import de.hjorven.fahrtenbuch.ui.HauptViewModel
import de.hjorven.fahrtenbuch.ui.alsZahl
import de.hjorven.fahrtenbuch.ui.datumKurz
import de.hjorven.fahrtenbuch.ui.farbe
import de.hjorven.fahrtenbuch.ui.kmStand
import de.hjorven.fahrtenbuch.ui.uhrzeit
import de.hjorven.fahrtenbuch.ui.zahl

@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("BatteryLife")
@Composable
fun EinstellungenScreen(vm: HauptViewModel, serverVerbinden: () -> Unit) {
    val context = LocalContext.current
    val anmeldung by vm.anmeldung.collectAsStateWithLifecycle()
    val sync by vm.syncStatus.collectAsStateWithLifecycle()
    val letzterSync by vm.letzterSync.collectAsStateWithLifecycle()
    val autoStop by vm.autoStop.collectAsStateWithLifecycle()
    val fahrzeuge by vm.alleFahrzeuge.collectAsStateWithLifecycle()
    val namen by vm.namen.collectAsStateWithLifecycle()
    var bearbeiten by remember { mutableStateOf<Fahrzeug?>(null) }
    var nameAendern by remember { mutableStateOf(false) }
    var adressenAendern by remember { mutableStateOf(false) }
    var testLoeschen by remember { mutableStateOf(false) }
    var einladung by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }  // null = zu, sonst Text im Dialog
    var problemMeldung by remember { mutableStateOf<String?>(null) }  // null = zu, "" = lädt, "FEHLER"
    // Rückfrage vor dem Abmelden: Anzahl noch nicht hochgeladener Einträge, null = keine Rückfrage offen
    var abmeldenOffen by remember { mutableStateOf<Int?>(null) }
    var testMeldung by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val aktiveAdresse by vm.aktiveAdresse.collectAsStateWithLifecycle()
    val lokalerName by vm.lokalerName.collectAsStateWithLifecycle()
    var slider by remember(autoStop) { mutableFloatStateOf(autoStop.toFloat()) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Abschnitt("Konto")
        val a = anmeldung
        if (a == null) {
            Card {
                ListItem(
                    headlineContent = { Text("Dein Name: $lokalerName") },
                    supportingContent = { Text("Nur auf diesem Handy gespeichert") },
                    leadingContent = { Icon(Icons.Filled.Person, null) },
                    modifier = Modifier.clickable { nameAendern = true },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Mit Server verbinden") },
                    supportingContent = { Text("Fahrten mit der Familie teilen. Bisherige Fahrten werden hochgeladen.") },
                    leadingContent = { Icon(Icons.Filled.CloudUpload, null) },
                    modifier = Modifier.clickable { serverVerbinden() },
                )
            }
        } else {
            Card {
                ListItem(
                    headlineContent = { Text(a.name.ifBlank { a.email }) },
                    supportingContent = { Text(a.email) },
                    leadingContent = { Icon(Icons.Filled.Person, null) },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Server-Adressen") },
                    supportingContent = {
                        val tail = vm.prefs.tailscaleUrl
                        Text(
                            "Heimnetz: ${a.serverUrl}" +
                                (if (tail.isNotEmpty()) "\nTailscale: $tail" else "\nTailscale: nicht eingetragen") +
                                when {
                                    sync == SyncStatus.Offline -> "\nZurzeit nicht erreichbar"
                                    aktiveAdresse == null -> ""
                                    aktiveAdresse == tail -> "\nVerbunden über Tailscale"
                                    else -> "\nVerbunden über das Heimnetz"
                                }
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.Dns, null) },
                    modifier = Modifier.clickable { adressenAendern = true },
                )
                HorizontalDivider()
                // Roadmap Nr. 1/2: QR mit Adressen und Einladungscode (24 h, einmal benutzbar).
                ListItem(
                    headlineContent = { Text("Familienmitglied einladen") },
                    supportingContent = { Text("QR-Code zeigen: das andere Handy scannt ihn im Login und legt sein Konto an") },
                    leadingContent = { Icon(Icons.Filled.QrCode, null) },
                    modifier = Modifier.clickable {
                        einladung = ""
                        scope.launch { einladung = vm.einladungsText() ?: "FEHLER" }
                    },
                )
                HorizontalDivider()
                // Roadmap Nr. 21: geht an den Admin (Admin-Oberfläche, Collection bericht)
                ListItem(
                    headlineContent = { Text("Problem melden") },
                    supportingContent = { Text(problemMeldung ?: "Kurz beschreiben, was nicht geht. Der Admin sieht es mit App-Version und Diagnose.") },
                    leadingContent = { Icon(Icons.Filled.BugReport, null) },
                    modifier = Modifier.clickable { problem = "" },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Passwort ändern") },
                    leadingContent = { Icon(Icons.Filled.Key, null) },
                    modifier = Modifier.clickable { vm.passwortDialog(true) },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Abmelden") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                    modifier = Modifier.clickable { scope.launch { abmeldenOffen = vm.offeneAenderungen() } },
                )
            }
        }

        if (a != null) {
        Abschnitt("Synchronisation")
        // Was noch nicht auf dem Server ist, neu zählen, sobald sich der Sync-Status ändert.
        var offen by remember { mutableIntStateOf(0) }
        LaunchedEffect(sync, letzterSync) { offen = vm.offeneAenderungen() }
        Card {
            ListItem(
                headlineContent = { Text("Jetzt synchronisieren") },
                overlineContent = if (offen > 0) {
                    { Text("$offen ${if (offen == 1) "Eintrag" else "Einträge"} noch nicht hochgeladen") }
                } else null,
                supportingContent = {
                    Text(
                        when (val s = sync) {
                            SyncStatus.Laeuft -> "Läuft ..."
                            is SyncStatus.Fehler -> s.text
                            SyncStatus.Offline -> "Server nicht erreichbar, Daten bleiben auf dem Handy"
                            SyncStatus.Bereit ->
                                if (letzterSync == 0L) "Noch nie" else "Zuletzt ${datumKurz(letzterSync)}, ${uhrzeit(letzterSync)}"
                        }
                    )
                },
                leadingContent = { Icon(Icons.Filled.Sync, null) },
                modifier = Modifier.clickable {
                    if ((sync as? SyncStatus.Fehler)?.abgelaufen == true) serverVerbinden() else vm.syncJetzt()
                },
            )
        }

        }

        Abschnitt("Verbindungstest")
        Card { de.hjorven.fahrtenbuch.ui.VerbindungstestKarte() }

        Abschnitt("Automatisches Fahrtende")
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Fahrt endet nach ${slider.toInt()} Minuten Stillstand")
                Slider(
                    value = slider,
                    onValueChange = { slider = it },
                    onValueChangeFinished = { vm.setAutoStop(slider.toInt()) },
                    valueRange = 3f..30f,
                    steps = 26,
                )
                Text(
                    "Gilt ab der nächsten Fahrt. Das Fahrtende wird auf den Beginn des Stillstands gesetzt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Roadmap Nr. 6.3
                var auto by remember { mutableStateOf(vm.prefs.autoVorlaeufig) }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Beim Einsteigen vorläufig aufzeichnen")
                        Text(
                            "Meldet das Handy \"im Auto\", zeichnet die App vorläufig auf und fragt \"Fährst du?\". " +
                                "Die Fahrt zählt erst nach Bestätigung, \"Nein\" löscht sie. Kostet etwas Akku.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = auto, onCheckedChange = { auto = it; vm.prefs.autoVorlaeufig = it })
                }
            }
        }

        if (a != null) {
            Abschnitt("Auto reservieren")
            Card { de.hjorven.fahrtenbuch.ui.ReservierungenKarte(vm, fahrzeuge, namen) }
            Abschnitt("Termine (TÜV, Inspektion ...)")
            Card { de.hjorven.fahrtenbuch.ui.TermineKarte(vm, fahrzeuge) }
            Abschnitt("Sonstige Kosten (Werkstatt, Parken ...)")
            Card { de.hjorven.fahrtenbuch.ui.KostenKarte(vm, fahrzeuge) }
        }

        Abschnitt("Bluetooth im Auto")
        Card { de.hjorven.fahrtenbuch.ui.BluetoothAutosKarte(fahrzeuge.filter { it.aktiv }) }

        Abschnitt("Fahrzeuge")
        Card {
            fahrzeuge.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider()
                ListItem(
                    headlineContent = { Text(f.name) },
                    supportingContent = {
                        val preis = if (f.preisProKm > 0) " · ${zahl(f.preisProKm, 2)} €/km fest" else ""
                        Text(kmStand(f.kmStand) + preis)
                    },
                    leadingContent = { Icon(Icons.Filled.DirectionsCar, null, tint = farbe(f.farbe)) },
                    modifier = Modifier.clickable { bearbeiten = f },
                )
            }
        }

        Abschnitt("Hintergrund")
        Card {
            ListItem(
                headlineContent = { Text("Berechtigungen") },
                supportingContent = { Text("Einrichtung erneut öffnen und fehlende Berechtigungen erteilen") },
                leadingContent = { Icon(Icons.Filled.Security, null) },
                modifier = Modifier.clickable { vm.einrichtung(true) },
            )
            HorizontalDivider()
            // Nach Rückkehr aus den System-Einstellungen neu lesen.
            var stand by remember { mutableIntStateOf(0) }
            LifecycleResumeEffect(Unit) {
                stand++
                onPauseOrDispose { }
            }
            val immer = remember(stand) {
                Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
            }
            ListItem(
                headlineContent = { Text("Standort immer erlauben") },
                supportingContent = {
                    Text(
                        if (immer) "Erlaubt, gut so"
                        else "Nicht erlaubt: Widget und Schnelleinstellung können Fahrten ohne geöffnete App nicht starten. Tippen, dann Berechtigungen > Standort > Immer zulassen."
                    )
                },
                leadingContent = { Icon(Icons.Filled.LocationOn, null) },
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                },
            )
            HorizontalDivider()
            val pm = context.getSystemService(PowerManager::class.java)
            val ignoriert = pm.isIgnoringBatteryOptimizations(context.packageName)
            ListItem(
                headlineContent = { Text("Akku-Optimierung ausschalten") },
                supportingContent = {
                    Text(if (ignoriert) "Ausgeschaltet, gut so" else "Sonst kann Android die Aufzeichnung unterbrechen")
                },
                leadingContent = { Icon(Icons.Filled.BatteryAlert, null) },
                modifier = Modifier.clickable(enabled = !ignoriert) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    )
                },
            )
        }

        Abschnitt("Test-Modus")
        val testModus by vm.testModus.collectAsStateWithLifecycle()
        val testAufnahme by vm.testAufnahme.collectAsStateWithLifecycle()
        Card {
            ListItem(
                headlineContent = { Text("Test-Modus") },
                supportingContent = {
                    Text("Neue Fahrten und Tankungen werden als Test markiert: sie zählen nicht in Statistik, " +
                        "km-Stand und Display und bekommen eine Diagnose (Akku, GPS, Hintergrund, Sync).")
                },
                leadingContent = { Icon(Icons.Filled.Science, null) },
                trailingContent = { Switch(checked = testModus, onCheckedChange = { vm.setTestModus(it) }) },
                modifier = Modifier.clickable { vm.setTestModus(!testModus) },
            )
            if (testModus) {
                HorizontalDivider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aufnahme-Rate (ab der nächsten Test-Fahrt)")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Normal", 10 to "10 m", 25 to "25 m", 50 to "50 m", 100 to "100 m").forEach { (m, text) ->
                            FilterChip(selected = testAufnahme == m, onClick = { vm.setTestAufnahme(m) }, label = { Text(text) })
                        }
                    }
                    Text(
                        "Normal = so oft wie möglich. Größere Abstände sparen Akku, die Strecke wird ungenauer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Alle Test-Fahrten löschen") },
                supportingContent = { Text(testMeldung ?: "Eigene Test-Fahrten und Test-Tankungen, auch auf dem Server") },
                leadingContent = { Icon(Icons.Filled.DeleteSweep, null) },
                modifier = Modifier.clickable { testLoeschen = true },
            )
        }

        Abschnitt("Hilfe")
        Card { de.hjorven.fahrtenbuch.ui.HilfeKarte() }

        Abschnitt("App")
        val update by vm.update.collectAsStateWithLifecycle()
        val meldung by vm.updateMeldung.collectAsStateWithLifecycle()
        Card {
            ListItem(
                headlineContent = { Text("Nach Updates suchen") },
                supportingContent = {
                    Text(
                        "Installiert: Version ${Update.installierteVersion(context)}" +
                            (update?.let { "\nNeu: Version ${it.nummer}, antippen zum Aktualisieren" } ?: meldung?.let { "\n$it" } ?: "")
                    )
                },
                leadingContent = { Icon(Icons.Filled.SystemUpdate, null) },
                modifier = Modifier.clickable {
                    val neu = update
                    if (neu != null) vm.updateInstallieren()
                    else vm.updatePruefen(erzwingen = true)
                },
            )
        }
    }

    problem?.let { text ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text("Problem melden") },
            text = {
                OutlinedTextField(text, { problem = it }, label = { Text("Was ist passiert?") }, minLines = 3,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = text
                    problem = null
                    scope.launch { problemMeldung = vm.problemMelden(t) ?: "Gesendet, danke!" }
                }, enabled = text.isNotBlank()) { Text("Senden") }
            },
            dismissButton = { TextButton(onClick = { problem = null }) { Text("Abbrechen") } },
        )
    }

    einladung?.let { text ->
        AlertDialog(
            onDismissRequest = { einladung = null },
            title = { Text("Familienmitglied einladen") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        text.isEmpty() -> CircularProgressIndicator()
                        text == "FEHLER" -> Text("Einladung ging nicht. Mit dem Heim-Server verbunden?")
                        else -> {
                            de.hjorven.fahrtenbuch.ui.QrBild(text)
                            val code = de.hjorven.fahrtenbuch.data.Verbinden.lesen(text)?.einladung.orEmpty()
                            Text("Im Login „QR-Code scannen“ oder den Code eintippen:", style = MaterialTheme.typography.bodySmall)
                            Text(code.chunked(4).joinToString("-"), style = MaterialTheme.typography.titleLarge)
                            Text("Gilt 24 Stunden und nur einmal.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { einladung = null }) { Text("Fertig") } },
        )
    }

    abmeldenOffen?.let { offen ->
        AlertDialog(
            onDismissRequest = { abmeldenOffen = null },
            title = { Text("Abmelden?") },
            text = {
                Text(
                    if (offen > 0) "$offen ${if (offen == 1) "Eintrag ist" else "Einträge sind"} noch nicht auf dem Server. " +
                        "Sie bleiben auf dem Handy und werden nach der nächsten Anmeldung hochgeladen. " +
                        "Sicherer ist es, vorher zu synchronisieren."
                    else "Alles ist auf dem Server. Zum Anmelden brauchst du danach wieder E-Mail und Passwort."
                )
            },
            confirmButton = {
                TextButton(onClick = { abmeldenOffen = null; vm.abmelden() }) {
                    Text(if (offen > 0) "Trotzdem abmelden" else "Abmelden")
                }
            },
            dismissButton = {
                TextButton(onClick = { if (offen > 0) vm.syncJetzt(); abmeldenOffen = null }) {
                    Text(if (offen > 0) "Erst synchronisieren" else "Abbrechen")
                }
            },
        )
    }

    if (adressenAendern) {
        var haupt by remember { mutableStateOf(anmeldung?.serverUrl ?: "") }
        var tail by remember { mutableStateOf(vm.prefs.tailscaleUrl) }
        AlertDialog(
            onDismissRequest = { adressenAendern = false },
            title = { Text("Server-Adressen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(haupt, { haupt = it }, singleLine = true, label = { Text("Heimnetz") },
                        placeholder = { Text("http://fahrtenbuch.local:8090") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(tail, { tail = it }, singleLine = true, label = { Text("Tailscale (optional)") },
                        placeholder = { Text("http://100.x.y.z:8090") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text(
                        "Die App versucht zuerst die Heimnetz-Adresse und dann Tailscale.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.adressenSpeichern(haupt, tail); adressenAendern = false }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { adressenAendern = false }) { Text("Abbrechen") } },
        )
    }

    if (testLoeschen) {
        AlertDialog(
            onDismissRequest = { testLoeschen = false },
            title = { Text("Alle Test-Fahrten löschen?") },
            text = { Text("Deine Test-Fahrten und Test-Tankungen werden gelöscht. Normale Einträge bleiben.") },
            confirmButton = {
                TextButton(onClick = {
                    testLoeschen = false
                    scope.launch {
                        val n = vm.alleTestLoeschen()
                        testMeldung = if (n == 0) "Keine Test-Einträge vorhanden" else "$n Test-Einträge gelöscht"
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { testLoeschen = false }) { Text("Abbrechen") } },
        )
    }

    if (nameAendern) {
        var name by remember { mutableStateOf(lokalerName) }
        AlertDialog(
            onDismissRequest = { nameAendern = false },
            title = { Text("Dein Name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { vm.setLokalerName(name); nameAendern = false }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { nameAendern = false }) { Text("Abbrechen") } },
        )
    }

    bearbeiten?.let { f ->
        var km by remember(f.id) { mutableStateOf(zahl(f.kmStand, 0)) }
        var preis by remember(f.id) { mutableStateOf(if (f.preisProKm > 0) zahl(f.preisProKm, 2) else "") }
        var antrieb by remember(f.id) { mutableStateOf(f.kraftstoff) }
        AlertDialog(
            onDismissRequest = { bearbeiten = null },
            title = { Text(f.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Antrieb", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Antrieb.ARTEN.forEach { (wert, text) ->
                            FilterChip(selected = antrieb == wert, onClick = { antrieb = wert }, label = { Text(text) })
                        }
                    }
                    OutlinedTextField(km, { km = it }, label = { Text("Kilometerstand") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(preis, { preis = it }, label = { Text("Fester Preis pro km (optional)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = {
                            Text(if (antrieb == "elektro") "Leer lassen = aus Ladedaten berechnen" else "Leer lassen = aus Tankdaten berechnen")
                        })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.fahrzeugSpeichern(
                        f.copy(
                            kmStand = km.replace(".", "").alsZahl() ?: f.kmStand,
                            preisProKm = preis.alsZahl() ?: 0.0,
                            kraftstoff = antrieb,
                        )
                    )
                    bearbeiten = null
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { bearbeiten = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Abschnitt(titel: String) {
    Text(titel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth())
}
