package de.hjorven.fahrtenbuch.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Diagnose einer Test-Fahrt (Test-Modus, docs/ABSTIMMUNG.md Runde 10): Akku, GPS, Hintergrund und Sync.
 * Wird als JSON in fahrt.diagnose gespeichert und mit hochgeladen.
 */
@Serializable
data class Diagnose(
    var version: Int = 1,
    var app: String = "",
    var system: String = "",
    var geraet: String = "",
    /** Aufnahme-Abstand in Metern, 0 = normal (so oft wie möglich). */
    var aufnahmeMeter: Int = 0,
    var akkuStart: Int = -1,
    var akkuEnde: Int = -1,
    var laedtStart: Boolean = false,
    var laedtEnde: Boolean = false,
    var energiesparen: Boolean = false,
    /** App ist von der Akku-Optimierung ausgenommen. */
    var akkuOptimierungAus: Boolean = false,
    var standortImmer: Boolean = false,
    var bewegungErlaubt: Boolean = false,
    var benachrichtigungen: Boolean = false,
    /** Alle GPS-Punkte, die ankamen. */
    var punkte: Int = 0,
    /** Davon gezählt (in die Strecke übernommen). */
    var punkteGezaehlt: Int = 0,
    var verworfenUngenau: Int = 0,
    var verworfenZittern: Int = 0,
    var genauigkeitSchnittM: Double = 0.0,
    var genauigkeitMaxM: Double = 0.0,
    /** Lücken über [LUECKE_MS] zwischen zwei Punkten (Gerät hat gedrosselt oder kein Empfang). */
    var luecken: Int = 0,
    var groessteLueckeSek: Long = 0,
    /** Dienst wurde während der Fahrt neu gestartet (z. B. von Android beendet). */
    var neustarts: Int = 0,
    /** manuell, stillstand, luecke, kein_signal */
    var ende: String = "",
    var offenZumSync: Int = -1,
    var adresse: String = "",
    var letzterSyncVorMin: Long = -1,
    private var genauigkeitSumme: Double = 0.0,
    private var letzteZeit: Long = 0,
) {
    /** Einen GPS-Punkt zählen. [gezaehlt] = Streckenrechner hat ihn übernommen. */
    fun punkt(p: GpsPunkt, gezaehlt: Boolean) {
        punkte++
        genauigkeitSumme += p.genauigkeit
        genauigkeitSchnittM = Math.round(genauigkeitSumme / punkte * 10) / 10.0
        if (p.genauigkeit > genauigkeitMaxM) genauigkeitMaxM = p.genauigkeit.toDouble()
        when {
            gezaehlt -> punkteGezaehlt++
            p.genauigkeit > Streckenrechner.MAX_GENAUIGKEIT_M -> verworfenUngenau++
            else -> verworfenZittern++
        }
        if (letzteZeit > 0) {
            val ms = p.zeit - letzteZeit
            if (ms > LUECKE_MS) {
                luecken++
                groessteLueckeSek = maxOf(groessteLueckeSek, ms / 1000)
            }
        }
        letzteZeit = p.zeit
    }

    fun alsJson(): String = JSON.encodeToString(serializer(), this)

    /** Lesbare Zusammenfassung für die Fahrt-Ansicht und den Export. */
    fun zeilen(): List<Pair<String, String>> = buildList {
        fun ja(b: Boolean) = if (b) "ja" else "nein"
        if (akkuStart >= 0) {
            val ende = if (akkuEnde >= 0) " → $akkuEnde %" else ""
            val verbrauch = if (akkuEnde >= 0 && !laedtStart && !laedtEnde) " (${akkuStart - akkuEnde} %)" else ""
            add("Akku" to "$akkuStart %$ende$verbrauch")
        }
        if (laedtStart || laedtEnde) add("Geladen" to "Start ${ja(laedtStart)}, Ende ${ja(laedtEnde)}")
        add("Aufnahme" to if (aufnahmeMeter > 0) "alle $aufnahmeMeter m" else "normal")
        add("GPS-Punkte" to "$punkte, davon $punkteGezaehlt gezählt")
        if (verworfenUngenau + verworfenZittern > 0)
            add("Verworfen" to "$verworfenUngenau ungenau, $verworfenZittern Zittern im Stand")
        if (punkte > 0) add("Genauigkeit" to "Schnitt $genauigkeitSchnittM m, schlechteste ${Math.round(genauigkeitMaxM)} m")
        add("Lücken über 60 s" to if (luecken > 0) "$luecken, längste $groessteLueckeSek s" else "keine")
        if (neustarts > 0) add("Dienst neu gestartet" to "$neustarts mal")
        add("Ende" to when (ende) {
            "manuell" -> "von Hand beendet"
            "stillstand" -> "automatisch (Stillstand)"
            "luecke" -> "automatisch (lange Lücke)"
            "kein_signal" -> "automatisch (kein GPS mehr)"
            "kein_standort" -> "abgebrochen (Standort nicht mehr erlaubt)"
            "" -> "läuft noch"
            else -> ende
        })
        add("Hintergrund" to "Standort immer ${ja(standortImmer)}, Akku-Optimierung aus ${ja(akkuOptimierungAus)}, " +
            "Energiesparen ${ja(energiesparen)}")
        add("Berechtigungen" to "Bewegung ${ja(bewegungErlaubt)}, Benachrichtigungen ${ja(benachrichtigungen)}")
        if (offenZumSync >= 0) add("Sync beim Ende" to buildString {
            append("$offenZumSync offen")
            append(if (adresse.isNotBlank()) ", Server $adresse" else ", kein Server erreichbar")
            if (letzterSyncVorMin >= 0) append(", letzter Abgleich vor $letzterSyncVorMin min")
        })
        add("Gerät" to listOf(geraet, system, if (app.isNotBlank()) "App $app" else "").filter { it.isNotBlank() }.joinToString(", "))
    }

    companion object {
        const val LUECKE_MS = 60_000L
        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun ausJson(text: String): Diagnose? =
            if (text.isBlank()) null else try { JSON.decodeFromString(serializer(), text) } catch (_: Exception) { null }

        /** Zustand des Geräts (Akku, Berechtigungen) zu Beginn der Fahrt. */
        fun start(context: Context, aufnahmeMeter: Int): Diagnose {
            val d = Diagnose(aufnahmeMeter = aufnahmeMeter)
            d.app = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            } catch (_: Exception) { "" }
            d.system = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            d.geraet = "${Build.MANUFACTURER} ${Build.MODEL}"
            fun erlaubt(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            d.standortImmer = if (Build.VERSION.SDK_INT >= 29) erlaubt(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else true
            d.bewegungErlaubt = if (Build.VERSION.SDK_INT >= 29) erlaubt(Manifest.permission.ACTIVITY_RECOGNITION) else true
            d.benachrichtigungen = if (Build.VERSION.SDK_INT >= 33) erlaubt(Manifest.permission.POST_NOTIFICATIONS) else true
            val pm = context.getSystemService(PowerManager::class.java)
            d.energiesparen = pm?.isPowerSaveMode == true
            d.akkuOptimierungAus = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            akku(context)?.let { (stand, laedt) -> d.akkuStart = stand; d.laedtStart = laedt }
            return d
        }

        /** Akkustand in Prozent und ob gerade geladen wird. */
        fun akku(context: Context): Pair<Int, Boolean>? {
            val bm = context.getSystemService(BatteryManager::class.java) ?: return null
            val stand = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return if (stand in 0..100) stand to bm.isCharging else null
        }
    }
}
