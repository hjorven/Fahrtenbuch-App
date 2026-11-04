package de.hjorven.fahrtenbuch

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import de.hjorven.fahrtenbuch.data.AppDatabase
import de.hjorven.fahrtenbuch.data.Belege
import de.hjorven.fahrtenbuch.data.Fahrzeug
import de.hjorven.fahrtenbuch.data.neueId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import de.hjorven.fahrtenbuch.data.PocketBaseApi
import de.hjorven.fahrtenbuch.data.Prefs
import de.hjorven.fahrtenbuch.data.SyncManager
import de.hjorven.fahrtenbuch.data.SyncWorker
import org.osmdroid.config.Configuration

class FahrtenbuchApp : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var api: PocketBaseApi
        private set
    lateinit var sync: SyncManager
        private set
    lateinit var belege: Belege
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.erstellen(this)
        prefs = Prefs(this)
        api = PocketBaseApi()
        belege = Belege(this)
        sync = SyncManager(db, prefs, api, belege)
        sync.appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        sync.geraet = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}".take(80)

        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = cacheDir.resolve("osmdroid")

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(KANAL_FAHRT, getString(R.string.channel_fahrt), NotificationManager.IMPORTANCE_LOW)
        )
        // Hinweise wie "Fährst du?" / "Fahrt vergessen?" (Roadmap Nr. 7), eigener Kanal zum Abschalten
        nm.createNotificationChannel(
            NotificationChannel(KANAL_HINWEIS, "Hinweise (Fahrt vergessen?, Abrechnung)", NotificationManager.IMPORTANCE_DEFAULT)
        )
        de.hjorven.fahrtenbuch.location.FahrtVergessen.registrieren(this)

        if (prefs.anmeldung.value != null) SyncWorker.planen(this)

        // Lokaler Modus: beim allerersten Start die drei Autos anlegen, damit die App ohne Server läuft.
        CoroutineScope(Dispatchers.IO).launch {
            if (db.fahrzeugDao().anzahl() == 0) {
                STARTAUTOS.forEach { (name, farbe, antrieb) ->
                    db.fahrzeugDao().upsert(
                        Fahrzeug(neueId(), name, kraftstoff = antrieb, farbe = farbe, dirty = false, aufServer = false)
                    )
                }
            }
        }
    }

    companion object {
        const val KANAL_FAHRT = "fahrt"
        const val KANAL_HINWEIS = "hinweis"

        /** Gleiche Namen wie in der Server-Migration, damit sie später zusammengelegt werden. */
        val STARTAUTOS = listOf(
            Triple("E-Auto", "#1565C0", "elektro"),
            Triple("Kombi", "#2E7D32", "diesel"),
            Triple("Bus", "#EF6C00", "diesel"),
        )
    }
}
