package de.hjorven.fahrtenbuch.data

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import de.hjorven.fahrtenbuch.MainActivity
import de.hjorven.fahrtenbuch.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * Monatsabrechnung als Nachricht (Roadmap Nr. 10): In den ersten Tagen eines Monats kommt einmal
 * "Abrechnung Oktober: Du zahlst Ben 23,40 €" mit dem Knopf **Bezahlt**. Der trägt den Ausgleich
 * ein (wie in der Statistik), alle sehen ihn nach dem nächsten Abgleich. Wer Geld bekommt, sieht
 * nur den Hinweis. Geprüft wird nach jedem Abgleich im Hintergrund (SyncWorker).
 */
object Monatsabrechnung {
    private const val ID = 72
    private const val BIS_TAG = 10
    private val MONATE = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

    data class Nachricht(val titel: String, val text: String, val bezahlen: Statistik.Ueberweisung?)

    /** Reine Regel (getestet): Text für mich aus den Überweisungen des Monats, null wenn nichts zu tun. */
    fun nachricht(ue: List<Statistik.Ueberweisung>, ich: String, namen: Map<String, String>, monat: Int): Nachricht? {
        val zahlen = ue.filter { it.von == ich }
        val bekommen = ue.filter { it.an == ich }
        if (zahlen.isEmpty() && bekommen.isEmpty()) return null
        fun n(id: String) = namen[id]?.takeIf { it.isNotBlank() } ?: "jemand"
        fun e(v: Double) = String.format(Locale.GERMANY, "%.2f €", v)
        val titel = "Abrechnung ${MONATE[monat]}"
        val teile = zahlen.map { "Du zahlst ${n(it.an)} ${e(it.betrag)}" } + bekommen.map { "${n(it.von)} zahlt dir ${e(it.betrag)}" }
        // "Bezahlt" nur bei genau einer eigenen Zahlung, sonst in der Statistik einzeln abhaken.
        return Nachricht(titel, teile.joinToString(". ") + ".", zahlen.singleOrNull())
    }

    /** Zeitraum ("2026-10") und Grenzen des Vormonats von [jetzt]. */
    fun vormonat(jetzt: Long): Triple<String, Long, Long> {
        val c = Calendar.getInstance().apply {
            timeInMillis = jetzt
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val bis = c.timeInMillis
        c.add(Calendar.MONTH, -1)
        return Triple(Statistik.zeitraum(c.timeInMillis), c.timeInMillis, bis)
    }

    suspend fun pruefen(context: Context, jetzt: Long = System.currentTimeMillis()) {
        val app = context.applicationContext as FahrtenbuchApp
        val a = app.prefs.anmeldung.value ?: return
        if (Calendar.getInstance().apply { timeInMillis = jetzt }.get(Calendar.DAY_OF_MONTH) > BIS_TAG) return
        val (zeitraum, von, bis) = vormonat(jetzt)
        if (app.prefs.abrechnungGemeldet == zeitraum) return
        val fahrten = app.db.fahrtDao().alle().first().filter { !it.test }
        val tank = app.db.tankDao().alle().first().filter { !it.test }
        val autos = app.db.fahrzeugDao().alle().first()
        val kosten = autos.mapNotNull { fz -> Statistik.kostenProKm(fz, tank.filter { it.fahrzeugId == fz.id })?.let { fz.id to it } }.toMap()
        val ausgleiche = app.db.ausgleichDao().alle().first().filter { it.zeitraum == zeitraum }
        val ue = Statistik.ueberweisungen(Statistik.abrechnung(fahrten, tank, kosten, von, bis, ausgleiche))
        val namen = app.db.benutzerDao().alle().first().associate { it.id to it.name }
        app.prefs.abrechnungGemeldet = zeitraum
        val monat = Calendar.getInstance().apply { timeInMillis = von }.get(Calendar.MONTH)
        val n = nachricht(ue, a.userId, namen, monat) ?: return
        zeigen(context, n, zeitraum)
    }

    private fun zeigen(context: Context, n: Nachricht, zeitraum: String) {
        val oeffnen = PendingIntent.getActivity(context, 12, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(context, FahrtenbuchApp.KANAL_HINWEIS)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(n.titel)
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
        n.bezahlen?.let { u ->
            val i = Intent(context, BezahltEmpfaenger::class.java)
                .putExtra("von", u.von).putExtra("an", u.an).putExtra("betrag", u.betrag).putExtra("zeitraum", zeitraum)
            b.addAction(0, "Bezahlt", PendingIntent.getBroadcast(context, 13, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(ID, b.build())
        } catch (_: SecurityException) {
        }
    }

    internal fun weg(context: Context) = context.getSystemService(NotificationManager::class.java)?.cancel(ID)
}

/** Knopf "Bezahlt": Ausgleich eintragen und hochladen. */
class BezahltEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val von = intent.getStringExtra("von") ?: return
        val an = intent.getStringExtra("an") ?: return
        val betrag = intent.getDoubleExtra("betrag", 0.0).takeIf { it > 0 } ?: return
        val zeitraum = intent.getStringExtra("zeitraum") ?: return
        Monatsabrechnung.weg(context)
        val app = context.applicationContext as FahrtenbuchApp
        val fertig = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.db.ausgleichDao().upsert(Ausgleich(neueId(), von, an, betrag, zeitraum, System.currentTimeMillis()))
                SyncWorker.jetzt(app)
            } finally {
                fertig.finish()
            }
        }
    }
}
