import Foundation
import Observation
import SwiftData
import UIKit

enum SyncStatus: Equatable {
    case bereit
    case laeuft
    case fehler(String)
    /// Server lehnt das Token ab (z. B. Passwort auf einem anderen Gerät geändert): neu anmelden.
    case abgelaufen
    /// Weder Heimnetz- noch Tailscale-Adresse antworten.
    case offline
}

/// Offline-first-Abgleich mit PocketBase: erst lokale Änderungen hochladen, dann Neues abholen.
/// Lokal noch nicht hochgeladene Änderungen werden beim Abholen nicht überschrieben.
@MainActor
@Observable
final class SyncManager {
    static let shared = SyncManager()

    private(set) var status: SyncStatus = .bereit
    /// Adresse, über die zuletzt verbunden wurde (Heimnetz oder Tailscale).
    private(set) var aktiveAdresse: String?
    private var laeuftGerade = false
    /// Gründe, aus denen der Server im laufenden Abgleich Einträge abgelehnt hat (HTTP 400).
    private var abgelehnt: [String] = []
    /// Während eines Abgleichs angefragt (z. B. Fahrt gerade beendet): danach gleich noch einmal.
    private var nochmal = false

    private var context: ModelContext { Datenbank.container.mainContext }
    private var konto: Konto { Konto.shared }

    @discardableResult
    func sync() async -> Bool {
        guard konto.anmeldung != nil else { return false }
        if laeuftGerade {
            nochmal = true
            return false
        }
        laeuftGerade = true
        defer { laeuftGerade = false }
        var ok = await einmal()
        while nochmal, konto.anmeldung != nil {
            nochmal = false
            ok = await einmal()
        }
        return ok
    }

    private func einmal() async -> Bool {
        guard let a = konto.anmeldung else { return false }
        status = .laeuft
        guard let url = await PocketBase.ersteErreichbare([a.serverUrl, konto.tailscaleUrl]) else {
            aktiveAdresse = nil
            status = .offline
            return false
        }
        aktiveAdresse = url
        abgelehnt = []
        do {
            let token = try await tokenPruefen(a, url)
            await adressenErgaenzen(url, token)
            if konto.zusammenfuehren { try await lokaleUebernehmen(url, token, a.userId) }
            try await hochladen(url, token)
            try await abholen(url, token)
            try? context.save()
            konto.letzterSync = Date()
            await geraetMelden(url, token, a.userId)
            await Monatsabrechnung.shared.pruefen(context)
            Monatsabrechnung.shared.widgetZeile(context)
            if let erster = abgelehnt.first {
                status = .fehler("\(abgelehnt.count) \(abgelehnt.count == 1 ? "Eintrag" : "Einträge") vom Server abgelehnt: \(erster) Bitte prüfen, er bleibt auf dem iPhone.")
            } else {
                status = .bereit
            }
            return true
        } catch let f as PBFehler where f.status == 401 {
            status = .abgelaufen
        } catch let f as PBFehler {
            status = .fehler(Fehlertext.aus(f, "Abgleichen"))
        } catch {
            aktiveAdresse = nil
            status = .offline
        }
        return false
    }

    /// Version, Gerät und Sync-Zeit am eigenen Konto (Roadmap Nr. 15), höchstens einmal am Tag oder nach Update.
    private func geraetMelden(_ url: String, _ token: String, _ userId: String) async {
        let version = UpdatePruefer.installierteVersion
        let d = UserDefaults.standard
        if d.string(forKey: "geraetGemeldetVersion") == version,
           Date().timeIntervalSince1970 - d.double(forKey: "geraetGemeldetUm") < 86400 { return }
        let geraet = "\(UIDevice.current.model), iOS \(UIDevice.current.systemVersion)"
        do {
            try await PocketBase.aendern(server: url, token: token, collection: "users", id: userId,
                                         body: ["app_version": version, "geraet": geraet, "letzter_sync": PbDatum.text(Date())])
            d.set(version, forKey: "geraetGemeldetVersion")
            d.set(Date().timeIntervalSince1970, forKey: "geraetGemeldetUm")
        } catch {}
    }

    /// Fehlende Tailscale-Adresse vom Server übernehmen (Roadmap Nr. 3). Eingetragene bleiben.
    private func adressenErgaenzen(_ url: String, _ token: String) async {
        guard konto.tailscaleUrl.isEmpty, let i = try? await PocketBase.info(server: url, token: token) else { return }
        let t = i.s("tailscale_ip").isEmpty ? i.s("tailscale") : i.s("tailscale_ip")
        if !t.isEmpty { konto.tailscaleUrl = Konto.normalisieren(t) }
    }

    private func tokenPruefen(_ a: Anmeldung, _ url: String) async throws -> String {
        do {
            let (neu, aendern) = try await PocketBase.tokenErneuern(server: url, token: a.token)
            konto.passwortAendern = aendern
            if !neu.isEmpty && neu != a.token {
                var b = a
                b.token = neu
                konto.speichern(b)
            }
            return neu.isEmpty ? a.token : neu
        } catch let f as PBFehler where f.status == 401 {
            throw f
        } catch let f as PBFehler {
            _ = f
            return a.token
        }
    }

    /// Erste Verbindung nach dem lokalen Modus: eigene Fahrten/Tankvorgänge dem Konto zuordnen und
    /// die lokal angelegten Autos mit den gleichnamigen Autos auf dem Server zusammenlegen.
    nonisolated static let startautoNamen = ["e-auto", "kombi", "bus"]

    /// Reine Regel (getestet): Startauto ohne Fahrten, Tankungen und km wird nicht hochgeladen, wenn der Server Autos hat.
    nonisolated static func unbenutztesStartauto(name: String, km: Double, benutzt: Int, serverHatAutos: Bool) -> Bool {
        serverHatAutos && benutzt == 0 && km <= 0 && startautoNamen.contains(Antrieb.namensSchluessel(name))
    }

    private func lokaleUebernehmen(_ url: String, _ token: String, _ userId: String) async throws {
        let lokal = Konto.lokal
        for f in try context.fetch(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.fahrerId == lokal })) {
            f.fahrerId = userId
            f.dirty = true
        }
        for t in try context.fetch(FetchDescriptor<Tankvorgang>(predicate: #Predicate { $0.fahrerId == lokal })) {
            t.fahrerId = userId
            t.dirty = true
        }
        for a in try context.fetch(FetchDescriptor<Ausgleich>(predicate: #Predicate { $0.von == lokal || $0.an == lokal })) {
            if a.von == lokal { a.von = userId }
            if a.an == lokal { a.an = userId }
            a.dirty = true
        }
        let server = try await PocketBase.liste(server: url, token: token, collection: "fahrzeug", seit: "")
        func norm(_ s: String) -> String { Antrieb.namensSchluessel(s) }
        for auto in try context.fetch(FetchDescriptor<Fahrzeug>(predicate: #Predicate { !$0.aufServer })) {
            guard let o = server.first(where: { norm($0.s("name")) == norm(auto.name) }) else {
                let id = auto.id
                let benutzt = try context.fetchCount(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.fahrzeugId == id }))
                    + context.fetchCount(FetchDescriptor<Tankvorgang>(predicate: #Predicate { $0.fahrzeugId == id }))
                if Self.unbenutztesStartauto(name: auto.name, km: auto.kmStand, benutzt: benutzt, serverHatAutos: !server.isEmpty) {
                    // Unbenutztes Startauto ("Kombi", "Bus", "E-Auto"): der Server hat die echten Autos, nicht doppelt anlegen.
                    context.delete(auto)
                } else {
                    auto.dirty = true
                }
                continue
            }
            let alteId = auto.id
            let serverId = o.s("id")
            for f in try context.fetch(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.fahrzeugId == alteId })) {
                f.fahrzeugId = serverId
                f.dirty = true
            }
            for t in try context.fetch(FetchDescriptor<Tankvorgang>(predicate: #Predicate { $0.fahrzeugId == alteId })) {
                t.fahrzeugId = serverId
                t.dirty = true
            }
            let km = max(auto.kmStand, o.d("km_stand"))
            context.delete(auto)
            let neu: Fahrzeug
            if let vorhanden = try einer(Fahrzeug.self, serverId) {
                neu = vorhanden
            } else {
                neu = Fahrzeug(id: serverId, name: o.s("name"))
                context.insert(neu)
            }
            uebernehmen(o, in: neu)
            if km > neu.kmStand {
                neu.kmStand = km
                neu.dirty = true
            }
            if GeteilterStatus.fahrzeugId == alteId {
                GeteilterStatus.fahrzeugId = serverId
            }
        }
        try? context.save()
        konto.zusammenfuehren = false
    }

    // MARK: Hochladen

    private func hochladen(_ url: String, _ token: String) async throws {
        let fahrzeuge = try context.fetch(FetchDescriptor<Fahrzeug>(predicate: #Predicate { $0.dirty }))
        for f in fahrzeuge {
            let body = json(f)
            if try await push(url, token, "fahrzeug", f.id, f.aufServer, body) {
                f.aufServer = true
                // Z. B. km-Stand durch ein Fahrtende während des Uploads geändert: dann beim nächsten Mal hochladen.
                if NSDictionary(dictionary: json(f)).isEqual(to: body) { f.dirty = false }
            }
        }
        let fahrten = try context.fetch(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.dirty }))
        for f in fahrten {
            let body = json(f)
            if try await push(url, token, "fahrt", f.id, f.aufServer, body) {
                f.aufServer = true
                // Während einer laufenden Fahrt kann sich der Datensatz inzwischen geändert haben.
                if NSDictionary(dictionary: json(f)).isEqual(to: body) { f.dirty = false }
            }
        }
        let tank = try context.fetch(FetchDescriptor<Tankvorgang>(predicate: #Predicate { $0.dirty }))
        for t in tank {
            let body = json(t)
            if try await push(url, token, "tankvorgang", t.id, t.aufServer, body) {
                t.aufServer = true
                if NSDictionary(dictionary: json(t)).isEqual(to: body) { t.dirty = false }
            }
        }
        try await belegeHochladen(url, token)
        let lokal = Konto.lokal
        let ausgleiche = try context.fetch(FetchDescriptor<Ausgleich>(predicate: #Predicate { $0.dirty }))
        for a in ausgleiche where a.von != lokal && a.an != lokal {
            let body = json(a)
            if try await push(url, token, "ausgleich", a.id, a.aufServer, body) {
                a.aufServer = true
                a.dirty = false
            }
        }
        try? context.save()
    }

    /// Belegfotos nach dem Datensatz hochladen. Fehler hier halten den restlichen Abgleich nicht auf.
    private func belegeHochladen(_ url: String, _ token: String) async throws {
        let offen = try context.fetch(FetchDescriptor<Tankvorgang>(predicate: #Predicate { $0.belegOffen && $0.aufServer && !$0.dirty }))
        for t in offen {
            guard let daten = try? Data(contentsOf: Belege.datei(t.id)) else {
                t.belegOffen = false
                continue
            }
            do {
                let name = try await PocketBase.belegHochladen(server: url, token: token, id: t.id, jpeg: daten)
                // Server ohne Migration 1762600000_beleg kennt das Feld nicht: offen lassen, später nochmal.
                if !name.isEmpty {
                    t.beleg = name
                    t.belegOffen = false
                }
            } catch let f as PBFehler where f.status == 401 {
                throw f
            } catch let f as PBFehler where [403, 404].contains(f.status) {
                // Gehört jemand anderem oder gibt es nicht: nicht ewig wiederholen.
                t.belegOffen = false
            } catch {
                continue
            }
        }
    }

    /// Belegfoto zum Anzeigen: lokal, sonst vom Server (z. B. Foto vom anderen Handy). nil = keins oder offline.
    func belegHolen(_ t: Tankvorgang) async -> URL? {
        let datei = Belege.datei(t.id)
        if Belege.vorhanden(t.id) { return datei }
        guard !t.beleg.isEmpty, let a = konto.anmeldung,
              let url = await PocketBase.ersteErreichbare([a.serverUrl, konto.tailscaleUrl]) else { return nil }
        guard let daten = try? await PocketBase.belegLaden(server: url, token: a.token, id: t.id, name: t.beleg) else { return nil }
        try? daten.write(to: datei, options: .atomic)
        return datei
    }

    /// true = erledigt (hochgeladen oder dauerhaft abgelehnt), false = später nochmal.
    private func push(_ url: String, _ token: String, _ col: String, _ id: String, _ aufServer: Bool, _ body: JSON) async throws -> Bool {
        do {
            if aufServer {
                try await PocketBase.aendern(server: url, token: token, collection: col, id: id, body: body)
            } else {
                var mitId = body
                mitId["id"] = id
                do {
                    try await PocketBase.anlegen(server: url, token: token, collection: col, body: mitId)
                } catch let f as PBFehler where f.status == 400 {
                    // Existiert evtl. schon (Antwort beim letzten Mal verloren gegangen).
                    do {
                        try await PocketBase.aendern(server: url, token: token, collection: col, id: id, body: body)
                    } catch let g as PBFehler where g.status == 404 {
                        // Gibt es nicht und Anlegen ging auch nicht, z. B. weil das Auto noch nicht hochgeladen
                        // ist. Nicht als erledigt markieren, sonst kommt der Datensatz nie auf den Server.
                        return false
                    }
                }
            }
            return true
        } catch let f as PBFehler {
            switch f.status {
            case 401: throw f
            case 400:
                // Nicht still als erledigt markieren, sonst fehlt der Eintrag unbemerkt auf dem Server.
                abgelehnt.append(f.text)
                return false
            case 403, 404: return true
            default: return false
            }
        }
    }

    // MARK: Abholen

    private func abholen(_ url: String, _ token: String) async throws {
        try await abholen(url, token, "users") { o in
            let id = o.s("id")
            let name = o.s("name").isEmpty ? o.s("email") : o.s("name")
            if let b = try self.einer(Benutzer.self, id) { b.name = name } else { self.context.insert(Benutzer(id: id, name: name)) }
        }
        try await abholen(url, token, "fahrzeug") { o in
            let id = o.s("id")
            if let f = try self.einer(Fahrzeug.self, id) {
                if !f.dirty { self.uebernehmen(o, in: f) }
            } else {
                let f = Fahrzeug(id: id, name: o.s("name"))
                self.uebernehmen(o, in: f)
                self.context.insert(f)
            }
        }
        try await abholen(url, token, "fahrt") { o in
            let id = o.s("id")
            if let f = try self.einer(Fahrt.self, id) {
                if !f.dirty { self.uebernehmen(o, in: f) }
            } else {
                let f = Fahrt(id: id, fahrzeugId: "", fahrerId: "", start: Date())
                self.uebernehmen(o, in: f)
                self.context.insert(f)
            }
        }
        try await abholen(url, token, "tankvorgang") { o in
            let id = o.s("id")
            if let t = try self.einer(Tankvorgang.self, id) {
                if !t.dirty { self.uebernehmen(o, in: t) }
            } else {
                let t = Tankvorgang(id: id, fahrzeugId: "", fahrerId: "", datum: Date(), kmStand: 0, liter: 0)
                self.uebernehmen(o, in: t)
                self.context.insert(t)
            }
        }
        try await abholen(url, token, "ausgleich") { o in
            let id = o.s("id")
            if let a = try self.einer(Ausgleich.self, id) {
                if !a.dirty { self.uebernehmen(o, in: a) }
            } else {
                let a = Ausgleich(id: id, von: "", an: "", betrag: 0, zeitraum: "")
                self.uebernehmen(o, in: a)
                self.context.insert(a)
            }
        }
    }

    private func abholen(_ url: String, _ token: String, _ col: String, speichern: (JSON) throws -> Void) async throws {
        let seit = konto.syncStand(col)
        let items: [JSON]
        do {
            items = try await PocketBase.liste(server: url, token: token, collection: col, seit: seit)
        } catch let f as PBFehler where (col == "users" || col == "ausgleich") && [400, 403, 404].contains(f.status) {
            return
        }
        var maxUpdated = seit
        for o in items {
            try speichern(o)
            let u = o.s("updated")
            if u > maxUpdated { maxUpdated = u }
        }
        try? context.save()
        if maxUpdated != seit { konto.setSyncStand(col, maxUpdated) }
    }

    private func einer<T: PersistentModel>(_ typ: T.Type, _ id: String) throws -> T? {
        // Kleine Datenmengen: einfach über alle suchen ist schnell genug und vermeidet generische Predicates.
        switch typ {
        case is Fahrzeug.Type:
            return try context.fetch(FetchDescriptor<Fahrzeug>(predicate: #Predicate { $0.id == id })).first as? T
        case is Fahrt.Type:
            return try context.fetch(FetchDescriptor<Fahrt>(predicate: #Predicate { $0.id == id })).first as? T
        case is Tankvorgang.Type:
            return try context.fetch(FetchDescriptor<Tankvorgang>(predicate: #Predicate { $0.id == id })).first as? T
        case is Ausgleich.Type:
            return try context.fetch(FetchDescriptor<Ausgleich>(predicate: #Predicate { $0.id == id })).first as? T
        case is Benutzer.Type:
            return try context.fetch(FetchDescriptor<Benutzer>(predicate: #Predicate { $0.id == id })).first as? T
        default:
            return nil
        }
    }

    // MARK: Umwandlung

    private func json(_ f: Fahrzeug) -> JSON {
        var j: JSON = ["name": f.name.hoechstens(60), "kennzeichen": f.kennzeichen.hoechstens(15), "km_stand": f.kmStand,
                       "preis_pro_km": f.preisProKm, "farbe": f.farbe, "aktiv": f.aktiv]
        if !f.kraftstoff.isEmpty { j["kraftstoff"] = f.kraftstoff }
        return j
    }

    private func uebernehmen(_ o: JSON, in f: Fahrzeug) {
        f.name = o.s("name")
        f.kennzeichen = o.s("kennzeichen")
        f.kraftstoff = o.s("kraftstoff")
        f.kmStand = o.d("km_stand")
        f.preisProKm = o.d("preis_pro_km")
        f.farbe = o.s("farbe").isEmpty ? "#1565C0" : o.s("farbe")
        f.aktiv = o.b("aktiv")
        f.dirty = false
        f.aufServer = true
    }

    private func json(_ f: Fahrt) -> JSON {
        var j: JSON = ["fahrzeug": f.fahrzeugId, "fahrer": f.fahrerId, "start": PbDatum.text(f.start), "ende": PbDatum.text(f.ende),
         "km_start": f.kmStart, "km_ende": f.kmEnde, "strecke_km": f.streckeKm, "max_kmh": f.maxKmh,
         "schnitt_kmh": f.schnittKmh, "zweck": f.zweck, "start_ort": f.startOrt.hoechstens(200), "ziel_ort": f.zielOrt.hoechstens(200),
         "route": f.route, "notiz": f.notiz.hoechstens(2000), "geloescht": f.geloescht, "test": f.test,
         // mitfahrer nie mitschicken: das setzt nur der Server-Hook, sonst überschreibt ein alter Stand.
         "vorlaeufig": f.vorlaeufig]
        if let d = f.diagnose.data(using: .utf8), !d.isEmpty,
           let o = try? JSONSerialization.jsonObject(with: d) as? JSON {
            j["diagnose"] = o
        }
        return j
    }

    private func uebernehmen(_ o: JSON, in f: Fahrt) {
        f.fahrzeugId = o.s("fahrzeug")
        f.fahrerId = o.s("fahrer")
        f.start = PbDatum.datum(o.s("start")) ?? Date(timeIntervalSince1970: 0)
        f.ende = PbDatum.datum(o.s("ende"))
        f.kmStart = o.d("km_start")
        f.kmEnde = o.d("km_ende")
        f.streckeKm = o.d("strecke_km")
        f.maxKmh = o.d("max_kmh")
        f.schnittKmh = o.d("schnitt_kmh")
        f.zweck = o.s("zweck").isEmpty ? "privat" : o.s("zweck")
        f.startOrt = o.s("start_ort")
        f.zielOrt = o.s("ziel_ort")
        f.route = o.s("route")
        f.notiz = o.s("notiz")
        f.geloescht = o.b("geloescht")
        f.test = o.b("test")
        f.mitfahrer = (o["mitfahrer"] as? [String])?.filter { !$0.isEmpty } ?? []
        f.vorlaeufig = o.b("vorlaeufig")
        if let d = o["diagnose"] as? JSON, let daten = try? JSONSerialization.data(withJSONObject: d),
           let text = String(data: daten, encoding: .utf8) {
            f.diagnose = text
        } else {
            f.diagnose = ""
        }
        f.dirty = false
        f.aufServer = true
    }

    private func json(_ t: Tankvorgang) -> JSON {
        var j: JSON = ["fahrzeug": t.fahrzeugId, "fahrer": t.fahrerId, "datum": PbDatum.text(t.datum), "km_stand": t.kmStand,
                       "liter": t.liter, "preis_pro_liter": t.preisProLiter, "gesamtpreis": t.gesamtpreis,
                       "vollgetankt": t.vollgetankt, "tankstelle": t.tankstelle.hoechstens(200), "notiz": t.notiz.hoechstens(2000), "geloescht": t.geloescht,
                       "test": t.test]
        if !t.ladeort.isEmpty { j["ladeort"] = t.ladeort }
        return j
    }

    private func uebernehmen(_ o: JSON, in t: Tankvorgang) {
        t.fahrzeugId = o.s("fahrzeug")
        t.fahrerId = o.s("fahrer")
        t.datum = PbDatum.datum(o.s("datum")) ?? Date(timeIntervalSince1970: 0)
        t.kmStand = o.d("km_stand")
        t.liter = o.d("liter")
        t.preisProLiter = o.d("preis_pro_liter")
        t.gesamtpreis = o.d("gesamtpreis")
        t.vollgetankt = o.b("vollgetankt")
        t.tankstelle = o.s("tankstelle")
        t.ladeort = o.s("ladeort")
        t.notiz = o.s("notiz")
        t.geloescht = o.b("geloescht")
        t.test = o.b("test")
        // Ein ersetztes Foto vom Server wird beim nächsten Anzeigen neu geladen.
        let beleg = o.s("beleg")
        if beleg != t.beleg && !t.belegOffen { try? FileManager.default.removeItem(at: Belege.datei(t.id)) }
        t.beleg = beleg
        t.dirty = false
        t.aufServer = true
    }

    private func json(_ a: Ausgleich) -> JSON {
        ["von": a.von, "an": a.an, "betrag": a.betrag, "zeitraum": a.zeitraum, "datum": PbDatum.text(a.datum),
         "notiz": a.notiz, "geloescht": a.geloescht]
    }

    private func uebernehmen(_ o: JSON, in a: Ausgleich) {
        a.von = o.s("von")
        a.an = o.s("an")
        a.betrag = o.d("betrag")
        a.zeitraum = o.s("zeitraum")
        a.datum = PbDatum.datum(o.s("datum")) ?? Date(timeIntervalSince1970: 0)
        a.notiz = o.s("notiz")
        a.geloescht = o.b("geloescht")
        a.dirty = false
        a.aufServer = true
    }
}

/// Lokaler Modus: beim allerersten Start die drei Autos anlegen (gleiche Namen wie auf dem Server).
enum Startdaten {
    @MainActor
    static func anlegen() {
        let ctx = Datenbank.container.mainContext
        let vorhanden = (try? ctx.fetch(FetchDescriptor<Fahrzeug>())) ?? []
        guard vorhanden.isEmpty else {
            korrigieren(vorhanden)
            try? ctx.save()
            return
        }
        for (name, farbe, antrieb) in [("E-Auto", "#1565C0", "elektro"), ("Kombi", "#2E7D32", "diesel"),
                                       ("Bus", "#EF6C00", "diesel")] {
            ctx.insert(Fahrzeug(id: neueId(), name: name, kraftstoff: antrieb, farbe: farbe, dirty: false, aufServer: false))
        }
        try? ctx.save()
    }

    /// Ältere Versionen: Antriebsart der drei Startautos setzen.
    /// Der Server macht dasselbe per Migration, deshalb nichts als geändert markieren.
    @MainActor
    private static func korrigieren(_ autos: [Fahrzeug]) {
        let antrieb = ["e-auto": "elektro", "kombi": "diesel", "bus": "diesel"]
        for fz in autos {
            let schluessel = Antrieb.namensSchluessel(fz.name)
            if fz.kraftstoff.isEmpty, let a = antrieb[schluessel] { fz.kraftstoff = a }
        }
    }
}

/// Gemeinsamer SwiftData-Container für App und Intents.
enum Datenbank {
    static let container: ModelContainer = {
        do {
            return try ModelContainer(for: Fahrzeug.self, Benutzer.self, Fahrt.self, Tankvorgang.self, Ausgleich.self)
        } catch {
            fatalError("Datenbank konnte nicht geöffnet werden: \(error)")
        }
    }()
}

extension String {
    /// Auf die Feldlänge des Servers kürzen. PocketBase zählt Unicode-Zeichen (Runen), nicht Grapheme;
    /// ein zu langer Text ließe sonst den ganzen Datensatz mit 400 abprallen.
    func hoechstens(_ n: Int) -> String {
        unicodeScalars.count <= n ? self : String(String.UnicodeScalarView(unicodeScalars.prefix(n)))
    }
}
