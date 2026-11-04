import CoreMotion
import Foundation

/// Bewegungserkennung (CoreMotion) für die Stau-Erkennung: Solange iOS "im Auto" meldet und niemand
/// ausgestiegen ist, endet eine Fahrt nicht automatisch, auch wenn das Auto lange steht.
@MainActor
final class Bewegung {
    static let shared = Bewegung()

    /// So lange gilt die letzte "im Auto"-Meldung. iOS meldet nur Wechsel, im Stau kommt also lange nichts Neues.
    /// Aussteigen (Gehen, Laufen, Rad) beendet "im Auto" sofort.
    nonisolated static let gueltig: TimeInterval = 3600

    private let manager = CMMotionActivityManager()
    private var imAutoZuletzt: Date?
    private var ausgestiegenZuletzt: Date?
    private var laeuft = false

    nonisolated static var verfuegbar: Bool { CMMotionActivityManager.isActivityAvailable() }
    nonisolated static var erlaubt: Bool { CMMotionActivityManager.authorizationStatus() == .authorized }
    nonisolated static var abgelehnt: Bool {
        let s = CMMotionActivityManager.authorizationStatus()
        return s == .denied || s == .restricted
    }

    /// Fragt einmal die Erlaubnis an (iOS zeigt den Dialog bei der ersten Abfrage).
    func erfragen() async {
        guard Self.verfuegbar else { return }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            let jetzt = Date()
            manager.queryActivityStarting(from: jetzt.addingTimeInterval(-60), to: jetzt, to: .main) { _, _ in
                c.resume()
            }
        }
    }

    func starten() {
        guard Self.verfuegbar, !Self.abgelehnt, !laeuft else { return }
        laeuft = true
        imAutoZuletzt = nil
        ausgestiegenZuletzt = nil
        manager.startActivityUpdates(to: .main) { [weak self] a in
            guard let a else { return }
            MainActor.assumeIsolated { self?.melden(a) }
        }
    }

    func stoppen() {
        guard laeuft else { return }
        manager.stopActivityUpdates()
        laeuft = false
    }

    private func melden(_ a: CMMotionActivity) {
        if a.automotive && a.confidence != .low {
            imAutoZuletzt = a.startDate
        } else if (a.walking || a.running || a.cycling) && a.confidence != .low {
            ausgestiegenZuletzt = a.startDate
        }
    }

    func imFahrzeug(_ jetzt: Date) -> Bool {
        Self.imFahrzeug(jetzt, imAuto: imAutoZuletzt, ausgestiegen: ausgestiegenZuletzt)
    }

    /// Reine Regel (getestet): "im Auto" ist frisch und danach ist niemand ausgestiegen.
    nonisolated static func imFahrzeug(_ jetzt: Date, imAuto: Date?, ausgestiegen: Date?) -> Bool {
        guard let imAuto, jetzt.timeIntervalSince(imAuto) < gueltig else { return false }
        if let ausgestiegen, ausgestiegen >= imAuto { return false }
        return true
    }

    // MARK: Fahrt vergessen? (Roadmap Nr. 7)

    struct Abschnitt: Equatable, Identifiable {
        let von: Date
        let bis: Date
        var id: Date { von }
    }

    /// Autofahrten der letzten 24 Stunden laut iOS, für die es keine Fahrt gibt.
    func vergesseneFahrten(fahrten: [(Date, Date)], jetzt: Date = Date()) async -> [Abschnitt] {
        guard Self.verfuegbar, Self.erlaubt else { return [] }
        let meldungen: [(Date, Bool, Bool)] = await withCheckedContinuation { c in
            manager.queryActivityStarting(from: jetzt.addingTimeInterval(-86400), to: jetzt, to: .main) { liste, _ in
                c.resume(returning: (liste ?? []).filter { $0.confidence != .low }.map { ($0.startDate, $0.automotive, $0.stationary) })
            }
        }
        return Self.abschnitte(meldungen, jetzt: jetzt).filter { a in
            !fahrten.contains { $0.0 < a.bis && $0.1 > a.von }
        }
    }

    /// Reine Regel (getestet): Aus Meldungen (Zeit, im Auto, steht) zusammenhängende Autofahrten bauen.
    /// "Steht" unterbricht nicht (Ampel, Stau), erst eine andere Bewegung beendet den Abschnitt.
    /// Kürzer als `mindestens` zählt nicht, die laufende (noch offene) Fahrt auch nicht.
    nonisolated static func abschnitte(_ meldungen: [(Date, Bool, Bool)], jetzt: Date,
                                       mindestens: TimeInterval = 300) -> [Abschnitt] {
        var aus: [Abschnitt] = []
        var beginn: Date?
        for (zeit, auto, steht) in meldungen.sorted(by: { $0.0 < $1.0 }) {
            if auto {
                if beginn == nil { beginn = zeit }
            } else if !steht, let b = beginn {
                if zeit.timeIntervalSince(b) >= mindestens { aus.append(Abschnitt(von: b, bis: zeit)) }
                beginn = nil
            }
        }
        return aus
    }
}
