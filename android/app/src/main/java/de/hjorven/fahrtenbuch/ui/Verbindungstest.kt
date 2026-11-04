package de.hjorven.fahrtenbuch.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import de.hjorven.fahrtenbuch.data.Prefs
import de.hjorven.fahrtenbuch.data.Update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Eine Zeile der Ampel: [ok] null = nicht eingerichtet (grau). */
data class Pruefpunkt(val name: String, val ok: Boolean?, val text: String, val beheben: String = "", val einstellungen: Boolean = false)

private fun erlaubt(c: Context, recht: String) = ContextCompat.checkSelfPermission(c, recht) == PackageManager.PERMISSION_GRANTED

/** Verbindungstest (Roadmap Nr. 5): Heimnetz, Tailscale, Downloads, Standort, Bewegung, Benachrichtigung. */
suspend fun verbindungPruefen(c: Context): List<Pruefpunkt> {
    val app = c.applicationContext as FahrtenbuchApp
    val p = app.prefs
    val heim = Prefs.normalisieren(p.anmeldung.value?.serverUrl ?: p.letzteServerUrl)
    val ip = Prefs.normalisieren(p.letzteIpUrl)
    val tail = Prefs.normalisieren(p.tailscaleUrl)
    return kotlinx.coroutines.coroutineScope {
        val heimOk = async { heim.isNotEmpty() && app.api.erreichbar(heim) }
        val ipOk = async { ip.isNotEmpty() && app.api.erreichbar(ip) }
        val tailOk = async { tail.isNotEmpty() && app.api.erreichbar(tail) }
        val dl = async {
            Update.quellen(listOf(heim, ip, tail)).firstOrNull { app.api.erreichbarRoh(it + "android.json") }
        }
        val liste = mutableListOf<Pruefpunkt>()
        liste += when {
            heim.isEmpty() -> Pruefpunkt("Heimnetz", null, "keine Adresse", "Unter Konto bei „Server-Adressen“ eintragen.")
            heimOk.await() -> Pruefpunkt("Heimnetz", true, heim)
            ipOk.await() -> Pruefpunkt("Heimnetz", true, "$heim antwortet nicht, aber $ip")
            else -> Pruefpunkt("Heimnetz", false, "$heim antwortet nicht", "Im WLAN zuhause? Pi an? Sonst unter „Server-Adressen“ die IP des Pi eintragen.")
        }
        liste += when {
            tail.isEmpty() -> Pruefpunkt("Tailscale", null, "nicht eingetragen", "Für unterwegs: Tailscale-App installieren und unter „Server-Adressen“ eintragen.")
            tailOk.await() -> Pruefpunkt("Tailscale", true, tail)
            else -> Pruefpunkt("Tailscale", false, "$tail antwortet nicht", "Tailscale-App öffnen und verbinden.")
        }
        val dlUrl = dl.await()
        liste += if (dlUrl != null) Pruefpunkt("Downloads", true, dlUrl)
        else Pruefpunkt("Downloads", false, "Pi-Startseite (Port 8091) antwortet nicht", "Updates gehen dann nicht. Pi prüfen oder später nochmal.")
        val standort = erlaubt(c, Manifest.permission.ACCESS_FINE_LOCATION)
        val immer = standort && (Build.VERSION.SDK_INT < 29 || erlaubt(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        liste += when {
            immer -> Pruefpunkt("Standort", true, "immer erlaubt")
            standort -> Pruefpunkt("Standort", false, "nur bei Nutzung", "Berechtigungen → Standort → „Immer zulassen“.", true)
            else -> Pruefpunkt("Standort", false, "nicht erlaubt", "Berechtigungen → Standort → „Immer zulassen“ und „Genauer Standort“.", true)
        }
        val bewegung = Build.VERSION.SDK_INT < 29 || erlaubt(c, Manifest.permission.ACTIVITY_RECOGNITION)
        liste += if (bewegung) Pruefpunkt("Bewegung", true, "erlaubt")
        else Pruefpunkt("Bewegung", false, "nicht erlaubt", "Berechtigungen → Körperliche Aktivitäten → Zulassen (für Stau-Erkennung).", true)
        val benachrichtigung = Build.VERSION.SDK_INT < 33 || erlaubt(c, Manifest.permission.POST_NOTIFICATIONS)
        liste += if (benachrichtigung) Pruefpunkt("Benachrichtigung", true, "erlaubt")
        else Pruefpunkt("Benachrichtigung", false, "nicht erlaubt", "Ohne sie siehst du die laufende Fahrt nicht. Benachrichtigungen → Zulassen.", true)
        liste
    }
}

@Composable
fun VerbindungstestKarte() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var ergebnis by remember { mutableStateOf<List<Pruefpunkt>?>(null) }
    var laeuft by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            laeuft = true
            scope.launch {
                ergebnis = verbindungPruefen(c)
                laeuft = false
            }
        }, enabled = !laeuft, modifier = Modifier.fillMaxWidth()) {
            if (laeuft) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Verbindung testen")
        }
        ergebnis?.forEach { p ->
            Row(verticalAlignment = Alignment.Top) {
                val farbe = when (p.ok) { true -> Color(0xFF2E7D32); false -> Color(0xFFD32F2F); null -> Color(0xFF9E9E9E) }
                Spacer(Modifier.padding(top = 6.dp).size(12.dp).background(farbe, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${p.name}: ${p.text}", style = MaterialTheme.typography.bodyMedium)
                    if (p.ok != true && p.beheben.isNotEmpty()) {
                        Text("So behebst du das: ${p.beheben}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p.einstellungen) TextButton(onClick = {
                            c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))
                        }) { Text("Einstellungen öffnen") }
                    }
                }
            }
        }
    }
}
