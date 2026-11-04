import Foundation
import SwiftData

// Lokale Kopien der PocketBase-Tabellen (siehe docs/DATENMODELL.md).
// dirty = lokal geändert, noch nicht hochgeladen. aufServer = existiert schon auf dem Server.

@Model
final class Fahrzeug {
    @Attribute(.unique) var id: String
    var name: String
    var kennzeichen: String
    var kraftstoff: String
    var kmStand: Double
    var preisProKm: Double
    var farbe: String
    var aktiv: Bool
    var dirty: Bool
    var aufServer: Bool

    init(id: String, name: String, kennzeichen: String = "", kraftstoff: String = "", kmStand: Double = 0,
         preisProKm: Double = 0, farbe: String = "#1565C0", aktiv: Bool = true, dirty: Bool = false, aufServer: Bool = true) {
        self.id = id
        self.name = name
        self.kennzeichen = kennzeichen
        self.kraftstoff = kraftstoff
        self.kmStand = kmStand
        self.preisProKm = preisProKm
        self.farbe = farbe
        self.aktiv = aktiv
        self.dirty = dirty
        self.aufServer = aufServer
    }
}

@Model
final class Benutzer {
    @Attribute(.unique) var id: String
    var name: String

    init(id: String, name: String) {
        self.id = id
        self.name = name
    }
}

@Model
final class Fahrt {
    @Attribute(.unique) var id: String
    var fahrzeugId: String
    var fahrerId: String
    var start: Date
    var ende: Date?
    var kmStart: Double
    var kmEnde: Double
    var streckeKm: Double
    var maxKmh: Double
    var schnittKmh: Double
    var zweck: String
    var startOrt: String
    var zielOrt: String
    var route: String
    var notiz: String
    var geloescht: Bool
    /// Im Test-Modus aufgezeichnet: zählt nirgends mit und hebt den km-Stand nicht an.
    var test: Bool = false
    /// Nur bei Test-Fahrten: Diagnose als JSON (siehe Fahrt/Diagnose.swift).
    var diagnose: String = ""
    var dirty: Bool
    var aufServer: Bool
    /// Mitfahrer (Benutzer-IDs). Setzt nur der Server (POST /api/fahrtenbuch/mitfahren), Roadmap Nr. 6.
    var mitfahrer: [String] = []
    /// Automatisch gestartet und noch nicht bestätigt: zählt nirgends.
    var vorlaeufig: Bool = false

    init(id: String = neueId(), fahrzeugId: String, fahrerId: String, start: Date, ende: Date? = nil,
         kmStart: Double = 0, kmEnde: Double = 0, streckeKm: Double = 0, maxKmh: Double = 0, schnittKmh: Double = 0,
         zweck: String = "privat", startOrt: String = "", zielOrt: String = "", route: String = "", notiz: String = "",
         geloescht: Bool = false, dirty: Bool = true, aufServer: Bool = false) {
        self.id = id
        self.fahrzeugId = fahrzeugId
        self.fahrerId = fahrerId
        self.start = start
        self.ende = ende
        self.kmStart = kmStart
        self.kmEnde = kmEnde
        self.streckeKm = streckeKm
        self.maxKmh = maxKmh
        self.schnittKmh = schnittKmh
        self.zweck = zweck
        self.startOrt = startOrt
        self.zielOrt = zielOrt
        self.route = route
        self.notiz = notiz
        self.geloescht = geloescht
        self.dirty = dirty
        self.aufServer = aufServer
    }
}

@Model
final class Tankvorgang {
    @Attribute(.unique) var id: String
    var fahrzeugId: String
    var fahrerId: String
    var datum: Date
    var kmStand: Double
    var liter: Double
    var preisProLiter: Double
    var gesamtpreis: Double
    var vollgetankt: Bool
    var tankstelle: String
    /// Nur bei E-Autos: "zuhause" oder "oeffentlich". Dann steht liter für kWh.
    var ladeort: String = ""
    var notiz: String
    var geloescht: Bool
    /// Im Test-Modus eingetragen: zählt nirgends mit.
    var test: Bool = false
    /// Dateiname des Belegfotos auf dem Server ("" = keins oder noch nicht hochgeladen).
    var beleg: String = ""
    /// Lokales Belegfoto (Belege.datei) muss noch hochgeladen werden.
    var belegOffen: Bool = false
    var dirty: Bool
    var aufServer: Bool

    init(id: String = neueId(), fahrzeugId: String, fahrerId: String, datum: Date, kmStand: Double, liter: Double,
         preisProLiter: Double = 0, gesamtpreis: Double = 0, vollgetankt: Bool = true, tankstelle: String = "",
         ladeort: String = "", notiz: String = "", geloescht: Bool = false, dirty: Bool = true, aufServer: Bool = false) {
        self.id = id
        self.fahrzeugId = fahrzeugId
        self.fahrerId = fahrerId
        self.datum = datum
        self.kmStand = kmStand
        self.liter = liter
        self.preisProLiter = preisProLiter
        self.gesamtpreis = gesamtpreis
        self.vollgetankt = vollgetankt
        self.tankstelle = tankstelle
        self.ladeort = ladeort
        self.notiz = notiz
        self.geloescht = geloescht
        self.dirty = dirty
        self.aufServer = aufServer
    }
}

/// Ausgleichszahlung für die Monatsabrechnung: `von` hat `an` den Betrag für `zeitraum` ("2026-10") bezahlt.
@Model
final class Ausgleich {
    @Attribute(.unique) var id: String
    var von: String
    var an: String
    var betrag: Double
    var zeitraum: String
    var datum: Date
    var notiz: String
    var geloescht: Bool
    var dirty: Bool
    var aufServer: Bool

    init(id: String = neueId(), von: String, an: String, betrag: Double, zeitraum: String, datum: Date = Date(),
         notiz: String = "", geloescht: Bool = false, dirty: Bool = true, aufServer: Bool = false) {
        self.id = id
        self.von = von
        self.an = an
        self.betrag = betrag
        self.zeitraum = zeitraum
        self.datum = datum
        self.notiz = notiz
        self.geloescht = geloescht
        self.dirty = dirty
        self.aufServer = aufServer
    }
}

extension Fahrt {
    /// Gefahrene km: km_ende - km_start, wenn beide plausibel sind (z. B. nach Tacho-Abgleich),
    /// sonst die GPS-Strecke. Dieselbe Regel nutzen Android und das Display.
    var km: Double { kmStart > 0 && kmEnde > kmStart ? kmEnde - kmStart : streckeKm }
}

extension Tankvorgang {
    /// Laden zuhause kostet nichts, unabhängig vom eingetragenen Preis.
    var kosten: Double { ladeort == "zuhause" ? 0 : gesamtpreis }
}

/// PocketBase-kompatible ID (15 Zeichen, a-z0-9), auf dem Gerät erzeugt.
func neueId() -> String {
    let zeichen = Array("abcdefghijklmnopqrstuvwxyz0123456789")
    return String((0..<15).map { _ in zeichen.randomElement()! })
}

/// Datumsformat von PocketBase: "2026-11-02 07:00:00.000Z" (immer UTC).
enum PbDatum {
    private static let format: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS'Z'"
        return f
    }()

    static func text(_ d: Date?) -> String {
        guard let d else { return "" }
        return format.string(from: d)
    }

    static func datum(_ s: String?) -> Date? {
        guard let s, !s.isEmpty else { return nil }
        return format.date(from: s)
    }
}

// MARK: Antrieb

extension Fahrzeug {
    /// Antriebsart steht in kraftstoff: benzin, diesel, elektro oder sonstiges.
    var elektrisch: Bool { kraftstoff == "elektro" }
    /// Einheit der Tank- bzw. Lademenge.
    var einheit: String { elektrisch ? "kWh" : "L" }
}

enum Antrieb {
    static let arten: [(wert: String, text: String)] = [
        ("diesel", "Diesel"), ("benzin", "Benzin"), ("elektro", "Elektro"), ("sonstiges", "Sonstiges"),
    ]
    static let ladeorte: [(wert: String, text: String)] = [("zuhause", "Zuhause"), ("oeffentlich", "Öffentlich")]

    /// Schlüssel für den Namensabgleich mit dem Server.
    static func namensSchluessel(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespaces).lowercased()
    }
}
