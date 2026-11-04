package de.hjorven.fahrtenbuch.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Roadmap Nr. 14: Screenshot aus einer Lade-App (oder ein Belegfoto) über "Teilen" an das
 * Fahrtenbuch schicken. Die App öffnet "Tanken und Laden" und liest das Bild wie einen Beleg.
 */
object Geteilt {
    val bild = MutableStateFlow<Uri?>(null)

    fun annehmen(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri != null) bild.value = uri
    }
}
