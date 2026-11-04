package de.hjorven.fahrtenbuch.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import de.hjorven.fahrtenbuch.data.Fahrzeug
import de.hjorven.fahrtenbuch.location.AutoBluetooth

/** Einstellungen: welches gekoppelte Bluetooth-Gerät zu welchem Auto gehört (Roadmap Nr. 6). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BluetoothAutosKarte(fahrzeuge: List<Fahrzeug>) {
    val context = LocalContext.current
    val prefs = (context.applicationContext as FahrtenbuchApp).prefs
    var neu by remember { mutableIntStateOf(0) }
    var zuordnung by remember { mutableStateOf(AutoBluetooth.lesen(prefs.bluetoothAutos)) }
    val recht = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { neu++ }
    val erlaubt = remember(neu) { AutoBluetooth.erlaubt(context) }
    val geraete = remember(neu) { AutoBluetooth.gekoppelte(context) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Verbindet sich das Handy mit dem Bluetooth eines Autos, fragt die App \"Fährst du?\". Sie startet nie von selbst.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            !erlaubt -> OutlinedButton(onClick = { if (Build.VERSION.SDK_INT >= 31) recht.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                Text("Bluetooth-Geräte erlauben")
            }
            geraete.isEmpty() -> Text("Keine gekoppelten Geräte. Erst in den Android-Einstellungen mit dem Auto koppeln.")
        }
        geraete.forEach { g ->
            Text(g.name, style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(zuordnung[g.adresse] == null, {
                    zuordnung = zuordnung - g.adresse; prefs.bluetoothAutos = AutoBluetooth.schreiben(zuordnung)
                }, { Text("kein Auto") })
                fahrzeuge.forEach { f ->
                    FilterChip(zuordnung[g.adresse] == f.id, {
                        zuordnung = zuordnung + (g.adresse to f.id); prefs.bluetoothAutos = AutoBluetooth.schreiben(zuordnung)
                    }, { Text(f.name, maxLines = 1) })
                }
            }
        }
    }
}
