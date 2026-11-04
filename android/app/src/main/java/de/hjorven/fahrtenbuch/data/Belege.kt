package de.hjorven.fahrtenbuch.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max

/**
 * Belegfotos auf dem Handy: ein JPEG pro Tankvorgang unter files/belege/<id>.jpg.
 * Kamera-Fotos landen zuerst im Cache (cache/scan/), bis der Eintrag gespeichert wird.
 */
class Belege(private val context: Context) {

    fun datei(tankId: String): File = File(context.filesDir, "belege/$tankId.jpg")

    /** Neue leere Datei für die Kamera-App plus die freigegebene Adresse dafür. */
    fun neuesFoto(): Pair<File, Uri> {
        val ordner = File(context.cacheDir, "scan").apply { mkdirs() }
        // Alte Scans vom letzten Mal wegräumen.
        ordner.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val datei = File(ordner, "foto-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", datei)
        return datei to uri
    }

    /**
     * Foto laden, richtig herum drehen und auf höchstens [maxSeite] Pixel verkleinern.
     * Reicht für die Texterkennung und hält die Datei klein (etwa 300 bis 600 KB).
     */
    fun laden(uri: Uri, maxSeite: Int = 2000): Bitmap? {
        val aufloesung = context.contentResolver.openInputStream(uri)?.use { s ->
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeStream(s, null, it) }
        } ?: return null
        if (aufloesung.outWidth <= 0 || aufloesung.outHeight <= 0) return null
        var stufe = 1
        while (max(aufloesung.outWidth, aufloesung.outHeight) / (stufe * 2) >= maxSeite) stufe *= 2
        val bild = context.contentResolver.openInputStream(uri)?.use { s ->
            BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = stufe })
        } ?: return null
        val drehung = try {
            context.contentResolver.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) {
            0f
        }
        val faktor = minOf(1f, maxSeite.toFloat() / max(bild.width, bild.height))
        if (drehung == 0f && faktor >= 1f) return bild
        val m = Matrix().apply {
            postScale(faktor, faktor)
            postRotate(drehung)
        }
        return Bitmap.createBitmap(bild, 0, 0, bild.width, bild.height, m, true).also { if (it != bild) bild.recycle() }
    }

    /** Verkleinertes Foto als Beleg zum Tankvorgang ablegen. */
    fun speichern(tankId: String, bild: Bitmap) {
        val ziel = datei(tankId)
        ziel.parentFile?.mkdirs()
        val tmp = File(ziel.path + ".tmp")
        tmp.outputStream().use { bild.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        tmp.renameTo(ziel)
    }

    /** Bereits verkleinertes Foto aus dem Scan-Ordner übernehmen. */
    fun uebernehmen(tankId: String, quelle: File) {
        val ziel = datei(tankId)
        ziel.parentFile?.mkdirs()
        quelle.copyTo(ziel, overwrite = true)
    }
}
