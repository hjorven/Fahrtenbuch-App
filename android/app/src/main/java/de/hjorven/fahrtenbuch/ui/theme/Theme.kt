package de.hjorven.fahrtenbuch.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Hell = lightColorScheme(
    primary = Color(0xFF1B5FAA),
    secondary = Color(0xFF545F71),
    tertiary = Color(0xFF8B5000),
)
private val Dunkel = darkColorScheme(
    primary = Color(0xFFA4C8FF),
    secondary = Color(0xFFBCC7DC),
    tertiary = Color(0xFFFFB86E),
)

/** Material 3 mit Dynamic Color (Farben aus dem Hintergrundbild ab Android 12). */
@Composable
fun FahrtenbuchTheme(content: @Composable () -> Unit) {
    val dunkel = isSystemInDarkTheme()
    val farben = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val c = LocalContext.current
            if (dunkel) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
        }
        dunkel -> Dunkel
        else -> Hell
    }
    MaterialTheme(colorScheme = farben, content = content)
}
