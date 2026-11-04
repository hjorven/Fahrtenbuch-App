import Foundation
import Observation

struct Anmeldung: Equatable {
    var serverUrl: String
    var token: String
    var userId: String
    var name: String
    var email: String
}

/// Anmeldung und Einstellungen, gespeichert in UserDefaults.
@MainActor
@Observable
final class Konto {
    static let shared = Konto()

    /// Benutzer-ID im lokalen Modus (ohne Server).
    static let lokal = "lokal"

    private let d = UserDefaults.standard

    private(set) var anmeldung: Anmeldung?

    /// Konto hat noch das Start-Passwort: die App bittet dann, ein eigenes festzulegen.
    var passwortAendern: Bool = UserDefaults.standard.bool(forKey: "passwortAendern") {
        didSet { d.set(passwortAendern, forKey: "passwortAendern") }
    }

    var autoStopMinuten: Int {
        didSet { d.set(autoStopMinuten, forKey: "autoStopMinuten") }
    }
    /// Roadmap Nr. 6.3: Kurzbefehl "Fragen: Fährst du?" zeichnet gleich vorläufig auf. Aus ab Werk.
    var autoVorlaeufig: Bool = UserDefaults.standard.bool(forKey: "autoVorlaeufig") {
        didSet { UserDefaults.standard.set(autoVorlaeufig, forKey: "autoVorlaeufig") }
    }

    /// Zweite Adresse (z. B. Tailscale), wird versucht wenn die Hauptadresse nicht antwortet.
    var tailscaleUrl: String = UserDefaults.standard.string(forKey: "tailscaleUrl") ?? "" {
        didSet { d.set(tailscaleUrl, forKey: "tailscaleUrl") }
    }

    /// Test-Modus: neue Fahrten und Tankungen werden als Test markiert (zählen nirgends mit).
    var testModus: Bool = UserDefaults.standard.bool(forKey: "testModus") {
        didSet { d.set(testModus, forKey: "testModus") }
    }

    /// Nur im Test-Modus: GPS-Punkte für die Strecke höchstens alle so viele Meter (0 = normal).
    var testAufnahmeMeter: Int = UserDefaults.standard.integer(forKey: "testAufnahmeMeter") {
        didSet { d.set(testAufnahmeMeter, forKey: "testAufnahmeMeter") }
    }

    var letzterSync: Date? {
        didSet { d.set(letzterSync, forKey: "letzterSync") }
    }

    private init() {
        let gespeichert = d.integer(forKey: "autoStopMinuten")
        autoStopMinuten = gespeichert == 0 ? 10 : gespeichert
        letzterSync = d.object(forKey: "letzterSync") as? Date
        if let token = d.string(forKey: "token") {
            anmeldung = Anmeldung(
                serverUrl: d.string(forKey: "serverUrl") ?? "",
                token: token,
                userId: d.string(forKey: "userId") ?? "",
                name: d.string(forKey: "name") ?? "",
                email: d.string(forKey: "email") ?? ""
            )
        }
    }

    /// Eigene ID: vom Server, oder "lokal" solange nicht verbunden.
    var userId: String { anmeldung?.userId ?? Konto.lokal }

    /// Name im lokalen Modus.
    var lokalerName: String = UserDefaults.standard.string(forKey: "lokalerName") ?? "Ich" {
        didSet { d.set(lokalerName, forKey: "lokalerName") }
    }

    /// Nach der ersten Anmeldung: lokale Daten dem Server-Konto zuordnen.
    var zusammenfuehren: Bool {
        get { d.bool(forKey: "zusammenfuehren") }
        set { d.set(newValue, forKey: "zusammenfuehren") }
    }

    var letzteServerUrl: String { d.string(forKey: "serverUrl") ?? "" }
    var letzteEmail: String { d.string(forKey: "email") ?? "" }

    func speichern(_ a: Anmeldung) {
        d.set(a.serverUrl, forKey: "serverUrl")
        d.set(a.token, forKey: "token")
        d.set(a.userId, forKey: "userId")
        d.set(a.name, forKey: "name")
        d.set(a.email, forKey: "email")
        anmeldung = a
    }

    /// Hauptadresse ändern, Anmeldung bleibt erhalten.
    func serverUrlAendern(_ url: String) {
        guard var a = anmeldung else { return }
        a.serverUrl = url
        speichern(a)
    }

    /// "fahrtenbuch.local:8090/" -> "http://fahrtenbuch.local:8090"
    nonisolated static func normalisieren(_ eingabe: String) -> String {
        var t = eingabe.trimmingCharacters(in: .whitespacesAndNewlines)
        while t.hasSuffix("/") { t.removeLast() }
        if t.isEmpty { return "" }
        return (t.hasPrefix("http://") || t.hasPrefix("https://")) ? t : "http://" + t
    }

    func abmelden() {
        d.removeObject(forKey: "token")
        d.removeObject(forKey: "userId")
        passwortAendern = false
        for c in ["users", "fahrzeug", "fahrt", "tankvorgang", "ausgleich"] { d.removeObject(forKey: "sync_\(c)") }
        anmeldung = nil
    }

    func syncStand(_ collection: String) -> String { d.string(forKey: "sync_\(collection)") ?? "" }
    func setSyncStand(_ collection: String, _ wert: String) { d.set(wert, forKey: "sync_\(collection)") }
}
