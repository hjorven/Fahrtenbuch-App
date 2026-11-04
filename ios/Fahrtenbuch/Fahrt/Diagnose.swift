import CoreLocation
import CoreMotion
import Foundation
import UIKit

/// Diagnose einer Test-Fahrt (Test-Modus, docs/ABSTIMMUNG.md Runde 10): Akku, GPS, Hintergrund und Sync.
/// Wird als JSON in fahrt.diagnose gespeichert und mit hochgeladen. Gleiche Felder wie in der Android-App,
/// damit beide Apps die Diagnose der anderen anzeigen können.
struct Diagnose: Codable, Equatable {
    var version = 1
    var app = ""
    var system = ""
    var geraet = ""
    /// Aufnahme-Abstand in Metern, 0 = normal.
    var aufnahmeMeter = 0
    var akkuStart = -1
    var akkuEnde = -1
    var laedtStart = false
    var laedtEnde = false
    var energiesparen = false
    /// Android: von der Akku-Optimierung ausgenommen. iOS: Hintergrundaktualisierung erlaubt.
    var akkuOptimierungAus = false
    var standortImmer = false
    var bewegungErlaubt = false
    var benachrichtigungen = false
    var punkte = 0
    var punkteGezaehlt = 0
    var verworfenUngenau = 0
    var verworfenZittern = 0
    /// Nur iOS: wegen der Aufnahme-Rate nicht für die Strecke genutzt.
    var ausgeduennt: Int? = nil
    var genauigkeitSchnittM = 0.0
    var genauigkeitMaxM = 0.0
    var luecken = 0
    var groessteLueckeSek: Int64 = 0
    var neustarts = 0
    /// manuell, stillstand, luecke, kein_signal
    var ende = ""
    var offenZumSync = -1
    var adresse = ""
    var letzterSyncVorMin: Int64 = -1
    /// Intern für den Schnitt.
    var genauigkeitSumme = 0.0
    /// Intern: letzter Punkt in Millisekunden seit 1970, wie bei Android.
    var letzteZeit: Int64 = 0

    static let lueckeSekunden: TimeInterval = 60

    enum Ergebnis { case gezaehlt, verworfen, ausgeduennt }

    mutating func punkt(_ p: GpsPunkt, _ ergebnis: Ergebnis) {
        punkte += 1
        let g = max(0, p.genauigkeit)
        genauigkeitSumme += g
        genauigkeitSchnittM = (genauigkeitSumme / Double(punkte) * 10).rounded() / 10
        genauigkeitMaxM = max(genauigkeitMaxM, g)
        switch ergebnis {
        case .gezaehlt: punkteGezaehlt += 1
        case .ausgeduennt: ausgeduennt = (ausgeduennt ?? 0) + 1
        case .verworfen:
            if p.genauigkeit < 0 || p.genauigkeit > Streckenrechner.maxGenauigkeit { verworfenUngenau += 1 } else { verworfenZittern += 1 }
        }
        let ms = Int64((p.zeit.timeIntervalSince1970 * 1000).rounded())
        if letzteZeit > 0, Double(ms - letzteZeit) > Self.lueckeSekunden * 1000 {
            luecken += 1
            groessteLueckeSek = max(groessteLueckeSek, (ms - letzteZeit) / 1000)
        }
        letzteZeit = ms
    }

    func alsJson() -> String {
        guard let d = try? JSONEncoder().encode(self) else { return "" }
        return String(data: d, encoding: .utf8) ?? ""
    }

    static func aus(_ text: String) -> Diagnose? {
        guard !text.isEmpty, let d = text.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(Diagnose.self, from: d)
    }

    /// Lesbare Zusammenfassung für die Fahrt-Ansicht und den Export.
    func zeilen() -> [(String, String)] {
        func ja(_ b: Bool) -> String { b ? "ja" : "nein" }
        var z: [(String, String)] = []
        if akkuStart >= 0 {
            let endeText = akkuEnde >= 0 ? " → \(akkuEnde) %" : ""
            let verbrauch = akkuEnde >= 0 && !laedtStart && !laedtEnde ? " (\(akkuStart - akkuEnde) %)" : ""
            z.append(("Akku", "\(akkuStart) %\(endeText)\(verbrauch)"))
        }
        if laedtStart || laedtEnde { z.append(("Geladen", "Start \(ja(laedtStart)), Ende \(ja(laedtEnde))")) }
        z.append(("Aufnahme", aufnahmeMeter > 0 ? "alle \(aufnahmeMeter) m" : "normal"))
        z.append(("GPS-Punkte", "\(punkte), davon \(punkteGezaehlt) gezählt"))
        if verworfenUngenau + verworfenZittern > 0 {
            z.append(("Verworfen", "\(verworfenUngenau) ungenau, \(verworfenZittern) Zittern im Stand"))
        }
        if let a = ausgeduennt, a > 0 { z.append(("Ausgedünnt", "\(a)")) }
        if punkte > 0 {
            z.append(("Genauigkeit", "Schnitt \(F.zahl(genauigkeitSchnittM, 1)) m, schlechteste \(Int(genauigkeitMaxM.rounded())) m"))
        }
        z.append(("Lücken über 60 s", luecken > 0 ? "\(luecken), längste \(groessteLueckeSek) s" : "keine"))
        if neustarts > 0 { z.append(("App neu gestartet", "\(neustarts) mal")) }
        let endeText: String = switch ende {
        case "manuell": "von Hand beendet"
        case "stillstand": "automatisch (Stillstand)"
        case "luecke": "automatisch (lange Lücke)"
        case "kein_signal": "automatisch (kein GPS mehr)"
        case "": "läuft noch"
        default: ende
        }
        z.append(("Ende", endeText))
        z.append(("Hintergrund", "Standort immer \(ja(standortImmer)), Hintergrund erlaubt \(ja(akkuOptimierungAus)), " +
                  "Energiesparen \(ja(energiesparen))"))
        z.append(("Berechtigungen", "Bewegung \(ja(bewegungErlaubt)), Benachrichtigungen \(ja(benachrichtigungen))"))
        if offenZumSync >= 0 {
            var s = "\(offenZumSync) offen"
            s += adresse.isEmpty ? ", kein Server erreichbar" : ", Server \(adresse)"
            if letzterSyncVorMin >= 0 { s += ", letzter Abgleich vor \(letzterSyncVorMin) min" }
            z.append(("Sync beim Ende", s))
        }
        let geraetText = [geraet, system, app.isEmpty ? "" : "App \(app)"].filter { !$0.isEmpty }.joined(separator: ", ")
        z.append(("Gerät", geraetText))
        return z
    }
}

extension Diagnose {
    /// Zustand des Geräts (Akku, Berechtigungen) zu Beginn der Fahrt.
    /// Benachrichtigungen fragt der Recorder danach getrennt ab (asynchron).
    @MainActor
    static func start(aufnahmeMeter: Int, standort: CLAuthorizationStatus) -> Diagnose {
        var d = Diagnose(aufnahmeMeter: aufnahmeMeter)
        let info = Bundle.main.infoDictionary
        d.app = (info?["CFBundleShortVersionString"] as? String ?? "") + " (" + (info?["CFBundleVersion"] as? String ?? "") + ")"
        d.system = "iOS \(UIDevice.current.systemVersion)"
        d.geraet = UIDevice.current.model
        d.standortImmer = standort == .authorizedAlways
        d.bewegungErlaubt = CMMotionActivityManager.authorizationStatus() == .authorized
        d.akkuOptimierungAus = UIApplication.shared.backgroundRefreshStatus == .available
        d.energiesparen = ProcessInfo.processInfo.isLowPowerModeEnabled
        if let a = akku() { d.akkuStart = a.0; d.laedtStart = a.1 }
        return d
    }

    /// Akkustand in Prozent und ob gerade geladen wird.
    @MainActor
    static func akku() -> (Int, Bool)? {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let stand = UIDevice.current.batteryLevel
        guard stand >= 0 else { return nil }
        let s = UIDevice.current.batteryState
        return (Int((stand * 100).rounded()), s == .charging || s == .full)
    }
}
