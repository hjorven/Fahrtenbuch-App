package de.hjorven.fahrtenbuch.location

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import de.hjorven.fahrtenbuch.MainActivity
import de.hjorven.fahrtenbuch.R
import de.hjorven.fahrtenbuch.data.Fahrt
import de.hjorven.fahrtenbuch.data.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Vorläufige Fahrten (Roadmap Nr. 6.3), nur wenn in den Einstellungen eingeschaltet (aus ab Werk):
 * Meldet Android "ins Auto eingestiegen" und läuft keine Fahrt, zeichnet die App vorläufig auf und
 * fragt sofort "Fährst du?" (**Ich fahre** bestätigt, **Nein** verwirft). Ohne Antwort zählt die
 * Fahrt erst nach Bestätigung; am Ende kommt "War das deine Fahrt?". Unter 3 Minuten wird verworfen.
 */
object Vorlaeufig {
    private const val ID_LAEUFT = 74
    private const val ID_ENDE = 75

    fun starten(context: Context) {
        TripService.starten(context.applicationContext, vorlaeufig = true)
        val ja = PendingIntent.getBroadcast(context, 18, Intent(context, VorlaeufigJaEmpfaenger::class.java), PendingIntent.FLAG_IMMUTABLE)
        val nein = PendingIntent.getBroadcast(context, 19, Intent(context, VorlaeufigNeinEmpfaenger::class.java), PendingIntent.FLAG_IMMUTABLE)
        zeigen(context, ID_LAEUFT, "Fährst du?", "Die App zeichnet vorläufig auf. Sie zählt erst, wenn du bestätigst.",
            listOf("Ich fahre" to ja, "Nein" to nein))
    }

    internal fun fragenAmEnde(context: Context, f: Fahrt) {
        weg(context, ID_LAEUFT)
        val ja = PendingIntent.getBroadcast(context, 20, Intent(context, VorlaeufigJaEmpfaenger::class.java).putExtra("fahrt", f.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val loeschen = PendingIntent.getBroadcast(context, 21, Intent(context, VorlaeufigLoeschenEmpfaenger::class.java).putExtra("fahrt", f.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val km = String.format(java.util.Locale.GERMANY, "%.1f km", f.streckeKm)
        zeigen(context, ID_ENDE, "War das deine Fahrt?", "$km automatisch aufgezeichnet. Ist jemand anderes gefahren: in der App \"Fahrer ändern\".",
            listOf("Ja" to ja, "Löschen" to loeschen))
    }

    private fun zeigen(context: Context, id: Int, titel: String, text: String, knoepfe: List<Pair<String, PendingIntent>>) {
        val oeffnen = PendingIntent.getActivity(context, 22, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(context, FahrtenbuchApp.KANAL_HINWEIS)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(titel)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
        knoepfe.forEach { (t, a) -> b.addAction(0, t, a) }
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(id, b.build())
        } catch (_: SecurityException) {
        }
    }

    internal fun weg(context: Context, id: Int) = context.getSystemService(NotificationManager::class.java)?.cancel(id)
    internal fun allesWeg(context: Context) { weg(context, ID_LAEUFT); weg(context, ID_ENDE) }

    /** Bestätigen: laufende (ohne ID) oder beendete Fahrt zählt ab jetzt. */
    internal suspend fun bestaetigen(context: Context, id: String?) {
        val app = context.applicationContext as FahrtenbuchApp
        val f = (id ?: FahrtStatus.aktiv.value?.fahrtId)?.let { app.db.fahrtDao().get(it) } ?: return
        if (f.vorlaeufig) app.db.fahrtDao().upsert(f.copy(vorlaeufig = false, dirty = true))
        SyncWorker.jetzt(app)
    }
}

private fun BroadcastReceiver.imHintergrund(block: suspend () -> Unit) {
    val fertig = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try { block() } finally { fertig.finish() }
    }
}

class VorlaeufigJaEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Vorlaeufig.allesWeg(context)
        imHintergrund { Vorlaeufig.bestaetigen(context, intent.getStringExtra("fahrt")) }
    }
}

class VorlaeufigNeinEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Vorlaeufig.allesWeg(context)
        if (FahrtStatus.aktiv.value == null) return
        ContextCompat.startForegroundService(context, Intent(context, TripService::class.java).setAction(TripService.AKTION_VERWERFEN))
    }
}

class VorlaeufigLoeschenEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Vorlaeufig.allesWeg(context)
        val id = intent.getStringExtra("fahrt") ?: return
        val app = context.applicationContext as FahrtenbuchApp
        imHintergrund {
            app.db.fahrtDao().get(id)?.takeIf { it.vorlaeufig }?.let { app.db.fahrtDao().upsert(it.copy(geloescht = true, dirty = true)) }
            SyncWorker.jetzt(app)
        }
    }
}
