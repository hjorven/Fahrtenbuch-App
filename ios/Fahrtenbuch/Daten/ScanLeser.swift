import Foundation

/// Wertet den Text aus, den die Texterkennung (Vision, auf dem Handy) auf einem Foto gefunden hat.
/// Reine Logik ohne Kamera, damit sie getestet werden kann. Dieselben Regeln stehen in
/// Android data/ScanLeser.kt.
enum ScanLeser {

    /// Was auf einem Tank- oder Ladebeleg erkannt wurde. Alles optional.
    struct Beleg: Equatable {
        var menge: Double?
        /// "L" oder "kWh".
        var einheit: String?
        var preisProEinheit: Double?
        var gesamt: Double?
        /// Tag vom Beleg (12 Uhr Ortszeit).
        var datum: Date?
        var tankstelle: String?

        var leer: Bool { menge == nil && preisProEinheit == nil && gesamt == nil }
    }

    // MARK: Hilfen für reguläre Ausdrücke

    private static func re(_ muster: String, _ optionen: NSRegularExpression.Options = [.caseInsensitive]) -> NSRegularExpression {
        try! NSRegularExpression(pattern: muster, options: optionen)
    }

    /// Alle Treffer mit ihren Gruppen (Gruppe 0 = ganzer Treffer, fehlende Gruppen = "").
    private static func treffer(_ r: NSRegularExpression, _ s: String) -> [[String]] {
        let ns = s as NSString
        return r.matches(in: s, range: NSRange(location: 0, length: ns.length)).map { m in
            (0..<m.numberOfRanges).map { i in
                let rg = m.range(at: i)
                return rg.location == NSNotFound ? "" : ns.substring(with: rg)
            }
        }
    }

    private static func passt(_ r: NSRegularExpression, _ s: String) -> Bool {
        r.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil
    }

    private static func dezimal(_ s: String) -> Double? {
        Double(s.replacingOccurrences(of: " ", with: "").replacingOccurrences(of: ",", with: "."))
    }

    private static func runde(_ x: Double, _ stellen: Int) -> Double {
        let f = pow(10.0, Double(stellen))
        return (x * f).rounded() / f
    }

    // MARK: Beleg

    private static let zahl = re(#"(?<![\d.,])(\d{1,4})\s?[.,]\s?(\d{1,3})(?!\d)"#)
    private static let mengeLiter = re(#"(\d{1,3}[.,]\d{1,3})\s*(?:l|ltr|liter|litre)\b"#)
    private static let mengeKwh = re(#"(\d{1,3}[.,]\d{1,4})\s*kwh\b"#)
    private static let preisEinheit = re(#"(\d[.,]\d{2,3})\s*(?:eur|€)?\s*/\s*(l|ltr|liter|kwh)\b"#)
    private static let malPreis = re(#"(?:x|\*|à|@)\s*(\d[.,]\d{2,3})"#)
    private static let datumDE = re(#"(?<!\d)(\d{1,2})\.(\d{1,2})\.(\d{4}|\d{2})(?!\d)"#)
    private static let datumISO = re(#"(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)"#)
    private static let summeWorte = ["zu zahlen", "summe", "gesamtbetrag", "gesamt", "total", "betrag"]
    private static let zahlWorte = ["girocard", "ec-karte", "ec karte", "kartenzahlung", "bar", "visa", "mastercard", "gegeben"]
    private static let nichtSumme = ["mwst", "ust", "netto", "steuer", "rückgeld", "rueckgeld", "zurück"]
    private static let marken = [
        "TotalEnergies", "Aral", "Shell", "Esso", "Total", "JET", "Avia", "Agip", "Eni", "BFT", "Star",
        "Orlen", "OMV", "HEM", "Westfalen", "Raiffeisen", "Tamoil", "Hoyer", "Classic", "Sprint", "Q1",
        "Globus", "Score", "Freie Tankstelle", "Ionity", "EnBW", "Allego", "Tesla", "Fastned", "EWE Go",
        "Mer", "Pfalzwerke", "E.ON", "Maingau", "Lidl", "Kaufland", "Aldi",
    ]

    /// [zeilen]: Textzeilen von oben nach unten, Nebeneinanderstehendes schon zusammengefügt.
    /// [elektro]: beim E-Auto wird nach kWh gesucht, sonst nach Litern.
    static func beleg(_ zeilen: [String], elektro: Bool, heute: Date = Date()) -> Beleg {
        let text = zeilen.map { $0.replacingOccurrences(of: "\u{00a0}", with: " ").trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        let mengeRe = elektro ? mengeKwh : mengeLiter
        let maxMenge = Plausibel.maxMenge
        let maxPreis = Plausibel.maxPreisEinheit

        var menge: Double? = text.lazy.compactMap { z in
            treffer(mengeRe, z).compactMap { dezimal($0[1]) }.first { $0 >= 0.5 && $0 <= maxMenge }
        }.first
        var preis: Double? = text.lazy.compactMap { z in
            treffer(preisEinheit, z)
                .filter { ($0[2].lowercased() == "kwh") == elektro }
                .compactMap { dezimal($0[1]) }
                .first { $0 >= 0.05 && $0 <= maxPreis }
        }.first
        if preis == nil {
            preis = text.lazy.compactMap { z -> Double? in
                guard passt(mengeRe, z) else { return nil }
                return treffer(malPreis, z).compactMap { dezimal($0[1]) }.first { $0 >= 0.05 && $0 <= maxPreis }
            }.first
        }
        var gesamt = summe(text)

        // Probe: Menge mal Preis muss die Summe ergeben, sonst ein Trio suchen, das aufgeht.
        let stimmt: Bool = {
            guard let m = menge, let p = preis, let g = gesamt else { return false }
            return abs(m * p - g) <= 0.06
        }()
        if !stimmt, let t = trio(text, elektro: elektro, menge: menge, preis: preis, gesamt: gesamt) {
            menge = t.0; preis = t.1; gesamt = t.2
        }
        if menge == nil, let p = preis, let g = gesamt, p > 0 {
            let m = runde(g / p, 2)
            if m >= 0.5 && m <= maxMenge { menge = m }
        }
        if preis == nil, let m = menge, let g = gesamt, m > 0 {
            let p = runde(g / m, 3)
            if p >= 0.05 && p <= maxPreis { preis = p }
        }
        if gesamt == nil, let m = menge, let p = preis { gesamt = runde(m * p, 2) }

        return Beleg(menge: menge, einheit: menge != nil ? (elektro ? "kWh" : "L") : nil,
                     preisProEinheit: preis, gesamt: gesamt,
                     datum: datum(text, heute: heute), tankstelle: tankstelle(text))
    }

    private static func zweiStellen(_ z: String) -> [Double] {
        treffer(zahl, z).filter { $0[2].count == 2 }.compactMap { dezimal($0[0]) }
    }

    private static func summe(_ text: [String]) -> Double? {
        for worte in [summeWorte, zahlWorte] {
            for (i, z) in text.enumerated() {
                let klein = z.lowercased()
                if !worte.contains(where: { klein.contains($0) }) || nichtSumme.contains(where: { klein.contains($0) }) { continue }
                var betrag = zweiStellen(z).last
                if betrag == nil, i + 1 < text.count {
                    let n = text[i + 1]
                    if n.filter(\.isLetter).count <= 4 { betrag = zweiStellen(n).last }
                }
                if let b = betrag, b > 0, b < 1000 { return b }
            }
        }
        return nil
    }

    private static func trio(_ text: [String], elektro: Bool, menge: Double?, preis: Double?, gesamt: Double?) -> (Double, Double, Double)? {
        var zahlen: [Double] = []
        for z in text {
            for t in treffer(zahl, z) {
                if let d = dezimal(t[0]), !zahlen.contains(d) { zahlen.append(d) }
            }
        }
        let mengen = menge.map { [$0] } ?? zahlen.filter { $0 >= 0.5 && $0 <= Plausibel.maxMenge }
        let preise = preis.map { [$0] } ?? zahlen.filter { $0 >= 0.05 && $0 <= Plausibel.maxPreisEinheit }
        let summen = (gesamt.map { [$0] } ?? []) + zahlen
        var bestes: (Double, Double, Double)?
        var besteGuete = Double.greatestFiniteMagnitude
        for m in mengen {
            for p in preise where p != m {
                let soll = m * p
                for g in summen where g != m && g != p && g >= 1 {
                    let abw = abs(soll - g)
                    if abw > 0.06 { continue }
                    let guete = abw + (gesamt != nil && g == gesamt ? -1 : 0)
                    if guete < besteGuete {
                        besteGuete = guete
                        bestes = (m, p, g)
                    }
                }
            }
        }
        guard let b = bestes, elektro || b.1 >= 0.5 else { return nil }
        return b
    }

    private static func datum(_ text: [String], heute: Date) -> Date? {
        let kal = Calendar(identifier: .gregorian)
        for z in text {
            var tmj: (Int, Int, Int)?
            if let t = treffer(datumDE, z).first, let tag = Int(t[1]), let monat = Int(t[2]), var jahr = Int(t[3]) {
                if jahr < 100 { jahr += 2000 }
                tmj = (tag, monat, jahr)
            } else if let t = treffer(datumISO, z).first, let jahr = Int(t[1]), let monat = Int(t[2]), let tag = Int(t[3]) {
                tmj = (tag, monat, jahr)
            }
            guard let tmj, (1...31).contains(tmj.0), (1...12).contains(tmj.1) else { continue }
            let (tag, monat, jahr) = tmj
            var k = DateComponents()
            k.year = jahr; k.month = monat; k.day = tag; k.hour = 12
            guard let d = kal.date(from: k) else { continue }
            // Nur plausible Daten: nicht in der Zukunft, höchstens ein Jahr alt.
            if d <= heute.addingTimeInterval(86_400) && d >= heute.addingTimeInterval(-366 * 86_400) { return d }
        }
        return nil
    }

    private static func tankstelle(_ text: [String]) -> String? {
        // Nur der Kopf des Bons und nur Zeilen ohne Beträge, sonst wird "TOTAL 80,92" zur Marke Total.
        let oben = text.prefix(6).filter { !passt(zahl, $0) }
        for marke in marken {
            let r = re("(?<![A-Za-zÄÖÜäöü])" + NSRegularExpression.escapedPattern(for: marke) + "(?![A-Za-zÄÖÜäöü])")
            if oben.contains(where: { passt(r, $0) }) { return marke }
        }
        return nil
    }

    // MARK: Tacho

    /// Ein Textstück vom Tacho-Foto mit seiner Höhe (größer = auffälliger).
    struct TachoText {
        var text: String
        var hoehe: Double = 1
    }

    private static let ziffern = re(#"\d{1,3}(?:[ .'’]\d{3})+|\d{3,7}"#, [])
    private static let tagesKm = re(#"^\s?[.,]\d(?!\d{2})"#, [])
    private static let uhrzeit = re(#"^\s?:\d"#, [])
    private static let andereEinheit = re(#"^\s?(°|km/h|kmh|mph|u/min|rpm|%)"#)
    private static let kmDanach = re(#"^\s?km\b"#)

    /// Mögliche km-Stände, bester zuerst (höchstens drei). [bisher] = letzter bekannter km-Stand
    /// (0 = unbekannt): Der Tacho kann nur höher stehen, und meist nur wenig.
    static func tacho(_ texte: [TachoText], bisher: Double) -> [Int] {
        struct Kandidat { let km: Int; let hoehe: Double; let mitKm: Bool }
        var kandidaten: [Kandidat] = []
        for t in texte {
            let roh = t.text
                .replacingOccurrences(of: "O", with: "0").replacingOccurrences(of: "o", with: "0")
                .replacingOccurrences(of: "I", with: "1").replacingOccurrences(of: "|", with: "1")
            let ns = roh as NSString
            for m in ziffern.matches(in: roh, range: NSRange(location: 0, length: ns.length)) {
                let nach = ns.substring(from: m.range.location + m.range.length)
                let vor = ns.substring(to: m.range.location)
                if passt(tagesKm, nach) || passt(uhrzeit, nach) || vor.hasSuffix(":") || passt(andereEinheit, nach) { continue }
                guard let km = Int(ns.substring(with: m.range).filter(\.isNumber)),
                      km >= 10, Double(km) <= Plausibel.maxKmStand else { continue }
                kandidaten.append(Kandidat(km: km, hoehe: t.hoehe, mitKm: passt(kmDanach, nach)))
            }
        }
        guard let maxHoehe = kandidaten.map(\.hoehe).max() else { return [] }
        let h = max(maxHoehe, 0.0001)

        func punkte(_ k: Kandidat) -> Double {
            var p = k.hoehe / h + (k.mitKm ? 1 : 0)
            if bisher > 0 {
                let diff = Double(k.km) - bisher
                if diff < -5 { p -= 10 }                     // Tacho läuft nicht rückwärts
                else if diff <= 3000 { p += 3 - diff / 3000 } // das Übliche
                else if diff <= 30000 { p += 0.5 }
                else { p -= 5 }
            } else {
                p += Double(String(k.km).count) * 0.4         // ohne Vorwissen: längere Zahlen eher Tacho
            }
            return p
        }

        var beste: [Int: Double] = [:]
        for k in kandidaten { beste[k.km] = max(beste[k.km] ?? -.greatestFiniteMagnitude, punkte(k)) }
        return beste.filter { $0.value > -5 }
            .sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
            .prefix(3)
            .map(\.key)
    }
}
