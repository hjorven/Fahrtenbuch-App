package de.hjorven.fahrtenbuch.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.location.Location
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import de.hjorven.fahrtenbuch.MainActivity
import de.hjorven.fahrtenbuch.R
import de.hjorven.fahrtenbuch.data.Fahrt
import de.hjorven.fahrtenbuch.data.Plausibel
import de.hjorven.fahrtenbuch.data.Polyline
import de.hjorven.fahrtenbuch.data.SyncWorker
import de.hjorven.fahrtenbuch.data.neueId
import de.hjorven.fahrtenbuch.widget.widgetsAktualisieren
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Was die Oberfläche, das Widget und die Kachel über die laufende Fahrt wissen müssen. */
data class LaufendeFahrt(
    val fahrtId: String,
    val fahrzeugId: String,
    val fahrzeugName: String,
    val start: Long,
    val km: Double = 0.0,
    val kmh: Double = 0.0,
    val maxKmh: Double = 0.0,
    val stehtSeit: Long? = null,
    val test: Boolean = false,
)

object FahrtStatus {
    internal val _aktiv = MutableStateFlow<LaufendeFahrt?>(null)
    val aktiv: StateFlow<LaufendeFahrt?> = _aktiv
}

/**
 * Foreground Service, der während der Fahrt GPS aufzeichnet.
 * Start/Stopp über App, Widget, Schnelleinstellung oder die Benachrichtigung.
 */
class TripService : LifecycleService() {

    private val app get() = application as FahrtenbuchApp
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var fahrt: Fahrt? = null
    private var rechner = Streckenrechner()
    private var stillstand = StillstandErkennung(10 * 60_000L)
    private var vorherigeRoute: List<Polyline.Punkt> = emptyList()
    private var meterVorher = 0.0
    private var letzterPunkt: GpsPunkt? = null
    private var ticker: Job? = null
    private var startJob: Job? = null
    private var beendet = false
    /** Nur bei Test-Fahrten. */
    private var diagnose: Diagnose? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { neuerStandort(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Muss innerhalb weniger Sekunden passieren, sonst beendet Android den Dienst.
        vordergrund("Fahrt wird gestartet ...")

        when (intent?.action) {
            AKTION_VERWERFEN -> {
                // "Nein" bei einer vorläufigen Fahrt (Roadmap Nr. 6.3): beenden und löschen.
                lifecycleScope.launch {
                    startJob?.join()
                    val id = fahrt?.id
                    beenden(System.currentTimeMillis(), "verworfen")
                    id?.let { app.db.fahrtDao().get(it) }?.let { app.db.fahrtDao().upsert(it.copy(geloescht = true, dirty = true)) }
                    SyncWorker.jetzt(this@TripService)
                }
            }
            AKTION_STOPP -> {
                val jetzt = System.currentTimeMillis()
                // Ein Start, der noch läuft, muss erst fertig sein, sonst zeichnet er nach dem Stopp weiter auf.
                lifecycleScope.launch {
                    startJob?.join()
                    beenden(jetzt, "manuell")
                }
            }
            // Doppelte Starts (Widget, Kachel und App gleichzeitig) dürfen keine zweite Fahrt anlegen.
            else -> if (fahrt == null && startJob?.isActive != true) {
                startJob = lifecycleScope.launch {
                    starten(intent?.getStringExtra(EXTRA_FAHRZEUG), intent?.getBooleanExtra(EXTRA_VORLAEUFIG, false) == true)
                }
            }
        }
        return START_STICKY
    }

    private fun hatStandort() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    private suspend fun starten(fahrzeugWunsch: String?, vorlaeufig: Boolean = false) {
        val userId = app.prefs.userId
        if (!hatStandort()) {
            // Standort-Recht weg (z. B. während der Fahrt entzogen): eine laufende Fahrt nicht für immer
            // offen und unsichtbar lassen, sondern zum letzten gespeicherten Stand beenden.
            val laufende = app.db.fahrtDao().laufende(userId)
            if (laufende == null) {
                stoppenOhneFahrt()
            } else {
                val stand = app.prefs.zwischenstandUm
                beenden(if (stand > laufende.start) stand else System.currentTimeMillis(), "kein_standort")
            }
            return
        }
        stillstand = StillstandErkennung(app.prefs.autoStopMinuten * 60_000L)

        // Nach einem Neustart des Dienstes die laufende Fahrt fortsetzen.
        val laufende = app.db.fahrtDao().laufende(userId)
        val f = if (laufende != null) {
            vorherigeRoute = Polyline.decode(laufende.route)
            meterVorher = laufende.streckeKm * 1000
            if (laufende.test) {
                diagnose = (Diagnose.ausJson(laufende.diagnose) ?: Diagnose.start(this, app.prefs.testAufnahmeMeter))
                    .also { it.neustarts++ }
            }
            laufende
        } else {
            val fahrzeugId = fahrzeugWunsch ?: app.prefs.gewaehltesFahrzeug
            val fahrzeug = fahrzeugId?.let { app.db.fahrzeugDao().get(it) }
            if (fahrzeug == null) {
                stoppenOhneFahrt()
                return
            }
            val test = app.prefs.testModus
            if (test) diagnose = Diagnose.start(this, app.prefs.testAufnahmeMeter)
            Fahrt(
                id = neueId(),
                fahrzeugId = fahrzeug.id,
                fahrerId = userId,
                start = System.currentTimeMillis(),
                kmStart = fahrzeug.kmStand,
                test = test,
                diagnose = diagnose?.alsJson() ?: "",
                vorlaeufig = vorlaeufig,
            ).also {
                app.db.fahrtDao().upsert(it)
                SyncWorker.jetzt(this)
            }
        }
        fahrt = f
        val name = app.db.fahrzeugDao().get(f.fahrzeugId)?.name ?: ""
        FahrtStatus._aktiv.value = LaufendeFahrt(f.id, f.fahrzeugId, name, f.start, km = meterVorher / 1000, test = f.test)
        standortStarten(diagnose?.aufnahmeMeter ?: 0)
        Bewegung.starten(this)
        tickerStarten()
        widgetsAktualisieren(this)
    }

    @SuppressLint("MissingPermission")
    private fun standortStarten(abstandMeter: Int) {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateIntervalMillis(1_000L)
            // Test-Modus: Aufnahme-Rate in Metern, um den Akkuverbrauch zu vergleichen.
            .apply { if (abstandMeter > 0) setMinUpdateDistanceMeters(abstandMeter.toFloat()) }
            .build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    private fun neuerStandort(loc: Location) {
        val f = fahrt ?: return
        if (beendet) return
        val vorher = letzterPunkt
        // Manche Geräte liefern keine Geschwindigkeit, dann aus zwei Punkten berechnen.
        val speed = when {
            loc.hasSpeed() -> loc.speed.toDouble()
            vorher != null && loc.time > vorher.zeit ->
                distanzMeter(vorher, GpsPunkt(loc.time, loc.latitude, loc.longitude, 0.0)) / ((loc.time - vorher.zeit) / 1000.0)
            else -> 0.0
        }
        val p = GpsPunkt(loc.time, loc.latitude, loc.longitude, speed, loc.accuracy)
        // Kam lange kein Punkt (Gerät hat die App gedrosselt, kein Empfang), ist die Stillstandszeit
        // vielleicht schon vorbei: Fahrt beim Halt beenden und diesen Punkt nicht mehr zählen.
        val sollEnden = stillstand.sollEnden(p.zeit, Bewegung.imFahrzeug(p.zeit))
        if (sollEnden || stillstand.lueckeIstHalt(vorher, p)) {
            val ende = stillstand.stehtSeit ?: vorher?.zeit ?: p.zeit
            lifecycleScope.launch { beenden(ende, if (sollEnden) "stillstand" else "luecke") }
            return
        }
        letzterPunkt = p
        val gezaehlt = rechner.hinzufuegen(p)
        diagnose?.punkt(p, gezaehlt)
        stillstand.punkt(p)

        val status = FahrtStatus._aktiv.value ?: return
        FahrtStatus._aktiv.value = status.copy(
            km = (meterVorher + rechner.meter) / 1000,
            kmh = speed * 3.6,
            maxKmh = rechner.maxMps * 3.6,
            stehtSeit = stillstand.stehtSeit,
        )
    }

    private fun tickerStarten() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            var runde = 0
            while (isActive) {
                delay(15_000L)
                runde++
                val jetzt = System.currentTimeMillis()
                val sollEnden = stillstand.sollEnden(jetzt, Bewegung.imFahrzeug(jetzt))
                if (sollEnden || stillstand.keinSignal(letzterPunkt, jetzt)) {
                    // Eigene Coroutine: beenden() bricht den Ticker ab und darf sich dabei nicht selbst abbrechen.
                    val ende = stillstand.stehtSeit ?: letzterPunkt?.zeit ?: jetzt
                    lifecycleScope.launch { beenden(ende, if (sollEnden) "stillstand" else "kein_signal") }
                    break
                }
                FahrtStatus._aktiv.value?.let { vordergrund(text(it)) }
                if (runde % 4 == 0) {
                    zwischenspeichern()
                    widgetsAktualisieren(this@TripService)
                }
                // Alle 2 Minuten hochladen, damit die anderen (und das Display) die Strecke unterwegs sehen
                // und bei einem Absturz nicht alles seit dem Start fehlt. Der Worker wartet selbst auf Netz;
                // SyncManager.hochladen markiert die Fahrt nur sauber, wenn sie sich nicht inzwischen geändert hat.
                if (runde % 8 == 0 && app.prefs.anmeldung.value != null) SyncWorker.unterwegs(this@TripService)
            }
        }
    }

    private fun route(): String =
        Polyline.encode(vorherigeRoute + rechner.punkte.map { Polyline.Punkt(it.lat, it.lon) })

    private suspend fun zwischenspeichern() {
        val f = fahrt ?: return
        val aktuell = app.db.fahrtDao().get(f.id) ?: f
        app.db.fahrtDao().upsert(
            aktuell.copy(
                streckeKm = (meterVorher + rechner.meter) / 1000,
                maxKmh = maxOf(aktuell.maxKmh, rechner.maxMps * 3.6),
                route = route(),
                diagnose = diagnose?.alsJson() ?: aktuell.diagnose,
                dirty = true,
            )
        )
        app.prefs.zwischenstandUm = System.currentTimeMillis()
    }

    private suspend fun beenden(ende: Long, grund: String) {
        if (beendet) return
        val f = fahrt
        if (f == null) {
            // Stopp ohne bekannte Fahrt (z. B. Dienst war tot): laufende aus der DB holen und abschließen.
            val laufende = app.db.fahrtDao().laufende(app.prefs.userId)
            if (laufende == null) {
                stoppenOhneFahrt()
                return
            }
            fahrt = laufende
            vorherigeRoute = Polyline.decode(laufende.route)
            meterVorher = laufende.streckeKm * 1000
            if (laufende.test) diagnose = Diagnose.ausJson(laufende.diagnose) ?: Diagnose()
            return beenden(ende, grund)
        }
        beendet = true
        fused.removeLocationUpdates(callback)
        Bewegung.stoppen(this)
        ticker?.cancel()
        try {
            // Speichern darf nicht mittendrin abgebrochen werden, sonst bleibt die Fahrt offen.
            withContext(NonCancellable) { abschliessen(f, ende, grund) }
        } catch (e: Exception) {
            // Fehlgeschlagen: Stopp-Knopf muss es erneut versuchen können.
            beendet = false
            throw e
        }
    }

    private suspend fun abschliessen(f: Fahrt, ende: Long, grund: String) {
        rechner.abschneidenNach(ende)
        val strecke = (meterVorher + rechner.meter) / 1000
        val erster = vorherigeRoute.firstOrNull()?.let { GpsPunkt(0, it.lat, it.lon, 0.0) } ?: rechner.punkte.firstOrNull()
        val letzter = rechner.punkte.lastOrNull()

        val aktuell = app.db.fahrtDao().get(f.id) ?: f
        val diagnoseText = diagnose?.let { d ->
            d.ende = grund
            Diagnose.akku(this)?.let { (stand, laedt) -> d.akkuEnde = stand; d.laedtEnde = laedt }
            d.offenZumSync = app.db.fahrtDao().anzahlDirty() + app.db.tankDao().anzahlDirty()
            d.adresse = app.sync.aktiveAdresse.value ?: ""
            val letzter = app.prefs.letzterSync
            d.letzterSyncVorMin = if (letzter > 0) (System.currentTimeMillis() - letzter) / 60_000 else -1
            d.alsJson()
        } ?: aktuell.diagnose
        // Fehlstart (Widget aus Versehen, nur aus der Einfahrt gerollt): nicht als Fahrt behalten.
        // Test-Fahrten bleiben, auch kurze sind für die Diagnose nützlich.
        // Vorläufig (automatisch gestartet) und kürzer als 3 Minuten: kein Auto gefahren, verwerfen.
        val zuKurzVorlaeufig = aktuell.vorlaeufig && ende - aktuell.start < VORLAEUFIG_MIN_MS
        if ((strecke < Plausibel.MIN_FAHRT_KM || zuKurzVorlaeufig) && !aktuell.test) {
            app.db.fahrtDao().upsert(aktuell.copy(ende = maxOf(ende, aktuell.start), streckeKm = strecke, geloescht = true, dirty = true))
            FahrtStatus._aktiv.value = null
            widgetsAktualisieren(this)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            SyncWorker.jetzt(this)
            stopSelf()
            return
        }
        val fertig = aktuell.copy(
            ende = ende,
            streckeKm = strecke,
            kmEnde = aktuell.kmStart + strecke,
            maxKmh = maxOf(aktuell.maxKmh, rechner.maxMps * 3.6),
            schnittKmh = if (ende > aktuell.start) strecke / ((ende - aktuell.start) / 3_600_000.0) else 0.0,
            route = route(),
            diagnose = diagnoseText,
            dirty = true,
        )
        // Erst abschließen und speichern, dann Ortsnamen holen: Wird der Dienst dabei beendet,
        // ist die Fahrt trotzdem fertig.
        app.db.fahrtDao().upsert(fertig)
        if (fertig.vorlaeufig) Vorlaeufig.fragenAmEnde(this, fertig)
        // Fahrzeug-ID aus der Datenbank: beim ersten Abgleich kann das lokale Auto auf das Server-Auto umgezogen sein.
        // Test-Fahrten heben den km-Stand nicht an.
        if (!fertig.test) app.db.fahrzeugDao().get(fertig.fahrzeugId)?.let { fz ->
            if (fertig.kmEnde > fz.kmStand) app.db.fahrzeugDao().upsert(fz.copy(kmStand = fertig.kmEnde, dirty = true))
        }
        FahrtStatus._aktiv.value = null
        widgetsAktualisieren(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        // Geocoder kann ohne Netz lange hängen: höchstens 8 Sekunden warten, sonst ohne Ortsnamen.
        // Eigener Job, damit ein hängender Aufruf nicht blockiert.
        val orte = lifecycleScope.async(Dispatchers.IO) { ort(erster) to ort(letzter) }
        val (startOrt, zielOrt) = withTimeoutOrNull(8_000L) { orte.await() } ?: ("" to "")
        if (startOrt.isNotBlank() || zielOrt.isNotBlank()) {
            app.db.fahrtDao().get(f.id)?.let { neu ->
                app.db.fahrtDao().upsert(
                    neu.copy(
                        startOrt = neu.startOrt.ifBlank { startOrt },
                        zielOrt = neu.zielOrt.ifBlank { zielOrt },
                        dirty = true,
                    )
                )
            }
        }
        SyncWorker.jetzt(this)
        stopSelf()
    }

    private fun stoppenOhneFahrt() {
        FahrtStatus._aktiv.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun ort(p: GpsPunkt?): String {
        if (p == null || !Geocoder.isPresent()) return ""
        return try {
            val a = Geocoder(this, Locale.GERMANY).getFromLocation(p.lat, p.lon, 1)?.firstOrNull() ?: return ""
            listOfNotNull(a.thoroughfare, a.locality).joinToString(", ")
        } catch (_: Exception) {
            ""
        }
    }

    private fun text(s: LaufendeFahrt): String {
        val min = (System.currentTimeMillis() - s.start) / 60_000
        return String.format(Locale.GERMANY, "%s · %.1f km · %d min · %.0f km/h", s.fahrzeugName, s.km, min, s.kmh)
    }

    private fun vordergrund(text: String) {
        val oeffnen = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stopp = PendingIntent.getService(
            this, 1, Intent(this, TripService::class.java).setAction(AKTION_STOPP), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, FahrtenbuchApp.KANAL_FAHRT)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(if (diagnose != null) "Test-Fahrt läuft" else "Fahrt läuft")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(oeffnen)
            .addAction(0, "Fahrt beenden", stopp)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Ohne Standortberechtigung darf kein Standort-Dienst laufen.
            stopSelf()
        }
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        Bewegung.stoppen(this)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        const val AKTION_STOPP = "stopp"
        const val AKTION_VERWERFEN = "verwerfen"
        const val EXTRA_FAHRZEUG = "fahrzeug"
        const val EXTRA_VORLAEUFIG = "vorlaeufig"
        const val VORLAEUFIG_MIN_MS = 3 * 60_000L

        fun starten(context: Context, fahrzeugId: String? = null, vorlaeufig: Boolean = false) {
            val i = Intent(context, TripService::class.java).putExtra(EXTRA_FAHRZEUG, fahrzeugId).putExtra(EXTRA_VORLAEUFIG, vorlaeufig)
            ContextCompat.startForegroundService(context, i)
        }

        fun stoppen(context: Context) {
            val i = Intent(context, TripService::class.java).setAction(AKTION_STOPP)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
