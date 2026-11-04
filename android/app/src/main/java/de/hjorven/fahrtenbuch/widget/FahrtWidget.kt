package de.hjorven.fahrtenbuch.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import de.hjorven.fahrtenbuch.MainActivity
import de.hjorven.fahrtenbuch.data.Statistik
import kotlinx.coroutines.flow.first
import de.hjorven.fahrtenbuch.location.FahrtStatus
import de.hjorven.fahrtenbuch.location.LaufendeFahrt
import de.hjorven.fahrtenbuch.location.TripService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/** Homescreen-Widget: zeigt die laufende Fahrt und startet/stoppt sie mit einem Tipp. */
class FahrtWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as FahrtenbuchApp
        val laufend = FahrtStatus.aktiv.value
        val fahrzeug = app.prefs.gewaehltesFahrzeug?.let { app.db.fahrzeugDao().get(it) }
        val monat = try { monatsZeile(app) } catch (_: Exception) { null }
        provideContent {
            GlanceTheme {
                Inhalt(context, laufend, fahrzeug?.name, monat)
            }
        }
    }

    /** Roadmap Nr. 24: "November: 312 km · du bekommst 12,30 €" für den laufenden Monat. */
    private suspend fun monatsZeile(app: FahrtenbuchApp): String? {
        val ich = app.prefs.userId
        val jetzt = System.currentTimeMillis()
        val c = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val von = c.timeInMillis
        val fahrten = app.db.fahrtDao().alle().first().filter { !it.test }
        val tank = app.db.tankDao().alle().first().filter { !it.test }
        val autos = app.db.fahrzeugDao().alle().first()
        val kpk = autos.mapNotNull { fz -> Statistik.kostenProKm(fz, tank.filter { it.fahrzeugId == fz.id })?.let { fz.id to it } }.toMap()
        val zeitraum = Statistik.zeitraum(von)
        val ausgl = app.db.ausgleichDao().alle().first().filter { it.zeitraum == zeitraum }
        val s = Statistik.abrechnung(fahrten, tank, kpk, von, jetzt + 1, ausgl).firstOrNull { it.fahrerId == ich } ?: return null
        val name = c.getDisplayName(java.util.Calendar.MONTH, java.util.Calendar.LONG, Locale.GERMANY)
        val geld = when {
            s.saldo > 0.005 -> String.format(Locale.GERMANY, " · du bekommst %.2f €", s.saldo)
            s.saldo < -0.005 -> String.format(Locale.GERMANY, " · du zahlst %.2f €", -s.saldo)
            else -> ""
        }
        return String.format(Locale.GERMANY, "%s: %.0f km", name, s.km) + geld
    }

    @Composable
    private fun Inhalt(context: Context, laufend: LaufendeFahrt?, fahrzeugName: String?, monat: String?) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val titel = when {
                laufend != null -> String.format(Locale.GERMANY, "%s · %.1f km", laufend.fahrzeugName, laufend.km)
                fahrzeugName != null -> fahrzeugName
                else -> "Kein Auto gewählt"
            }
            Text(
                titel,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp),
                maxLines = 1,
            )
            if (laufend == null && monat != null) {
                Text(monat, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
            }
            Spacer(GlanceModifier.height(8.dp))
            if (laufend != null) {
                Button(
                    text = "Fahrt beenden",
                    onClick = actionStartService(
                        Intent(context, TripService::class.java).setAction(TripService.AKTION_STOPP),
                        isForegroundService = true,
                    ),
                )
            } else if (fahrzeugName != null) {
                Button(
                    text = "Fahrt starten",
                    onClick = actionStartService(Intent(context, TripService::class.java), isForegroundService = true),
                )
            } else {
                Button(text = "App öffnen", onClick = actionStartActivity<MainActivity>())
            }
        }
    }
}

class FahrtWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FahrtWidget()
}

private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Widget und Schnelleinstellungs-Kachel neu zeichnen. */
fun widgetsAktualisieren(context: Context) {
    val c = context.applicationContext
    widgetScope.launch {
        try {
            FahrtWidget().updateAll(c)
        } catch (_: Exception) {
        }
    }
    TileService.requestListeningState(c, ComponentName(c, FahrtTileService::class.java))
}
