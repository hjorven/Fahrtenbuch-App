import SwiftData
import SwiftUI

/// Zeitraum-Filter der Fahrtenliste (wie in der Android-App).
enum Zeitraum: String, CaseIterable {
    case alle, monat, vormonat, jahr

    var text: String {
        switch self {
        case .alle: "Alle"
        case .monat: "Dieser Monat"
        case .vormonat: "Letzter Monat"
        case .jahr: "Dieses Jahr"
        }
    }

    /// [von, bis), nil = keine Grenze.
    func grenzen(_ jetzt: Date = Date()) -> (Date, Date)? {
        let cal = Calendar.current
        let monat = cal.date(from: cal.dateComponents([.year, .month], from: jetzt))!
        switch self {
        case .alle: return nil
        case .monat: return (monat, cal.date(byAdding: .month, value: 1, to: monat)!)
        case .vormonat: return (cal.date(byAdding: .month, value: -1, to: monat)!, monat)
        case .jahr:
            let jahr = cal.date(from: cal.dateComponents([.year], from: jetzt))!
            return (jahr, cal.date(byAdding: .year, value: 1, to: jahr)!)
        }
    }
}

struct FahrtenView: View {
    @Query(filter: #Predicate<Fahrt> { !$0.geloescht }, sort: \Fahrt.start, order: .reverse) private var fahrten: [Fahrt]
    @Query(sort: \Fahrzeug.name) private var fahrzeuge: [Fahrzeug]
    @Query private var benutzer: [Benutzer]
    @State private var filter: String?
    @State private var fahrerFilter: String?
    @State private var zweckFilter: String?
    @State private var zeitraum = Zeitraum.alle
    @State private var mehrFilter = false
    @State private var nachtragen = false

    var body: some View {
        let g = zeitraum.grenzen()
        let liste = fahrten.filter { passt($0, g) }
        let cal = Calendar.current
        let monate = Dictionary(grouping: liste) { cal.date(from: cal.dateComponents([.year, .month], from: $0.start))! }
        let n = namen(benutzer)
        let fahrer = Array(Set(fahrten.map(\.fahrerId))).sorted { (n[$0] ?? $0) < (n[$1] ?? $1) }

        List {
            ForEach(monate.keys.sorted(by: >), id: \.self) { monat in
                let eintraege = monate[monat] ?? []
                Section {
                    ForEach(eintraege) { f in
                        NavigationLink {
                            FahrtDetailView(fahrt: f)
                        } label: {
                            FahrtZeile(fahrt: f, fahrzeug: fahrzeuge.first { $0.id == f.fahrzeugId }, fahrer: n[f.fahrerId], mitTag: true)
                        }
                    }
                } header: {
                    HStack {
                        Text(F.monat(monat))
                        Spacer()
                        // Test-Fahrten zählen nicht mit.
                        // Test-Fahrten getrennt nennen, sonst steht "0 Fahrten" über einer sichtbaren Fahrt.
                        let echte = eintraege.filter { !$0.test }
                        let tests = eintraege.count - echte.count
                        Text("\(echte.count) \(echte.count == 1 ? "Fahrt" : "Fahrten") · \(F.km(echte.reduce(0) { $0 + $1.km }))\(tests > 0 ? " · \(tests) Test" : "")")
                    }
                }
            }
        }
        .overlay {
            if liste.isEmpty {
                if fahrten.isEmpty {
                    ContentUnavailableView("Noch keine Fahrten", systemImage: "car", description: Text("Gestartete Fahrten aller Geräte erscheinen hier."))
                } else {
                    ContentUnavailableView("Keine Fahrten für diesen Filter", systemImage: "line.3.horizontal.decrease.circle")
                }
            }
        }
        .safeAreaInset(edge: .top) {
            VStack(spacing: 0) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        FilterKnopf(text: "Filter", farbe: nil, aktiv: mehrFilter) { mehrFilter.toggle() }
                        FilterKnopf(text: "Alle", farbe: nil, aktiv: filter == nil) { filter = nil }
                        ForEach(fahrzeuge.filter(\.aktiv)) { fz in
                            FilterKnopf(text: fz.name, farbe: Color(hex: fz.farbe), aktiv: filter == fz.id) {
                                filter = filter == fz.id ? nil : fz.id
                            }
                        }
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 6)
                }
                if mehrFilter {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(Zeitraum.allCases, id: \.self) { z in
                                FilterKnopf(text: z.text, farbe: nil, aktiv: zeitraum == z) { zeitraum = z }
                            }
                        }
                        .padding(.horizontal)
                        .padding(.vertical, 6)
                    }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(fahrer, id: \.self) { id in
                                FilterKnopf(text: n[id] ?? "Fahrer", farbe: nil, aktiv: fahrerFilter == id) {
                                    fahrerFilter = fahrerFilter == id ? nil : id
                                }
                            }
                            ForEach(F.zwecke, id: \.0) { z in
                                FilterKnopf(text: z.1, farbe: nil, aktiv: zweckFilter == z.0) {
                                    zweckFilter = zweckFilter == z.0 ? nil : z.0
                                }
                            }
                        }
                        .padding(.horizontal)
                        .padding(.vertical, 6)
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            Button {
                nachtragen = true
            } label: {
                Label("Nachtragen", systemImage: "plus")
                    .font(.headline)
                    .padding(.horizontal, 8)
                    .frame(minHeight: 36)
            }
            .buttonStyle(.glassProminent)
            .padding(.bottom, 8)
        }
        .navigationTitle("Fahrten")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                NavigationLink {
                    KarteView()
                } label: {
                    Image(systemName: "map")
                }
            }
            // Roadmap Nr. 22: gefilterte Fahrten als CSV teilen
            ToolbarItem(placement: .topBarLeading) {
                if let datei = csvDatei(liste, n) {
                    ShareLink(item: datei) { Image(systemName: "square.and.arrow.up") }
                }
            }
            StandardToolbar()
        }
        .refreshable { await SyncManager.shared.sync() }
        .sheet(isPresented: $nachtragen) {
            FahrtFormular(fahrzeuge: fahrzeuge.filter(\.aktiv))
        }
    }

    private func csvDatei(_ liste: [Fahrt], _ n: [String: String]) -> URL? {
        let autos = Dictionary(fahrzeuge.map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
        let zeilen = liste.filter { !$0.test && !$0.geloescht && $0.ende != nil }.map {
            Export.Zeile(start: $0.start, ende: $0.ende!, auto: autos[$0.fahrzeugId] ?? "", fahrer: n[$0.fahrerId] ?? "",
                         zweck: $0.zweck, km: $0.km, kmStart: $0.kmStart, kmEnde: $0.kmEnde,
                         von: $0.startOrt, nach: $0.zielOrt, notiz: $0.notiz)
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("fahrtenbuch.csv")
        guard (try? Export.csv(zeilen).write(to: url, atomically: true, encoding: .utf8)) != nil else { return nil }
        return url
    }

    private func passt(_ f: Fahrt, _ g: (Date, Date)?) -> Bool {
        if let filter, f.fahrzeugId != filter { return false }
        if let fahrerFilter, f.fahrerId != fahrerFilter { return false }
        if let zweckFilter, f.zweck != zweckFilter { return false }
        if let g, f.start < g.0 || f.start >= g.1 { return false }
        return true
    }
}

struct FilterKnopf: View {
    let text: String
    let farbe: Color?
    let aktiv: Bool
    let aktion: () -> Void

    var body: some View {
        Button(action: aktion) {
            HStack(spacing: 6) {
                if let farbe { Circle().fill(farbe).frame(width: 8, height: 8) }
                Text(text).font(.subheadline.weight(aktiv ? .semibold : .regular))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
        .glassEffect(aktiv ? .regular.tint(.accentColor.opacity(0.3)).interactive() : .regular.interactive(), in: .capsule)
    }
}
