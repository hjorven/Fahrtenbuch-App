package de.hjorven.fahrtenbuch.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import de.hjorven.fahrtenbuch.ui.QrScanKnoepfe
import de.hjorven.fahrtenbuch.data.Verbinden
import de.hjorven.fahrtenbuch.ui.serverSuchen
import androidx.compose.material3.TextButton
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.hjorven.fahrtenbuch.ui.HauptViewModel
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(vm: HauptViewModel, fertig: () -> Unit) {
    var server by remember { mutableStateOf(vm.prefs.letzteServerUrl) }
    var tailscale by remember { mutableStateOf(vm.prefs.tailscaleUrl) }
    var email by remember { mutableStateOf(vm.prefs.letzteEmail) }
    var passwort by remember { mutableStateOf("") }
    var fehler by remember { mutableStateOf<String?>(null) }
    var laedt by remember { mutableStateOf(false) }
    var suche by remember { mutableStateOf<String?>(null) }
    // "login", "einladung" (Konto mit Code anlegen) oder "reset" (neues Passwort mit Code vom Admin)
    var modus by remember { mutableStateOf("login") }
    var code by remember { mutableStateOf("") }
    var codeEintippen by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var qrHinweis by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val autofill = LocalAutofillManager.current

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Icon(Icons.Filled.DirectionsCar, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Fahrtenbuch", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Mit dem Server im Heimnetz verbinden. Fahrten, die du bisher nur auf diesem Handy gespeichert hast, werden danach hochgeladen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Schnellster Weg: QR-Code vom Display, von der Pi-Startseite oder vom Handy eines Familienmitglieds.
            QrScanKnoepfe { text ->
                val d = Verbinden.lesen(text)
                if (d == null) {
                    qrHinweis = "Das ist kein Fahrtenbuch-Code."
                } else {
                    if (d.heim.isNotBlank()) server = d.heim
                    if (d.tail.isNotBlank()) tailscale = d.tail
                    when {
                        d.einladung.isNotBlank() -> { modus = "einladung"; code = d.einladung }
                        d.reset.isNotBlank() -> { modus = "reset"; code = d.reset }
                    }
                    qrHinweis = when (modus) {
                        "einladung" -> "Einladung erkannt. Name, E-Mail und ein Passwort eintragen."
                        "reset" -> "Code zum Zurücksetzen erkannt. Neues Passwort eintragen."
                        else -> "Adressen übernommen."
                    }
                }
            }
            qrHinweis?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            // Zweiter Weg ohne Kamera (Nachtplan 5c): Code abtippen.
            TextButton(onClick = { codeEintippen = !codeEintippen }) { Text("Einladungs- oder Reset-Code eintippen") }
            if (codeEintippen) {
                OutlinedTextField(
                    code, { code = it },
                    label = { Text("Code (12 Zeichen)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = modus == "einladung", onClick = { modus = "einladung" }, label = { Text("Einladung") })
                    FilterChip(selected = modus == "reset", onClick = { modus = "reset" }, label = { Text("Passwort vergessen") })
                    FilterChip(selected = modus == "login", onClick = { modus = "login" }, label = { Text("Normal anmelden") })
                }
            }
            OutlinedTextField(
                server, { server = it },
                label = { Text("Server-Adresse im Heimnetz") },
                placeholder = { Text("http://fahrtenbuch.local:8090") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            // Server im WLAN finden (mDNS, Roadmap Nr. 3); die Adresse lässt sich weiter von Hand eintragen.
            TextButton(
                onClick = {
                    suche = "Suche im WLAN ..."
                    scope.launch {
                        val gefunden = serverSuchen(context)
                        if (gefunden != null) server = gefunden
                        suche = gefunden?.let { "Gefunden: $it" } ?: "Kein Server im WLAN gefunden. Adresse von Hand eintragen."
                    }
                },
                enabled = !laedt,
            ) { Text("Server im WLAN suchen") }
            suche?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(
                tailscale, { tailscale = it },
                label = { Text("Tailscale-Adresse (optional)") },
                placeholder = { Text("http://100.x.y.z:8090") },
                supportingText = { Text("Wird benutzt, wenn die Heimnetz-Adresse nicht antwortet") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (modus == "einladung") OutlinedTextField(
                name, { name = it },
                label = { Text("Dein Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (modus != "reset") OutlinedTextField(
                email, { email = it },
                label = { Text("E-Mail") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                // Damit der Google Passwortmanager E-Mail und Passwort speichern und ausfüllen kann.
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
            )
            OutlinedTextField(
                passwort, { passwort = it },
                label = { Text(if (modus == "login") "Passwort" else "Neues Passwort (mindestens 8 Zeichen)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            )
            fehler?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    laedt = true
                    fehler = null
                    scope.launch {
                        if (modus != "login") {
                            val c = Verbinden.code(code)
                            fehler = if (c.isEmpty()) "Der Code hat 12 Zeichen." else
                                vm.mitCode(server, tailscale, c, modus == "reset", name, email, passwort)
                            laedt = false
                            if (fehler == null) { autofill?.commit(); fertig() }
                            return@launch
                        }
                        fehler = vm.login(server, tailscale, email, passwort)
                        // fahrtenbuch.local löst nicht auf jedem Handy auf: dann im WLAN suchen und nochmal.
                        if (fehler?.startsWith("Server nicht erreichbar") == true) {
                            serverSuchen(context)?.let { gefunden ->
                                server = gefunden
                                fehler = vm.login(gefunden, tailscale, email, passwort)
                            }
                        }
                        laedt = false
                        if (fehler == null) {
                            // Fragt "Passwort im Passwortmanager speichern?"
                            autofill?.commit()
                            fertig()
                        }
                    }
                },
                enabled = !laedt && (server.isNotBlank() || tailscale.isNotBlank()) && passwort.isNotBlank() &&
                    (modus == "reset" || email.isNotBlank()) && (modus != "einladung" || name.isNotBlank()),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (laedt) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(when (modus) { "einladung" -> "Konto anlegen"; "reset" -> "Passwort setzen und anmelden"; else -> "Anmelden" })
            }
            Text(
                "Passwort vergessen? Admin zeigt dir am Display unter „Mehr“ oder in der Admin-Oberfläche einen Code zum Zurücksetzen. Den hier scannen oder eintippen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
