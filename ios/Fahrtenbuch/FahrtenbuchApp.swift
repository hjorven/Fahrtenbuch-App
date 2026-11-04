import AppIntents
import CoreLocation
import SwiftData
import SwiftUI

@main
struct FahrtenbuchApp: App {
    @Environment(\.scenePhase) private var phase

    init() {
        // Auch wenn die App nur für einen Intent im Hintergrund startet.
        Startdaten.anlegen()
        FahrtRecorder.shared.fortsetzenFallsNoetig()
        Monatsabrechnung.shared.einrichten()
    }

    var body: some Scene {
        WindowGroup {
            Wurzel()
        }
        .modelContainer(Datenbank.container)
        .onChange(of: phase) { _, neu in
            if neu == .active {
                Task { await SyncManager.shared.sync() }
                Task { await UpdatePruefer.shared.pruefen() }
            }
        }
    }
}

/// Siri und Kurzbefehle, z. B. Automation "Wenn mit Bluetooth Auto verbunden".
struct FahrtenbuchShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: FahrtStartenIntent(), phrases: ["Fahrt starten mit \(.applicationName)"],
                    shortTitle: "Fahrt starten", systemImageName: "car.fill")
        AppShortcut(intent: FahrtFragenIntent(), phrases: ["Frag ob ich fahre mit \(.applicationName)"],
                    shortTitle: "Fährst du?", systemImageName: "questionmark.circle")
        AppShortcut(intent: FahrtBeendenIntent(), phrases: ["Fahrt beenden mit \(.applicationName)"],
                    shortTitle: "Fahrt beenden", systemImageName: "stop.circle.fill")
    }
}

/// Kein Anmeldezwang: ohne Server läuft die App rein lokal.
/// Die Einrichtung erscheint auch wieder, solange der Standort fehlt: ohne ihn geht nichts.
struct Wurzel: View {
    @AppStorage("einrichtungFertig") private var einrichtungFertig = false
    @State private var standortFehlt = Wurzel.ohneStandort()

    var body: some View {
        if einrichtungFertig && !standortFehlt {
            Hauptansicht()
        } else {
            EinrichtungView {
                einrichtungFertig = true
                standortFehlt = false
            }
        }
    }

    private static func ohneStandort() -> Bool {
        let s = CLLocationManager().authorizationStatus
        return s != .authorizedAlways && s != .authorizedWhenInUse
    }
}

struct Hauptansicht: View {
    /// "Später" gilt bis zum nächsten Start der App.
    @State private var passwortSpaeter = false
    @State private var passwortZeigen = false

    var body: some View {
        tabs
            .sheet(isPresented: $passwortZeigen, onDismiss: { passwortSpaeter = true }) { PasswortView() }
            .task(id: Konto.shared.passwortAendern) { passwortPruefen() }
            .onChange(of: FahrtRecorder.shared.laufend == nil) { _, _ in passwortPruefen() }
    }

    /// Nicht während einer Fahrt fragen.
    private func passwortPruefen() {
        if Konto.shared.passwortAendern && Konto.shared.anmeldung != nil && !passwortSpaeter
            && FahrtRecorder.shared.laufend == nil {
            passwortZeigen = true
        }
    }

    private var tabs: some View {
        TabView {
            Tab("Fahrt", systemImage: "car.fill") {
                NavigationStack { FahrtView() }
                    .offlineHinweis()
            }
            Tab("Fahrten", systemImage: "list.bullet") {
                NavigationStack { FahrtenView() }
                    .offlineHinweis()
            }
            Tab("Tanken", systemImage: "fuelpump.fill") {
                NavigationStack { TankenView() }
                    .offlineHinweis()
            }
            Tab("Statistik", systemImage: "chart.bar.fill") {
                NavigationStack { StatistikView() }
                    .offlineHinweis()
            }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
    }
}

/// Hinweis oben in jedem Tab, wenn der Server weder im Heimnetz noch über Tailscale antwortet.
struct OfflineHinweis: ViewModifier {
    private var sync: SyncManager { SyncManager.shared }
    @State private var neuAnmelden = false

    func body(content: Content) -> some View {
        content.sheet(isPresented: $neuAnmelden) { NavigationStack { LoginView() } }
        .safeAreaInset(edge: .top, spacing: 0) {
          VStack(spacing: 0) {
            // Test-Modus: gut sichtbar in allen Tabs, damit er nicht aus Versehen an bleibt.
            if Konto.shared.testModus {
                HStack(spacing: 10) {
                    Image(systemName: "flask.fill")
                    Text("TEST-MODUS: neue Fahrten und Tankungen zählen nicht mit. Ausschalten in den Einstellungen.")
                        .font(.subheadline.bold())
                    Spacer(minLength: 0)
                }
                .foregroundStyle(.black)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity)
                .background(Color.yellow)
            }
            if Konto.shared.anmeldung != nil && sync.status == .offline {
                Button {
                    Task { await sync.sync() }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "icloud.slash")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Keine Verbindung zum Heim-Server").font(.subheadline.bold())
                            Text("Alles wird auf dem iPhone gespeichert und später übertragen. Tippen: erneut versuchen.").font(.subheadline)
                        }
                        Spacer(minLength: 0)
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity)
                    .background(Color.red.opacity(0.85))
                }
                .buttonStyle(.plain)
            }
            // Ohne diesen Hinweis würde nur das kleine Sync-Symbol rot, und Fahrten blieben unbemerkt auf dem iPhone.
            if Konto.shared.anmeldung != nil && sync.status == .abgelaufen {
                Button {
                    neuAnmelden = true
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "person.crop.circle.badge.exclamationmark")
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Anmeldung abgelaufen").font(.subheadline.bold())
                            Text("Tippen, um dich neu anzumelden. Deine Fahrten bleiben auf dem iPhone.").font(.subheadline)
                        }
                        Spacer(minLength: 0)
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity)
                    .background(Color.red.opacity(0.85))
                }
                .buttonStyle(.plain)
            }
            UpdateBanner()
          }
        }
    }
}

/// Großer Hinweis auf eine neue Version, mit den Schritten für SideStore.
/// Nicht während einer Fahrt: SideStore beendet die App beim Aktualisieren.
struct UpdateBanner: View {
    private var pruefer: UpdatePruefer { UpdatePruefer.shared }

    var body: some View {
        if let neu = pruefer.neu, !pruefer.ausgeblendet, FahrtRecorder.shared.laufend == nil {
            VStack(alignment: .leading, spacing: 10) {
                Label("Neue Version der App ist da", systemImage: "arrow.down.app.fill")
                    .font(.headline)
                Text("1. LocalDevVPN einschalten.\n2. Unten auf \"SideStore öffnen\" tippen.\n3. Bei Fahrtenbuch auf \"Update\" tippen und warten.\nAlle Fahrten bleiben erhalten.")
                    .font(.body)
                    .fixedSize(horizontal: false, vertical: true)
                if let m = pruefer.meldung {
                    Text(m).font(.subheadline.bold())
                }
                HStack {
                    Button {
                        pruefer.sideStoreOeffnen()
                    } label: {
                        Text("SideStore öffnen")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    Button("Später") { pruefer.ausgeblendet = true }
                        .frame(minHeight: 44)
                }
                Text("Version \(neu.version), installiert: \(UpdatePruefer.installierteVersion)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.blue.opacity(0.15))
        }
    }
}

extension View {
    func offlineHinweis() -> some View { modifier(OfflineHinweis()) }
}

/// Gemeinsame Toolbar: Sync-Status und Einstellungen.
struct StandardToolbar: ToolbarContent {
    private var sync: SyncManager { SyncManager.shared }

    var body: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            if Konto.shared.anmeldung != nil {
            Button {
                Task { await sync.sync() }
            } label: {
                switch sync.status {
                case .laeuft: ProgressView()
                case .fehler, .abgelaufen: Image(systemName: "exclamationmark.arrow.triangle.2.circlepath")
                case .offline: Image(systemName: "icloud.slash")
                case .bereit: Image(systemName: "arrow.triangle.2.circlepath")
                }
            }
            }
            NavigationLink {
                EinstellungenView()
            } label: {
                Image(systemName: "gearshape")
            }
        }
    }
}

extension View {
    /// Namen der Benutzer aus der Datenbank, der eigene aus der Anmeldung.
    func namen(_ benutzer: [Benutzer]) -> [String: String] {
        var m = Dictionary(benutzer.map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
        if let a = Konto.shared.anmeldung, !a.name.isEmpty { m[a.userId] = a.name }
        m[Konto.lokal] = Konto.shared.lokalerName
        return m
    }
}
