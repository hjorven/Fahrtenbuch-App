import MapKit
import SwiftData
import SwiftUI

/// Alle Fahrten auf einer Karte, als Linien oder als Heatmap (wo wird am meisten gefahren).
struct KarteView: View {
    @Query(filter: #Predicate<Fahrt> { !$0.geloescht && !$0.test && $0.ende != nil }, sort: \Fahrt.start, order: .reverse)
    private var fahrten: [Fahrt]
    @Query(sort: \Fahrzeug.name) private var fahrzeuge: [Fahrzeug]
    @State private var heatmap = false
    @State private var filter: String?
    @State private var zeitraum = Zeitraum.alle

    private struct Linie: Identifiable {
        let id: String
        let punkte: [CLLocationCoordinate2D]
        let farbe: Color
    }

    var body: some View {
        let g = zeitraum.grenzen()
        let auswahl = fahrten.filter { f in
            if let filter, f.fahrzeugId != filter { return false }
            if let g, f.start < g.0 || f.start >= g.1 { return false }
            return !f.route.isEmpty
        }
        let farben = Dictionary(fahrzeuge.map { ($0.id, Color(hex: $0.farbe)) }, uniquingKeysWith: { a, _ in a })
        let routen = auswahl.map { (f: $0, p: Polyline.decode($0.route)) }.filter { $0.p.count >= 2 }

        Map(initialPosition: .automatic) {
            if heatmap {
                ForEach(Karte.heatmap(routen.map(\.p))) { z in
                    MapCircle(center: z.mitte, radius: 300)
                        .foregroundStyle(farbe(z.staerke).opacity(0.25 + 0.5 * z.staerke))
                }
            } else {
                ForEach(routen.prefix(400).map { Linie(id: $0.f.id, punkte: Karte.ausduennen($0.p), farbe: farben[$0.f.fahrzeugId] ?? .blue) }) { l in
                    MapPolyline(coordinates: l.punkte)
                        .stroke(l.farbe.opacity(0.7), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                }
            }
        }
        .overlay {
            if routen.isEmpty {
                ContentUnavailableView("Keine Strecken", systemImage: "map", description: Text("Für diese Auswahl gibt es keine aufgezeichneten Routen."))
            }
        }
        .safeAreaInset(edge: .top) {
            VStack(spacing: 8) {
                Picker("Ansicht", selection: $heatmap) {
                    Text("Fahrten").tag(false)
                    Text("Heatmap").tag(true)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        FilterKnopf(text: "Alle", farbe: nil, aktiv: filter == nil) { filter = nil }
                        ForEach(fahrzeuge.filter(\.aktiv)) { fz in
                            FilterKnopf(text: fz.name, farbe: Color(hex: fz.farbe), aktiv: filter == fz.id) {
                                filter = filter == fz.id ? nil : fz.id
                            }
                        }
                        ForEach(Zeitraum.allCases, id: \.self) { z in
                            FilterKnopf(text: z.text, farbe: nil, aktiv: zeitraum == z) { zeitraum = z }
                        }
                    }
                    .padding(.horizontal)
                }
            }
            .padding(.vertical, 6)
        }
        .navigationTitle("Karte")
        .navigationBarTitleDisplayMode(.inline)
    }

    /// Gelb (selten) bis Rot (oft).
    private func farbe(_ s: Double) -> Color {
        Color(hue: 0.15 * (1 - s), saturation: 0.9, brightness: 0.95)
    }
}
