package de.hjorven.fahrtenbuch.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Häufige Fragen mit "Was tun?" (Roadmap Nr. 11). Gleicher Inhalt wie in der iOS-App. */
val HILFE = listOf(
    "Die Fahrt startet nicht von selbst" to
        "Standort auf \"Immer erlauben\" und Bewegung erlauben (Verbindungstest oben zeigt, was fehlt). Akku-Optimierung für die App ausschalten. Notfalls die Fahrt mit dem großen Knopf starten.",
    "Die Fahrt endet nicht oder zu früh" to
        "Unter \"Automatisches Fahrtende\" die Wartezeit ändern. Im Stau endet nichts, solange das Handy \"im Auto\" meldet. Eine zu früh beendete Fahrt lässt sich in der Fahrt mit der nächsten zusammenlegen.",
    "\"Server nicht erreichbar\"" to
        "Zuhause ins WLAN, unterwegs Tailscale einschalten. Es geht nichts verloren: Fahrten und Tankungen bleiben auf dem Handy und werden später hochgeladen.",
    "\"Anmeldung abgelaufen\"" to
        "Oben unter Konto neu anmelden. Passwort vergessen: am Display unter Mehr > Passwort vergessen (mit PIN) oder beim Admin.",
    "Eine Fahrt fehlt" to
        "Unter Fahrten \"Nachtragen\". Hat das Handy eine Autofahrt erkannt, kommt ein Hinweis \"Fahrt vergessen?\" mit den Uhrzeiten.",
    "Der Beleg wurde falsch erkannt" to
        "Werte vor dem Sichern im Formular korrigieren. Das Foto bleibt am Eintrag und ist für alle zu sehen.",
    "Falscher km-Stand" to
        "Beim nächsten Tanken den Tacho fotografieren oder den km-Stand von Hand eintragen, er gilt dann für das Auto.",
    "Etwas anderes stimmt nicht" to
        "Oben \"Problem melden\": schickt deinen Text mit App-Version, Gerät und der Diagnose der letzten Test-Fahrt an den Server, Der Admin sieht ihn dort.",
)

@Composable
fun HilfeKarte() {
    Column(Modifier.padding(vertical = 4.dp)) {
        HILFE.forEachIndexed { i, (frage, antwort) ->
            var offen by remember { mutableStateOf(false) }
            Column(Modifier.clickable { offen = !offen }.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize()) {
                Text(frage, style = MaterialTheme.typography.bodyLarge)
                if (offen) Text(antwort, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            if (i < HILFE.lastIndex) HorizontalDivider()
        }
    }
}
