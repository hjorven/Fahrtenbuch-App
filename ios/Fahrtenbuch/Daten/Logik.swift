import CoreLocation
import Foundation

/// Encoded Polyline (Google-Format, Präzision 5), wie im Datenmodell festgelegt.
enum Polyline {
    static func encode(_ punkte: [CLLocationCoordinate2D]) -> String {
        var out = ""
        var lastLat = 0
        var lastLon = 0
        for p in punkte {
            let lat = Int((p.latitude * 1e5).rounded())
            let lon = Int((p.longitude * 1e5).rounded())
            wert(lat - lastLat, &out)
            wert(lon - lastLon, &out)
            lastLat = lat
            lastLon = lon
        }
        return out
    }

    private static func wert(_ v: Int, _ out: inout String) {
        var x = v < 0 ? ~(v << 1) : (v << 1)
        while x >= 0x20 {
            out.unicodeScalars.append(UnicodeScalar(UInt8((0x20 | (x & 0x1f)) + 63)))
            x >>= 5
        }
        out.unicodeScalars.append(UnicodeScalar(UInt8(x + 63)))
    }

    static func decode(_ text: String) -> [CLLocationCoordinate2D] {
        let bytes = Array(text.utf8)
        var i = 0
        var lat = 0
        var lon = 0
        var result: [CLLocationCoordinate2D] = []
        func lese() -> Int? {
            var shift = 0
            var r = 0
            while i < bytes.count {
                let b = Int(bytes[i]) - 63
                i += 1
                r |= (b & 0x1f) << shift
                shift += 5
                if b < 0x20 { return (r & 1) != 0 ? ~(r >> 1) : (r >> 1) }
            }
            return nil
        }
        while i < bytes.count {
            guard let dLat = lese(), let dLon = lese() else { break }
            lat += dLat
            lon += dLon
            result.append(CLLocationCoordinate2D(latitude: Double(lat) / 1e5, longitude: Double(lon) / 1e5))
        }
        return result
    }
}

/// Verbrauch und Kostenaufteilung wie in docs/PLAN.md (gleiche Rechnung wie in der Android-App).
enum Statistik {
    struct Verbrauch {
        let literPro100km: Double
        let kostenProKm: Double
    }

    /// Beim E-Auto zählt jeder Ladevorgang (gleitend über die letzten 5), sonst nur volle Tankungen.
    /// Laden zuhause zählt 0 Euro.
    static func verbrauch(_ tankungen: [Tankvorgang], anzahlVolle: Int = 3, elektrisch: Bool = false) -> Verbrauch? {
        let s = tankungen.filter { !$0.geloescht }.sorted { $0.kmStand < $1.kmStand }
        let volle = elektrisch ? Array(s.indices) : s.indices.filter { s[$0].vollgetankt }
        let anzahl = elektrisch ? 5 : anzahlVolle
        guard volle.count >= 2, let bis = volle.last else { return nil }
        let von = volle[max(0, volle.count - 1 - anzahl)]
        let km = s[bis].kmStand - s[von].kmStand
        guard km > 0 else { return nil }
        let abschnitt = s[(von + 1)...bis]
        let liter = abschnitt.reduce(0.0) { $0 + $1.liter }
        let kosten = abschnitt.reduce(0.0) { $0 + $1.kosten }
        return Verbrauch(literPro100km: liter / km * 100, kostenProKm: kosten / km)
    }

    struct VerbrauchsPunkt: Identifiable {
        let datum: Date
        let wert: Double
        var id: Date { datum }
    }

    /// Verbrauch je Abschnitt zwischen zwei vollen Tankungen (E-Auto: zwischen zwei Ladungen).
    static func verbrauchVerlauf(_ tankungen: [Tankvorgang], elektrisch: Bool = false) -> [VerbrauchsPunkt] {
        let s = tankungen.filter { !$0.geloescht }.sorted { $0.kmStand < $1.kmStand }
        var aus: [VerbrauchsPunkt] = []
        var letzteVolle: Tankvorgang?
        var menge = 0.0
        for t in s {
            if letzteVolle != nil { menge += t.liter }
            if elektrisch || t.vollgetankt {
                if let v = letzteVolle, t.kmStand > v.kmStand {
                    let wert = menge / (t.kmStand - v.kmStand) * 100
                    // Ausreißer (falsch getippter km-Stand) nicht in den Verlauf.
                    if (1...50).contains(wert) { aus.append(VerbrauchsPunkt(datum: t.datum, wert: wert)) }
                }
                letzteVolle = t
                menge = 0
            }
        }
        return aus
    }

    static func kostenProKm(_ f: Fahrzeug, _ tankungen: [Tankvorgang]) -> Double? {
        f.preisProKm > 0 ? f.preisProKm : verbrauch(tankungen, elektrisch: f.elektrisch)?.kostenProKm
    }

    struct Saldo: Identifiable {
        let fahrerId: String
        let km: Double
        let anteil: Double
        let bezahlt: Double
        /// Ausgleich: + selbst überwiesen, - bekommen.
        var ausgleich: Double = 0
        var id: String { fahrerId }
        /// positiv = bekommt Geld zurück, negativ = muss zahlen
        var saldo: Double { bezahlt + ausgleich - anteil }
    }

    /// Abrechnung über alle Autos. Laufende Fahrten zählen noch nicht.
    static func abrechnung(fahrten: [Fahrt], tankungen: [Tankvorgang], kostenProKm: [String: Double],
                           von: Date, bis: Date, ausgleiche: [Ausgleich] = []) -> [Saldo] {
        // Vorläufige Fahrten zählen erst nach Bestätigung (Roadmap Nr. 6).
        let f = fahrten.filter { !$0.geloescht && !$0.vorlaeufig && $0.ende != nil && $0.start >= von && $0.start < bis }
        let t = tankungen.filter { !$0.geloescht && $0.datum >= von && $0.datum < bis }
        let a = ausgleiche.filter { !$0.geloescht }
        let fahrer = Set(f.flatMap { [$0.fahrerId] + $0.mitfahrer } + t.map(\.fahrerId) + a.flatMap { [$0.von, $0.an] }).sorted()
        return fahrer.map { id in
            let eigene = f.filter { $0.fahrerId == id }
            // Mit Mitfahrern teilen sich alle den Anteil gleichmäßig, die km zählen nur beim Fahrer.
            let beteiligt = f.filter { $0.fahrerId == id || $0.mitfahrer.contains(id) }
            let gezahlt = a.filter { $0.von == id }.reduce(0.0) { $0 + $1.betrag }
            let erhalten = a.filter { $0.an == id }.reduce(0.0) { $0 + $1.betrag }
            return Saldo(
                fahrerId: id,
                km: eigene.reduce(0.0) { $0 + $1.km },
                anteil: beteiligt.reduce(0.0) { $0 + $1.km * (kostenProKm[$1.fahrzeugId] ?? 0) / Double(1 + $1.mitfahrer.count) },
                bezahlt: t.filter { $0.fahrerId == id }.reduce(0.0) { $0 + $1.kosten },
                ausgleich: gezahlt - erhalten
            )
        }
    }

    struct Ueberweisung: Equatable, Identifiable {
        let von: String
        let an: String
        let betrag: Double
        var id: String { von + ">" + an }
    }

    /// Wer muss wem wie viel zahlen, damit alle bei 0 sind (wenige Überweisungen, größte zuerst).
    static func ueberweisungen(_ salden: [Saldo]) -> [Ueberweisung] {
        var schuldner = salden.filter { $0.saldo < -0.005 }.map { ($0.fahrerId, -$0.saldo) }.sorted { $0.1 > $1.1 }
        var glaeubiger = salden.filter { $0.saldo > 0.005 }.map { ($0.fahrerId, $0.saldo) }.sorted { $0.1 > $1.1 }
        var aus: [Ueberweisung] = []
        var i = 0
        var j = 0
        while i < schuldner.count && j < glaeubiger.count {
            let betrag = min(schuldner[i].1, glaeubiger[j].1)
            if betrag > 0.005 {
                aus.append(Ueberweisung(von: schuldner[i].0, an: glaeubiger[j].0, betrag: (betrag * 100).rounded() / 100))
            }
            schuldner[i].1 -= betrag
            glaeubiger[j].1 -= betrag
            if schuldner[i].1 <= 0.005 { i += 1 }
            if glaeubiger[j].1 <= 0.005 { j += 1 }
        }
        return aus
    }

    /// "2026-10" für den Monat von `d` (lokale Zeit).
    static func zeitraum(_ d: Date) -> String {
        let c = Calendar.current.dateComponents([.year, .month], from: d)
        return String(format: "%04d-%02d", c.year ?? 0, c.month ?? 0)
    }

    /// km pro Monat (Index 0 = Januar) im Jahr `jahr` für ein Auto oder alle (nil).
    static func kmProMonat(_ fahrten: [Fahrt], jahr: Int, fahrzeugId: String? = nil) -> [Double] {
        var aus = Array(repeating: 0.0, count: 12)
        let cal = Calendar.current
        for f in fahrten where !f.geloescht && f.ende != nil && (fahrzeugId == nil || f.fahrzeugId == fahrzeugId) {
            let c = cal.dateComponents([.year, .month], from: f.start)
            if c.year == jahr, let m = c.month { aus[m - 1] += f.km }
        }
        return aus
    }

    struct TachoAbweichung {
        let fahrten: Int
        let gpsKm: Double
        let tachoKm: Double
        /// positiv = GPS zählt zu viel
        var prozent: Double { tachoKm > 0 ? (gpsKm - tachoKm) / tachoKm * 100 : 0 }
    }

    /// Fahrten, deren km per Tacho korrigiert wurden: Wie weit liegt GPS daneben?
    static func tachoAbweichung(_ fahrten: [Fahrt]) -> TachoAbweichung? {
        let k = fahrten.filter {
            !$0.geloescht && $0.ende != nil && $0.streckeKm > 0.5 && $0.kmStart > 0 && $0.kmEnde > $0.kmStart
                && abs(($0.kmEnde - $0.kmStart) - $0.streckeKm) > 0.05
        }
        guard !k.isEmpty else { return nil }
        return TachoAbweichung(fahrten: k.count, gpsKm: k.reduce(0) { $0 + $1.streckeKm },
                               tachoKm: k.reduce(0) { $0 + ($1.kmEnde - $1.kmStart) })
    }
}

/// Dieselben Grenzen wie im Server-Hook server/pb_hooks/plausibel.js und in der Android-App.
enum Plausibel {
    static let maxMenge = 200.0
    static let maxPreisEinheit = 5.0
    static let maxKmStand = 2_000_000.0
    /// Schneller im Schnitt (Tacho-km durch Fahrzeit) ist ein Tippfehler im km-Stand.
    static let maxSchnittKmh = 250.0
    /// Kürzere Fahrten sind Fehlstarts und werden verworfen.
    static let minFahrtKm = 0.3

    /// nil = in Ordnung, sonst der Fehlertext.
    static func tank(menge: Double?, preisProEinheit: Double?, kmStand: Double?) -> String? {
        // 0 lehnt der Server ab (Pflichtfeld), die Tankung bliebe sonst im Sync hängen.
        guard let kmStand, kmStand != 0 else { return "Kilometerstand fehlt" }
        if kmStand < 0 || kmStand > maxKmStand { return "Kilometerstand ist unplausibel" }
        guard let menge, menge > 0 else { return "Menge fehlt" }
        if menge > maxMenge { return "Mehr als \(Int(maxMenge)) auf einmal ist unplausibel" }
        if let p = preisProEinheit, p > maxPreisEinheit { return "Mehr als \(Int(maxPreisEinheit)) € pro Einheit ist unplausibel" }
        return nil
    }

    /// Spielraum für Uhren, die etwas vorgehen.
    static let zukunftSpielraum: TimeInterval = 600

    static func fahrt(start: Date, ende: Date?, kmStart: Double, kmEnde: Double, jetzt: Date = Date()) -> String? {
        if let ende, ende < start { return "Das Ende liegt vor dem Start" }
        let grenze = jetzt.addingTimeInterval(zukunftSpielraum)
        if start > grenze || (ende ?? start) > grenze { return "Die Fahrt liegt in der Zukunft" }
        if kmStart < 0 || kmEnde < 0 { return "Kilometer dürfen nicht negativ sein" }
        if kmEnde > 0 && kmEnde < kmStart { return "km am Ende kleiner als am Start" }
        if kmEnde > maxKmStand { return "Kilometerstand ist unplausibel" }
        if let ende, kmStart > 0, kmEnde > kmStart {
            let stunden = max(ende.timeIntervalSince(start), 60) / 3600
            if (kmEnde - kmStart) / stunden > maxSchnittKmh {
                return "\(Int(kmEnde - kmStart)) km in dieser Zeit sind unplausibel, bitte km-Stand oder Uhrzeit prüfen"
            }
        }
        return nil
    }

    /// Erster Tacho-Stand einer Fahrt ohne bekannten Start: der Start ergibt sich aus der GPS-Strecke,
    /// damit die Fahrt nicht plötzlich den ganzen Tacho-Stand als Strecke zählt.
    static func startAusTacho(kmStart: Double, kmEnde: Double, gpsKm: Double) -> Double {
        kmStart > 0 ? kmStart : max(0, kmEnde - gpsKm)
    }
}

/// Zwei Fahrten zusammenlegen und eine Fahrt teilen (Werte, ohne Datenbank; getestet in LogikTests).
enum FahrtBearbeitung {
    struct Werte: Equatable {
        var start: Date
        var ende: Date?
        var kmStart: Double
        var kmEnde: Double
        var streckeKm: Double
        var maxKmh: Double
        var schnittKmh: Double
        var startOrt: String
        var zielOrt: String
        var route: String
        var notiz: String

        init(_ f: Fahrt) {
            start = f.start; ende = f.ende; kmStart = f.kmStart; kmEnde = f.kmEnde; streckeKm = f.streckeKm
            maxKmh = f.maxKmh; schnittKmh = f.schnittKmh; startOrt = f.startOrt; zielOrt = f.zielOrt
            route = f.route; notiz = f.notiz
        }

        func schreiben(in f: Fahrt) {
            f.start = start; f.ende = ende; f.kmStart = kmStart; f.kmEnde = kmEnde; f.streckeKm = streckeKm
            f.maxKmh = maxKmh; f.schnittKmh = schnittKmh; f.startOrt = startOrt; f.zielOrt = zielOrt
            f.route = route; f.notiz = notiz; f.dirty = true
        }
    }

    /// `b` wird an `a` angehängt (die frühere zuerst).
    static func zusammenlegen(_ a: Werte, _ b: Werte) -> Werte {
        let (erste, zweite) = a.start <= b.start ? (a, b) : (b, a)
        var m = erste
        m.ende = zweite.ende ?? erste.ende
        m.kmEnde = max(erste.kmEnde, zweite.kmEnde)
        m.streckeKm = erste.streckeKm + zweite.streckeKm
        m.maxKmh = max(erste.maxKmh, zweite.maxKmh)
        let stunden = (m.ende ?? erste.start).timeIntervalSince(erste.start) / 3600
        m.schnittKmh = stunden > 0 ? m.streckeKm / stunden : 0
        m.zielOrt = zweite.zielOrt.isEmpty ? erste.zielOrt : zweite.zielOrt
        m.route = Polyline.encode(Polyline.decode(erste.route) + Polyline.decode(zweite.route))
        m.notiz = [erste.notiz, zweite.notiz].filter { !$0.isEmpty }.joined(separator: "\n")
        return m
    }

    static func kmBisPunkt(_ p: [CLLocationCoordinate2D], _ index: Int) -> Double {
        guard p.count >= 2 else { return 0 }
        var m = 0.0
        for i in stride(from: 1, through: min(index, p.count - 1), by: 1) {
            m += CLLocation(latitude: p[i - 1].latitude, longitude: p[i - 1].longitude)
                .distance(from: CLLocation(latitude: p[i].latitude, longitude: p[i].longitude))
        }
        return m / 1000
    }

    /// Anteil der Strecke bis Punkt `index` (0...1).
    static func anteilBeiPunkt(_ p: [CLLocationCoordinate2D], _ index: Int) -> Double {
        let gesamt = kmBisPunkt(p, p.count - 1)
        return gesamt > 0 ? kmBisPunkt(p, index) / gesamt : 0
    }

    /// Teilt beim Anteil `anteil` der Strecke, mit Route an Punkt `punkt`.
    /// Die Uhrzeit des Teilpunkts wird anteilig geschätzt (die Route hat keine Zeitstempel).
    static func teilen(_ f: Werte, anteil: Double, punkt: Int?) -> (Werte, Werte) {
        let ende = f.ende ?? f.start
        let q = min(max(anteil, 0), 1)
        let teilZeit = f.start.addingTimeInterval(ende.timeIntervalSince(f.start) * q)
        let km1 = f.streckeKm * q
        let km2 = f.streckeKm - km1
        let kmTeil = f.kmEnde > f.kmStart ? f.kmStart + (f.kmEnde - f.kmStart) * q : f.kmStart + km1
        let p = Polyline.decode(f.route)
        var r1 = "", r2 = ""
        if p.count >= 2, let punkt {
            let i = min(max(punkt, 0), p.count - 1)
            r1 = Polyline.encode(Array(p[0...i]))
            r2 = Polyline.encode(Array(p[i...]))
        }
        func schnitt(_ km: Double, _ von: Date, _ bis: Date) -> Double {
            let h = bis.timeIntervalSince(von) / 3600
            return h > 0 ? km / h : 0
        }
        var eins = f
        eins.ende = teilZeit
        eins.kmEnde = kmTeil
        eins.streckeKm = km1
        eins.schnittKmh = schnitt(km1, f.start, teilZeit)
        eins.zielOrt = ""
        eins.route = r1
        var zwei = f
        zwei.start = teilZeit
        zwei.kmStart = kmTeil
        zwei.streckeKm = km2
        zwei.schnittKmh = schnitt(km2, teilZeit, ende)
        zwei.startOrt = ""
        zwei.route = r2
        return (eins, zwei)
    }
}

struct GpsPunkt {
    let zeit: Date
    let ort: CLLocationCoordinate2D
    /// m/s
    let speed: Double
    let genauigkeit: Double

    func distanz(zu b: GpsPunkt) -> Double {
        CLLocation(latitude: ort.latitude, longitude: ort.longitude)
            .distance(from: CLLocation(latitude: b.ort.latitude, longitude: b.ort.longitude))
    }
}

/// Automatisches Fahrtende: unter 3 km/h und innerhalb von 100 m für die eingestellte Zeit.
struct StillstandErkennung {
    let dauer: TimeInterval
    var maxSpeed: Double = 3.0 / 3.6
    var radius: Double = 100
    private(set) var anker: GpsPunkt?

    init(dauer: TimeInterval) { self.dauer = dauer }

    var stehtSeit: Date? { anker?.zeit }

    mutating func punkt(_ p: GpsPunkt) {
        if p.speed >= maxSpeed {
            anker = nil
        } else if anker == nil || anker!.distanz(zu: p) > radius {
            anker = p
        }
    }

    /// `imFahrzeug` = die Bewegungserkennung meldet gerade "im Auto" (Stau): dann endet die Fahrt nicht,
    /// bis jemand aussteigt (docs/PLAN.md, "Stau").
    func sollEnden(_ jetzt: Date, imFahrzeug: Bool = false) -> Bool {
        if imFahrzeug { return false }
        guard let seit = stehtSeit else { return false }
        return jetzt.timeIntervalSince(seit) >= dauer
    }

    /// Lange Lücke zwischen zwei Punkten, in der das Auto kaum vorwärtsgekommen ist: iOS hat die App
    /// im Stand eingefroren oder es gab keinen Empfang. Dann war das ein Halt, die Fahrt endet beim alten Punkt.
    /// Ein Tunnel bei normaler Fahrt hat einen viel höheren Schnitt und zählt nicht.
    func lueckeIstHalt(vorher: GpsPunkt?, neu: GpsPunkt) -> Bool {
        guard let v = vorher else { return false }
        let sekunden = neu.zeit.timeIntervalSince(v.zeit)
        guard sekunden >= dauer else { return false }
        return v.distanz(zu: neu) / sekunden < StillstandErkennung.lueckeMaxSpeed
    }

    /// Gar kein Standort mehr seit der doppelten Stillstandszeit (z. B. Tiefgarage): als Halt werten.
    func keinSignal(letzter: GpsPunkt?, jetzt: Date) -> Bool {
        guard let l = letzter else { return false }
        return jetzt.timeIntervalSince(l.zeit) >= 2 * dauer
    }

    /// 10 km/h im Schnitt über die Lücke.
    static let lueckeMaxSpeed: Double = 10.0 / 3.6
}

/// Sammelt Punkte und rechnet Strecke und Höchstgeschwindigkeit.
struct Streckenrechner {
    private(set) var punkte: [GpsPunkt] = []
    private(set) var meter: Double = 0
    private(set) var maxSpeed: Double = 0

    /// Ungenauere Punkte (Tiefgarage, Tunnel) werden verworfen.
    static let maxGenauigkeit: Double = 35
    /// 250 km/h: schneller fährt keins der Autos, das ist ein GPS-Sprung.
    static let maxSprungMps: Double = 250 / 3.6
    static let sprungMinMeter: Double = 200
    static let sprungBestaetigt = 3

    /// Verworfene Sprünge in Folge; passen `sprungBestaetigt` davon zueinander, war der alte Ort falsch.
    private var spruenge: [GpsPunkt] = []

    private static func istSprung(_ a: GpsPunkt, _ b: GpsPunkt) -> Bool {
        let d = a.distanz(zu: b)
        let sek = max(b.zeit.timeIntervalSince(a.zeit), 1)
        return d > sprungMinMeter && d / sek > maxSprungMps
    }

    /// - Returns: false, wenn der Punkt verworfen wurde.
    @discardableResult
    mutating func hinzufuegen(_ p: GpsPunkt) -> Bool {
        if p.genauigkeit < 0 || p.genauigkeit > Self.maxGenauigkeit { return false }
        if let letzter = punkte.last {
            let d = letzter.distanz(zu: p)
            // GPS-Zittern im Stand nicht als Strecke zählen.
            if d < 5 && p.speed < 1 { return false }
            if Self.istSprung(letzter, p) {
                // Ein Punkt weit daneben ergäbe Fantasie-km und -km/h (Nachtplan 5b Fall 9).
                if let vorher = spruenge.last, !Self.istSprung(vorher, p) { spruenge.append(p) } else { spruenge = [p] }
                if spruenge.count < Self.sprungBestaetigt { return false }
                // Mehrere Punkte passen zueinander: der alte Ort war falsch, ohne die Sprung-Strecke weiter.
                if punkte.count < Self.sprungBestaetigt {
                    punkte.removeAll()
                    meter = 0
                }
                for i in 1..<spruenge.count { meter += spruenge[i - 1].distanz(zu: spruenge[i]) }
                punkte.append(contentsOf: spruenge)
                spruenge.removeAll()
                return true
            }
            meter += d
        }
        spruenge.removeAll()
        if p.speed <= Self.maxSprungMps { maxSpeed = max(maxSpeed, p.speed) }
        punkte.append(p)
        return true
    }

    /// Alle Punkte nach `zeit` entfernen (Stillstand am Ende abschneiden).
    mutating func abschneiden(nach zeit: Date) {
        while punkte.count > 1, let letzter = punkte.last, letzter.zeit > zeit {
            punkte.removeLast()
            meter -= punkte.last!.distanz(zu: letzter)
        }
        meter = max(0, meter)
    }
}

/// Gesamtkarte: Routen ausdünnen und als Heatmap in ein Raster zählen.
enum Karte {
    /// Höchstens `max` Punkte, Anfang und Ende bleiben erhalten.
    static func ausduennen(_ p: [CLLocationCoordinate2D], max: Int = 200) -> [CLLocationCoordinate2D] {
        guard p.count > max, max >= 2 else { return p }
        let schritt = Double(p.count - 1) / Double(max - 1)
        return (0..<max).map { p[Int((Double($0) * schritt).rounded())] }
    }

    struct Zelle: Identifiable {
        let mitte: CLLocationCoordinate2D
        let anzahl: Int
        /// 0...1 relativ zur vollsten Zelle
        let staerke: Double
        var id: String { "\(mitte.latitude),\(mitte.longitude)" }
    }

    /// Zählt, wie viele Fahrten durch jedes Rasterfeld (`grad` Kantenlänge) gehen.
    /// Jede Fahrt zählt pro Feld nur einmal, damit lange Standzeiten nicht als "oft gefahren" gelten.
    static func heatmap(_ routen: [[CLLocationCoordinate2D]], grad: Double = 0.005, maxZellen: Int = 1500) -> [Zelle] {
        struct Schluessel: Hashable { let x: Int; let y: Int }
        var zaehler: [Schluessel: Int] = [:]
        for r in routen {
            var gesehen = Set<Schluessel>()
            for c in r {
                gesehen.insert(Schluessel(x: Int((c.longitude / grad).rounded(.down)), y: Int((c.latitude / grad).rounded(.down))))
            }
            for k in gesehen { zaehler[k, default: 0] += 1 }
        }
        let groesste = zaehler.values.max() ?? 1
        return zaehler.sorted { $0.value > $1.value }.prefix(maxZellen).map { k, n in
            Zelle(mitte: CLLocationCoordinate2D(latitude: (Double(k.y) + 0.5) * grad, longitude: (Double(k.x) + 0.5) * grad),
                  anzahl: n, staerke: groesste > 1 ? log(Double(n)) / log(Double(groesste)) : 1)
        }
    }
}


/// Fahrten als CSV (Roadmap Nr. 22), gleiches Format wie Android: Semikolon, Dezimalkomma,
/// UTF-8 mit BOM für Excel, ohne Test- und gelöschte Fahrten.
enum Export {
    static let zwecke = ["privat": "Privat", "geschaeftlich": "Geschäftlich", "sonstiges": "Sonstiges"]

    static func feld(_ t: String) -> String {
        t.contains(where: { $0 == ";" || $0 == "\"" || $0 == "\n" || $0 == "\r" }) ? "\"" + t.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : t
    }

    static func zahl(_ v: Double, _ stellen: Int) -> String {
        v.formatted(.number.precision(.fractionLength(stellen)).grouping(.never).locale(Locale(identifier: "de_DE")))
    }

    struct Zeile { let start: Date; let ende: Date; let auto: String; let fahrer: String; let zweck: String
        let km: Double; let kmStart: Double; let kmEnde: Double; let von: String; let nach: String; let notiz: String }

    static func csv(_ zeilen: [Zeile]) -> String {
        let tag = DateFormatter(); tag.locale = Locale(identifier: "de_DE"); tag.dateFormat = "dd.MM.yyyy"
        let zeit = DateFormatter(); zeit.locale = Locale(identifier: "de_DE"); zeit.dateFormat = "HH:mm"
        var aus = ["Datum;Beginn;Ende;Auto;Fahrer;Zweck;km;km-Stand Beginn;km-Stand Ende;Von;Nach;Notiz"]
        for z in zeilen.sorted(by: { $0.start < $1.start }) {
            aus.append([tag.string(from: z.start), zeit.string(from: z.start), zeit.string(from: z.ende), z.auto, z.fahrer,
                        zwecke[z.zweck] ?? z.zweck, zahl(z.km, 1), z.kmStart > 0 ? zahl(z.kmStart, 0) : "",
                        z.kmEnde > 0 ? zahl(z.kmEnde, 0) : "", z.von, z.nach, z.notiz].map(feld).joined(separator: ";"))
        }
        return "\u{FEFF}" + aus.joined(separator: "\r\n") + "\r\n"
    }
}
