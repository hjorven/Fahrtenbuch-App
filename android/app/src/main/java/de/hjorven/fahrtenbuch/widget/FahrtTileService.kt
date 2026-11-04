package de.hjorven.fahrtenbuch.widget

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import de.hjorven.fahrtenbuch.MainActivity
import de.hjorven.fahrtenbuch.location.FahrtStatus
import de.hjorven.fahrtenbuch.location.TripService
import java.util.Locale

/** Kachel in den Schnelleinstellungen: Fahrt starten/stoppen. */
class FahrtTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val laufend = FahrtStatus.aktiv.value
        tile.state = if (laufend != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Fahrt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = laufend?.let { String.format(Locale.GERMANY, "%.1f km", it.km) } ?: "starten"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val app = application as FahrtenbuchApp
        val erlaubt = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        when {
            FahrtStatus.aktiv.value != null -> TripService.stoppen(this)
            erlaubt && app.prefs.gewaehltesFahrzeug != null ->
                TripService.starten(this)
            else -> appOeffnen()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun appOeffnen() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
