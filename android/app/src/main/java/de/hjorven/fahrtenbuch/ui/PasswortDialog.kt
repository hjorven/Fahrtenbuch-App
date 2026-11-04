package de.hjorven.fahrtenbuch.ui

import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Bittet nach dem Start einmal, ein eigenes Passwort festzulegen, solange das Konto noch das
 * Start-Passwort hat (Server-Feld users.passwort_aendern). Über die Einstellungen jederzeit erreichbar.
 */
@Composable
fun PasswortDialog(vm: HauptViewModel) {
    val noetig by vm.passwortAendernNoetig.collectAsStateWithLifecycle()
    val offen by vm.passwortDialog.collectAsStateWithLifecycle()
    val laufend by vm.laufend.collectAsStateWithLifecycle()
    LaunchedEffect(noetig, laufend) {
        if (noetig && laufend == null && vm.passwortHinweisZeigen) vm.passwortDialog(true)
    }
    if (!offen) return

    var alt by remember { mutableStateOf("") }
    var neu by remember { mutableStateOf("") }
    var wieder by remember { mutableStateOf("") }
    var fehler by remember { mutableStateOf<String?>(null) }
    var laeuft by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val autofill = LocalAutofillManager.current
    val pw = KeyboardOptions(keyboardType = KeyboardType.Password)

    AlertDialog(
        onDismissRequest = { if (!laeuft) vm.passwortDialog(false, spaeter = true) },
        title = { Text(if (noetig) "Eigenes Passwort festlegen" else "Passwort ändern") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (noetig) Text(
                    "Du nutzt noch das Start-Passwort, das alle bekommen haben. Bitte lege jetzt ein eigenes fest.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedTextField(alt, { alt = it }, label = { Text("Bisheriges Passwort") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = pw,
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password })
                OutlinedTextField(neu, { neu = it }, label = { Text("Neues Passwort (mind. 8 Zeichen)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = pw,
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword })
                OutlinedTextField(wieder, { wieder = it }, label = { Text("Neues Passwort wiederholen") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = pw,
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword })
                Text(
                    "Bisheriges Passwort vergessen? Admin kann es auf dem Server neu setzen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                fehler?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
                if (laeuft) CircularProgressIndicator()
            }
        },
        confirmButton = {
            Button(
                enabled = !laeuft && alt.isNotEmpty() && neu.isNotEmpty() && wieder.isNotEmpty(),
                onClick = {
                    laeuft = true
                    fehler = null
                    scope.launch {
                        fehler = vm.passwortSetzen(alt, neu, wieder)
                        laeuft = false
                        // Neues Passwort im Passwortmanager speichern lassen.
                        if (fehler == null) autofill?.commit()
                    }
                },
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(enabled = !laeuft, onClick = { vm.passwortDialog(false, spaeter = true) }) { Text("Später") }
        },
    )
}
