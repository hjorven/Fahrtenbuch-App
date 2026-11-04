import CoreLocation
import SwiftData
import SwiftUI
import UIKit

struct FahrtView: View {
    @Query(filter: #Predicate<Fahrzeug> { $0.aktiv }, sort: \Fahrzeug.name) private var fahrzeuge: [Fahrzeug]
    @Query(filter: #Predicate<Fahrt> { !$0.geloescht && $0.ende != nil }, sort: \Fahrt.start, order: .reverse)
    private var fahrten: [Fahrt]
    @Query(filter: #Predicate<Fahrt> { !$0.geloescht && $0.ende == nil }) private var offene: [Fahrt]
    @Query private var benutzer: [Benutzer]

    @State private var recorder = FahrtRecorder.shared
    @State private var konto = Konto.shared
    @State private var gewaehlt: String? = GeteilterStatus.fahrzeugId
    @State private var fehler: String?
    @State private var beendenFragen = false
    @State private var vergessen: [Bewegung.Abschnitt] = []
    @State private var nachtragen: Bewegung.Abschnitt?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let l = recorder.laufend {
                    LaufendeKarte(fahrt: l, autoStop: konto.autoStopMinuten)
                    Button(role: .destructive) {
                        beendenFragen = true
                    } label: {
                        Label("Fahrt beenden", systemImage: "stop.fill")
                            .font(.title3.bold())
                            .frame(maxWidth: .infinity, minHeight: 56)
                    }
                    .buttonStyle(.glassProminent)
                    .tint(.red)
                    .confirmationDialog("Fahrt beenden?", isPresented: $beendenFragen, titleVisibility: .visible) {
                        Button("Ja, beenden", role: .destructive) { Task { await recorder.beenden() } }
                        Button("Weiterfahren", role: .cancel) {}
                    } message: {
                        Text("Die Fahrt wird gespeichert.")
                    }
                } else {
                    ForEach(vergessen) { a in
                        Button { nachtragen = a } label: {
                            Label("Fahrt vergessen? \(a.von.formatted(date: .omitted, time: .shortened))–\(a.bis.formatted(date: .omitted, time: .shortened)) → Nachtragen",
                                  systemImage: "car.badge.gearshape")
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding()
                                .background(.yellow.opacity(0.2), in: .rect(cornerRadius: 14))
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button("Keine Fahrt, ausblenden", systemImage: "eye.slash") {
                                var weg = UserDefaults.standard.array(forKey: "vergessenAus") as? [Double] ?? []
                                weg = Array((weg + [a.von.timeIntervalSince1970]).suffix(50))
                                UserDefaults.standard.set(weg, forKey: "vergessenAus")
                                vergessen.removeAll { $0 == a }
                            }
                        }
                    }
                    Text("Auto wählen").font(.headline)
                    if fahrzeuge.isEmpty {
                        Text("Noch keine Autos. Bitte oben rechts synchronisieren.")
                            .foregroundStyle(.secondary)
                    }
                    let n = namen(benutzer)
                    let u = unterwegs()
                    ForEach(fahrzeuge) { fz in
                        FahrzeugKarte(fahrzeug: fz, gewaehlt: fz.id == gewaehlt,
                                      unterwegs: u[fz.id].map { "unterwegs mit \(n[$0.fahrerId] ?? "jemandem") seit \(F.uhrzeit($0.start))" }) {
                            waehlen(fz)
                        }
                    }
                    if let fehler {
                        Text(fehler).foregroundStyle(.red).font(.body)
                        if recorder.berechtigung == .denied || recorder.berechtigung == .restricted {
                            Text("Bitte in den Einstellungen bei \"Standort\" \"Immer\" oder \"Beim Verwenden der App\" wählen.")
                                .font(.body)
                            Button("Einstellungen öffnen") {
                                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                            }
                            .buttonStyle(.bordered)
                            .frame(minHeight: 44)
                        }
                    }
                    let autoGewaehlt = gewaehlt != nil && fahrzeuge.contains { $0.id == gewaehlt }
                    if !autoGewaehlt && !fahrzeuge.isEmpty {
                        Text("Zuerst oben das Auto antippen, mit dem du fährst.")
                            .font(.body)
                            .foregroundStyle(.tint)
                    }
                    Button {
                        starten()
                    } label: {
                        Label("Fahrt starten", systemImage: "play.fill")
                            .font(.title3.bold())
                            .frame(maxWidth: .infinity, minHeight: 56)
                    }
                    .buttonStyle(.glassProminent)
                    .disabled(!autoGewaehlt)
                }

                let letzte = Array(fahrten.prefix(3))
                if !letzte.isEmpty {
                    Text("Letzte Fahrten").font(.headline).padding(.top, 8)
                    let n = namen(benutzer)
                    VStack(spacing: 0) {
                        ForEach(letzte) { f in
                            NavigationLink {
                                FahrtDetailView(fahrt: f)
                            } label: {
                                FahrtZeile(fahrt: f, fahrzeug: fahrzeuge.first { $0.id == f.fahrzeugId }, fahrer: n[f.fahrerId])
                                    .padding(.horizontal)
                                    .padding(.vertical, 10)
                            }
                            .buttonStyle(.plain)
                            if f.id != letzte.last?.id { Divider().padding(.leading) }
                        }
                    }
                    .background(.background.secondary, in: .rect(cornerRadius: 20))
                }
            }
            .padding()
        }
        .navigationTitle("Fahrtenbuch")
        .toolbar { StandardToolbar() }
        .task(id: fahrten.count) { await vergesseneSuchen() }
        .sheet(item: $nachtragen, onDismiss: { Task { await vergesseneSuchen() } }) { a in
            FahrtFormular(fahrzeuge: fahrzeuge, zeitraum: a)
        }
    }

    /// Roadmap Nr. 7: iOS hat eine Autofahrt erkannt, die nicht im Fahrtenbuch steht.
    private func vergesseneSuchen() async {
        let eigene = fahrten.filter { $0.fahrerId == konto.userId }.compactMap { f in f.ende.map { (f.start, $0) } }
        let weg = Set(UserDefaults.standard.array(forKey: "vergessenAus") as? [Double] ?? [])
        vergessen = await Bewegung.shared.vergesseneFahrten(fahrten: eigene)
            .filter { !weg.contains($0.von.timeIntervalSince1970) }
    }

    /// Wer gerade mit welchem Auto unterwegs ist (laufende Fahrten der anderen, höchstens 12 Stunden alt).
    private func unterwegs() -> [String: Fahrt] {
        let ich = konto.userId
        let grenze = Date().addingTimeInterval(-12 * 3600)
        var m: [String: Fahrt] = [:]
        for f in offene where f.fahrerId != ich && f.start > grenze {
            if let v = m[f.fahrzeugId], v.start > f.start { continue }
            m[f.fahrzeugId] = f
        }
        return m
    }

    private func waehlen(_ fz: Fahrzeug) {
        // Erneutes Antippen hebt die Auswahl auf.
        let neu: Fahrzeug? = gewaehlt == fz.id ? nil : fz
        gewaehlt = neu?.id
        GeteilterStatus.fahrzeugId = neu?.id
        GeteilterStatus.fahrzeugName = neu?.name
        WidgetKitHelfer.neuLaden()
    }

    private func starten() {
        fehler = nil
        Task {
            do {
                try await recorder.starten(fahrzeugId: gewaehlt)
            } catch {
                fehler = error.localizedDescription
            }
        }
    }
}

struct LaufendeKarte: View {
    let fahrt: LaufendeFahrt
    let autoStop: Int

    var body: some View {
        VStack(spacing: 12) {
            Text(fahrt.fahrzeugName).font(.headline)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(F.zahl(fahrt.kmh, 0))
                    .font(.system(size: 72, weight: .bold, design: .rounded))
                    .contentTransition(.numericText())
                    .monospacedDigit()
                Text("km/h").font(.title3)
            }
            HStack {
                Wert(titel: "Strecke") { Text(F.km(fahrt.km)) }
                Wert(titel: "Dauer") { Text(fahrt.start, style: .timer) }
                Wert(titel: "Max") { Text("\(F.zahl(fahrt.maxKmh, 0)) km/h") }
            }
            if let seit = fahrt.stehtSeit, Date().timeIntervalSince(seit) > 60 {
                Text(Bewegung.shared.imFahrzeug(Date())
                     ? "Steht seit \(F.dauer(Date().timeIntervalSince(seit))), Stau erkannt: Fahrt läuft weiter"
                     : "Steht seit \(F.dauer(Date().timeIntervalSince(seit))), endet automatisch nach \(autoStop) min")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(24)
        .glassEffect(.regular.tint(.accentColor.opacity(0.15)), in: .rect(cornerRadius: 28))
        .animation(.default, value: fahrt.kmh)
    }
}

private struct Wert<Inhalt: View>: View {
    let titel: String
    @ViewBuilder let inhalt: Inhalt

    var body: some View {
        VStack(spacing: 2) {
            inhalt.font(.title3.monospacedDigit())
            Text(titel).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

struct FahrzeugKarte: View {
    let fahrzeug: Fahrzeug
    let gewaehlt: Bool
    var unterwegs: String? = nil
    let aktion: () -> Void

    var body: some View {
        Button(action: aktion) {
            HStack(spacing: 14) {
                Circle().fill(Color(hex: fahrzeug.farbe)).frame(width: 14, height: 14)
                VStack(alignment: .leading, spacing: 2) {
                    Text(fahrzeug.name).font(.headline)
                    Text(F.kmStand(fahrzeug.kmStand)).font(.subheadline).foregroundStyle(.secondary)
                    if let unterwegs {
                        Text(unterwegs).font(.caption).foregroundStyle(.orange)
                    }
                }
                Spacer()
                if gewaehlt {
                    Image(systemName: "checkmark.circle.fill").font(.title2).foregroundStyle(.tint)
                }
            }
            .padding()
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .glassEffect(gewaehlt ? .regular.tint(.accentColor.opacity(0.25)).interactive() : .regular.interactive(),
                     in: .rect(cornerRadius: 20))
    }
}

struct FahrtZeile: View {
    let fahrt: Fahrt
    let fahrzeug: Fahrzeug?
    let fahrer: String?
    /// In der Monatsliste steht der Tag mit dabei.
    var mitTag = false

    var body: some View {
        HStack(spacing: 12) {
            Circle().fill(Color(hex: fahrzeug?.farbe ?? "")).frame(width: 10, height: 10)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(fahrt.test ? "TEST · " : "")\(fahrzeug?.name ?? "Auto") · \(F.km(fahrt.km))").font(.body)
                Text(untertitel).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
        }
    }

    private var untertitel: String {
        let zeit = fahrt.ende.map { "\(F.uhrzeit(fahrt.start))–\(F.uhrzeit($0))" } ?? "seit \(F.uhrzeit(fahrt.start)), läuft"
        let ziel = fahrt.zielOrt.isEmpty ? "" : " · \(fahrt.zielOrt)"
        let tag = mitTag ? fahrt.start.formatted(.dateTime.weekday(.abbreviated).day().month(.twoDigits).locale(F.de)) + " · " : ""
        return "\(tag)\(fahrer ?? "Fahrer") · \(zeit)\(ziel)"
    }
}
