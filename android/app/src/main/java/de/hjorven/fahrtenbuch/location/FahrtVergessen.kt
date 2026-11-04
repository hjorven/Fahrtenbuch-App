package de.hjorven.fahrtenbuch.location

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import de.hjorven.fahrtenbuch.MainActivity
import de.hjorven.fahrtenbuch.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Fahrt vergessen?" (Roadmap Nr. 7): Meldet Android "ins Auto eingestiegen" und es läuft keine
 * Fahrt, fragt eine Benachrichtigung "Fährst du?" mit **Fahrt starten** (nie still starten, man
 * kann Beifahrer sein, Roadmap Nr. 6). Beim Aussteigen nach mehr als 5 Minuten ohne Fahrt:
 * "Fahrt vergessen?" mit Hinweis aufs Nachtragen. Ohne Google-Dienste oder ohne Recht: nichts.
 */
object FahrtVergessen {
    private const val ID_FRAGE = 71
    private const val MIN_DAUER_MS = 5 * 60_000L

    private fun intent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 8, Intent(context, FahrtVergessenEmpfaenger::class.java), flags)
    }

    @SuppressLint("MissingPermission")
    fun registrieren(context: Context) {
        if (!Bewegung.erlaubt(context)) return
        val uebergaenge = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
            ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(it).build()
        }
        try {
            ActivityRecognition.getClient(context).requestActivityTransitionUpdates(ActivityTransitionRequest(uebergaenge), intent(context))
        } catch (_: Exception) {
            // Keine Google-Dienste: ohne Hinweis weiter.
        }
    }

    internal fun eingestiegen(context: Context, zeit: Long) {
        val app = context.applicationContext as FahrtenbuchApp
        app.prefs.eingestiegenUm = zeit
        if (FahrtStatus.aktiv.value != null) return
        // Roadmap Nr. 6.3 (nur wenn eingeschaltet): gleich vorläufig aufzeichnen und dabei fragen.
        if (app.prefs.autoVorlaeufig && app.prefs.gewaehltesFahrzeug != null) {
            Vorlaeufig.starten(context)
            return
        }
        val starten = PendingIntent.getBroadcast(
            context, 9, Intent(context, FahrtStartenEmpfaenger::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        zeigen(context, "Fährst du?", "Android meint, du sitzt im Auto. Fahrt jetzt aufzeichnen?", "Fahrt starten", starten)
    }

    internal suspend fun ausgestiegen(context: Context, zeit: Long) {
        val app = context.applicationContext as FahrtenbuchApp
        val seit = app.prefs.eingestiegenUm
        app.prefs.eingestiegenUm = 0L
        context.getSystemService(NotificationManager::class.java)?.cancel(ID_FRAGE)
        // Ausgestiegen: eine vorläufige Fahrt endet hier (Stillstand würde sonst erst nach Minuten greifen).
        FahrtStatus.aktiv.value?.let { l ->
            if (app.db.fahrtDao().get(l.fahrtId)?.vorlaeufig == true) TripService.stoppen(context.applicationContext)
            return
        }
        if (seit <= 0 || zeit - seit < MIN_DAUER_MS || FahrtStatus.aktiv.value != null) return
        // Gab es in der Zeit eine eigene Fahrt? Dann nichts vergessen.
        val fahrten = app.db.fahrtDao().zwischen(app.prefs.userId, seit - 10 * 60_000L, zeit)
        if (fahrten > 0) return
        val min = ((zeit - seit) / 60_000L).toInt()
        val uhr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY)
        zeigen(
            context, "Fahrt vergessen?",
            "Etwa $min Minuten im Auto (${uhr.format(java.util.Date(seit))}–${uhr.format(java.util.Date(zeit))}). " +
                "Unter Fahrten → Nachtragen eintragen.", null, null,
        )
    }

    private fun zeigen(context: Context, titel: String, text: String, knopf: String?, aktion: PendingIntent?) {
        val oeffnen = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, FahrtenbuchApp.KANAL_HINWEIS)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(titel)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
            .apply { if (knopf != null && aktion != null) addAction(0, knopf, aktion) }
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(ID_FRAGE, n)
        } catch (_: SecurityException) {
        }
    }

    internal fun frageWeg(context: Context) = context.getSystemService(NotificationManager::class.java)?.cancel(ID_FRAGE)
}

class FahrtVergessenEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val r = if (ActivityTransitionResult.hasResult(intent)) ActivityTransitionResult.extractResult(intent) else null
        val e = r?.transitionEvents?.lastOrNull() ?: return
        if (e.activityType != DetectedActivity.IN_VEHICLE) return
        val zeit = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtimeNanos() - e.elapsedRealTimeNanos) / 1_000_000L
        val fertig = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) FahrtVergessen.eingestiegen(context, zeit)
                else FahrtVergessen.ausgestiegen(context, zeit)
            } finally {
                fertig.finish()
            }
        }
    }
}

/** Knopf "Fahrt starten" in der Benachrichtigung. */
class FahrtStartenEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FahrtVergessen.frageWeg(context)
        AutoBluetooth.weg(context)
        // Läuft schon eine vorläufige Fahrt (Roadmap Nr. 6.3), bestätigt "Ich fahre" sie nur.
        if (FahrtStatus.aktiv.value != null) {
            Vorlaeufig.allesWeg(context)
            val fertig = goAsync()
            CoroutineScope(Dispatchers.IO).launch { try { Vorlaeufig.bestaetigen(context, null) } finally { fertig.finish() } }
            return
        }
        TripService.starten(context.applicationContext, intent.getStringExtra(TripService.EXTRA_FAHRZEUG))
    }
}
