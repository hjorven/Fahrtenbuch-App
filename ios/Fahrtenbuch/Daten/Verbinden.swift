import Foundation

/// QR-Code zum Verbinden (Roadmap Nr. 1/2), gleiches Format wie Android:
/// `fahrtenbuch://verbinden?heim=<url>&tail=<url>&dl=<url>[&einladung=<code>|&reset=<code>]`.
/// Zweiter Weg (Nachtplan 5c): Adressen und Code lassen sich im Login abtippen.
enum Verbinden {
    struct Daten: Equatable {
        var heim = "", tail = "", dl = "", einladung = "", reset = ""
    }

    static let anfang = "fahrtenbuch://verbinden"

    static func lesen(_ text: String) -> Daten? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix(anfang), let teile = URLComponents(string: t) else { return nil }
        var w: [String: String] = [:]
        for q in teile.queryItems ?? [] { w[q.name] = q.value ?? "" }
        return Daten(heim: w["heim"] ?? "", tail: w["tail"] ?? "", dl: w["dl"] ?? "",
                     einladung: code(w["einladung"] ?? ""), reset: code(w["reset"] ?? ""))
    }

    static func bauen(_ d: Daten) -> String {
        var c = URLComponents(string: anfang)!
        let paare = [("heim", d.heim), ("tail", d.tail), ("dl", d.dl), ("einladung", d.einladung), ("reset", d.reset)]
            .filter { !$0.1.isEmpty }
        if !paare.isEmpty { c.queryItems = paare.map { URLQueryItem(name: $0.0, value: $0.1) } }
        // URLComponents kodiert ":" und "/" in Werten nicht; Android und der Parser kommen mit beidem klar.
        return c.string ?? anfang
    }

    /// Abgetippter Code: Leerzeichen und Bindestriche weg, Großbuchstaben, genau 12 Zeichen.
    static func code(_ eingabe: String) -> String {
        let c = eingabe.uppercased().filter { $0.isLetter || $0.isNumber }
        return c.count == 12 ? c : ""
    }
}
