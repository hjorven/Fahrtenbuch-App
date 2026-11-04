import MapKit
import SwiftData
import SwiftUI

struct FahrtDetailView: View {
    @Bindable var fahrt: Fahrt
    @Query private var fahrzeuge: [Fahrzeug]
    @Query private var benutzer: [Benutzer]
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var loeschenFragen = false
    @State private var zweck = ""
    @State private var notiz = ""
    @State private var gespeichert = false
    @State private var bearbeiten = false
    @State private var tacho = false
    @State private var teilen = false
    @State private var naechste: Fahrt?
    @State private var zusammenFragen = false
    @State private var mitFehler: String?
    @State private var fahrerWaehlen = false

    private var eigene: Bool { fahrt.fahrerId == Konto.shared.userId }

    var body: some View {
        let fz = fahrzeuge.first { $0.id == fahrt.fahrzeugId }
        let punkte = Polyline.decode(fahrt.route)
        let n = namen(benutzer)

        Form {
            if punkte.count >= 2 {
                Section {
                    Map(initialPosition: .automatic, interactionModes: [.zoom, .pan]) {
                        MapPolyline(coordinates: punkte)
                            .stroke(Color(hex: fz?.farbe ?? ""), style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
                        if let a = punkte.first { Marker("Start", systemImage: "flag", coordinate: a).tint(.green) }
                        if let b = punkte.last { Marker("Ziel", systemImage: "flag.checkered", coordinate: b).tint(.red) }
                    }
                    .frame(height: 280)
                    .listRowInsets(EdgeInsets())
                }
            }
            Section {
                LabeledContent("Auto", value: fz?.name ?? "Auto")
                LabeledContent("Fahrer", value: n[fahrt.fahrerId] ?? "Fahrer")
                LabeledContent("Datum", value: F.tag(fahrt.start))
                if let ende = fahrt.ende {
                    LabeledContent("Zeit", value: "\(F.uhrzeit(fahrt.start))–\(F.uhrzeit(ende)) (\(F.dauer(ende.timeIntervalSince(fahrt.start))))")
                } else {
                    LabeledContent("Zeit", value: "läuft seit \(F.uhrzeit(fahrt.start))")
                }
                LabeledContent("Strecke", value: F.km(fahrt.km))
                if abs(fahrt.km - fahrt.streckeKm) > 0.05 && fahrt.streckeKm > 0 {
                    LabeledContent("GPS gemessen", value: F.km(fahrt.streckeKm))
                }
                if !fahrt.startOrt.isEmpty || !fahrt.zielOrt.isEmpty {
                    LabeledContent("Von", value: fahrt.startOrt)
                    LabeledContent("Nach", value: fahrt.zielOrt)
                }
                LabeledContent("Kilometerstand", value: "\(F.kmStand(fahrt.kmStart)) → \(F.kmStand(fahrt.kmEnde))")
                LabeledContent("Durchschnitt", value: "\(F.zahl(fahrt.schnittKmh, 0)) km/h")
                LabeledContent("Höchstgeschwindigkeit", value: "\(F.zahl(fahrt.maxKmh, 0)) km/h")
            }
            if fahrt.test {
                let diagnose = Diagnose.aus(fahrt.diagnose)
                Section {
                    if let diagnose {
                        ForEach(Array(diagnose.zeilen().enumerated()), id: \.offset) { _, z in
                            LabeledContent(z.0, value: z.1)
                        }
                    } else {
                        Text("Keine Diagnose gespeichert.")
                    }
                    ShareLink(item: diagnoseText(fz?.name ?? "", diagnose), subject: Text("Fahrtenbuch Diagnose")) {
                        Label("Diagnose exportieren", systemImage: "square.and.arrow.up")
                    }
                } header: {
                    Text("Test-Fahrt")
                } footer: {
                    Text("Zählt nicht in Statistik, km-Stand und Display.")
                }
            }
            Section("Zweck") {
                Picker("Zweck", selection: $zweck) {
                    ForEach(F.zwecke, id: \.0) { Text($0.1).tag($0.0) }
                }
                .pickerStyle(.segmented)
                .disabled(!eigene)
            }
            Section("Notiz") {
                TextField("Notiz", text: $notiz, axis: .vertical)
                    .disabled(!eigene)
            }
            if eigene && fahrt.ende != nil {
                Section {
                    Button("Tacho-Stand eintragen", systemImage: "gauge.with.dots.needle.50percent") { tacho = true }
                    Button("Bearbeiten", systemImage: "pencil") { bearbeiten = true }
                    Button("Teilen", systemImage: "scissors") { teilen = true }
                    Button("Mit nächster Fahrt zusammenlegen", systemImage: "arrow.triangle.merge") {
                        naechste = naechsteFahrt()
                        zusammenFragen = true
                    }
                }
            }
            // Roadmap Nr. 6: vorläufige Fahrt bestätigen, Mitfahrer, Fahrer ändern.
            if eigene && fahrt.vorlaeufig && fahrt.ende != nil {
                Section {
                    Button("Ja, das war meine Fahrt") { fahrt.vorlaeufig = false; fahrt.dirty = true; speichernUndSync() }
                    Button("Jemand anderes ist gefahren") { fahrerWaehlen = true }.disabled(!fahrt.aufServer)
                    Button("Löschen", role: .destructive) { loeschenFragen = true }
                } header: {
                    Text("War das deine Fahrt?")
                } footer: {
                    Text("Automatisch aufgezeichnet. Sie zählt erst, wenn du sie bestätigst.")
                }
            }
            if fahrt.ende != nil && !fahrt.test {
                Section {
                    LabeledContent("Mitfahrer", value: fahrt.mitfahrer.isEmpty ? "niemand" : fahrt.mitfahrer.map { n[$0] ?? "jemand" }.joined(separator: ", "))
                    if !eigene {
                        let dabei = fahrt.mitfahrer.contains(Konto.shared.userId)
                        Button(dabei ? "Ich war doch nicht dabei" : "Ich bin mitgefahren") { Task { await mitfahren(raus: dabei) } }
                    }
                    if let mitFehler { Text(mitFehler).font(.footnote).foregroundStyle(.red) }
                } footer: {
                    if !fahrt.mitfahrer.isEmpty {
                        Text("Die Kosten der Fahrt teilen sich \(1 + fahrt.mitfahrer.count) Personen, die km zählen beim Fahrer.")
                    }
                }
            }
            if eigene {
                Section {
                    if fahrt.ende != nil && fahrt.aufServer && !fahrt.test {
                        Button("Fahrer ändern") { fahrerWaehlen = true }
                    }
                    Button("Fahrt löschen", role: .destructive) { loeschenFragen = true }
                }
            } else {
                Section {
                    Text("Nur \(n[fahrt.fahrerId] ?? "der Fahrer") kann diese Fahrt bearbeiten.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(fz?.name ?? "Fahrt")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if eigene && (zweck != fahrt.zweck || notiz != fahrt.notiz) {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Sichern") {
                        sichern()
                        withAnimation { gespeichert = true }
                        Task {
                            try? await Task.sleep(for: .seconds(1.5))
                            withAnimation { gespeichert = false }
                        }
                    }
                }
            }
        }
        .onAppear {
            zweck = fahrt.zweck
            notiz = fahrt.notiz
        }
        // Zurück ohne "Sichern" verlor sonst still die Änderungen an Zweck und Notiz.
        .onDisappear {
            if eigene && !fahrt.geloescht && (zweck != fahrt.zweck || notiz.trimmingCharacters(in: .whitespacesAndNewlines) != fahrt.notiz) {
                sichern()
            }
        }
        .overlay(alignment: .bottom) {
            if gespeichert {
                Text("Gespeichert")
                    .font(.subheadline.bold())
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(.thinMaterial, in: Capsule())
                    .padding(.bottom, 24)
                    .transition(.opacity)
            }
        }
        .onChange(of: fahrt.zweck) { zweck = fahrt.zweck }
        .onChange(of: fahrt.notiz) { notiz = fahrt.notiz }
        .sheet(isPresented: $bearbeiten) {
            FahrtFormular(fahrzeuge: fahrzeuge.filter { $0.aktiv || $0.id == fahrt.fahrzeugId }, vorhanden: fahrt)
        }
        .sheet(isPresented: $tacho) {
            TachoFormular(fahrt: fahrt).presentationDetents([.medium])
        }
        .sheet(isPresented: $teilen) {
            TeilenView(fahrt: fahrt, farbe: Color(hex: fz?.farbe ?? ""))
        }
        .confirmationDialog(zusammenTitel, isPresented: $zusammenFragen, titleVisibility: .visible) {
            if let b = naechste {
                Button("Zusammenlegen") { zusammenlegen(b) }
            }
        } message: {
            if let b = naechste {
                Text("Die Fahrt ab \(F.uhrzeit(b.start)) (\(F.km(b.km))) wird an diese angehängt.")
            } else {
                Text("Es gibt keine spätere eigene Fahrt mit diesem Auto.")
            }
        }
        .confirmationDialog("Wer ist gefahren?", isPresented: $fahrerWaehlen, titleVisibility: .visible) {
            ForEach(benutzer.filter { $0.id != Konto.shared.userId && $0.id != Konto.lokal }, id: \.id) { b in
                Button(b.name) {
                    fahrt.fahrerId = b.id
                    fahrt.vorlaeufig = false
                    fahrt.dirty = true
                    speichernUndSync()
                    dismiss()
                }
            }
        } message: {
            Text("Die Fahrt gehört dann dieser Person und zählt dort. Zurück geht es nur über sie.")
        }
        .confirmationDialog("Fahrt löschen?", isPresented: $loeschenFragen, titleVisibility: .visible) {
            Button("Löschen", role: .destructive) {
                fahrt.geloescht = true
                fahrt.dirty = true
                try? context.save()
                Task { await SyncManager.shared.sync() }
                dismiss()
            }
        } message: {
            Text("Die Fahrt verschwindet auf allen Geräten.")
        }
    }

    private var zusammenTitel: String { naechste == nil ? "Keine nächste Fahrt" : "Fahrten zusammenlegen?" }

    private func speichernUndSync() {
        try? context.save()
        Task { await SyncManager.shared.sync() }
    }

    private func mitfahren(raus: Bool) async {
        guard let a = Konto.shared.anmeldung else { mitFehler = "Mitfahren geht nur mit Server-Anmeldung."; return }
        guard let url = await PocketBase.ersteErreichbare([a.serverUrl, Konto.shared.tailscaleUrl]) else { mitFehler = Fehlertext.offline; return }
        do {
            fahrt.mitfahrer = try await PocketBase.mitfahren(server: url, token: a.token, fahrt: fahrt.id, raus: raus)
            try? context.save()
            mitFehler = nil
        } catch {
            mitFehler = Fehlertext.aus(error, "Mitfahren")
        }
    }

    private func sichern() {
        fahrt.zweck = zweck
        fahrt.notiz = notiz.trimmingCharacters(in: .whitespacesAndNewlines)
        fahrt.dirty = true
        try? context.save()
        Task { await SyncManager.shared.sync() }
    }

    /// Nächste eigene Fahrt mit demselben Auto, die sich anhängen lässt.
    private func diagnoseText(_ auto: String, _ d: Diagnose?) -> String {
        var s = "Test-Fahrt \(auto), \(F.tag(fahrt.start)), \(F.km(fahrt.km))\n"
        for (t, w) in d?.zeilen() ?? [] { s += "\(t): \(w)\n" }
        return s + "\n" + fahrt.diagnose
    }

    private func naechsteFahrt() -> Fahrt? {
        guard let ende = fahrt.ende else { return nil }
        let fahrer = fahrt.fahrerId
        let auto = fahrt.fahrzeugId
        let ohne = fahrt.id
        let test = fahrt.test
        var d = FetchDescriptor<Fahrt>(
            predicate: #Predicate {
                $0.fahrerId == fahrer && $0.fahrzeugId == auto && !$0.geloescht && $0.ende != nil && $0.start >= ende && $0.id != ohne
                    && $0.test == test
            },
            sortBy: [SortDescriptor(\.start)]
        )
        d.fetchLimit = 1
        return try? context.fetch(d).first
    }

    private func zusammenlegen(_ b: Fahrt) {
        let m = FahrtBearbeitung.zusammenlegen(FahrtBearbeitung.Werte(fahrt), FahrtBearbeitung.Werte(b))
        m.schreiben(in: fahrt)
        b.geloescht = true
        b.dirty = true
        try? context.save()
        Task { await SyncManager.shared.sync() }
    }
}

/// Fahrt an einem Punkt der Route teilen (z. B. Zwischenstopp, den die Stillstandserkennung nicht beendet hat).
struct TeilenView: View {
    let fahrt: Fahrt
    let farbe: Color
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var index = 1.0
    @State private var anteilOhneRoute = 0.5

    var body: some View {
        let punkte = Polyline.decode(fahrt.route)
        let mitRoute = punkte.count >= 3
        let i = min(max(Int(index.rounded()), 1), max(punkte.count - 2, 1))
        let anteil = mitRoute ? FahrtBearbeitung.anteilBeiPunkt(punkte, i) : anteilOhneRoute
        let km1 = fahrt.km * anteil

        NavigationStack {
            Form {
                if mitRoute {
                    Section {
                        Map(initialPosition: .automatic, interactionModes: [.zoom, .pan]) {
                            MapPolyline(coordinates: Array(punkte[0...i]))
                                .stroke(farbe, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                            MapPolyline(coordinates: Array(punkte[i...]))
                                .stroke(.gray, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                            Marker("Teilen", systemImage: "scissors", coordinate: punkte[i]).tint(.orange)
                        }
                        .frame(height: 280)
                        .listRowInsets(EdgeInsets())
                        Slider(value: $index, in: 1...Double(max(punkte.count - 2, 2)), step: 1)
                    }
                } else {
                    Section {
                        Slider(value: $anteilOhneRoute, in: 0.05...0.95)
                    } footer: {
                        Text("Diese Fahrt hat keine Route, die Strecke wird anteilig aufgeteilt.")
                    }
                }
                Section {
                    LabeledContent("Erster Teil", value: F.km(km1))
                    LabeledContent("Zweiter Teil", value: F.km(fahrt.km - km1))
                } footer: {
                    Text("Die Uhrzeit am Teilpunkt wird anhand der Strecke geschätzt und lässt sich danach bearbeiten.")
                }
            }
            .navigationTitle("Fahrt teilen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Teilen") { teilen(anteil: anteil, punkt: mitRoute ? i : nil) }
                }
            }
            .onAppear { index = Double(max(punkte.count / 2, 1)) }
        }
    }

    private func teilen(anteil: Double, punkt: Int?) {
        let (eins, zwei) = FahrtBearbeitung.teilen(FahrtBearbeitung.Werte(fahrt), anteil: anteil, punkt: punkt)
        eins.schreiben(in: fahrt)
        let neu = Fahrt(fahrzeugId: fahrt.fahrzeugId, fahrerId: fahrt.fahrerId, start: zwei.start)
        neu.zweck = fahrt.zweck
        neu.test = fahrt.test
        zwei.schreiben(in: neu)
        context.insert(neu)
        try? context.save()
        Task { await SyncManager.shared.sync() }
        dismiss()
    }
}
