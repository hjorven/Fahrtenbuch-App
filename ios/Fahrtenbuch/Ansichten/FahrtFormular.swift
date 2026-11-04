import SwiftData
import SwiftUI

/// Fahrt nachtragen (ohne GPS, z. B. vergessen oder Handy leer) oder eine eigene Fahrt bearbeiten.
struct FahrtFormular: View {
    let fahrzeuge: [Fahrzeug]
    /// Gesetzt = diese Fahrt bearbeiten, sonst neue Fahrt nachtragen.
    var vorhanden: Fahrt? = nil
    /// Vorbelegte Zeit beim Nachtragen ("Fahrt vergessen?", Roadmap Nr. 7).
    var zeitraum: Bewegung.Abschnitt? = nil
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss

    @State private var fahrzeugId = GeteilterStatus.fahrzeugId ?? ""
    @State private var start = Date().addingTimeInterval(-3600)
    @State private var ende = Date()
    @State private var kmStart = ""
    @State private var kmEnde = ""
    @State private var zweck = "privat"
    @State private var startOrt = ""
    @State private var zielOrt = ""
    @State private var notiz = ""

    private var fz: Fahrzeug? { fahrzeuge.first { $0.id == fahrzeugId } }
    private var kS: Double { F.zahl(aus: kmStart.replacingOccurrences(of: ".", with: "")) ?? 0 }
    private var kE: Double { F.zahl(aus: kmEnde.replacingOccurrences(of: ".", with: "")) ?? 0 }
    /// Mit GPS aufgezeichnet: die gemessene Strecke bleibt erhalten.
    private var mitGps: Bool { (vorhanden?.route.isEmpty == false) }

    private var fehler: String? {
        if fz == nil { return "Bitte ein Auto wählen" }
        if let f = Plausibel.fahrt(start: start, ende: ende, kmStart: kS, kmEnde: kE) { return f }
        if !mitGps && kE <= kS { return "Bitte km-Stand am Start und am Ende eintragen" }
        return nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Auto") {
                    Picker("Auto", selection: $fahrzeugId) {
                        ForEach(fahrzeuge) { Text($0.name).tag($0.id) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
                Section("Zeit") {
                    DatePicker("Abfahrt", selection: $start)
                    DatePicker("Ankunft", selection: $ende, in: start...)
                }
                Section {
                    TextField("km-Stand Start", text: $kmStart).keyboardType(.numberPad)
                    TextField("km-Stand Ende", text: $kmEnde).keyboardType(.numberPad)
                    TachoScanKnopf(bisher: kS > 0 ? kS : (fz?.kmStand ?? 0)) {
                        kmEnde = String($0)
                    }
                } header: {
                    Text("Kilometer")
                } footer: {
                    if kE > kS { Text("Gefahren: \(F.km(kE - kS))") }
                }
                Section("Zweck") {
                    Picker("Zweck", selection: $zweck) {
                        ForEach(F.zwecke, id: \.0) { Text($0.1).tag($0.0) }
                    }
                    .pickerStyle(.segmented)
                }
                Section("Strecke") {
                    TextField("Von", text: $startOrt)
                    TextField("Nach", text: $zielOrt)
                    TextField("Notiz", text: $notiz, axis: .vertical)
                }
                if let fehler {
                    Section { Text(fehler).foregroundStyle(.red).font(.footnote) }
                }
            }
            .navigationTitle(vorhanden == nil ? "Fahrt nachtragen" : "Fahrt bearbeiten")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Sichern") { sichern() }.disabled(fehler != nil) }
            }
            .onAppear(perform: laden)
            .onChange(of: fahrzeugId) {
                if vorhanden == nil, let fz, fz.kmStand > 0, kmStart.isEmpty { kmStart = F.zahl(fz.kmStand, 0) }
            }
        }
    }

    private func laden() {
        if let f = vorhanden {
            fahrzeugId = f.fahrzeugId
            start = f.start
            ende = f.ende ?? Date()
            kmStart = f.kmStart > 0 ? F.zahl(f.kmStart, 0) : ""
            kmEnde = f.kmEnde > 0 ? F.zahl(f.kmEnde, 0) : ""
            zweck = f.zweck
            startOrt = f.startOrt
            zielOrt = f.zielOrt
            notiz = f.notiz
        } else {
            if let z = zeitraum { start = z.von; ende = z.bis }
            if fz == nil { fahrzeugId = fahrzeuge.first?.id ?? "" }
            if let fz, fz.kmStand > 0 { kmStart = F.zahl(fz.kmStand, 0) }
        }
    }

    private func sichern() {
        guard fehler == nil, let fz else { return }
        let f: Fahrt
        if let v = vorhanden {
            f = v
        } else {
            f = Fahrt(fahrzeugId: fz.id, fahrerId: Konto.shared.userId, start: start)
            f.test = Konto.shared.testModus
            context.insert(f)
        }
        f.fahrzeugId = fz.id
        f.start = start
        f.ende = ende
        f.kmStart = kS
        f.kmEnde = kE
        if !mitGps { f.streckeKm = max(kE - kS, 0) }
        let stunden = ende.timeIntervalSince(start) / 3600
        f.schnittKmh = stunden > 0 ? f.km / stunden : 0
        f.zweck = zweck
        f.startOrt = startOrt.trimmingCharacters(in: .whitespaces)
        f.zielOrt = zielOrt.trimmingCharacters(in: .whitespaces)
        f.notiz = notiz.trimmingCharacters(in: .whitespacesAndNewlines)
        f.dirty = true
        if !f.test && kE > fz.kmStand {
            fz.kmStand = kE
            fz.dirty = true
        }
        try? context.save()
        Task { await SyncManager.shared.sync() }
        dismiss()
    }
}

/// Tacho-Stand am Ende eintragen: km_ende wird ersetzt, die GPS-Strecke bleibt zum Vergleich.
struct TachoFormular: View {
    @Bindable var fahrt: Fahrt
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Query private var fahrzeuge: [Fahrzeug]
    @State private var text = ""

    private var wert: Double? { F.zahl(aus: text.replacingOccurrences(of: ".", with: "")) }
    private var fehler: String? {
        guard let wert else { return nil }
        if fahrt.kmStart > 0 && wert <= fahrt.kmStart { return "Muss größer als der Start-Stand (\(F.kmStand(fahrt.kmStart))) sein" }
        return Plausibel.fahrt(start: fahrt.start, ende: fahrt.ende, kmStart: startNeu(wert), kmEnde: wert)
    }

    private func startNeu(_ wert: Double) -> Double {
        Plausibel.startAusTacho(kmStart: fahrt.kmStart, kmEnde: wert, gpsKm: fahrt.streckeKm)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Tacho am Ende", text: $text).keyboardType(.numberPad)
                    TachoScanKnopf(bisher: fahrt.kmStart) { text = String($0) }
                } footer: {
                    if let wert, fehler == nil {
                        let km = wert - startNeu(wert)
                        let abw = km > 0 ? (fahrt.streckeKm - km) / km * 100 : 0
                        Text("Tacho: \(F.km(km)), GPS: \(F.km(fahrt.streckeKm)) (\(abw >= 0 ? "+" : "")\(F.zahl(abw)) %)")
                    } else if let fehler {
                        Text(fehler).foregroundStyle(.red)
                    } else {
                        Text("Start: \(F.kmStand(fahrt.kmStart)). Die gefahrenen km kommen dann vom Tacho statt vom GPS.")
                    }
                }
            }
            .navigationTitle("Tacho-Abgleich")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Sichern") { sichern() }.disabled(wert == nil || fehler != nil)
                }
            }
            .onAppear { if fahrt.kmEnde > 0 { text = F.zahl(fahrt.kmEnde, 0) } }
        }
    }

    private func sichern() {
        guard let wert, fehler == nil else { return }
        fahrt.kmStart = startNeu(wert)
        fahrt.kmEnde = wert
        let stunden = (fahrt.ende ?? fahrt.start).timeIntervalSince(fahrt.start) / 3600
        if stunden > 0 { fahrt.schnittKmh = fahrt.km / stunden }
        fahrt.dirty = true
        let id = fahrt.fahrzeugId
        if !fahrt.test, let fz = fahrzeuge.first(where: { $0.id == id }), wert > fz.kmStand {
            fz.kmStand = wert
            fz.dirty = true
        }
        try? context.save()
        Task { await SyncManager.shared.sync() }
        dismiss()
    }
}
