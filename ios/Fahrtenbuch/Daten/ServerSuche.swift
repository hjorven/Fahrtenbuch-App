import Foundation
import Network

/// Sucht den Server im WLAN über Bonjour (_fahrtenbuch._tcp, Avahi auf dem Pi, Roadmap Nr. 3).
/// Liefert z. B. "http://192.168.1.10:8090" oder nil nach `warten` Sekunden.
/// Zweiter Weg (Nachtplan 5c): Die Adresse lässt sich im Login weiter von Hand eintragen.
enum ServerSuche {
    static func suchen(warten: TimeInterval = 5) async -> String? {
        await withCheckedContinuation { fortsetzen in
            let browser = NWBrowser(for: .bonjour(type: "_fahrtenbuch._tcp", domain: nil), using: .tcp)
            let schlange = DispatchQueue(label: "serversuche")
            var fertig = false
            func ende(_ url: String?) {
                schlange.async {
                    guard !fertig else { return }
                    fertig = true
                    browser.cancel()
                    fortsetzen.resume(returning: url)
                }
            }
            browser.browseResultsChangedHandler = { ergebnisse, _ in
                guard let erster = ergebnisse.first else { return }
                // Dienst auflösen: kurz verbinden und die Gegenstelle (IP, Port) ablesen.
                let verbindung = NWConnection(to: erster.endpoint, using: .tcp)
                verbindung.stateUpdateHandler = { zustand in
                    if case .ready = zustand, case let .hostPort(host, port)? = verbindung.currentPath?.remoteEndpoint {
                        var h = "\(host)"
                        if let p = h.firstIndex(of: "%") { h = String(h[..<p]) }
                        if h.contains(":") { h = "[\(h)]" }
                        ende("http://\(h):\(port.rawValue)")
                        verbindung.cancel()
                    } else if case .failed = zustand {
                        verbindung.cancel()
                    }
                }
                verbindung.start(queue: schlange)
            }
            browser.start(queue: schlange)
            schlange.asyncAfter(deadline: .now() + warten) { ende(nil) }
        }
    }
}
