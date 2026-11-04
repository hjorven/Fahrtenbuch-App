package de.hjorven.fahrtenbuch.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FahrtenbuchApp
        if (!app.sync.sync()) return Result.retry()
        try { Monatsabrechnung.pruefen(app) } catch (_: Exception) {}
        de.hjorven.fahrtenbuch.widget.widgetsAktualisieren(app)  // Monatszeile im Widget (Roadmap Nr. 24)
        return Result.success()
    }

    companion object {
        private val netz = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Alle 30 Minuten im Hintergrund, sobald WLAN/Netz da ist. */
        fun planen(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).setConstraints(netz).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /**
         * Einmal sofort (z. B. nach einer neuen Fahrt), wiederholt sich bei fehlendem Netz automatisch.
         * Hinten anstellen statt ersetzen: REPLACE hat einen laufenden Abgleich mitten im Upload abgebrochen.
         */
        fun jetzt(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(netz).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-jetzt", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        /**
         * Zwischen-Upload einer laufenden Fahrt. KEEP statt APPEND: ohne Netz wartet höchstens ein
         * Auftrag, statt dass sich alle 2 Minuten ein weiterer anhängt.
         */
        fun unterwegs(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(netz).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-unterwegs", ExistingWorkPolicy.KEEP, req)
        }
    }
}
