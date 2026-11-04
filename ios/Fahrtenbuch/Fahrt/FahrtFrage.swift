import Foundation
import UserNotifications

/// "Fährst du?" als Mitteilung (Roadmap Nr. 6), ausgelöst vom Kurzbefehl FahrtFragenIntent.
/// Die Antwort nimmt Monatsabrechnung (Delegate der Mitteilungen) entgegen.
enum FahrtFrage {
    nonisolated static let kategorie = "fahrstDu"
    nonisolated static let ichFahre = "ichFahre"
    nonisolated static let nein = "nein"

    @MainActor
    static func zeigen() async {
        guard !GeteilterStatus.laeuft else { return }
        // Roadmap Nr. 6.3 (nur wenn eingeschaltet): gleich vorläufig aufzeichnen, die Frage bestätigt oder verwirft.
        var vorlaeufig = false
        if Konto.shared.autoVorlaeufig {
            vorlaeufig = (try? await FahrtRecorder.shared.starten(vorlaeufig: true)) != nil
        }
        let inhalt = UNMutableNotificationContent()
        inhalt.title = "\(GeteilterStatus.fahrzeugName ?? "Auto") verbunden. Fährst du?"
        inhalt.body = vorlaeufig
            ? "Die App zeichnet vorläufig auf. „Ich fahre“ bestätigt, „Nein“ löscht die Fahrt."
            : "„Ich fahre“ startet die Aufzeichnung. Fährst du mit, einfach ignorieren."
        inhalt.categoryIdentifier = kategorie
        if let id = GeteilterStatus.fahrzeugId { inhalt.userInfo = ["fahrzeug": id] }
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "fahrstDu", content: inhalt, trigger: nil))
    }
}
