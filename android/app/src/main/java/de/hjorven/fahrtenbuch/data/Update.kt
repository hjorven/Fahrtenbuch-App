package de.hjorven.fahrtenbuch.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** [sha256] leer = nicht prüfen. */
data class NeueVersion(val nummer: Int, val name: String, val apkUrl: String, val sha256: String)

/**
 * Updates kommen vom Raspberry Pi (Port 8091), nicht direkt von GitHub: Das Repo ist privat,
 * ohne Anmeldung liefert GitHub nichts. Der Pi holt jede neue APK selbst ab und legt daneben
 * `android.json` (siehe server/downloads/README.md).
 *
 * Ablauf für den Nutzer: Hinweis "Neue Version" antippen, die App lädt die APK, prüft sie und
 * öffnet den Android-Installer. Dort einmal "Aktualisieren" tippen. Die Daten bleiben erhalten.
 */
object Update {
    const val PORT = 8091
    private const val STANDARD = "http://fahrtenbuch.local:$PORT/"
    private const val EINMAL_PRO_MS = 3 * 3_600_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun installierteVersion(context: Context): Int = try {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()
    } catch (_: Exception) {
        0
    }

    /**
     * Wo der Pi die Downloads anbietet: derselbe Rechner wie der Server, aber Port 8091.
     * Reihenfolge: Heimnetz-Adresse, Tailscale, zuletzt fahrtenbuch.local. Reine Funktion für Tests.
     */
    fun quellen(serverUrls: List<String>): List<String> =
        (serverUrls.mapNotNull { basis(it) } + STANDARD).distinct()

    private fun basis(serverUrl: String): String? {
        val s = serverUrl.trim()
        if (s.isEmpty()) return null
        return try {
            val u = URI(if ("://" in s) s else "http://$s")
            val host = u.host?.takeIf { it.isNotBlank() } ?: return null
            val h = if (':' in host && !host.startsWith("[")) "[$host]" else host
            "http://$h:$PORT/"
        } catch (_: Exception) {
            null
        }
    }

    /** `android.json` vom Pi auswerten. Reine Funktion für Tests. */
    fun auswerten(json: JsonObject, basis: String): NeueVersion? {
        val nummer = json["versionCode"]?.jsonPrimitive?.intOrNull ?: return null
        val datei = json["datei"]?.jsonPrimitive?.content?.takeIf { it.endsWith(".apk") && '/' !in it } ?: return null
        val name = json["versionName"]?.jsonPrimitive?.content ?: "$nummer"
        val sha = json["sha256"]?.jsonPrimitive?.content?.lowercase() ?: ""
        return NeueVersion(nummer, name, basis + datei, sha)
    }

    /**
     * null = aktuell, nicht erreichbar oder schon vor kurzem geprüft ([erzwingen] = trotzdem prüfen).
     */
    suspend fun pruefen(context: Context, prefs: Prefs, erzwingen: Boolean = false): NeueVersion? {
        val jetzt = System.currentTimeMillis()
        val installiert = installierteVersion(context)
        val bekannt = prefs.updateBekannt()?.takeIf { it.nummer > installiert }
        if (!erzwingen && jetzt - prefs.updateGeprueft < EINMAL_PRO_MS) return bekannt
        // Mit der gemerkten IP, falls fahrtenbuch.local nicht auflöst (Nachtplan 5b).
        val adressen = quellen(listOf(prefs.anmeldung.value?.serverUrl ?: "", prefs.letzteServerUrl, prefs.letzteIpUrl, prefs.tailscaleUrl))
        val neu = withContext(Dispatchers.IO) {
            adressen.firstNotNullOfOrNull { b ->
                try {
                    val req = Request.Builder().url(b + "android.json").header("Cache-Control", "no-cache").build()
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) null
                        else auswerten(Json.parseToJsonElement(r.body?.string() ?: "{}").jsonObject, b)
                    }
                } catch (_: Exception) {
                    null
                }
            }
        } ?: return bekannt
        prefs.updateGeprueft = jetzt
        val treffer = neu.takeIf { it.nummer > installiert }
        prefs.updateMerken(treffer)
        if (treffer == null) aufraeumen(context)
        return treffer
    }

    private fun datei(context: Context) = File(File(context.cacheDir, "updates").apply { mkdirs() }, "Fahrtenbuch-neu.apk")

    /** APK laden und prüfen. [fortschritt] bekommt 0..1. Wirft bei Fehlern eine Meldung für den Nutzer. */
    suspend fun herunterladen(context: Context, neu: NeueVersion, fortschritt: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val ziel = datei(context)
            val teil = File(ziel.parentFile, ziel.name + ".teil")
            val req = Request.Builder().url(neu.apkUrl).build()
            val md = MessageDigest.getInstance("SHA-256")
            try {
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw UpdateFehler("Download fehlgeschlagen (${r.code}). Bitte zuhause im WLAN erneut versuchen.")
                    val body = r.body ?: throw UpdateFehler("Download leer.")
                    val gesamt = body.contentLength()
                    var geladen = 0L
                    body.byteStream().use { ein ->
                        teil.outputStream().use { aus ->
                            val puffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = ein.read(puffer)
                                if (n < 0) break
                                aus.write(puffer, 0, n)
                                md.update(puffer, 0, n)
                                geladen += n
                                if (gesamt > 0) fortschritt(geladen.toFloat() / gesamt)
                            }
                        }
                    }
                }
            } catch (e: UpdateFehler) {
                teil.delete(); throw e
            } catch (_: Exception) {
                teil.delete()
                throw UpdateFehler("Keine Verbindung zum Heim-Server. Bitte zuhause im WLAN erneut versuchen.")
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (neu.sha256.isNotEmpty() && sha != neu.sha256) {
                teil.delete()
                throw UpdateFehler("Die Datei ist beschädigt angekommen. Bitte noch einmal versuchen.")
            }
            ziel.delete()
            if (!teil.renameTo(ziel)) throw UpdateFehler("Speichern fehlgeschlagen.")
            ziel
        }

    /** Darf die App den Installer öffnen? Ab Android 8 muss das einmal pro App erlaubt werden. */
    fun installierenErlaubt(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Öffnet die Einstellung "Unbekannte Apps installieren" direkt für diese App. */
    fun erlaubnisOeffnen(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(i)
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Geladene APK, falls noch vorhanden (z. B. nach dem Erlauben in den Einstellungen). */
    fun geladen(context: Context): File? = datei(context).takeIf { it.exists() && it.length() > 0 }

    fun aufraeumen(context: Context) {
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    /** Android-Installer mit der geladenen APK öffnen. */
    fun installerOeffnen(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}

class UpdateFehler(meldung: String) : Exception(meldung)
