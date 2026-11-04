package de.hjorven.fahrtenbuch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hjorven.fahrtenbuch.data.Update

/**
 * Großer, einfacher Update-Hinweis oben in allen Menüs: ein Knopf, die App erledigt den Rest
 * (laden, prüfen, Installer öffnen). Dazu die Dialoge für Erlaubnis und Fehler.
 */
@Composable
fun UpdateHinweis(vm: HauptViewModel) {
    val update by vm.update.collectAsStateWithLifecycle()
    val lauf by vm.updateLauf.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Zurück aus der Einstellung "Unbekannte Apps": gleich weitermachen.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val beobachter = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.updateFortsetzen() }
        owner.lifecycle.addObserver(beobachter)
        onDispose { owner.lifecycle.removeObserver(beobachter) }
    }

    val neu = update
    if (neu != null) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SystemUpdate, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Neue Version der App ist da", style = MaterialTheme.typography.titleMedium)
                }
                val l = lauf
                if (l is UpdateLauf.Laedt) {
                    Text("Wird geladen, bitte warten ... ${(l.anteil * 100).toInt()} %", style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(progress = { l.anteil }, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(
                        "Alle Fahrten bleiben erhalten. Danach fragt Android noch einmal, dort \"Aktualisieren\" tippen.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { vm.updateInstallieren() },
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        ) { Text("Jetzt aktualisieren", style = MaterialTheme.typography.titleMedium) }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { vm.updateAusblenden() }, modifier = Modifier.heightIn(min = 56.dp)) {
                            Text("Später")
                        }
                    }
                }
            }
        }
    }

    when (val l = lauf) {
        UpdateLauf.ErlaubnisNoetig -> AlertDialog(
            onDismissRequest = { vm.updateLaufSchliessen() },
            title = { Text("Einmal erlauben") },
            text = {
                Text(
                    "Beim ersten Update braucht Android eine Erlaubnis.\n\n" +
                        "1. Auf \"Weiter\" tippen.\n" +
                        "2. Den Schalter \"Dieser Quelle vertrauen\" bzw. \"Aus dieser Quelle zulassen\" einschalten.\n" +
                        "3. Oben links auf den Pfeil \"Zurück\" tippen.\n\n" +
                        "Das Update geht dann von selbst weiter. Beim nächsten Mal entfällt dieser Schritt.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = { Button(onClick = { Update.erlaubnisOeffnen(context) }) { Text("Weiter") } },
            dismissButton = { TextButton(onClick = { vm.updateLaufSchliessen() }) { Text("Abbrechen") } },
        )
        is UpdateLauf.Fehler -> AlertDialog(
            onDismissRequest = { vm.updateLaufSchliessen() },
            title = { Text("Update hat nicht geklappt") },
            text = { Text(l.text, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { Button(onClick = { vm.updateLaufSchliessen() }) { Text("OK") } },
        )
        else -> {}
    }
}
