package de.hjorven.fahrtenbuch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.hjorven.fahrtenbuch.ui.FahrtenbuchOberflaeche
import de.hjorven.fahrtenbuch.ui.theme.FahrtenbuchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) de.hjorven.fahrtenbuch.ui.Geteilt.annehmen(intent)
        enableEdgeToEdge()
        setContent {
            FahrtenbuchTheme {
                FahrtenbuchOberflaeche()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        de.hjorven.fahrtenbuch.ui.Geteilt.annehmen(intent)
    }
}
