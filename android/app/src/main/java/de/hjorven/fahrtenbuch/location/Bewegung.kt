package de.hjorven.fahrtenbuch.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Bewegungserkennung gegen falsches Fahrtende im Stau (docs/PLAN.md):
 * Solange Android "im Fahrzeug" meldet, endet die Fahrt nicht automatisch, auch wenn das
 * Auto steht. Erst wenn "gehend" oder "still" kommt, läuft der Stillstands-Zähler.
 *
 * Ohne Berechtigung (ACTIVITY_RECOGNITION) oder ohne Google-Dienste bleibt alles wie vorher.
 */
object Bewegung {
    /** Zeitpunkt der letzten sicheren Meldung "im Fahrzeug". */
    @Volatile
    var imFahrzeugZuletzt: Long = 0L
        internal set

    /** Zeitpunkt der letzten sicheren Meldung "zu Fuß" oder "still". */
    @Volatile
    var ausgestiegenZuletzt: Long = 0L
        internal set

    /** Eine Meldung gilt so lange (Android schickt im Stand seltener Updates). */
    const val GUELTIG_MS = 3 * 60_000L
    private const val MIN_SICHERHEIT = 70

    /** Reine Regel, getestet in LogikTest. */
    fun imFahrzeug(jetzt: Long, fahrzeug: Long = imFahrzeugZuletzt, ausgestiegen: Long = ausgestiegenZuletzt): Boolean =
        fahrzeug > 0 && jetzt - fahrzeug <= GUELTIG_MS && fahrzeug >= ausgestiegen

    fun erlaubt(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun intent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 7, Intent(context, BewegungEmpfaenger::class.java), flags)
    }

    @SuppressLint("MissingPermission")
    fun starten(context: Context) {
        zuruecksetzen()
        if (!erlaubt(context)) return
        try {
            ActivityRecognition.getClient(context).requestActivityUpdates(30_000L, intent(context))
        } catch (_: Exception) {
            // Keine Google-Dienste: ohne Stau-Erkennung weiter.
        }
    }

    @SuppressLint("MissingPermission")
    fun stoppen(context: Context) {
        if (!erlaubt(context)) return
        try {
            ActivityRecognition.getClient(context).removeActivityUpdates(intent(context))
        } catch (_: Exception) {
        }
        zuruecksetzen()
    }

    private fun zuruecksetzen() {
        imFahrzeugZuletzt = 0L
        ausgestiegenZuletzt = 0L
    }

    internal fun auswerten(result: ActivityRecognitionResult) {
        val a = result.mostProbableActivity ?: return
        if (a.confidence < MIN_SICHERHEIT) return
        val zeit = result.time
        when (a.type) {
            DetectedActivity.IN_VEHICLE -> imFahrzeugZuletzt = zeit
            DetectedActivity.ON_FOOT, DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.STILL ->
                ausgestiegenZuletzt = zeit
        }
    }
}

class BewegungEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityRecognitionResult.hasResult(intent)) {
            ActivityRecognitionResult.extractResult(intent)?.let { Bewegung.auswerten(it) }
        }
    }
}
