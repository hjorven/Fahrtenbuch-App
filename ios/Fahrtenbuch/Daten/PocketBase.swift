import Foundation

struct PBFehler: LocalizedError {
    let status: Int
    let text: String
    var errorDescription: String? { text }
}

typealias JSON = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func s(_ k: String) -> String { (self[k] as? String) ?? "" }
    func d(_ k: String) -> Double { (self[k] as? NSNumber)?.doubleValue ?? 0 }
    func b(_ k: String) -> Bool { (self[k] as? Bool) ?? false }
}

/// Minimaler PocketBase-Client: Login, Token erneuern, Liste seit Zeitpunkt, Anlegen, Ändern.
enum PocketBase {
    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 15
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    /// Kurze Zeitlimits, damit beim Ausprobieren der Adressen niemand lange wartet.
    private static let pruefSession: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 4
        c.timeoutIntervalForResource = 5
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }()

    /// Antwortet unter dieser Adresse ein PocketBase-Server?
    static func erreichbar(_ server: String) async -> Bool {
        guard let url = URL(string: server + "/api/health") else { return false }
        do {
            let (_, resp) = try await pruefSession.data(from: url)
            return ((resp as? HTTPURLResponse)?.statusCode ?? 0) == 200
        } catch {
            return false
        }
    }

    /// Erste erreichbare Adresse in der angegebenen Reihenfolge (leere und doppelte werden übersprungen).
    static func ersteErreichbare(_ adressen: [String]) async -> String? {
        var gesehen = Set<String>()
        for roh in adressen {
            let u = Konto.normalisieren(roh)
            if u.isEmpty || !gesehen.insert(u).inserted { continue }
            if await erreichbar(u) { return u }
        }
        return nil
    }

    struct Login {
        let token: String
        let userId: String
        let name: String
        /// Konto hat noch das Start-Passwort (Feld users.passwort_aendern).
        var passwortAendern = false
    }

    static func login(server: String, email: String, passwort: String) async throws -> Login {
        let res = try await senden(server, nil, "POST", "/api/collections/users/auth-with-password",
                                   ["identity": email, "password": passwort])
        let record = res["record"] as? JSON ?? [:]
        return Login(token: res.s("token"), userId: record.s("id"), name: record.s("name"),
                     passwortAendern: record.b("passwort_aendern"))
    }

    private static func anmeldung(_ res: JSON) -> Login {
        let record = res["record"] as? JSON ?? [:]
        return Login(token: res.s("token"), userId: record.s("id"), name: record.s("name"),
                     passwortAendern: record.b("passwort_aendern"))
    }

    /// Konto mit Einladungscode anlegen (Roadmap Nr. 1/2), danach angemeldet.
    static func beitreten(server: String, code: String, name: String, email: String, passwort: String) async throws -> Login {
        anmeldung(try await senden(server, nil, "POST", "/api/fahrtenbuch/beitreten",
                                   ["code": code, "name": name, "email": email, "passwort": passwort]))
    }

    /// Neues Passwort mit Reset-Code vom Admin (Nachtplan 5c), danach angemeldet.
    static func zuruecksetzen(server: String, code: String, passwort: String) async throws -> Login {
        anmeldung(try await senden(server, nil, "POST", "/api/fahrtenbuch/zuruecksetzen", ["code": code, "passwort": passwort]))
    }

    /// "Problem melden" (Roadmap Nr. 21): nur der Admin liest die Berichte.
    static func bericht(server: String, token: String, userId: String, text: String, version: String, geraet: String) async throws {
        _ = try await senden(server, token, "POST", "/api/collections/bericht/records",
                             ["von": userId, "text": String(text.prefix(4000)), "app_version": version, "geraet": geraet])
    }

    /// Einladungscode (24 h, einmal benutzbar).
    /// "Ich fahre mit" bzw. austragen (Roadmap Nr. 6). Gibt die neue Mitfahrer-Liste zurück.
    static func mitfahren(server: String, token: String, fahrt: String, raus: Bool) async throws -> [String] {
        (try await senden(server, token, "POST", "/api/fahrtenbuch/mitfahren", ["fahrt": fahrt, "raus": raus])["mitfahrer"] as? [String]) ?? []
    }

    static func einladung(server: String, token: String) async throws -> String {
        try await senden(server, token, "POST", "/api/fahrtenbuch/einladung", [:]).s("code")
    }

    /// Adressen des Servers (Roadmap Nr. 3).
    static func info(server: String, token: String) async throws -> JSON {
        guard let url = URL(string: server.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/api/fahrtenbuch/info") else { return [:] }
        var req = URLRequest(url: url)
        req.setValue(token, forHTTPHeaderField: "Authorization")
        return try await ausfuehren(req)
    }

    /// Neues Token und ob das Konto noch das Start-Passwort hat.
    static func tokenErneuern(server: String, token: String) async throws -> (token: String, passwortAendern: Bool) {
        let res = try await senden(server, token, "POST", "/api/collections/users/auth-refresh", [:])
        let record = res["record"] as? JSON ?? [:]
        return (res.s("token"), record.b("passwort_aendern"))
    }

    /// Eigenes Passwort ändern. Danach gilt das alte Token nicht mehr, also neu anmelden.
    static func passwortAendern(server: String, token: String, userId: String, alt: String, neu: String) async throws {
        _ = try await senden(server, token, "PATCH", "/api/collections/users/records/\(userId)",
                             ["oldPassword": alt, "password": neu, "passwordConfirm": neu])
    }

    /// Alle Datensätze mit updated >= seit, aufsteigend sortiert, über alle Seiten.
    static func liste(server: String, token: String, collection: String, seit: String) async throws -> [JSON] {
        var alle: [JSON] = []
        var seite = 1
        while true {
            var c = URLComponents(string: server.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/api/collections/\(collection)/records")!
            var q = [
                URLQueryItem(name: "page", value: String(seite)),
                URLQueryItem(name: "perPage", value: "200"),
                URLQueryItem(name: "sort", value: "updated"),
                URLQueryItem(name: "skipTotal", value: "1"),
            ]
            if !seit.isEmpty { q.append(URLQueryItem(name: "filter", value: "updated >= '\(seit)'")) }
            c.queryItems = q
            var req = URLRequest(url: c.url!)
            req.setValue(token, forHTTPHeaderField: "Authorization")
            let res = try await ausfuehren(req)
            let items = res["items"] as? [JSON] ?? []
            alle.append(contentsOf: items)
            let perPage = (res["perPage"] as? NSNumber)?.intValue ?? 200
            if items.count < perPage { break }
            seite += 1
        }
        return alle
    }

    static func anlegen(server: String, token: String, collection: String, body: JSON) async throws {
        _ = try await senden(server, token, "POST", "/api/collections/\(collection)/records", body)
    }

    static func aendern(server: String, token: String, collection: String, id: String, body: JSON) async throws {
        _ = try await senden(server, token, "PATCH", "/api/collections/\(collection)/records/\(id)", body)
    }

    /// Belegfoto als Datei an einen Tankvorgang hängen (multipart PATCH). Gibt den Dateinamen zurück ("" = Server kennt das Feld nicht).
    static func belegHochladen(server: String, token: String, id: String, jpeg: Data) async throws -> String {
        guard let url = URL(string: server.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/api/collections/tankvorgang/records/\(id)") else {
            throw PBFehler(status: 0, text: "Ungültige Server-Adresse")
        }
        let grenze = "fahrtenbuch-\(UUID().uuidString)"
        var body = Data()
        body.append("--\(grenze)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"beleg\"; filename=\"beleg.jpg\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: image/jpeg\r\n\r\n".data(using: .utf8)!)
        body.append(jpeg)
        body.append("\r\n--\(grenze)--\r\n".data(using: .utf8)!)
        var req = URLRequest(url: url)
        req.httpMethod = "PATCH"
        req.setValue("multipart/form-data; boundary=\(grenze)", forHTTPHeaderField: "Content-Type")
        req.setValue(token, forHTTPHeaderField: "Authorization")
        req.httpBody = body
        req.timeoutInterval = 60
        return try await ausfuehren(req).s("beleg")
    }

    /// Belegfoto vom Server holen. Die Fotos sind geschützt, deshalb erst ein Datei-Token.
    static func belegLaden(server: String, token: String, id: String, name: String) async throws -> Data {
        let dateiToken = try await senden(server, token, "POST", "/api/files/token", [:]).s("token")
        var c = URLComponents(string: server.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/api/files/tankvorgang/\(id)/\(name)")!
        c.queryItems = [URLQueryItem(name: "token", value: dateiToken)]
        let (data, resp) = try await session.data(from: c.url!)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw PBFehler(status: status, text: "HTTP \(status)") }
        return data
    }

    private static func senden(_ server: String, _ token: String?, _ methode: String, _ pfad: String, _ body: JSON) async throws -> JSON {
        guard let url = URL(string: server.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + pfad) else {
            throw PBFehler(status: 0, text: "Ungültige Server-Adresse")
        }
        var req = URLRequest(url: url)
        req.httpMethod = methode
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token { req.setValue(token, forHTTPHeaderField: "Authorization") }
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await ausfuehren(req)
    }

    private static func ausfuehren(_ req: URLRequest) async throws -> JSON {
        let (data, resp) = try await session.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) as? JSON ?? [:]
        guard (200..<300).contains(status) else {
            throw PBFehler(status: status, text: (json["message"] as? String) ?? "HTTP \(status)")
        }
        return json
    }
}
