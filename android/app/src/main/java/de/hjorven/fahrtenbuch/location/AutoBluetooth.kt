package de.hjorven.fahrtenbuch.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import de.hjorven.fahrtenbuch.MainActivity
import de.hjorven.fahrtenbuch.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Bluetooth im Auto (Roadmap Nr. 6): Verbindet sich das Handy mit dem Bluetooth eines Autos, das in
 * den Einstellungen zugeordnet ist, fragt eine Benachrichtigung "Kombi verbunden. Fährst du?" mit
 * **Ich fahre** und **Nein**. Es startet nie still, man kann ja Beifahrer sein.
 */
object AutoBluetooth {
    private const val ID = 73

    fun erlaubt(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    data class Geraet(val adresse: String, val name: String)

    /** Gekoppelte Geräte (Name, Adresse), leer ohne Recht oder ohne Bluetooth. */
    @SuppressLint("MissingPermission")
    fun gekoppelte(context: Context): List<Geraet> {
        if (!erlaubt(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map { Geraet(it.address, it.name ?: it.address) }.sortedBy { it.name.lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** Zuordnung "Adresse=FahrzeugId;..." lesen und schreiben (reine Funktionen, getestet). */
    fun lesen(text: String): Map<String, String> =
        text.split(';').mapNotNull { t -> t.split('=').takeIf { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }?.let { it[0] to it[1] } }.toMap()

    fun schreiben(m: Map<String, String>): String = m.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    internal suspend fun verbunden(context: Context, adresse: String) {
        val app = context.applicationContext as FahrtenbuchApp
        val fahrzeugId = lesen(app.prefs.bluetoothAutos)[adresse] ?: return
        if (FahrtStatus.aktiv.value != null) return
        val auto = app.db.fahrzeugDao().get(fahrzeugId)?.takeIf { it.aktiv } ?: return
        // Fährt gerade jemand anderes dieses Auto? Dank Live-Upload höchstens 2 Minuten alt.
        if (app.prefs.anmeldung.value != null) kotlinx.coroutines.withTimeoutOrNull(7_000) { app.sync.sync() }
        val andere = app.db.fahrtDao().laufendeAndere(auto.id, app.prefs.userId, System.currentTimeMillis() - 12 * 3_600_000L)
        if (andere != null) {
            val name = app.db.benutzerDao().alle().first().firstOrNull { it.id == andere.fahrerId }?.name ?: "Jemand"
            val mit = PendingIntent.getBroadcast(
                context, 17, Intent(context, MitfahrenEmpfaenger::class.java).putExtra("fahrt", andere.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            zeigen(context, "$name fährt den ${auto.name}", "Fährst du mit? Dann teilt ihr euch die Kosten.", "Ich fahre mit", mit)
            return
        }
        val starten = PendingIntent.getBroadcast(
            context, 14, Intent(context, FahrtStartenEmpfaenger::class.java).putExtra(TripService.EXTRA_FAHRZEUG, auto.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        zeigen(context, "${auto.name} verbunden. Fährst du?", "Tippe auf \"Ich fahre\", dann zeichnet die App die Fahrt auf.", "Ich fahre", starten)
    }

    private fun zeigen(context: Context, titel: String, text: String, knopf: String, aktion: PendingIntent) {
        val nein = PendingIntent.getBroadcast(context, 15, Intent(context, BluetoothNeinEmpfaenger::class.java), PendingIntent.FLAG_IMMUTABLE)
        val oeffnen = PendingIntent.getActivity(context, 16, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, FahrtenbuchApp.KANAL_HINWEIS)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(titel)
            .setContentText(text)
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
            .setTimeoutAfter(15 * 60_000L)
            .addAction(0, knopf, aktion)
            .addAction(0, "Nein", nein)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(ID, n)
        } catch (_: SecurityException) {
        }
    }

    internal fun weg(context: Context) = context.getSystemService(NotificationManager::class.java)?.cancel(ID)
}

/** ACL_CONNECTED kommt auch bei geschlossener App (Ausnahme von den Hintergrund-Regeln). */
class AutoBluetoothEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val geraet: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val adresse = geraet?.address ?: return
        val fertig = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AutoBluetooth.verbunden(context, adresse)
            } finally {
                fertig.finish()
            }
        }
    }
}

/** Knopf "Ich fahre mit": über den Server-Hook als Mitfahrer eintragen. */
class MitfahrenEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutoBluetooth.weg(context)
        val id = intent.getStringExtra("fahrt") ?: return
        val app = context.applicationContext as FahrtenbuchApp
        val fertig = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val a = app.prefs.anmeldung.value ?: return@launch
                val url = app.sync.adresseFinden(a) ?: return@launch
                val liste = app.api.mitfahren(url, a.token, id, raus = false)
                app.db.fahrtDao().get(id)?.let { app.db.fahrtDao().upsert(it.copy(mitfahrer = liste.joinToString(","))) }
            } catch (_: Exception) {
                // Ohne Netz: später im Fahrt-Detail "Ich bin mitgefahren".
            } finally {
                fertig.finish()
            }
        }
    }
}

class BluetoothNeinEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutoBluetooth.weg(context)
    }
}
