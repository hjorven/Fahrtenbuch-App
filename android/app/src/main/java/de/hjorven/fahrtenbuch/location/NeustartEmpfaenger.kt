package de.hjorven.fahrtenbuch.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Nach einem App-Update oder Handy-Neustart eine laufende Fahrt sofort weiter aufzeichnen.
 * Vorher lief die Aufzeichnung erst wieder, wenn jemand die App öffnete (Nachtplan 5b Fall 7/12).
 */
class NeustartEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED && intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as FahrtenbuchApp
        FahrtVergessen.registrieren(app)  // Übergänge gelten nur bis zum Neustart
        val fertig = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (app.db.fahrtDao().laufende(app.prefs.userId) != null) TripService.starten(app)
            } catch (e: Exception) {
                // z. B. ForegroundServiceStartNotAllowedException: dann setzt die App beim Öffnen fort.
                Log.w("Neustart", "Laufende Fahrt nicht fortgesetzt", e)
            } finally {
                fertig.finish()
            }
        }
    }
}
