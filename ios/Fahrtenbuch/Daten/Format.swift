import SwiftUI

enum F {
    static let de = Locale(identifier: "de_DE")

    static func km(_ v: Double) -> String { v.formatted(.number.precision(.fractionLength(1)).locale(de)) + " km" }
    static func euro(_ v: Double) -> String { v.formatted(.currency(code: "EUR").locale(de)) }
    static func zahl(_ v: Double, _ stellen: Int = 1) -> String {
        v.formatted(.number.precision(.fractionLength(stellen)).grouping(.never).locale(de))
    }
    static func kmStand(_ v: Double) -> String { v.formatted(.number.precision(.fractionLength(0)).locale(de)) + " km" }
    static func uhrzeit(_ d: Date) -> String { d.formatted(.dateTime.hour().minute().locale(de)) }
    static func tag(_ d: Date) -> String { d.formatted(.dateTime.weekday(.wide).day().month(.wide).year().locale(de)) }
    static func datum(_ d: Date) -> String { d.formatted(.dateTime.day().month(.twoDigits).year().locale(de)) }
    static func monat(_ d: Date) -> String { d.formatted(.dateTime.month(.wide).year().locale(de)) }

    static func dauer(_ s: TimeInterval) -> String {
        let min = Int(s / 60)
        return min < 60 ? "\(min) min" : "\(min / 60) h \(min % 60) min"
    }

    /// Akzeptiert Komma und Punkt als Dezimaltrenner.
    static func zahl(aus text: String) -> Double? {
        Double(text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: "."))
    }

    static let zwecke: [(String, String)] = [("privat", "Privat"), ("geschaeftlich", "Geschäftlich"), ("sonstiges", "Sonstiges")]
}

extension Color {
    init(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespaces)
        if s.hasPrefix("#") { s.removeFirst() }
        let v = UInt64(s, radix: 16) ?? 0x1565C0
        self.init(red: Double((v >> 16) & 0xff) / 255, green: Double((v >> 8) & 0xff) / 255, blue: Double(v & 0xff) / 255)
    }
}
