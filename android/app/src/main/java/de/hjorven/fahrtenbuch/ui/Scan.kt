package de.hjorven.fahrtenbuch.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text as MlText
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import de.hjorven.fahrtenbuch.data.ScanLeser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * Beleg- und Tacho-Scan: Foto mit der normalen Kamera-App, Texterkennung mit ML Kit
 * direkt auf dem Handy (kein Internet nötig), Auswertung in data/ScanLeser.kt.
 */
object Texterkennung {

    suspend fun lesen(bild: Bitmap): MlText {
        val erkenner = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            return erkenner.process(InputImage.fromBitmap(bild, 0)).await()
        } finally {
            erkenner.close()
        }
    }

    /**
     * Zeilen von oben nach unten. Teile, die auf gleicher Höhe nebeneinander stehen
     * ("Diesel" links, "45,23 l" rechts), werden zu einer Zeile zusammengefügt.
     */
    fun zeilen(text: MlText): List<String> {
        data class Teil(val text: String, val links: Int, val mitte: Float, val hoehe: Int)
        val teile = text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
            val box = l.boundingBox ?: return@mapNotNull null
            Teil(l.text, box.left, box.exactCenterY(), box.height().coerceAtLeast(1))
        }.sortedBy { it.mitte }
        val reihen = ArrayList<MutableList<Teil>>()
        for (t in teile) {
            val reihe = reihen.lastOrNull()?.takeIf { r ->
                val v = r.first()
                abs(v.mitte - t.mitte) < 0.5f * min(v.hoehe, t.hoehe)
            }
            if (reihe != null) reihe.add(t) else reihen.add(mutableListOf(t))
        }
        return reihen.map { r -> r.sortedBy { it.links }.joinToString("  ") { it.text } }
    }

    fun tachoTexte(text: MlText): List<ScanLeser.TachoText> =
        text.textBlocks.flatMap { it.lines }.map { l ->
            ScanLeser.TachoText(l.text, (l.boundingBox?.height() ?: 1).toFloat())
        }
}

/** Foto über die Kamera-App aufnehmen. Gibt eine Funktion zurück, die die Kamera öffnet. */
@Composable
fun rememberKamera(fertig: (Uri) -> Unit): () -> Unit {
    val app = LocalContext.current.applicationContext as FahrtenbuchApp
    var offen by rememberSaveable { mutableStateOf<String?>(null) }
    val starter = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val pfad = offen
        offen = null
        if (ok && pfad != null) fertig(Uri.fromFile(File(pfad)))
    }
    return {
        val (datei, uri) = app.belege.neuesFoto()
        offen = datei.path
        try {
            starter.launch(uri)
        } catch (e: android.content.ActivityNotFoundException) {
            // Keine Kamera-App (oder gesperrt): nicht abstürzen, Galerie und Eingabe gehen weiter.
            offen = null
            android.widget.Toast.makeText(app, "Keine Kamera gefunden. Bitte ein Foto aus der Galerie nehmen oder die Werte eintippen.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/** Ergebnis eines Beleg-Scans: verkleinertes Foto im Cache und was darauf erkannt wurde. */
class BelegScan(val foto: File, val erkannt: ScanLeser.Beleg)

/**
 * Großer Knopf "Beleg fotografieren" plus kleiner Knopf "Foto aus der Galerie".
 * Nach dem Foto wird der Text gelesen und [fertig] mit dem Ergebnis aufgerufen.
 */
@Composable
fun BelegScanKnoepfe(elektro: Boolean, sofortKamera: Boolean = false, startBild: Uri? = null, fertig: (BelegScan) -> Unit) {
    val app = LocalContext.current.applicationContext as FahrtenbuchApp
    val scope = rememberCoroutineScope()
    var laeuft by remember { mutableStateOf(false) }
    var fehler by remember { mutableStateOf<String?>(null) }

    fun auswerten(uri: Uri) {
        laeuft = true
        fehler = null
        scope.launch {
            val ergebnis = withContext(Dispatchers.Default) {
                try {
                    val bild = app.belege.laden(uri) ?: return@withContext null
                    val text = Texterkennung.lesen(bild)
                    val (datei, _) = app.belege.neuesFoto()
                    datei.outputStream().use { bild.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    BelegScan(datei, ScanLeser.beleg(Texterkennung.zeilen(text), elektro))
                } catch (_: Exception) {
                    null
                }
            }
            laeuft = false
            if (ergebnis == null) fehler = "Das Foto ließ sich nicht öffnen. Bitte noch einmal versuchen."
            else fertig(ergebnis)
        }
    }

    val kamera = rememberKamera { auswerten(it) }
    val galerie = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) auswerten(uri)
    }
    // Roadmap Nr. 9: "Getankt" öffnet gleich die Kamera; abbrechen führt ins normale Formular.
    var schonGestartet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(sofortKamera, startBild) {
        // Roadmap Nr. 14: geteiltes Bild gleich auswerten statt die Kamera zu öffnen.
        if (startBild != null && !schonGestartet) {
            schonGestartet = true
            auswerten(startBild)
        } else if (sofortKamera && !schonGestartet) {
            schonGestartet = true
            kamera()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalButton(
            onClick = kamera,
            enabled = !laeuft,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            if (laeuft) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text("  Beleg wird gelesen ...", style = MaterialTheme.typography.titleMedium)
            } else {
                Icon(Icons.Filled.PhotoCamera, null, Modifier.size(28.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(if (elektro) "Ladebeleg fotografieren" else "Tankbeleg fotografieren", style = MaterialTheme.typography.titleMedium)
                    Text("Menge und Preis werden dann ausgefüllt", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton(
            onClick = { galerie.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = !laeuft,
        ) { Text("Oder ein Foto aus der Galerie nehmen") }
        fehler?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * Knopf "Tacho fotografieren". Erkennt den km-Stand und fragt nach, bevor er übernommen wird.
 * [bisher] = letzter bekannter km-Stand des Autos, hilft beim Aussuchen der richtigen Zahl.
 */
@Composable
fun TachoScanKnopf(bisher: Double, modifier: Modifier = Modifier, uebernehmen: (Int) -> Unit) {
    val app = LocalContext.current.applicationContext as FahrtenbuchApp
    val scope = rememberCoroutineScope()
    var laeuft by remember { mutableStateOf(false) }
    var vorschlaege by remember { mutableStateOf<List<Int>?>(null) }

    val kamera = rememberKamera { uri ->
        laeuft = true
        scope.launch {
            vorschlaege = withContext(Dispatchers.Default) {
                try {
                    val bild = app.belege.laden(uri, maxSeite = 2400) ?: return@withContext emptyList()
                    ScanLeser.tacho(Texterkennung.tachoTexte(Texterkennung.lesen(bild)), bisher)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            laeuft = false
        }
    }

    OutlinedButton(onClick = kamera, enabled = !laeuft, modifier = modifier.heightIn(min = 52.dp)) {
        if (laeuft) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("  Tacho wird gelesen ...")
        } else {
            Icon(Icons.Filled.Speed, null)
            Text("  km-Stand vom Tacho fotografieren")
        }
    }

    vorschlaege?.let { liste ->
        val schliessen = { vorschlaege = null }
        if (liste.isEmpty()) {
            AlertDialog(
                onDismissRequest = schliessen,
                title = { Text("Keine Zahl erkannt") },
                text = {
                    Text(
                        "Bitte näher an den Tacho gehen, gerade von vorne fotografieren und auf Spiegelungen achten. " +
                            "Der km-Stand muss im Display zu sehen sein (Zündung an).",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                confirmButton = { Button(onClick = { schliessen(); kamera() }) { Text("Nochmal fotografieren") } },
                dismissButton = { TextButton(onClick = schliessen) { Text("Selbst eintippen") } },
            )
        } else {
            AlertDialog(
                onDismissRequest = schliessen,
                title = { Text("Steht das auf dem Tacho?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "${kmStand(liste.first().toDouble())}",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (liste.size > 1) {
                            Text("Oder eine dieser Zahlen:", style = MaterialTheme.typography.bodyLarge)
                            liste.drop(1).forEach { km ->
                                OutlinedButton(
                                    onClick = { uebernehmen(km); schliessen() },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) { Text(kmStand(km.toDouble()), style = MaterialTheme.typography.titleMedium) }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { uebernehmen(liste.first()); schliessen() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Ja, stimmt", style = MaterialTheme.typography.titleMedium)
                    }
                },
                dismissButton = { TextButton(onClick = schliessen) { Text("Nein, selbst eintippen") } },
            )
        }
    }
}

/** Vorschaubild eines Belegfotos; Antippen zeigt es groß. */
@Composable
fun BelegBild(datei: File, modifier: Modifier = Modifier) {
    var gross by remember { mutableStateOf(false) }
    val bild = bildLaden(datei, 600)
    bild?.let {
        Image(
            it, "Beleg",
            contentScale = ContentScale.Crop,
            modifier = modifier.clickable { gross = true },
        )
    }
    if (gross) {
        Dialog(onDismissRequest = { gross = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val voll = bildLaden(datei, 2000)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).clickable { gross = false },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                voll?.let { Image(it, "Beleg", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
                Row(Modifier.padding(16.dp)) {
                    Button(onClick = { gross = false }, modifier = Modifier.heightIn(min = 52.dp)) { Text("Schließen") }
                }
            }
        }
    }
}

@Composable
private fun bildLaden(datei: File, maxSeite: Int): ImageBitmap? {
    val bild by produceState<ImageBitmap?>(null, datei.path, datei.lastModified()) {
        value = withContext(Dispatchers.IO) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(datei.path, o)
                var stufe = 1
                while (maxOf(o.outWidth, o.outHeight) / (stufe * 2) >= maxSeite) stufe *= 2
                BitmapFactory.decodeFile(datei.path, BitmapFactory.Options().apply { inSampleSize = stufe })?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    return bild
}
