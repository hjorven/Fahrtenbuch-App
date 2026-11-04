package de.hjorven.fahrtenbuch.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import de.hjorven.fahrtenbuch.FahrtenbuchApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** QR-Code in einem Foto finden (ML Kit, offline). null = keiner drauf. */
suspend fun qrLesen(bild: Bitmap): String? {
    val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    return try {
        scanner.process(InputImage.fromBitmap(bild, 0)).await().firstOrNull { it.rawValue != null }?.rawValue
    } finally {
        scanner.close()
    }
}

/** Knopf "QR-Code scannen" (Kamera) plus "aus der Galerie"; [fertig] bekommt den Text des QR-Codes. */
@Composable
fun QrScanKnoepfe(fertig: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as FahrtenbuchApp
    val scope = rememberCoroutineScope()
    var laeuft by remember { mutableStateOf(false) }
    var fehler by remember { mutableStateOf<String?>(null) }
    fun auswerten(uri: Uri) {
        laeuft = true
        fehler = null
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                try { app.belege.laden(uri)?.let { qrLesen(it) } } catch (_: Exception) { null }
            }
            laeuft = false
            if (text == null) fehler = "Kein QR-Code erkannt. Näher rangehen, oder den Code unten eintippen."
            else fertig(text)
        }
    }
    val kamera = rememberKamera { auswerten(it) }
    val galerie = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) auswerten(uri) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalButton(onClick = kamera, enabled = !laeuft, modifier = Modifier.fillMaxWidth()) {
            if (laeuft) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else { Icon(Icons.Filled.QrCodeScanner, null); Text("  QR-Code scannen") }
        }
        TextButton(onClick = { galerie.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !laeuft) {
            Text("QR-Code aus der Galerie")
        }
        fehler?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** QR-Code anzeigen (zum Abscannen mit einem anderen Handy). */
@Composable
fun QrBild(text: String, groesse: Dp = 220.dp) {
    val bild = remember(text) {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512, mapOf(EncodeHintType.MARGIN to 2))
        val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        for (y in 0 until m.height) for (x in 0 until m.width) b.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        b.asImageBitmap()
    }
    Image(bild, "QR-Code", Modifier.size(groesse), filterQuality = FilterQuality.None)
}
