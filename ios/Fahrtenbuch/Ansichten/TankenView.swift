import SwiftData
import SwiftUI

struct TankenView: View {
    @Query(filter: #Predicate<Tankvorgang> { !$0.geloescht }, sort: \Tankvorgang.datum, order: .reverse)
    private var tankungen: [Tankvorgang]
    @Query(sort: \Fahrzeug.name) private var fahrzeuge: [Fahrzeug]
    @Query private var benutzer: [Benutzer]
    @Environment(\.modelContext) private var context
    @State private var formular = false
    @State private var bearbeiten: Tankvorgang?
    @State private var ansehen: Tankvorgang?
    @State private var mitKamera = true

    var body: some View {
        let n = namen(benutzer)
        List {
            ForEach(tankungen) { t in
                let fz = fahrzeuge.first { $0.id == t.fahrzeugId }
                HStack(spacing: 12) {
                    Circle().fill(Color(hex: fz?.farbe ?? "")).frame(width: 10, height: 10)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(t.test ? "TEST · " : "")\(fz?.name ?? "Auto") · \(F.zahl(t.liter, 2)) \(fz?.einheit ?? "L") · \(t.ladeort == "zuhause" ? "kostenlos" : F.euro(t.gesamtpreis))")
                        Text("\(F.datum(t.datum)) · \(F.kmStand(t.kmStand)) · \(n[t.fahrerId] ?? "Fahrer") · \(art(t, fz))")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if !t.beleg.isEmpty || Belege.vorhanden(t.id) {
                        Button { ansehen = t } label: {
                            Image(systemName: "doc.text").foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Beleg ansehen")
                    }
                }
                .contentShape(.rect)
                .onTapGesture {
                    // Eigene bearbeiten, bei anderen den Beleg zeigen (Roadmap Nr. 13).
                    if t.fahrerId == Konto.shared.userId { bearbeiten = t }
                    else if !t.beleg.isEmpty || Belege.vorhanden(t.id) { ansehen = t }
                }
                .swipeActions {
                    if t.fahrerId == Konto.shared.userId {
                        Button("Löschen", role: .destructive) {
                            t.geloescht = true
                            t.dirty = true
                            try? context.save()
                            Task { await SyncManager.shared.sync() }
                        }
                    }
                }
            }
        }
        .overlay {
            if tankungen.isEmpty {
                ContentUnavailableView("Noch nichts getankt oder geladen", systemImage: "fuelpump",
                                       description: Text("Nach dem Tanken oder Laden einfach unten auf den Knopf tippen."))
            }
        }
        .safeAreaInset(edge: .bottom) {
            // Roadmap Nr. 9 / Nachtplan 5c: Hauptweg mit Kamera, "Ohne Beleg" immer daneben.
            HStack(spacing: 12) {
                Button("Ohne Beleg") { mitKamera = false; formular = true }
                    .buttonStyle(.glass)
                    .frame(minHeight: 36)
                Button {
                    mitKamera = true
                    formular = true
                } label: {
                    Label(elektroGewaehlt ? "Geladen" : "Getankt",
                          systemImage: elektroGewaehlt ? "bolt.car.fill" : "fuelpump.fill")
                        .font(.headline)
                        .padding(.horizontal, 8)
                        .frame(minHeight: 36)
                }
                .buttonStyle(.glassProminent)
            }
            .padding(.bottom, 8)
        }
        .navigationTitle("Tanken und Laden")
        .toolbar { StandardToolbar() }
        .sheet(isPresented: $formular) {
            TankFormular(fahrzeuge: fahrzeuge.filter(\.aktiv), bisher: tankungen.filter { !$0.test }, sofortKamera: mitKamera)
                .presentationDetents([.large])
        }
        .fullScreenCover(item: $ansehen) { t in
            BelegAnsicht(tank: t, titel: "\(fahrzeuge.first { $0.id == t.fahrzeugId }?.name ?? "Auto") · \(F.datum(t.datum)) · \(n[t.fahrerId] ?? "Fahrer")")
        }
        .sheet(item: $bearbeiten) { t in
            TankFormular(fahrzeuge: fahrzeuge.filter { $0.aktiv || $0.id == t.fahrzeugId }, bisher: tankungen.filter { !$0.test }, vorhanden: t)
                .presentationDetents([.large])
        }
    }

    private var elektroGewaehlt: Bool {
        fahrzeuge.first { $0.id == GeteilterStatus.fahrzeugId }?.elektrisch ?? false
    }

    private func art(_ t: Tankvorgang, _ fz: Fahrzeug?) -> String {
        if fz?.elektrisch == true {
            return Antrieb.ladeorte.first { $0.wert == t.ladeort }?.text ?? "geladen"
        }
        return t.vollgetankt ? "voll" : "teilweise"
    }
}

struct TankFormular: View {
    let fahrzeuge: [Fahrzeug]
    let bisher: [Tankvorgang]
    /// Gesetzt = bestehenden Eintrag bearbeiten.
    var vorhanden: Tankvorgang? = nil
    var sofortKamera = false
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss

    @State private var fahrzeugId: String = GeteilterStatus.fahrzeugId ?? ""
    @State private var km = ""
    @State private var liter = ""
    @State private var ppl = ""
    @State private var gesamt = ""
    @State private var voll = true
    @State private var tankstelle = ""
    @State private var ladeort = "zuhause"
    @State private var fehler: String?
    @State private var scan: BelegScan?
    @State private var belegDatum: Date?
    @State private var scanHinweis: String?
    @State private var vorhandenesBild: UIImage?

    private var fz: Fahrzeug? { fahrzeuge.first { $0.id == fahrzeugId } }
    private var l: Double? { F.zahl(aus: liter) }
    private var elektro: Bool { fz?.elektrisch ?? false }
    /// Laden zuhause kostet nichts.
    private var kostenlos: Bool { elektro && ladeort == "zuhause" }
    /// Strompreis wiederholt sich (vor allem zuhause): letzten Preis für diesen Ladeort vorschlagen.
    private var letzterPreis: Double? {
        bisher.filter { $0.id != vorhanden?.id && $0.fahrzeugId == fahrzeugId && (!elektro || $0.ladeort == ladeort) && $0.preisProLiter > 0 }
            .max { $0.datum < $1.datum }?.preisProLiter
    }
    private var p: Double? { F.zahl(aus: ppl) ?? (elektro ? letzterPreis : nil) }
    private var g: Double? { F.zahl(aus: gesamt) }
    private var kmWert: Double? { F.zahl(aus: km.replacingOccurrences(of: ".", with: "")) }
    private var ok: Bool { fz != nil && kmWert != nil && (l ?? 0) > 0 && (kostenlos || p != nil || g != nil) }

    var body: some View {
        NavigationStack {
            Form {
                if vorhanden?.test ?? Konto.shared.testModus {
                    Section {
                        Label("Test-Modus: wird als Test gespeichert und zählt nirgends mit.", systemImage: "flask.fill")
                            .foregroundStyle(.orange)
                    }
                }
                Section("Auto") {
                    Picker("Auto", selection: $fahrzeugId) {
                        ForEach(fahrzeuge) { Text($0.name).tag($0.id) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
                // Beleg zuerst: ein Foto füllt Menge, Preis, Tankstelle und Datum aus.
                Section("Beleg") {
                    BelegScanKnoepfe(elektro: elektro, sofortKamera: sofortKamera && vorhanden == nil) { uebernehmen($0) }
                    if let bild = scan?.bild ?? vorhandenesBild {
                        HStack(spacing: 12) {
                            BelegVorschau(bild: bild)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(scan != nil ? "Neues Belegfoto" : "Belegfoto").font(.subheadline.bold())
                                Text("Antippen zum Vergrößern").font(.caption).foregroundStyle(.secondary)
                                if let belegDatum { Text("Datum vom Beleg: \(F.datum(belegDatum))").font(.subheadline) }
                            }
                        }
                    }
                    if let scanHinweis { Text(scanHinweis).foregroundStyle(.tint) }
                }
                if elektro {
                    Section("Ladeort") {
                        Picker("Ladeort", selection: $ladeort) {
                            ForEach(Antrieb.ladeorte, id: \.wert) { Text($0.text).tag($0.wert) }
                        }
                        .pickerStyle(.segmented)
                    }
                }
                Section("Werte") {
                    // Datum immer von Hand änderbar (Nachtplan 5c), nicht nur über das Belegfoto.
                    DatePicker("Datum", selection: Binding(
                        get: { belegDatum ?? vorhanden?.datum ?? Date() },
                        set: { belegDatum = $0 }
                    ), in: ...Date(), displayedComponents: .date)
                    TextField("Kilometerstand", text: $km).keyboardType(.numberPad)
                    TachoScanKnopf(bisher: fz?.kmStand ?? 0) { km = String($0) }
                    TextField(elektro ? "Geladen (kWh)" : "Liter", text: $liter).keyboardType(.decimalPad)
                    if kostenlos {
                        LabeledContent("Kosten", value: "kostenlos (zuhause)")
                    } else {
                        TextField(placeholderPreis, text: $ppl).keyboardType(.decimalPad)
                        TextField(placeholderGesamt, text: $gesamt).keyboardType(.decimalPad)
                        // Ausgerechneter Betrag gut sichtbar, nicht nur als blasser Platzhalter.
                        if gesamt.isEmpty, let l, let p {
                            LabeledContent("Gesamt", value: F.euro(l * p))
                        } else if ppl.isEmpty, let l, let g, l > 0 {
                            LabeledContent("Preis pro \(elektro ? "kWh" : "Liter")", value: "\(F.zahl(g / l, 3)) €")
                        }
                    }
                    // Beim E-Auto zählt jeder Ladevorgang für den Verbrauch, deshalb kein Schalter.
                    if !elektro {
                        Toggle(isOn: $voll) {
                            VStack(alignment: .leading) {
                                Text("Vollgetankt")
                                Text("Nur volle Tankungen zählen für den Verbrauch").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                // Zuhause gibt es keine Ladesäule zu benennen.
                if !kostenlos {
                    Section("Optional") {
                        TextField(elektro ? "Ladesäule" : "Tankstelle", text: $tankstelle)
                    }
                }
                if let fehler {
                    Section { Text(fehler).foregroundStyle(.red) }
                }
            }
            .navigationTitle(vorhanden != nil ? "Bearbeiten" : (elektro ? "Ladevorgang" : "Tankvorgang"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Sichern") { sichern() }.disabled(!ok) }
            }
            .onAppear {
                if let t = vorhanden {
                    fahrzeugId = t.fahrzeugId
                    km = F.zahl(t.kmStand, 0)
                    liter = F.zahl(t.liter, 2)
                    ppl = t.preisProLiter > 0 ? F.zahl(t.preisProLiter, 3) : ""
                    gesamt = t.gesamtpreis > 0 ? F.zahl(t.gesamtpreis, 2) : ""
                    voll = t.vollgetankt
                    tankstelle = t.tankstelle
                    ladeort = t.ladeort.isEmpty ? "zuhause" : t.ladeort
                    return
                }
                if fz == nil { fahrzeugId = fahrzeuge.first?.id ?? "" }
                kmVorschlag()
            }
            .onChange(of: fahrzeugId) { if vorhanden == nil { kmVorschlag() } }
            .task {
                // Vorhandenes Belegfoto (eigenes oder vom Server) beim Bearbeiten anzeigen.
                if let t = vorhanden, let url = await SyncManager.shared.belegHolen(t) {
                    vorhandenesBild = UIImage(contentsOfFile: url.path)
                }
            }
        }
    }

    private func uebernehmen(_ s: BelegScan) {
        scan = s
        let e = s.erkannt
        if let m = e.menge { liter = F.zahl(m, 2) }
        if let p = e.preisProEinheit { ppl = F.zahl(p, 3) }
        if let g = e.gesamt { gesamt = F.zahl(g, 2) }
        if let t = e.tankstelle { tankstelle = t }
        // Falsch gelesenes Datum in der Zukunft nicht übernehmen.
        belegDatum = e.datum.flatMap { Calendar.current.isDateInToday($0) || $0 > Date() ? nil : $0 }
        if elektro, let g = e.gesamt, g > 0 { ladeort = "oeffentlich" }
        scanHinweis = e.leer
            ? "Auf dem Foto war nichts lesbar. Das Foto wird trotzdem als Beleg gespeichert. Bitte Menge und Preis selbst eintippen."
            : "Vom Beleg übernommen. Bitte die Zahlen kurz mit dem Beleg vergleichen."
    }

    private var placeholderPreis: String {
        let titel = elektro ? "Preis pro kWh" : "Preis pro Liter"
        if let l, let g, l > 0 { return "\(titel) (\(F.zahl(g / l, 3)))" }
        if elektro, let letzterPreis { return "\(titel) (\(F.zahl(letzterPreis, 3)))" }
        return titel
    }

    private var placeholderGesamt: String {
        if let l, let p { return "Gesamt in Euro (\(F.zahl(l * p, 2)))" }
        return "Gesamt in Euro"
    }

    private func kmVorschlag() {
        if let fz, fz.kmStand > 0 { km = F.zahl(fz.kmStand, 0) }
    }

    private func sichern() {
        guard let fz, let kmWert, let l else { return }
        let preis = kostenlos ? 0 : (p ?? (g.map { $0 / l } ?? 0))
        let summe = kostenlos ? 0 : (g ?? l * preis)
        // Dieselben Grenzen wie auf dem Server: sonst lehnt er den Eintrag ab und er bliebe nur lokal.
        if let f = Plausibel.tank(menge: l, preisProEinheit: preis, kmStand: kmWert) {
            fehler = f
            return
        }
        let ort = tankstelle.trimmingCharacters(in: .whitespaces)
        if let t = vorhanden {
            t.fahrzeugId = fz.id
            t.kmStand = kmWert
            t.liter = l
            t.preisProLiter = preis
            t.gesamtpreis = summe
            t.vollgetankt = voll || elektro
            t.tankstelle = ort
            t.ladeort = elektro ? ladeort : ""
            if let belegDatum { t.datum = belegDatum }
            if let scan {
                Belege.speichern(scan.jpeg, tankId: t.id)
                t.belegOffen = true
            }
            t.dirty = true
        } else {
            let neu = Tankvorgang(fahrzeugId: fz.id, fahrerId: Konto.shared.userId, datum: belegDatum ?? Date(), kmStand: kmWert, liter: l,
                                  preisProLiter: preis, gesamtpreis: summe, vollgetankt: voll || elektro,
                                  tankstelle: ort, ladeort: elektro ? ladeort : "")
            neu.test = Konto.shared.testModus
            if let scan {
                Belege.speichern(scan.jpeg, tankId: neu.id)
                neu.belegOffen = true
            }
            context.insert(neu)
        }
        // Test-Tankungen heben den km-Stand nicht an.
        let test = vorhanden?.test ?? Konto.shared.testModus
        if !test && kmWert > fz.kmStand {
            fz.kmStand = kmWert
            fz.dirty = true
        }
        try? context.save()
        Task { await SyncManager.shared.sync() }
        dismiss()
    }
}

/// Beleg im Vollbild (Roadmap Nr. 13), mit Zoom. Fremde Fotos kommen bei Bedarf vom Server.
struct BelegAnsicht: View {
    let tank: Tankvorgang
    let titel: String
    @Environment(\.dismiss) private var dismiss
    @State private var bild: UIImage?
    @State private var fertig = false
    @State private var zoom: CGFloat = 1

    var body: some View {
        NavigationStack {
            Group {
                if let bild {
                    ScrollView([.horizontal, .vertical]) {
                        Image(uiImage: bild).resizable().scaledToFit()
                            .frame(width: UIScreen.main.bounds.width * zoom)
                    }
                    .gesture(MagnifyGesture().onChanged { zoom = min(4, max(1, $0.magnification)) })
                } else if fertig {
                    ContentUnavailableView("Beleg nicht geladen", systemImage: "wifi.slash",
                                           description: Text("Ohne Verbindung zum Server gibt es nur eigene Fotos."))
                } else {
                    ProgressView("Beleg wird geladen …")
                }
            }
            .navigationTitle(titel)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Schließen") { dismiss() } } }
        }
        .task {
            if let url = await SyncManager.shared.belegHolen(tank) { bild = UIImage(contentsOfFile: url.path) }
            fertig = true
        }
    }
}
