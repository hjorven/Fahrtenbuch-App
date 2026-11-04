import Foundation

/// Gemeinsamer Speicher von App und Widget-Erweiterung.
///
/// SideStore hängt beim Signieren die Team-ID an Bundle-ID und App-Group an
/// (z. B. `group.de.hjorven.fahrtenbuch.ABCDE12345`). Deshalb wird der Gruppenname
/// zur Laufzeit aus der Bundle-ID der App abgeleitet statt fest eingetragen.
enum AppGruppe {
    static let id: String = {
        var bundle = Bundle.main.bundleIdentifier ?? "de.hjorven.fahrtenbuch"
        if Bundle.main.bundleURL.pathExtension == "appex", let punkt = bundle.lastIndex(of: ".") {
            bundle = String(bundle[..<punkt])
        }
        return "group." + bundle
    }()

    static var defaults: UserDefaults {
        UserDefaults(suiteName: id) ?? .standard
    }
}

/// Was Widget, Kontrollzentrum und Live Activity über die laufende Fahrt wissen müssen.
enum GeteilterStatus {
    private static var d: UserDefaults { AppGruppe.defaults }

    static var laeuft: Bool {
        get { d.bool(forKey: "laeuft") }
        set { d.set(newValue, forKey: "laeuft") }
    }

    static var start: Date? {
        get { d.object(forKey: "start") as? Date }
        set { d.set(newValue, forKey: "start") }
    }

    static var km: Double {
        get { d.double(forKey: "km") }
        set { d.set(newValue, forKey: "km") }
    }

    /// Zuletzt gewähltes Auto (wird beim Start per Widget benutzt).
    static var fahrzeugId: String? {
        get { d.string(forKey: "fahrzeugId") }
        set { d.set(newValue, forKey: "fahrzeugId") }
    }

    static var fahrzeugName: String? {
        get { d.string(forKey: "fahrzeugName") }
        set { d.set(newValue, forKey: "fahrzeugName") }
    }

    /// Zeile für das Widget, z. B. "November: 312 km · du bekommst 12,30 €" (Roadmap Nr. 24).
    static var monatsZeile: String? {
        get { d.string(forKey: "monatsZeile") }
        set { d.set(newValue, forKey: "monatsZeile") }
    }
}
