import CoreLocation
import SwiftUI

/// Beobachtet die Standort-Berechtigung, damit die Einrichtung sofort reagiert.
@MainActor @Observable
final class StandortRechte: NSObject, CLLocationManagerDelegate {
    @ObservationIgnored private let manager: CLLocationManager
    var status: CLAuthorizationStatus
    var genau: Bool

    override init() {
        let m = CLLocationManager()
        manager = m
        status = m.authorizationStatus
        genau = m.accuracyAuthorization == .fullAccuracy
        super.init()
        m.delegate = self
    }

    func wennInBenutzung() { manager.requestWhenInUseAuthorization() }
    func immer() { manager.requestAlwaysAuthorization() }

    nonisolated func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        let s = m.authorizationStatus
        let g = m.accuracyAuthorization == .fullAccuracy
        Task { @MainActor in
            self.status = s
            self.genau = g
        }
    }
}

/// Einrichtung beim ersten Start: Standort und Standort "Immer".
/// Auf dem iPhone braucht die Live-Aktivität keine Benachrichtigungs-Erlaubnis,
/// und eine Akku-Optimierung wie bei Android gibt es nicht.
struct EinrichtungView: View {
    var fertig: () -> Void
    @State private var rechte = StandortRechte()
    @State private var immerGefragt = false
    @State private var bewegungOk = Bewegung.erlaubt
    @State private var bewegungAbgelehnt = Bewegung.abgelehnt

    private var standortOk: Bool { rechte.status == .authorizedWhenInUse || rechte.status == .authorizedAlways }
    private var immerOk: Bool { rechte.status == .authorizedAlways }
    private var abgelehnt: Bool { rechte.status == .denied || rechte.status == .restricted }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Willkommen").font(.largeTitle.bold())
                Text("Damit Fahrten zuverlässig aufgezeichnet werden, braucht die App Zugriff auf deinen Standort. Alles bleibt auf deinem iPhone, solange du keinen Server verbindest.")
                    .foregroundStyle(.secondary)

                schritt(
                    symbol: "location.fill",
                    titel: "Standort",
                    text: abgelehnt
                        ? "Abgelehnt. Bitte in den iOS-Einstellungen unter Standort \"Beim Verwenden der App\" wählen."
                        : (standortOk && !rechte.genau
                            ? "Bitte \"Genauer Standort\" einschalten, sonst wird die Strecke ungenau."
                            : "Für Strecke, Kilometer und Geschwindigkeit."),
                    erledigt: standortOk,
                    knopf: abgelehnt ? "Einstellungen" : "Erlauben",
                    aktiv: true
                ) {
                    if abgelehnt { einstellungen() } else { rechte.wennInBenutzung() }
                }

                schritt(
                    symbol: "location.circle.fill",
                    titel: "Standort immer",
                    text: standortOk
                        ? "Damit Widget, Kontrollzentrum und Kurzbefehle Fahrten auch ohne geöffnete App starten. Im Fenster \"Zu Immer ändern\" wählen."
                        : "Zuerst den Standort erlauben.",
                    erledigt: immerOk,
                    knopf: immerGefragt ? "Einstellungen" : "Erlauben",
                    aktiv: standortOk
                ) {
                    if immerGefragt {
                        einstellungen()
                    } else {
                        immerGefragt = true
                        rechte.immer()
                    }
                }

                if Bewegung.verfuegbar {
                    schritt(
                        symbol: "car.fill",
                        titel: "Bewegung",
                        text: bewegungAbgelehnt
                            ? "Abgelehnt. Im Stau kann die Fahrt dann nach der Stillstandszeit enden."
                            : "Erkennt Stau: Solange du im Auto sitzt, endet die Fahrt nicht automatisch.",
                        erledigt: bewegungOk,
                        knopf: bewegungAbgelehnt ? "Einstellungen" : "Erlauben",
                        aktiv: true
                    ) {
                        if bewegungAbgelehnt {
                            einstellungen()
                        } else {
                            Task {
                                await Bewegung.shared.erfragen()
                                bewegungOk = Bewegung.erlaubt
                                bewegungAbgelehnt = Bewegung.abgelehnt
                            }
                        }
                    }
                }

                Button {
                    fertig()
                } label: {
                    Text(immerOk ? "Los geht's" : "Weiter")
                        .font(.title3.bold())
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.glassProminent)
                .padding(.top, 8)

                if !immerOk {
                    Text("Fehlende Berechtigungen kannst du später unter Einstellungen > Berechtigungen nachholen.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .padding()
        }
        .onAppear {
            // Wie bei anderen Apps direkt mit der ersten Abfrage anfangen.
            if rechte.status == .notDetermined { rechte.wennInBenutzung() }
        }
    }

    private func einstellungen() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }

    @ViewBuilder
    private func schritt(symbol: String, titel: String, text: String, erledigt: Bool,
                         knopf: String, aktiv: Bool, aktion: @escaping () -> Void) -> some View {
        HStack(alignment: .center, spacing: 14) {
            Image(systemName: symbol)
                .font(.title2)
                .foregroundStyle(.tint)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 4) {
                Text(titel).font(.headline)
                Text(text).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            if erledigt {
                Image(systemName: "checkmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(.green)
            } else {
                Button(knopf, action: aktion)
                    .buttonStyle(.glass)
                    .disabled(!aktiv)
            }
        }
        .padding()
        .glassEffect(.regular, in: .rect(cornerRadius: 20))
    }
}
