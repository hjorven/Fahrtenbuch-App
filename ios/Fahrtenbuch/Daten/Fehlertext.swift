import Foundation

/// Einheitliche Fehlermeldungen mit "Was tun?" (Roadmap Nr. 11), gleiche Texte wie in der Android-App.
enum Fehlertext {
    static let offline = "Server nicht erreichbar. Was tun: Zuhause ins WLAN, unterwegs Tailscale einschalten. Es geht nichts verloren, die App lädt später hoch."

    /// `was` = was gerade nicht ging, z. B. "Speichern".
    static func aus(_ e: Error, _ was: String = "Das") -> String {
        guard let f = e as? PBFehler else {
            return e is URLError ? offline : "\(was) ging nicht. Was tun: Später erneut, sonst unter Einstellungen „Problem melden“."
        }
        switch f.status {
        case 0: return offline
        case 401: return "Anmeldung abgelaufen. Was tun: In den Einstellungen neu anmelden."
        case 403: return "\(was) ist nur für eigene Einträge erlaubt."
        case 404: return "\(was) gibt es auf dem Server noch nicht. Was tun: Server aktualisieren (Pi), dann erneut."
        case 400: return serverText(f.text) ?? "\(was) ging nicht, der Server hat die Werte abgelehnt. Was tun: Eingaben prüfen."
        case 413: return "Die Datei ist zu groß. Was tun: Foto erneut aufnehmen."
        case 500...599: return "Der Server hat gerade ein Problem. Was tun: Später erneut, sonst unter Einstellungen „Problem melden“."
        default: return "\(was) ging nicht (Fehler \(f.status)). Was tun: Später erneut, sonst „Problem melden“."
        }
    }

    /// Deutsche Meldung vom Server übernehmen (plausibel.js), technische ("HTTP 400", englisch) nicht.
    private static func serverText(_ t: String) -> String? {
        let t = t.trimmingCharacters(in: .whitespaces)
        if t.isEmpty || t.hasPrefix("HTTP ") || t.hasPrefix("Failed") || t.hasPrefix("Something") { return nil }
        return t
    }

    /// Häufige Fragen für die Hilfe in den Einstellungen.
    static let hilfe: [(String, String)] = [
        ("Die Fahrt startet nicht von selbst",
         "Standort auf „Immer“ und Bewegung & Fitness erlauben (der Verbindungstest zeigt, was fehlt). Für den Start über Bluetooth die Kurzbefehl-Automation einrichten. Notfalls die Fahrt mit dem großen Knopf starten."),
        ("Die Fahrt endet nicht oder zu früh",
         "Unter „Automatisches Fahrtende“ die Wartezeit ändern. Im Stau endet nichts, solange das iPhone „im Auto“ meldet. Eine zu früh beendete Fahrt lässt sich in der Fahrt mit der nächsten zusammenlegen."),
        ("„Server nicht erreichbar“",
         "Zuhause ins WLAN, unterwegs Tailscale einschalten. Es geht nichts verloren: Fahrten und Tankungen bleiben auf dem iPhone und werden später hochgeladen."),
        ("„Anmeldung abgelaufen“",
         "Oben unter Konto neu anmelden. Passwort vergessen: am Display unter Mehr > Passwort vergessen (mit PIN) oder beim Admin."),
        ("Eine Fahrt fehlt",
         "Unter Fahrten „Nachtragen“. Hat das iPhone eine Autofahrt erkannt, steht auf der Startseite „Fahrt vergessen?“ mit den Uhrzeiten."),
        ("Der Beleg wurde falsch erkannt",
         "Werte vor dem Sichern im Formular korrigieren. Das Foto bleibt am Eintrag und ist für alle zu sehen."),
        ("Falscher km-Stand",
         "Beim nächsten Tanken den Tacho fotografieren oder den km-Stand von Hand eintragen, er gilt dann für das Auto."),
        ("Etwas anderes stimmt nicht",
         "Oben „Problem melden“: schickt deinen Text mit App-Version, Gerät und der Diagnose der letzten Test-Fahrt an den Server, Der Admin sieht ihn dort."),
    ]
}
