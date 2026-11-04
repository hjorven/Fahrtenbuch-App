import CoreLocation
import SwiftData
import XCTest
@testable import Fahrtenbuch

/// Reine Logik (Polyline, Abrechnung, Plausibilität, Teilen/Zusammenlegen, Stau-Regel, Heatmap).
/// Läuft in CI auf dem Simulator, siehe .github/workflows/ios.yml.
@MainActor
final class LogikTests: XCTestCase {
    private var container: ModelContainer!

    override func setUp() async throws {
        container = try ModelContainer(for: Fahrzeug.self, Benutzer.self, Fahrt.self, Tankvorgang.self, Ausgleich.self,
                                       configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }

    private func t(_ s: TimeInterval) -> Date { Date(timeIntervalSince1970: 1_790_000_000 + s) }

    private func fahrt(_ fahrer: String, km: Double, start: TimeInterval = 0, ende: TimeInterval? = 3600,
                       auto: String = "a", kmStart: Double = 0, kmEnde: Double = 0) -> Fahrt {
        let f = Fahrt(fahrzeugId: auto, fahrerId: fahrer, start: t(start), ende: ende.map(t), kmStart: kmStart, kmEnde: kmEnde, streckeKm: km)
        container.mainContext.insert(f)
        return f
    }

    private func tank(_ fahrer: String, km: Double, menge: Double, preis: Double, voll: Bool = true,
                      ladeort: String = "", datum: TimeInterval = 100) -> Tankvorgang {
        let x = Tankvorgang(fahrzeugId: "a", fahrerId: fahrer, datum: t(datum), kmStand: km, liter: menge,
                            gesamtpreis: preis, vollgetankt: voll, ladeort: ladeort)
        container.mainContext.insert(x)
        return x
    }

    func testPolylineHinUndZurueck() {
        let p = [CLLocationCoordinate2D(latitude: 38.5, longitude: -120.2),
                 CLLocationCoordinate2D(latitude: 40.7, longitude: -120.95),
                 CLLocationCoordinate2D(latitude: 43.252, longitude: -126.453)]
        XCTAssertEqual(Polyline.encode(p), "_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        let d = Polyline.decode(Polyline.encode(p))
        XCTAssertEqual(d.count, 3)
        XCTAssertEqual(d[2].latitude, 43.252, accuracy: 1e-5)
    }

    func testKmNimmtTachoWennPlausibel() {
        XCTAssertEqual(fahrt("x", km: 10, kmStart: 1000, kmEnde: 1012).km, 12)
        XCTAssertEqual(fahrt("x", km: 10, kmStart: 0, kmEnde: 12).km, 10)
        XCTAssertEqual(fahrt("x", km: 10, kmStart: 1000, kmEnde: 990).km, 10)
    }

    func testZuhauseLadenKostetNichts() {
        XCTAssertEqual(tank("x", km: 0, menge: 20, preis: 6, ladeort: "zuhause").kosten, 0)
        XCTAssertEqual(tank("x", km: 0, menge: 20, preis: 9, ladeort: "oeffentlich").kosten, 9)
    }

    func testAbrechnungMitAusgleich() {
        let f = [fahrt("anna", km: 100), fahrt("ben", km: 300), fahrt("ben", km: 50, ende: nil)]
        let tk = [tank("anna", km: 1000, menge: 40, preis: 80)]
        let kpk = ["a": 0.2]
        var s = Statistik.abrechnung(fahrten: f, tankungen: tk, kostenProKm: kpk, von: t(-10), bis: t(10_000))
        let anna = s.first { $0.fahrerId == "anna" }!
        let ben = s.first { $0.fahrerId == "ben" }!
        // Laufende Fahrt zählt nicht mit.
        XCTAssertEqual(ben.km, 300)
        XCTAssertEqual(anna.saldo, 60, accuracy: 0.001)
        XCTAssertEqual(ben.saldo, -60, accuracy: 0.001)
        XCTAssertEqual(Statistik.ueberweisungen(s), [Statistik.Ueberweisung(von: "ben", an: "anna", betrag: 60)])

        let a = Ausgleich(von: "ben", an: "anna", betrag: 60, zeitraum: "2026-09")
        container.mainContext.insert(a)
        s = Statistik.abrechnung(fahrten: f, tankungen: tk, kostenProKm: kpk, von: t(-10), bis: t(10_000), ausgleiche: [a])
        XCTAssertTrue(s.allSatisfy { abs($0.saldo) < 0.005 })
        XCTAssertTrue(Statistik.ueberweisungen(s).isEmpty)
    }

    func testUeberweisungenMehrerePersonen() {
        let s = [Statistik.Saldo(fahrerId: "a", km: 0, anteil: 0, bezahlt: 90),
                 Statistik.Saldo(fahrerId: "b", km: 0, anteil: 60, bezahlt: 0),
                 Statistik.Saldo(fahrerId: "c", km: 0, anteil: 30, bezahlt: 0)]
        let u = Statistik.ueberweisungen(s)
        XCTAssertEqual(u.count, 2)
        XCTAssertEqual(u.reduce(0) { $0 + $1.betrag }, 90, accuracy: 0.001)
        XCTAssertTrue(u.allSatisfy { $0.an == "a" })
    }

    func testVerbrauchElektroZaehltJedeLadung() {
        let tk = [tank("x", km: 1000, menge: 10, preis: 0, voll: false, ladeort: "zuhause"),
                  tank("x", km: 1100, menge: 15, preis: 0, voll: false, ladeort: "zuhause"),
                  tank("x", km: 1200, menge: 15, preis: 9, voll: false, ladeort: "oeffentlich")]
        let v = Statistik.verbrauch(tk, elektrisch: true)!
        XCTAssertEqual(v.literPro100km, 15, accuracy: 0.001)
        XCTAssertEqual(v.kostenProKm, 0.045, accuracy: 0.0001)
        XCTAssertNil(Statistik.verbrauch(tk, elektrisch: false))
    }

    func testPlausibel() {
        XCTAssertNil(Plausibel.tank(menge: 40, preisProEinheit: 1.7, kmStand: 120_000))
        XCTAssertNotNil(Plausibel.tank(menge: 400, preisProEinheit: 1.7, kmStand: 120_000))
        XCTAssertNotNil(Plausibel.tank(menge: 40, preisProEinheit: 17, kmStand: 120_000))
        XCTAssertNotNil(Plausibel.tank(menge: 40, preisProEinheit: 1.7, kmStand: nil))
        XCTAssertNotNil(Plausibel.tank(menge: 40, preisProEinheit: 1.7, kmStand: 0))
        let halbeStunde: TimeInterval = 1800
        XCTAssertNil(Plausibel.fahrt(start: t(0), ende: t(halbeStunde), kmStart: 100, kmEnde: 120))
        XCTAssertNotNil(Plausibel.fahrt(start: t(10), ende: t(0), kmStart: 100, kmEnde: 120))
        XCTAssertNotNil(Plausibel.fahrt(start: t(0), ende: t(halbeStunde), kmStart: 120, kmEnde: 100))
        // Tippfehler im Tacho, ohne bekannten Start keine Prüfung, 240 km/h noch erlaubt
        XCTAssertNotNil(Plausibel.fahrt(start: t(0), ende: t(halbeStunde), kmStart: 100, kmEnde: 123_456))
        XCTAssertNil(Plausibel.fahrt(start: t(0), ende: t(halbeStunde), kmStart: 0, kmEnde: 123_456))
        XCTAssertNil(Plausibel.fahrt(start: t(0), ende: t(halbeStunde), kmStart: 1000, kmEnde: 1120))
        XCTAssertEqual(Plausibel.startAusTacho(kmStart: 0, kmEnde: 123_456, gpsKm: 12.5), 123_443.5, accuracy: 1e-6)
        XCTAssertEqual(Plausibel.startAusTacho(kmStart: 500, kmEnde: 520, gpsKm: 12.5), 500, accuracy: 1e-6)
        XCTAssertEqual(Plausibel.startAusTacho(kmStart: 0, kmEnde: 5, gpsKm: 12.5), 0, accuracy: 1e-6)
    }

    func testZeitraum() {
        var c = DateComponents()
        c.year = 2026; c.month = 3; c.day = 15
        XCTAssertEqual(Statistik.zeitraum(Calendar.current.date(from: c)!), "2026-03")
    }

    func testTachoAbweichung() {
        let f = [fahrt("x", km: 102, kmStart: 1000, kmEnde: 1100), fahrt("x", km: 50)]
        let a = Statistik.tachoAbweichung(f)!
        XCTAssertEqual(a.fahrten, 1)
        XCTAssertEqual(a.prozent, 2, accuracy: 0.001)
    }

    func testZusammenlegenUndTeilen() {
        let p = (0...10).map { CLLocationCoordinate2D(latitude: 52 + Double($0) * 0.001, longitude: 13) }
        let a = fahrt("x", km: 5, start: 0, ende: 600, kmStart: 1000, kmEnde: 1005)
        a.route = Polyline.encode(Array(p[0...5]))
        a.zielOrt = "Mitte"
        let b = fahrt("x", km: 5, start: 900, ende: 1500, kmStart: 1005, kmEnde: 1010)
        b.route = Polyline.encode(Array(p[5...10]))
        b.zielOrt = "Ziel"
        let m = FahrtBearbeitung.zusammenlegen(FahrtBearbeitung.Werte(b), FahrtBearbeitung.Werte(a))
        XCTAssertEqual(m.start, t(0))
        XCTAssertEqual(m.ende, t(1500))
        XCTAssertEqual(m.streckeKm, 10)
        XCTAssertEqual(m.kmEnde, 1010)
        XCTAssertEqual(m.zielOrt, "Ziel")
        XCTAssertEqual(Polyline.decode(m.route).count, 12)

        let anteil = FahrtBearbeitung.anteilBeiPunkt(p, 5)
        XCTAssertEqual(anteil, 0.5, accuracy: 0.01)
        let (eins, zwei) = FahrtBearbeitung.teilen(m, anteil: anteil, punkt: 6)
        XCTAssertEqual(eins.streckeKm + zwei.streckeKm, 10, accuracy: 0.001)
        XCTAssertEqual(eins.kmEnde, zwei.kmStart)
        XCTAssertEqual(eins.ende, zwei.start)
        XCTAssertEqual(Polyline.decode(eins.route).count, 7)
        XCTAssertEqual(Polyline.decode(zwei.route).count, 6)
    }

    func testStauRegel() {
        XCTAssertTrue(Bewegung.imFahrzeug(t(600), imAuto: t(0), ausgestiegen: nil))
        XCTAssertFalse(Bewegung.imFahrzeug(t(600), imAuto: t(0), ausgestiegen: t(300)))
        XCTAssertTrue(Bewegung.imFahrzeug(t(600), imAuto: t(400), ausgestiegen: t(300)))
        XCTAssertFalse(Bewegung.imFahrzeug(t(4000), imAuto: t(0), ausgestiegen: nil))
        XCTAssertFalse(Bewegung.imFahrzeug(t(10), imAuto: nil, ausgestiegen: nil))

        var s = StillstandErkennung(dauer: 600)
        let ort = CLLocationCoordinate2D(latitude: 52, longitude: 13)
        s.punkt(GpsPunkt(zeit: t(0), ort: ort, speed: 0, genauigkeit: 5))
        XCTAssertTrue(s.sollEnden(t(700)))
        XCTAssertFalse(s.sollEnden(t(700), imFahrzeug: true))
    }

    func testMonatsabrechnungNachricht() {
        let u = { (v: String, a: String, b: Double) in Statistik.Ueberweisung(von: v, an: a, betrag: b) }
        let namen = ["ben": "Ben", "anna": "Anna"]
        let n = Monatsabrechnung.nachricht([u("ich", "ben", 23.4)], ich: "ich", namen: namen, monat: 9)
        XCTAssertEqual(n?.titel, "Abrechnung Oktober")
        XCTAssertEqual(n?.text, "Du zahlst Ben 23,40 €.")
        XCTAssertEqual(n?.bezahlen?.an, "ben")
        let b = Monatsabrechnung.nachricht([u("anna", "ich", 5)], ich: "ich", namen: namen, monat: 0)
        XCTAssertEqual(b?.text, "Anna zahlt dir 5,00 €.")
        XCTAssertNil(b?.bezahlen)
        XCTAssertNil(Monatsabrechnung.nachricht([u("anna", "ben", 5)], ich: "ich", namen: namen, monat: 0))
        XCTAssertNil(Monatsabrechnung.nachricht([u("ich", "ben", 1), u("ich", "anna", 2)], ich: "ich", namen: namen, monat: 0)?.bezahlen)
        var k = Calendar(identifier: .gregorian)
        k.timeZone = TimeZone(identifier: "Europe/Berlin")!
        let (z, von, bis) = Monatsabrechnung.vormonat(k.date(from: DateComponents(year: 2026, month: 11, day: 3, hour: 12))!, kalender: k)
        XCTAssertEqual(z, Statistik.zeitraum(von))
        XCTAssertEqual(k.component(.month, from: von), 10)
        XCTAssertEqual(k.component(.month, from: bis), 11)
    }

    func testAbrechnungMitMitfahrernUndVorlaeufig() {
        let a = fahrt("ben", km: 100, start: 100, ende: 200, auto: "bus")
        a.mitfahrer = ["anna"]
        let b = fahrt("ben", km: 500, start: 300, ende: 400, auto: "bus")
        b.vorlaeufig = true
        let r = Dictionary(uniqueKeysWithValues: Statistik.abrechnung(fahrten: [a, b], tankungen: [], kostenProKm: ["bus": 0.2],
                                                                      von: t(0), bis: t(1000)).map { ($0.fahrerId, $0) })
        XCTAssertEqual(r["ben"]?.km ?? 0, 100, accuracy: 1e-9)
        XCTAssertEqual(r["ben"]?.anteil ?? 0, 10, accuracy: 1e-9)
        XCTAssertEqual(r["anna"]?.km ?? -1, 0, accuracy: 1e-9)
        XCTAssertEqual(r["anna"]?.anteil ?? 0, 10, accuracy: 1e-9)
    }

    func testUnbenutzteStartautosNichtDoppeltHochladen() {
        XCTAssertTrue(SyncManager.unbenutztesStartauto(name: "Kombi", km: 0, benutzt: 0, serverHatAutos: true))
        XCTAssertFalse(SyncManager.unbenutztesStartauto(name: "Kombi", km: 0, benutzt: 1, serverHatAutos: true))
        XCTAssertFalse(SyncManager.unbenutztesStartauto(name: "Kombi", km: 1200, benutzt: 0, serverHatAutos: true))
        XCTAssertFalse(SyncManager.unbenutztesStartauto(name: "Kombi", km: 0, benutzt: 0, serverHatAutos: false))
        XCTAssertFalse(SyncManager.unbenutztesStartauto(name: "Mein Auto", km: 0, benutzt: 0, serverHatAutos: true))
    }

    func testWidgetMonatszeile() {
        XCTAssertEqual(Monatsabrechnung.zeile(Statistik.Saldo(fahrerId: "ich", km: 312.4, anteil: 10, bezahlt: 22.3), monat: 10),
                       "November: 312 km · du bekommst 12,30 €")
        XCTAssertEqual(Monatsabrechnung.zeile(Statistik.Saldo(fahrerId: "ich", km: 5, anteil: 0, bezahlt: 0), monat: 0), "Januar: 5 km")
    }

    func testFehlertextMitWasTun() {
        XCTAssertTrue(Fehlertext.aus(PBFehler(status: 401, text: "x")).contains("neu anmelden"))
        XCTAssertEqual(Fehlertext.aus(PBFehler(status: 400, text: "Datum liegt in der Zukunft.")), "Datum liegt in der Zukunft.")
        XCTAssertTrue(Fehlertext.aus(PBFehler(status: 400, text: "HTTP 400"), "Speichern").hasPrefix("Speichern ging nicht"))
        XCTAssertTrue(Fehlertext.aus(PBFehler(status: 502, text: "HTTP 502")).contains("Problem melden"))
        XCTAssertEqual(Fehlertext.aus(URLError(.timedOut)), Fehlertext.offline)
    }

    func testKostenBetragLesen() {
        XCTAssertEqual(PlanungAbschnitte.betragLesen("189,90"), 189.9)
        XCTAssertEqual(PlanungAbschnitte.betragLesen("1.200,50 €"), 1200.5)
        XCTAssertEqual(PlanungAbschnitte.betragLesen("45"), 45)
        XCTAssertNil(PlanungAbschnitte.betragLesen("0"))
        XCTAssertNil(PlanungAbschnitte.betragLesen("-5"))
        XCTAssertNil(PlanungAbschnitte.betragLesen("60000"))
        XCTAssertNil(PlanungAbschnitte.betragLesen("abc"))
    }

    func testFahrtVergessenAbschnitte() {
        let m: [(Date, Bool, Bool)] = [
            (t(0), false, false), (t(100), true, false), (t(400), false, true), (t(500), true, false),
            (t(1300), false, false),                       // ausgestiegen: 100...1300
            (t(2000), true, false), (t(2100), false, false), // nur 100 s: zu kurz
            (t(3000), true, false),                        // läuft noch: zählt nicht
        ]
        let a = Bewegung.abschnitte(m, jetzt: t(4000))
        XCTAssertEqual(a, [Bewegung.Abschnitt(von: t(100), bis: t(1300))])
    }

    func testHeatmapZaehltJeFahrtEinmal() {
        let a = CLLocationCoordinate2D(latitude: 52.0001, longitude: 13.0001)
        let b = CLLocationCoordinate2D(latitude: 52.0002, longitude: 13.0002)
        let weit = CLLocationCoordinate2D(latitude: 53, longitude: 14)
        let z = Karte.heatmap([[a, b, a, b], [a, weit]])
        XCTAssertEqual(z.count, 2)
        XCTAssertEqual(z.first?.anzahl, 2)
        XCTAssertEqual(z.first?.staerke, 1)
        XCTAssertEqual(Karte.ausduennen(Array(repeating: a, count: 1000), max: 50).count, 50)
    }

    func testCsvExport() {
        let z = Export.Zeile(start: t(0), ende: t(1800), auto: "Kombi", fahrer: "Admin", zweck: "geschaeftlich",
                             km: 12.5, kmStart: 1000, kmEnde: 1012.5, von: "A; B", nach: "C", notiz: "sagt \"hallo\"")
        let zeilen = Export.csv([z]).dropFirst().split(separator: "\r\n")
        XCTAssertEqual(zeilen.count, 2)
        XCTAssertTrue(zeilen[1].contains(";Kombi;Admin;Geschäftlich;12,5;1000;"))
        XCTAssertTrue(zeilen[1].hasSuffix(";\"A; B\";C;\"sagt \"\"hallo\"\"\""))
    }

    func testQrVerbinden() {
        let d = Verbinden.Daten(heim: "http://fahrtenbuch.local:8090", tail: "http://100.1.2.3:8090",
                                dl: "http://fahrtenbuch.local:8091", einladung: "ABCD2345EFGH")
        XCTAssertEqual(Verbinden.lesen(Verbinden.bauen(d)), d)
        // Von Android gebaut (Werte voll kodiert)
        let android = "fahrtenbuch://verbinden?heim=http%3A%2F%2Ffahrtenbuch.local%3A8090&einladung=ABCD2345EFGH"
        XCTAssertEqual(Verbinden.lesen(android)?.heim, "http://fahrtenbuch.local:8090")
        XCTAssertNil(Verbinden.lesen("https://example.com"))
        XCTAssertEqual(Verbinden.code("abcd-2345-efgh"), "ABCD2345EFGH")
        XCTAssertEqual(Verbinden.code("kurz"), "")
    }

    func testTexteUndZukunft() {
        XCTAssertEqual("abc".hoechstens(5), "abc")
        let e = String(repeating: "🚗ä", count: 2500).hoechstens(2000)
        XCTAssertEqual(e.unicodeScalars.count, 2000)
        let jetzt = t(0)
        XCTAssertNotNil(Plausibel.fahrt(start: t(3600), ende: t(7200), kmStart: 0, kmEnde: 0, jetzt: jetzt))
        XCTAssertNil(Plausibel.fahrt(start: t(-600), ende: t(300), kmStart: 0, kmEnde: 0, jetzt: jetzt))
    }

    func testGpsSprungZaehltNicht() {
        func p(_ s: TimeInterval, _ lat: Double, _ speed: Double = 14) -> GpsPunkt {
            GpsPunkt(zeit: t(s), ort: CLLocationCoordinate2D(latitude: lat, longitude: 8), speed: speed, genauigkeit: 5)
        }
        var r = Streckenrechner()
        r.hinzufuegen(p(0, 50.0))
        r.hinzufuegen(p(10, 50.0013))
        XCTAssertFalse(r.hinzufuegen(p(11, 50.45, 250)))      // 50 km daneben, 900 km/h
        r.hinzufuegen(p(20, 50.0025))
        XCTAssertEqual(r.meter, 278, accuracy: 10)
        XCTAssertLessThan(r.maxSpeed, 20)

        var falsch = Streckenrechner()
        falsch.hinzufuegen(p(0, 51.0, 0))                      // falscher erster Fix
        for (s, lat) in [(5.0, 50.0), (15.0, 50.0009), (25.0, 50.0018)] { falsch.hinzufuegen(p(s, lat, 10)) }
        XCTAssertEqual(falsch.meter, 200, accuracy: 10)
        XCTAssertEqual(falsch.punkte.first!.ort.latitude, 50.0, accuracy: 1e-9)

        var tunnel = Streckenrechner()
        tunnel.hinzufuegen(p(0, 50.0, 25))
        tunnel.hinzufuegen(p(300, 50.06, 25))                  // 6,7 km in 5 min
        XCTAssertEqual(tunnel.meter, 6670, accuracy: 50)
    }

    func testDiagnoseZaehltPunkteUndLuecken() {
        var d = Diagnose(aufnahmeMeter: 25, akkuStart: 80, akkuEnde: 74)
        var r = Streckenrechner()
        func p(_ s: TimeInterval, _ lat: Double, _ speed: Double, _ gen: Double) -> GpsPunkt {
            GpsPunkt(zeit: t(s), ort: CLLocationCoordinate2D(latitude: lat, longitude: 9), speed: speed, genauigkeit: gen)
        }
        for x in [p(0, 52.0, 10, 5), p(3, 52.0003, 10, 5), p(6, 52.0003, 0, 60), p(9, 52.00031, 0, 5), p(100, 52.003, 10, 8)] {
            d.punkt(x, r.hinzufuegen(x) ? .gezaehlt : .verworfen)
        }
        XCTAssertEqual(d.punkte, 5)
        XCTAssertEqual(d.punkteGezaehlt, 3)
        XCTAssertEqual(d.verworfenUngenau, 1)
        XCTAssertEqual(d.verworfenZittern, 1)
        XCTAssertEqual(d.luecken, 1)
        XCTAssertEqual(d.groessteLueckeSek, 91)
        XCTAssertEqual(d.genauigkeitMaxM, 60)

        // JSON hin und zurück (z. B. nach Neustart der App während der Fahrt).
        XCTAssertEqual(Diagnose.aus(d.alsJson()), d)
        let z = Dictionary(uniqueKeysWithValues: d.zeilen())
        XCTAssertEqual(z["Akku"], "80 % → 74 % (6 %)")
        XCTAssertEqual(z["Aufnahme"], "alle 25 m")
        XCTAssertNil(Diagnose.aus(""))
        XCTAssertNil(Diagnose.aus("kaputt"))
    }

    /// Diagnose einer Android-Test-Fahrt (gleiche Felder, ohne die iOS-eigenen) muss lesbar sein.
    func testDiagnoseVonAndroidLesbar() {
        let android = Diagnose(akkuStart: 50, punkte: 3)
        var json = try! JSONSerialization.jsonObject(with: android.alsJson().data(using: .utf8)!) as! [String: Any]
        json.removeValue(forKey: "ausgeduennt")
        let text = String(data: try! JSONSerialization.data(withJSONObject: json), encoding: .utf8)!
        XCTAssertEqual(Diagnose.aus(text)?.punkte, 3)
    }

    func testTestEintraegeStandardmaessigNormal() {
        XCTAssertFalse(fahrt("x", km: 1).test)
        XCTAssertFalse(tank("x", km: 1, menge: 1, preis: 1).test)
    }

    func testUpdateQuellen() {
        XCTAssertEqual(
            UpdatePruefer.quellen(["http://192.168.1.10:8090", "", "https://fahrtenbuch.beispiel.ts.net"]),
            ["http://192.168.1.10:8091/", "http://fahrtenbuch.beispiel.ts.net:8091/", "http://fahrtenbuch.local:8091/"]
        )
    }

    func testUpdateAusSideStoreQuelle() {
        let json = """
        {"identifier":"x","apps":[{"bundleIdentifier":"de.hjorven.fahrtenbuch",
          "versions":[{"version":"1.0.19","buildVersion":"19"},{"version":"1.0","buildVersion":"11"}]}]}
        """
        let neu = UpdatePruefer.auswerten(Data(json.utf8), bundleId: "de.hjorven.fahrtenbuch")
        XCTAssertEqual(neu, NeueVersion(build: 19, version: "1.0.19"))
        XCTAssertNil(UpdatePruefer.auswerten(Data("kaputt".utf8), bundleId: "de.hjorven.fahrtenbuch"))
    }

    // MARK: Beleg- und Tacho-Scan (gleiche Fälle wie Android LogikTest)

    private func tagUm(_ jahr: Int, _ monat: Int, _ tag: Int, _ stunde: Int = 12) -> Date {
        Calendar(identifier: .gregorian).date(from: DateComponents(year: jahr, month: monat, day: tag, hour: stunde))!
    }

    func testBelegAralDiesel() {
        let b = ScanLeser.beleg([
            "ARAL Tankstelle", "Hauptstr. 12, 12345 Musterstadt", "03.11.2026 14:22", "Säule 4 Diesel",
            "45,23 l x 1,789 EUR/l", "80,92 EUR A", "Summe EUR 80,92", "MwSt 19% 12,92", "Girocard 80,92",
        ], elektro: false, heute: tagUm(2026, 11, 3, 18))
        XCTAssertEqual(b.menge ?? 0, 45.23, accuracy: 1e-9)
        XCTAssertEqual(b.einheit, "L")
        XCTAssertEqual(b.preisProEinheit ?? 0, 1.789, accuracy: 1e-9)
        XCTAssertEqual(b.gesamt ?? 0, 80.92, accuracy: 1e-9)
        XCTAssertEqual(b.tankstelle, "Aral")
        XCTAssertEqual(b.datum, tagUm(2026, 11, 3))
    }

    func testBelegZweispaltigOhneEinheitBeimPreis() {
        let b = ScanLeser.beleg(["Shell Station", "Datum: 28.10.26", "Diesel", "Menge 52,10 L", "Preis 1,699", "Gesamt", "88,52"],
                                elektro: false, heute: tagUm(2026, 11, 3))
        XCTAssertEqual(b.menge ?? 0, 52.10, accuracy: 1e-9)
        XCTAssertEqual(b.preisProEinheit ?? 0, 1.699, accuracy: 1e-9)
        XCTAssertEqual(b.gesamt ?? 0, 88.52, accuracy: 1e-9)
        XCTAssertEqual(b.tankstelle, "Shell")
        XCTAssertEqual(b.datum, tagUm(2026, 10, 28))
    }

    func testBelegLadenKwh() {
        let b = ScanLeser.beleg(["EnBW mobility+", "Ladevorgang 01.11.2026", "Energie 23,456 kWh", "Preis 0,59 EUR/kWh", "Gesamtbetrag 13,84 EUR"],
                                elektro: true, heute: tagUm(2026, 11, 3))
        XCTAssertEqual(b.menge ?? 0, 23.456, accuracy: 1e-9)
        XCTAssertEqual(b.einheit, "kWh")
        XCTAssertEqual(b.preisProEinheit ?? 0, 0.59, accuracy: 1e-9)
        XCTAssertEqual(b.gesamt ?? 0, 13.84, accuracy: 1e-9)
        XCTAssertEqual(b.tankstelle, "EnBW")
    }

    func testBelegFehlenderPreisWirdBerechnet() {
        let b = ScanLeser.beleg(["JET", "Diesel 40,00 l", "zu zahlen 70,00"], elektro: false, heute: tagUm(2026, 11, 3))
        XCTAssertEqual(b.menge ?? 0, 40, accuracy: 1e-9)
        XCTAssertEqual(b.preisProEinheit ?? 0, 1.75, accuracy: 1e-9)
        XCTAssertEqual(b.gesamt ?? 0, 70, accuracy: 1e-9)
    }

    func testBelegUnlesbarIstLeer() {
        let b = ScanLeser.beleg(["Vielen Dank", "Gute Fahrt", "24.12.2027"], elektro: false, heute: tagUm(2026, 11, 3))
        XCTAssertTrue(b.leer)
        XCTAssertNil(b.datum)
        XCTAssertNil(b.tankstelle)
    }

    func testTachoNimmtGesamtKm() {
        let texte = [
            ScanLeser.TachoText(text: "123456 km", hoehe: 40), ScanLeser.TachoText(text: "87 km/h", hoehe: 60),
            ScanLeser.TachoText(text: "234,5", hoehe: 20), ScanLeser.TachoText(text: "12:45", hoehe: 20),
            ScanLeser.TachoText(text: "18°C", hoehe: 20),
        ]
        XCTAssertEqual(ScanLeser.tacho(texte, bisher: 123_000).first, 123_456)
        XCTAssertEqual(ScanLeser.tacho(texte, bisher: 0).first, 123_456)
    }

    func testTachoTausenderpunktUndOhneVorwissen() {
        XCTAssertEqual(ScanLeser.tacho([ScanLeser.TachoText(text: "ODO 123.456 km")], bisher: 0), [123_456])
        let ohne = ScanLeser.tacho([ScanLeser.TachoText(text: "15:30"), ScanLeser.TachoText(text: "045210"),
                                    ScanLeser.TachoText(text: "312.4 km")], bisher: 0)
        XCTAssertEqual(ohne.first, 45_210)
    }

    func testTachoLaeuftNichtRueckwaerts() {
        let v = ScanLeser.tacho([ScanLeser.TachoText(text: "49990", hoehe: 50), ScanLeser.TachoText(text: "50120", hoehe: 30)], bisher: 50_000)
        XCTAssertEqual(v, [50_120])
    }
}
