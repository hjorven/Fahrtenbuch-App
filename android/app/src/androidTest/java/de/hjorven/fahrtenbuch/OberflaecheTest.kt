package de.hjorven.fahrtenbuch

import android.graphics.Bitmap
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Oberflächentest (Roadmap Nr. 20): App frisch starten, Einrichtung überspringen, alle Reiter und
 * die Einstellungen öffnen. Pro Schritt ein Screenshot (die CI hängt sie als Artefakt an).
 */
@RunWith(AndroidJUnit4::class)
class OberflaecheTest {
    // Rechte vorab erteilen: sonst legt sich die System-Abfrage der Einrichtung über die App
    // ("No compose hierarchies found" im ersten CI-Lauf).
    @get:Rule(order = 0)
    val rechte: GrantPermissionRule = GrantPermissionRule.grant(*buildList {
        add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 29) add(android.Manifest.permission.ACTIVITY_RECOGNITION)
        if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    @get:Rule(order = 1)
    val regel = createAndroidComposeRule<MainActivity>()

    private fun bild(name: String) {
        regel.waitForIdle()
        val instr = InstrumentationRegistry.getInstrumentation()
        val b: Bitmap = instr.uiAutomation.takeScreenshot() ?: return
        val ordner = File(instr.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(ordner, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun da(text: String) = regel.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun durchAlleSeiten() {
        regel.waitForIdle()
        if (da("Willkommen")) {
            bild("01_einrichtung")
            val weiter = if (da("Los geht")) "Los geht" else "Weiter"
            regel.onNode(hasText(weiter, substring = true)).performScrollTo().performClick()
        }
        regel.waitUntil(5000) { da("Fahrt") }
        bild("02_fahrt")
        for ((reiter, name) in listOf("Fahrten" to "03_fahrten", "Tanken" to "04_tanken", "Statistik" to "05_statistik")) {
            regel.onAllNodes(hasText(reiter))[0].performClick()
            bild(name)
        }
        regel.onNode(hasContentDescription("Einstellungen")).performClick()
        regel.waitUntil(5000) { da("Test-Modus") || da("Automatisches Fahrtende") }
        bild("06_einstellungen")
        regel.onNodeWithText("Verbindungstest", substring = true).performScrollTo()
        bild("07_einstellungen_unten")
    }
}
