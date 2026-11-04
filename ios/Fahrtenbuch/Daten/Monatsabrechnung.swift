import Foundation
import SwiftData
import UserNotifications

/// Monatsabrechnung als Nachricht (Roadmap Nr. 10): In den ersten 10 Tagen eines Monats kommt nach
/// dem Abgleich einmal "Abrechnung Oktober: Du zahlst Ben 23,40 €" mit **Bezahlt**. Der Knopf trägt
/// den Ausgleich ein wie in der Statistik. iOS gleicht beim Öffnen der App ab, die Nachricht kommt
/// also beim ersten Öffnen im neuen Monat.
@MainActor
final class Monatsabrechnung: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Monatsabrechnung()
    nonisolated private static let kategorie = "abrechnung"
    nonisolated private static let bezahlt = "bezahlt"
    nonisolated private static let monate = ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August",
                                 "September", "Oktober", "November", "Dezember"]

    struct Nachricht: Equatable {
        let titel: String
        let text: String
        let bezahlen: Statistik.Ueberweisung?
    }

    /// Einmal beim Start: Knopf "Bezahlt" anmelden und Antworten annehmen.
    func einrichten() {
        let c = UNUserNotificationCenter.current()
        c.delegate = self
        let knopf = UNNotificationAction(identifier: Self.bezahlt, title: "Bezahlt", options: [])
        c.setNotificationCategories([
            UNNotificationCategory(identifier: Self.kategorie, actions: [knopf], intentIdentifiers: []),
            UNNotificationCategory(identifier: FahrtFrage.kategorie, actions: [
                UNNotificationAction(identifier: FahrtFrage.ichFahre, title: "Ich fahre", options: []),
                UNNotificationAction(identifier: FahrtFrage.nein, title: "Nein", options: [.destructive]),
            ], intentIdentifiers: []),
        ])
    }

    /// Reine Regel (getestet): Text für mich, nil wenn nichts zu tun. `monat` 0 = Januar.
    nonisolated static func nachricht(_ ue: [Statistik.Ueberweisung], ich: String, namen: [String: String], monat: Int) -> Nachricht? {
        let zahlen = ue.filter { $0.von == ich }
        let bekommen = ue.filter { $0.an == ich }
        if zahlen.isEmpty && bekommen.isEmpty { return nil }
        func n(_ id: String) -> String { (namen[id]?.isEmpty == false ? namen[id] : nil) ?? "jemand" }
        func e(_ v: Double) -> String { String(format: "%.2f €", v).replacingOccurrences(of: ".", with: ",") }
        let teile = zahlen.map { "Du zahlst \(n($0.an)) \(e($0.betrag))" } + bekommen.map { "\(n($0.von)) zahlt dir \(e($0.betrag))" }
        return Nachricht(titel: "Abrechnung \(monate[monat])", text: teile.joined(separator: ". ") + ".",
                         bezahlen: zahlen.count == 1 ? zahlen[0] : nil)
    }

    /// Zeitraum ("2026-10") und Grenzen des Vormonats.
    nonisolated static func vormonat(_ jetzt: Date, kalender: Calendar = .current) -> (String, Date, Date) {
        let bis = kalender.date(from: kalender.dateComponents([.year, .month], from: jetzt))!
        let von = kalender.date(byAdding: .month, value: -1, to: bis)!
        return (Statistik.zeitraum(von), von, bis)
    }

    /// Monatszeile fürs Widget neu berechnen (nach jedem Abgleich).
    func widgetZeile(_ context: ModelContext, jetzt: Date = Date()) {
        let ich = Konto.shared.userId
        let von = Calendar.current.date(from: Calendar.current.dateComponents([.year, .month], from: jetzt))!
        let fahrten = ((try? context.fetch(FetchDescriptor<Fahrt>())) ?? []).filter { !$0.test }
        let tank = ((try? context.fetch(FetchDescriptor<Tankvorgang>())) ?? []).filter { !$0.test }
        var kpk: [String: Double] = [:]
        for fz in (try? context.fetch(FetchDescriptor<Fahrzeug>())) ?? [] {
            if let k = Statistik.kostenProKm(fz, tank.filter { $0.fahrzeugId == fz.id }) { kpk[fz.id] = k }
        }
        let z = Statistik.zeitraum(von)
        let ausgl = ((try? context.fetch(FetchDescriptor<Ausgleich>())) ?? []).filter { $0.zeitraum == z }
        let s = Statistik.abrechnung(fahrten: fahrten, tankungen: tank, kostenProKm: kpk, von: von,
                                     bis: jetzt.addingTimeInterval(1), ausgleiche: ausgl).first { $0.fahrerId == ich }
        let neu = s.map { Self.zeile($0, monat: Calendar.current.component(.month, from: von) - 1) }
        if neu != GeteilterStatus.monatsZeile {
            GeteilterStatus.monatsZeile = neu
            WidgetKitHelfer.neuLaden()
        }
    }

    nonisolated static func zeile(_ s: Statistik.Saldo, monat: Int) -> String {
        func e(_ v: Double) -> String { String(format: "%.2f €", v).replacingOccurrences(of: ".", with: ",") }
        let geld = s.saldo > 0.005 ? " · du bekommst \(e(s.saldo))" : s.saldo < -0.005 ? " · du zahlst \(e(-s.saldo))" : ""
        return "\(monate[monat]): \(Int(s.km.rounded())) km" + geld
    }

    func pruefen(_ context: ModelContext, jetzt: Date = Date()) async {
        guard let a = Konto.shared.anmeldung, Calendar.current.component(.day, from: jetzt) <= 10 else { return }
        let (zeitraum, von, bis) = Self.vormonat(jetzt)
        let d = UserDefaults.standard
        guard d.string(forKey: "abrechnungGemeldet") != zeitraum else { return }
        let fahrten = ((try? context.fetch(FetchDescriptor<Fahrt>())) ?? []).filter { !$0.test }
        let tank = ((try? context.fetch(FetchDescriptor<Tankvorgang>())) ?? []).filter { !$0.test }
        let autos = (try? context.fetch(FetchDescriptor<Fahrzeug>())) ?? []
        var kpk: [String: Double] = [:]
        for fz in autos { if let k = Statistik.kostenProKm(fz, tank.filter { $0.fahrzeugId == fz.id }) { kpk[fz.id] = k } }
        let ausgleiche = ((try? context.fetch(FetchDescriptor<Ausgleich>())) ?? []).filter { $0.zeitraum == zeitraum }
        let ue = Statistik.ueberweisungen(Statistik.abrechnung(fahrten: fahrten, tankungen: tank, kostenProKm: kpk,
                                                               von: von, bis: bis, ausgleiche: ausgleiche))
        let namen = Dictionary(((try? context.fetch(FetchDescriptor<Benutzer>())) ?? []).map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
        d.set(zeitraum, forKey: "abrechnungGemeldet")
        guard let n = Self.nachricht(ue, ich: a.userId, namen: namen, monat: Calendar.current.component(.month, from: von) - 1) else { return }
        let inhalt = UNMutableNotificationContent()
        inhalt.title = n.titel
        inhalt.body = n.text
        if let u = n.bezahlen {
            inhalt.categoryIdentifier = Self.kategorie
            inhalt.userInfo = ["von": u.von, "an": u.an, "betrag": u.betrag, "zeitraum": zeitraum]
        }
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "abrechnung", content: inhalt, trigger: nil))
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        if response.actionIdentifier == FahrtFrage.ichFahre {
            let auto = response.notification.request.content.userInfo["fahrzeug"] as? String
            await MainActor.run {
                // Läuft schon eine (vorläufige) Fahrt, bestätigt "Ich fahre" sie nur (Roadmap Nr. 6.3).
                if FahrtRecorder.shared.laufend != nil { FahrtRecorder.shared.bestaetigen() }
                else { Task { try? await FahrtRecorder.shared.starten(fahrzeugId: auto) } }
            }
            return
        }
        if response.actionIdentifier == FahrtFrage.nein {
            await MainActor.run { Task { await FahrtRecorder.shared.verwerfen() } }
            return
        }
        guard response.actionIdentifier == Self.bezahlt else { return }
        let i = response.notification.request.content.userInfo
        guard let von = i["von"] as? String, let an = i["an"] as? String, let betrag = i["betrag"] as? Double, betrag > 0,
              let zeitraum = i["zeitraum"] as? String else { return }
        await MainActor.run {
            let c = Datenbank.container.mainContext
            c.insert(Ausgleich(von: von, an: an, betrag: betrag, zeitraum: zeitraum))
            try? c.save()
        }
        await SyncManager.shared.sync()
    }

    /// Auch bei offener App als Banner zeigen.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list]
    }
}
