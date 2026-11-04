package de.hjorven.fahrtenbuch.ui.screens

import android.Manifest
import androidx.compose.material.icons.filled.SystemUpdate
import de.hjorven.fahrtenbuch.data.Update
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

private fun erlaubt(context: Context, p: String) =
    ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

private fun appEinstellungen(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    )
}

/**
 * Einrichtung beim ersten Start: fragt nacheinander Standort, Standort im Hintergrund,
 * Benachrichtigungen, Bewegung, Akku-Optimierung und das Installieren von Updates ab. Jeder Schritt zeigt seinen Stand und
 * aktualisiert sich, wenn man aus den Systemeinstellungen zurückkommt.
 */
@SuppressLint("BatteryLife", "InlinedApi")
@Composable
fun EinrichtungScreen(fertig: () -> Unit) {
    val context = LocalContext.current
    // Wird bei jedem Zurückkehren in die App erhöht, damit der Stand neu gelesen wird.
    var stand by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        stand++
        onPauseOrDispose { }
    }

    val standort = remember(stand) { erlaubt(context, Manifest.permission.ACCESS_FINE_LOCATION) }
    // Ohne Standort überhaupt ist "immer" wertlos, auch wenn Android das Recht noch als erteilt führt.
    val hintergrund = remember(stand) {
        standort && (Build.VERSION.SDK_INT < 29 || erlaubt(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }
    val benachrichtigung = remember(stand) {
        Build.VERSION.SDK_INT < 33 || erlaubt(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    val bewegung = remember(stand) {
        Build.VERSION.SDK_INT < 29 || erlaubt(context, Manifest.permission.ACTIVITY_RECOGNITION)
    }
    val bewegungAnfrage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { stand++ }
    val updates = remember(stand) { Update.installierenErlaubt(context) }
    val akku = remember(stand) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    // Wie oft eine Anfrage schon abgelehnt wurde: danach fragt Android nicht mehr, dann App-Einstellungen öffnen.
    var standortAbgelehnt by rememberSaveable { mutableIntStateOf(0) }
    var hintergrundAbgelehnt by rememberSaveable { mutableIntStateOf(0) }
    var benachrichtigungAbgelehnt by rememberSaveable { mutableIntStateOf(0) }

    val standortAnfrage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] != true) standortAbgelehnt++
        stand++
    }
    val hintergrundAnfrage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) hintergrundAbgelehnt++
        stand++
    }
    val benachrichtigungAnfrage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) benachrichtigungAbgelehnt++
        stand++
    }

    fun standortFragen() {
        if (standortAbgelehnt >= 2) appEinstellungen(context)
        else standortAnfrage.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    // Beim ersten Öffnen direkt mit dem wichtigsten Schritt anfangen.
    var automatisch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!automatisch && !standort) {
            automatisch = true
            standortFragen()
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Willkommen", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Damit Fahrten zuverlässig aufgezeichnet werden, braucht die App ein paar Berechtigungen. " +
                    "Alles bleibt auf deinem Handy, solange du keinen Server verbindest.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Schritt(
                icon = Icons.Filled.MyLocation,
                titel = "Standort",
                text = "Für Strecke, Kilometer und Geschwindigkeit. Bitte \"Genau\" auswählen.",
                erledigt = standort,
                knopf = if (standortAbgelehnt >= 2) "Einstellungen" else "Erlauben",
            ) { standortFragen() }

            if (Build.VERSION.SDK_INT >= 29) {
                Schritt(
                    icon = Icons.Filled.LocationOn,
                    titel = "Standort immer",
                    text = "Damit Widget und Schnelleinstellung Fahrten auch ohne geöffnete App starten. " +
                        "Im nächsten Fenster \"Immer zulassen\" wählen.",
                    erledigt = hintergrund,
                    aktiv = standort,
                    knopf = if (hintergrundAbgelehnt >= 1) "Einstellungen" else "Erlauben",
                ) {
                    if (hintergrundAbgelehnt >= 1) appEinstellungen(context)
                    else hintergrundAnfrage.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }

            if (Build.VERSION.SDK_INT >= 33) {
                Schritt(
                    icon = Icons.Filled.Notifications,
                    titel = "Benachrichtigungen",
                    text = "Zeigt die laufende Fahrt mit Tempo und Kilometern an und erlaubt das Beenden.",
                    erledigt = benachrichtigung,
                    knopf = if (benachrichtigungAbgelehnt >= 2) "Einstellungen" else "Erlauben",
                ) {
                    if (benachrichtigungAbgelehnt >= 2) appEinstellungen(context)
                    else benachrichtigungAnfrage.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            if (Build.VERSION.SDK_INT >= 29) {
                Schritt(
                    icon = Icons.Filled.DirectionsCar,
                    titel = "Bewegung (Stau)",
                    text = "Erkennt, dass du noch im Auto sitzt, damit die Fahrt im Stau nicht automatisch endet. Optional.",
                    erledigt = bewegung,
                    knopf = "Erlauben",
                ) { bewegungAnfrage.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
            }

            Schritt(
                icon = Icons.Filled.BatteryAlert,
                titel = "Akku-Optimierung ausschalten",
                text = "Sonst kann Android die Aufzeichnung bei langen Fahrten unterbrechen.",
                erledigt = akku,
                knopf = "Ausschalten",
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }

            Schritt(
                icon = Icons.Filled.SystemUpdate,
                titel = "Updates installieren",
                text = "Damit neue Versionen mit einem Tipp in der App kommen. Im nächsten Fenster den Schalter " +
                    "\"Dieser Quelle vertrauen\" einschalten, dann zurück.",
                erledigt = updates,
                knopf = "Erlauben",
            ) { Update.erlaubnisOeffnen(context) }

            Spacer(Modifier.height(8.dp))
            val alles = standort && hintergrund && benachrichtigung && akku
            Button(onClick = fertig, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (alles) "Los geht's" else "Weiter")
            }
            if (!alles) {
                Text(
                    "Fehlende Berechtigungen kannst du später unter Einstellungen > Berechtigungen nachholen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Schritt(
    icon: ImageVector,
    titel: String,
    text: String,
    erledigt: Boolean,
    knopf: String,
    aktiv: Boolean = true,
    onClick: () -> Unit,
) {
    Card {
        ListItem(
            leadingContent = { Icon(icon, null) },
            headlineContent = { Text(titel) },
            supportingContent = {
                Text(if (!aktiv && !erledigt) "$text Zuerst den Standort erlauben." else text)
            },
            trailingContent = {
                if (erledigt) {
                    Icon(Icons.Filled.CheckCircle, "erledigt", tint = MaterialTheme.colorScheme.primary)
                } else {
                    FilledTonalButton(onClick = onClick, enabled = aktiv) { Text(knopf) }
                }
            },
        )
    }
}
