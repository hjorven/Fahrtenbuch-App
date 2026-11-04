import ActivityKit
import CoreLocation
import Foundation
import Observation
import SwiftData
import UserNotifications
import WidgetKit

struct LaufendeFahrt: Equatable {
    var fahrtId: String
    var fahrzeugId: String
    var fahrzeugName: String
    var start: Date
    var km: Double = 0
    var kmh: Double = 0
    var maxKmh: Double = 0
    var stehtSeit: Date?
}

enum FahrtFehler: LocalizedError {
    case nichtAngemeldet, keinFahrzeug, keinStandort

    var errorDescription: String? {
        switch self {
        case .nichtAngemeldet: "Bitte zuerst in der App anmelden."
        case .keinFahrzeug: "Bitte in der App ein Auto wählen."
        case .keinStandort: "Standortzugriff fehlt. Bitte in den Einstellungen erlauben."
        }
    }
}

/// Zeichnet die Fahrt mit CoreLocation auf (auch im Hintergrund), steuert die Live Activity
/// und beendet die Fahrt automatisch nach der eingestellten Stillstandszeit.
@MainActor
@Observable
final class FahrtRecorder {
    static let shared = FahrtRecorder()

    private(set) var laufend: LaufendeFahrt?

    private let manager = CLLocationManager()
    private var hintergrund: CLBackgroundActivitySession?
    private var dienst: CLServiceSession?
    private var standortTask: Task<Void, Never>?
    private var tickerTask: Task<Void, Never>?
    private var activity: Activity<FahrtAttributes>?

    private var rechner = Streckenrechner()
    private var stillstand = StillstandErkennung(dauer: 600)
    private var vorherigeRoute: [CLLocationCoordinate2D] = []
    private var meterVorher: Double = 0
    private var letzterPunkt: GpsPunkt?
    private var letzteActivityAktualisierung = Date.distantPast
    /// Läuft gerade das Abschließen (Ortsnamen holen, speichern)? Verhindert doppeltes Beenden.
    private var beendetGerade = false
    /// Nur bei Test-Fahrten.
    private var diagnose: Diagnose?

    private var context: ModelContext { Datenbank.container.mainContext }

    var berechtigung: CLAuthorizationStatus { manager.authorizationStatus }

    func standortErfragen() {
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
    }

    func immerErfragen() {
        manager.requestAlwaysAuthorization()
    }

    // MARK: Start

    /// - Parameter nurFortsetzen: nur eine offene Fahrt weiter aufzeichnen, nie eine neue anlegen.
    func starten(fahrzeugId wunsch: String? = nil, nurFortsetzen: Bool = false, vorlaeufig: Bool = false) async throws {
        guard laufend == nil, !beendetGerade else { return }
        let status = manager.authorizationStatus
        if status == .denied || status == .restricted { throw FahrtFehler.keinStandort }

        stillstand = StillstandErkennung(dauer: TimeInterval(Konto.shared.autoStopMinuten * 60))
        rechner = Streckenrechner()
        letzterPunkt = nil

        let userId = Konto.shared.userId
        let offene = try context.fetch(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.ende == nil && $0.fahrerId == userId && !$0.geloescht }))
        let fahrt: Fahrt
        if let offen = offene.first {
            // App wurde während einer Fahrt beendet: dort weitermachen.
            fahrt = offen
            vorherigeRoute = Polyline.decode(offen.route)
            meterVorher = offen.streckeKm * 1000
            if offen.test {
                var d = Diagnose.aus(offen.diagnose) ?? Diagnose.start(aufnahmeMeter: Konto.shared.testAufnahmeMeter, standort: status)
                d.neustarts += 1
                diagnose = d
            } else {
                diagnose = nil
            }
        } else {
            // Inzwischen beendet (z. B. Stopp per Kurzbefehl kurz nach dem App-Start): nichts neu anlegen.
            if nurFortsetzen { return }
            guard let id = wunsch ?? GeteilterStatus.fahrzeugId,
                  let fz = try context.fetch(FetchDescriptor<Fahrzeug>(predicate: #Predicate { $0.id == id })).first
            else { throw FahrtFehler.keinFahrzeug }
            fahrt = Fahrt(fahrzeugId: fz.id, fahrerId: userId, start: Date(), kmStart: fz.kmStand)
            fahrt.vorlaeufig = vorlaeufig  // Roadmap Nr. 6.3: zählt erst nach Bestätigung
            if Konto.shared.testModus {
                let d = Diagnose.start(aufnahmeMeter: Konto.shared.testAufnahmeMeter, standort: status)
                diagnose = d
                fahrt.test = true
                fahrt.diagnose = d.alsJson()
            } else {
                diagnose = nil
            }
            context.insert(fahrt)
            try? context.save()
            vorherigeRoute = []
            meterVorher = 0
        }

        let fzId = fahrt.fahrzeugId
        let name = (try? context.fetch(FetchDescriptor<Fahrzeug>(predicate: #Predicate { $0.id == fzId })).first?.name) ?? "Auto"
        laufend = LaufendeFahrt(fahrtId: fahrt.id, fahrzeugId: fahrt.fahrzeugId, fahrzeugName: name,
                                start: fahrt.start, km: meterVorher / 1000)

        dienst = CLServiceSession(authorization: .whenInUse)
        hintergrund = CLBackgroundActivitySession()
        standortTask = Task { [weak self] in
            do {
                for try await update in CLLocationUpdate.liveUpdates(.automotiveNavigation) {
                    guard let self, !Task.isCancelled else { break }
                    if let loc = update.location {
                        self.verarbeiten(loc)
                    } else if update.stationary {
                        // iOS meldet "steht" und schickt danach keine Punkte mehr: Stillstand selbst festhalten.
                        self.stillstehen()
                    }
                }
            } catch {}
        }
        if diagnose != nil {
            Task { [weak self] in
                let erlaubt = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .authorized
                self?.diagnose?.benachrichtigungen = erlaubt
            }
        }
        tickerStarten()
        Bewegung.shared.starten()
        liveActivityStarten()
        geteiltAktualisieren()
        Task { await SyncManager.shared.sync() }
    }

    /// Beim App-Start eine offene Fahrt (z. B. nach Absturz) weiter aufzeichnen.
    func fortsetzenFallsNoetig() {
        guard laufend == nil else { return }
        let userId = Konto.shared.userId
        let offen = (try? context.fetch(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.ende == nil && $0.fahrerId == userId && !$0.geloescht })))?.first
        if offen != nil {
            Task { try? await starten(nurFortsetzen: true) }
        } else {
            GeteilterStatus.laeuft = false
        }
    }

    // MARK: Aufzeichnung

    private func verarbeiten(_ loc: CLLocation) {
        guard var l = laufend, !beendetGerade else { return }
        let speed: Double
        if loc.speed >= 0 {
            speed = loc.speed
        } else if let v = letzterPunkt, loc.timestamp > v.zeit {
            let p = GpsPunkt(zeit: loc.timestamp, ort: loc.coordinate, speed: 0, genauigkeit: 0)
            speed = v.distanz(zu: p) / loc.timestamp.timeIntervalSince(v.zeit)
        } else {
            speed = 0
        }
        let p = GpsPunkt(zeit: loc.timestamp, ort: loc.coordinate, speed: speed, genauigkeit: loc.horizontalAccuracy)
        // War die App im Stand eingefroren, kommt der nächste Punkt erst bei der Weiterfahrt.
        // Dann ist die Stillstandszeit längst vorbei: Fahrt beim Halt beenden, diesen Punkt nicht mehr zählen.
        let imAuto = Bewegung.shared.imFahrzeug(p.zeit)
        let sollEnden = stillstand.sollEnden(p.zeit, imFahrzeug: imAuto)
        if sollEnden || (!imAuto && stillstand.lueckeIstHalt(vorher: letzterPunkt, neu: p)) {
            let ende = stillstand.stehtSeit ?? letzterPunkt?.zeit ?? p.zeit
            Task { await self.beenden(ende: ende, grund: sollEnden ? "stillstand" : "luecke") }
            return
        }
        letzterPunkt = p
        if var d = diagnose {
            // Test-Modus mit Aufnahme-Rate: für die Strecke nur Punkte im eingestellten Abstand nehmen.
            // iOS kennt für liveUpdates keinen Abstandsfilter, GPS läuft also gleich oft.
            let abstand = Double(d.aufnahmeMeter)
            if abstand > 0, let v = rechner.punkte.last, v.distanz(zu: p) < abstand {
                d.punkt(p, .ausgeduennt)
            } else {
                d.punkt(p, rechner.hinzufuegen(p) ? .gezaehlt : .verworfen)
            }
            diagnose = d
        } else {
            rechner.hinzufuegen(p)
        }
        stillstand.punkt(p)

        l.km = (meterVorher + rechner.meter) / 1000
        l.kmh = speed * 3.6
        l.maxKmh = rechner.maxSpeed * 3.6
        l.stehtSeit = stillstand.stehtSeit
        laufend = l

        if Date().timeIntervalSince(letzteActivityAktualisierung) > 10 {
            letzteActivityAktualisierung = Date()
            liveActivityAktualisieren()
        }
    }

    /// Stillstandsmeldung von iOS ohne neuen Punkt: letzten Ort als stehend werten.
    private func stillstehen() {
        guard var l = laufend, !beendetGerade, let v = letzterPunkt else { return }
        stillstand.punkt(GpsPunkt(zeit: Date(), ort: v.ort, speed: 0, genauigkeit: v.genauigkeit))
        l.kmh = 0
        l.stehtSeit = stillstand.stehtSeit
        laufend = l
    }

    private func tickerStarten() {
        tickerTask?.cancel()
        tickerTask = Task { [weak self] in
            var runde = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15))
                guard let self, !Task.isCancelled else { return }
                runde += 1
                let jetzt = Date()
                // Im Stau (Bewegungserkennung meldet "im Auto") weder nach Stillstand noch ohne Signal beenden.
                let imAuto = Bewegung.shared.imFahrzeug(jetzt)
                let sollEnden = self.stillstand.sollEnden(jetzt)
                if !imAuto && (sollEnden || self.stillstand.keinSignal(letzter: self.letzterPunkt, jetzt: jetzt)) {
                    let ende = self.stillstand.stehtSeit ?? self.letzterPunkt?.zeit ?? jetzt
                    // Eigener Task: beenden() bricht den Ticker ab und darf sich dabei nicht selbst treffen.
                    Task { await self.beenden(ende: ende, grund: sollEnden ? "stillstand" : "kein_signal") }
                    return
                }
                if runde % 4 == 0 { self.zwischenspeichern() }
                // Alle 2 Minuten hochladen, damit die anderen und das Display die Strecke unterwegs sehen
                // und bei einem Absturz nicht alles seit dem Start fehlt.
                if runde % 8 == 0, Konto.shared.anmeldung != nil {
                    Task { _ = await SyncManager.shared.sync() }
                }
            }
        }
    }

    private func routeText() -> String {
        Polyline.encode(vorherigeRoute + rechner.punkte.map(\.ort))
    }

    private func fahrtLaden() -> Fahrt? {
        guard let id = laufend?.fahrtId else { return nil }
        return try? context.fetch(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.id == id })).first
    }

    private func zwischenspeichern() {
        guard let f = fahrtLaden() else { return }
        f.streckeKm = (meterVorher + rechner.meter) / 1000
        f.maxKmh = max(f.maxKmh, rechner.maxSpeed * 3.6)
        f.route = routeText()
        if let d = diagnose { f.diagnose = d.alsJson() }
        f.dirty = true
        try? context.save()
        geteiltAktualisieren()
    }

    // MARK: Ende

    func beenden() async {
        await beenden(ende: Date(), grund: "manuell")
    }

    /// "Nein" bei einer vorläufigen Fahrt (Roadmap Nr. 6.3): beenden und löschen.
    func verwerfen() async {
        guard let id = laufend?.fahrtId else { return }
        await beenden(ende: Date(), grund: "verworfen")
        if let f = try? context.fetch(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.id == id })).first, !f.geloescht {
            f.geloescht = true
            f.dirty = true
            try? context.save()
        }
        await SyncManager.shared.sync()
    }

    /// "Ich fahre" bei einer vorläufigen Fahrt: zählt ab jetzt.
    func bestaetigen(_ id: String? = nil) {
        guard let id = id ?? laufend?.fahrtId,
              let f = try? context.fetch(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.id == id })).first, f.vorlaeufig else { return }
        f.vorlaeufig = false
        f.dirty = true
        try? context.save()
        Task { await SyncManager.shared.sync() }
    }

    private func beenden(ende: Date, grund: String) async {
        guard !beendetGerade else { return }
        let f: Fahrt
        if laufend != nil, let aktiv = fahrtLaden() {
            f = aktiv
        } else if let offen = offeneFahrt() {
            // Stopp kam, bevor die Aufzeichnung nach einem App-Neustart wieder lief
            // (z. B. Kurzbefehl, Kontrollzentrum, Live Activity): mit dem gespeicherten Stand abschließen.
            f = offen
            vorherigeRoute = Polyline.decode(offen.route)
            meterVorher = offen.streckeKm * 1000
            rechner = Streckenrechner()
            diagnose = offen.test ? (Diagnose.aus(offen.diagnose) ?? Diagnose()) : nil
        } else {
            aufraeumen()
            return
        }
        beendetGerade = true
        standortTask?.cancel()
        tickerTask?.cancel()

        rechner.abschneiden(nach: ende)
        let strecke = (meterVorher + rechner.meter) / 1000
        let erster = vorherigeRoute.first ?? rechner.punkte.first?.ort
        let letzter = rechner.punkte.last?.ort

        f.ende = max(ende, f.start)
        f.streckeKm = strecke
        f.kmEnde = f.kmStart + strecke
        f.maxKmh = max(f.maxKmh, rechner.maxSpeed * 3.6)
        let stunden = (f.ende ?? ende).timeIntervalSince(f.start) / 3600
        f.schnittKmh = stunden > 0 ? strecke / stunden : 0
        f.route = routeText()
        f.dirty = true
        if var d = diagnose {
            d.ende = grund
            if let a = Diagnose.akku() { d.akkuEnde = a.0; d.laedtEnde = a.1 }
            let offeneFahrten = (try? context.fetchCount(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.dirty }))) ?? 0
            let offeneTank = (try? context.fetchCount(FetchDescriptor<Tankvorgang>(predicate: #Predicate { $0.dirty }))) ?? 0
            d.offenZumSync = offeneFahrten + offeneTank
            d.adresse = SyncManager.shared.aktiveAdresse ?? ""
            if let s = Konto.shared.letzterSync { d.letzterSyncVorMin = Int64(Date().timeIntervalSince(s) / 60) }
            f.diagnose = d.alsJson()
            diagnose = nil
        }

        // Fehlstart (z. B. nur umgeparkt oder kurz eingestiegen): verwerfen statt speichern.
        // Test-Fahrten bleiben, auch kurze sind für die Diagnose nützlich.
        // Vorläufig und kürzer als 3 Minuten (Roadmap Nr. 6.3): niemand ist gefahren, verwerfen.
        let zuKurzVorlaeufig = f.vorlaeufig && (f.ende ?? ende).timeIntervalSince(f.start) < 180
        if (strecke < Plausibel.minFahrtKm || zuKurzVorlaeufig) && !f.test {
            f.geloescht = true
            try? context.save()
            aufraeumen()
            beendetGerade = false
            Task { await SyncManager.shared.sync() }
            return
        }

        let fzId = f.fahrzeugId
        // Test-Fahrten heben den km-Stand nicht an.
        if !f.test, let fz = try? context.fetch(FetchDescriptor<Fahrzeug>(predicate: #Predicate { $0.id == fzId })).first,
           f.kmEnde > fz.kmStand {
            fz.kmStand = f.kmEnde
            fz.dirty = true
        }
        // Erst speichern, dann Ortsnamen holen: Wird die App dabei beendet, ist die Fahrt trotzdem abgeschlossen.
        try? context.save()
        if f.vorlaeufig {
            // Roadmap Nr. 6.3: unbestätigte Fahrt am Ende nachfragen (bestätigen im Fahrt-Detail).
            let inhalt = UNMutableNotificationContent()
            inhalt.title = "War das deine Fahrt?"
            inhalt.body = "\(F.km(f.streckeKm)) vorläufig aufgezeichnet. In der App bestätigen, Fahrer ändern oder löschen."
            Task { try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "vorlaeufigEnde", content: inhalt, trigger: nil)) }
        }
        aufraeumen()
        beendetGerade = false

        // Ab hier ist die Fahrt abgeschlossen, eine neue darf schon starten.
        if f.startOrt.isEmpty, let erster { f.startOrt = await ort(erster) }
        if let letzter { f.zielOrt = await ort(letzter) }
        try? context.save()
        Task { await SyncManager.shared.sync() }
    }

    private func offeneFahrt() -> Fahrt? {
        let userId = Konto.shared.userId
        return (try? context.fetch(FetchDescriptor<Fahrt>(predicate: #Predicate {
            $0.ende == nil && $0.fahrerId == userId && !$0.geloescht
        })))?.first
    }

    private func aufraeumen() {
        Bewegung.shared.stoppen()
        standortTask?.cancel()
        tickerTask?.cancel()
        hintergrund?.invalidate()
        hintergrund = nil
        dienst?.invalidate()
        dienst = nil
        laufend = nil
        let a = activity
        activity = nil
        Task {
            for act in Activity<FahrtAttributes>.activities where act.id == a?.id || a == nil {
                await act.end(nil, dismissalPolicy: .immediate)
            }
        }
        geteiltAktualisieren()
    }

    private func ort(_ c: CLLocationCoordinate2D) async -> String {
        // Ohne Netz kann die Abfrage lange hängen: nach 8 Sekunden abbrechen, dann bleibt der Ort leer.
        let geo = CLGeocoder()
        let abbruch = Task {
            guard (try? await Task.sleep(for: .seconds(8))) != nil else { return }
            geo.cancelGeocode()
        }
        defer { abbruch.cancel() }
        let marks = try? await geo.reverseGeocodeLocation(CLLocation(latitude: c.latitude, longitude: c.longitude),
                                                          preferredLocale: F.de)
        guard let m = marks?.first else { return "" }
        return [m.thoroughfare, m.locality].compactMap { $0 }.joined(separator: ", ")
    }

    // MARK: Live Activity, Widget, Kontrollzentrum

    private func liveActivityStarten() {
        guard ActivityAuthorizationInfo().areActivitiesEnabled, let l = laufend else { return }
        if let vorhanden = Activity<FahrtAttributes>.activities.first {
            activity = vorhanden
            return
        }
        let attr = FahrtAttributes(fahrzeugName: l.fahrzeugName, start: l.start)
        let state = FahrtAttributes.ContentState(km: l.km, kmh: 0, stehtSeit: nil)
        activity = try? Activity.request(attributes: attr, content: .init(state: state, staleDate: nil))
    }

    private func liveActivityAktualisieren() {
        guard let act = activity, let l = laufend else { return }
        let state = FahrtAttributes.ContentState(km: l.km, kmh: l.kmh, stehtSeit: l.stehtSeit)
        Task { await act.update(.init(state: state, staleDate: nil)) }
    }

    private func geteiltAktualisieren() {
        GeteilterStatus.laeuft = laufend != nil
        GeteilterStatus.start = laufend?.start
        GeteilterStatus.km = laufend?.km ?? 0
        WidgetCenter.shared.reloadAllTimelines()
        ControlCenter.shared.reloadAllControls()
    }
}
