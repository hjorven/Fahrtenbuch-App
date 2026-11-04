import Foundation
import Observation
import UIKit

/// Neue Version, die der Pi in der SideStore-Quelle anbietet.
struct NeueVersion: Equatable {
    let build: Int
    let version: String
}

/// Fragt die SideStore-Quelle auf dem Pi (Port 8091, apps.json) nach einer neueren Build-Nummer.
/// Installieren kann nur SideStore selbst: Der Hinweis öffnet SideStore, dort einmal "Aktualisieren".
@MainActor
@Observable
final class UpdatePruefer {
    static let shared = UpdatePruefer()
    static let port = 8091

    private(set) var neu: NeueVersion?
    private(set) var meldung: String?
    /// "Später" gilt nur bis zum nächsten Start der App.
    var ausgeblendet = false
    private var zuletzt: Date = .distantPast

    static var installierterBuild: Int {
        Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0
    }

    static var installierteVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }

    /// Basis-Adressen: derselbe Rechner wie der Server, aber Port 8091. Zuletzt fahrtenbuch.local.
    nonisolated static func quellen(_ serverUrls: [String]) -> [String] {
        var liste: [String] = []
        for s in serverUrls + ["http://fahrtenbuch.local"] {
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !t.isEmpty, let u = URLComponents(string: t.contains("://") ? t : "http://\(t)"),
                  let host = u.host, !host.isEmpty else { continue }
            let h = host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
            let b = "http://\(h):\(port)/"
            if !liste.contains(b) { liste.append(b) }
        }
        return liste
    }

    /// Neueste Build-Nummer aus apps.json (SideStore-Format). Reine Funktion für Tests.
    nonisolated static func auswerten(_ daten: Data, bundleId: String) -> NeueVersion? {
        guard let o = try? JSONSerialization.jsonObject(with: daten) as? [String: Any],
              let apps = o["apps"] as? [[String: Any]] else { return nil }
        let app = apps.first { ($0["bundleIdentifier"] as? String) == bundleId } ?? apps.first
        guard let app else { return nil }
        let versionen = (app["versions"] as? [[String: Any]]) ?? []
        let beste = versionen.compactMap { v -> NeueVersion? in
            guard let b = Int(v["buildVersion"] as? String ?? "") else { return nil }
            return NeueVersion(build: b, version: v["version"] as? String ?? "")
        }.max { $0.build < $1.build }
        return beste
    }

    func pruefen(erzwingen: Bool = false) async {
        if !erzwingen && Date().timeIntervalSince(zuletzt) < 3 * 3600 { return }
        let k = Konto.shared
        let adressen = Self.quellen([k.anmeldung?.serverUrl ?? "", k.letzteServerUrl, k.tailscaleUrl])
        let bundleId = Bundle.main.bundleIdentifier ?? "de.hjorven.fahrtenbuch"
        var gefunden: NeueVersion?
        var erreicht = false
        for b in adressen {
            guard let url = URL(string: b + "apps.json") else { continue }
            var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 4)
            req.httpMethod = "GET"
            guard let (daten, antwort) = try? await URLSession.shared.data(for: req),
                  (antwort as? HTTPURLResponse)?.statusCode == 200 else { continue }
            erreicht = true
            gefunden = Self.auswerten(daten, bundleId: bundleId)
            break
        }
        guard erreicht else {
            if erzwingen { meldung = "Keine Verbindung zum Heim-Server. Bitte zuhause im WLAN versuchen." }
            return
        }
        zuletzt = Date()
        if let g = gefunden, g.build > Self.installierterBuild {
            neu = g
            meldung = nil
        } else {
            neu = nil
            meldung = erzwingen ? "Die App ist aktuell." : nil
        }
    }

    /// SideStore öffnen. Klappt das nicht (z. B. abgelaufen), bleibt der Hinweis stehen.
    func sideStoreOeffnen() {
        guard let url = URL(string: "sidestore://") else { return }
        UIApplication.shared.open(url) { ok in
            if !ok {
                Task { @MainActor in
                    UpdatePruefer.shared.meldung = "SideStore ließ sich nicht öffnen. Bitte SideStore auf dem Home-Bildschirm antippen."
                }
            }
        }
    }
}
