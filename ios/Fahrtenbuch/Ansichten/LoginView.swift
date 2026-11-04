import SwiftUI

struct LoginView: View {
    @State private var server = Konto.shared.letzteServerUrl
    @State private var tailscale = Konto.shared.tailscaleUrl
    @State private var email = Konto.shared.letzteEmail
    @State private var passwort = ""
    @State private var fehler: String?
    @State private var laedt = false
    /// "login", "einladung" (Konto mit Code anlegen) oder "reset" (neues Passwort mit Code vom Admin)
    @State private var modus = "login"
    @State private var code = ""
    @State private var name = ""
    @State private var qrHinweis: String?
    @State private var suche: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
            Form {
                Section {
                    VStack(spacing: 12) {
                        Image(systemName: "car.fill")
                            .font(.system(size: 56))
                            .foregroundStyle(.tint)
                        Text("Fahrtenbuch").font(.largeTitle.bold())
                        Text("Mit dem Server im Heimnetz verbinden. Bisher nur auf diesem iPhone gespeicherte Fahrten werden danach hochgeladen.")
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .listRowBackground(Color.clear)
                }
                Section {
                    QrScanKnoepfe { text in
                        guard let d = Verbinden.lesen(text) else { qrHinweis = "Das ist kein Fahrtenbuch-Code."; return }
                        if !d.heim.isEmpty { server = d.heim }
                        if !d.tail.isEmpty { tailscale = d.tail }
                        if !d.einladung.isEmpty { modus = "einladung"; code = d.einladung }
                        if !d.reset.isEmpty { modus = "reset"; code = d.reset }
                        qrHinweis = modus == "einladung" ? "Einladung erkannt. Name, E-Mail und Passwort eintragen."
                            : modus == "reset" ? "Code zum Zurücksetzen erkannt. Neues Passwort eintragen." : "Adressen übernommen."
                    }
                    if let qrHinweis { Text(qrHinweis).foregroundStyle(.tint) }
                    // Zweiter Weg ohne Kamera (Nachtplan 5c)
                    Picker("Art", selection: $modus) {
                        Text("Anmelden").tag("login")
                        Text("Einladung").tag("einladung")
                        Text("Passwort vergessen").tag("reset")
                    }
                    .pickerStyle(.segmented)
                    if modus != "login" {
                        TextField("Code (12 Zeichen)", text: $code)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                    }
                } header: {
                    Text("QR-Code oder Code")
                }
                Section {
                    TextField("Heimnetz, z. B. http://fahrtenbuch.local:8090", text: $server)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Tailscale (optional), z. B. http://100.x.y.z:8090", text: $tailscale)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Server im WLAN suchen") {
                        suche = "Suche im WLAN ..."
                        Task {
                            if let gefunden = await ServerSuche.suchen() {
                                server = gefunden
                                suche = "Gefunden: \(gefunden)"
                            } else {
                                suche = "Kein Server gefunden. Adresse von Hand eintragen."
                            }
                        }
                    }
                    if let suche { Text(suche).font(.footnote).foregroundStyle(.secondary) }
                } header: {
                    Text("Server")
                } footer: {
                    Text("Zuerst wird die Heimnetz-Adresse versucht, danach Tailscale.")
                }
                Section {
                    if modus == "einladung" {
                        TextField("Dein Name", text: $name)
                    }
                    if modus != "reset" {
                    TextField("E-Mail", text: $email)
                        .keyboardType(.emailAddress)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    }
                    SecureField(modus == "login" ? "Passwort" : "Neues Passwort (mindestens 8 Zeichen)", text: $passwort)
                        .textContentType(modus == "login" ? .password : .newPassword)
                } header: {
                    Text("Zugang")
                } footer: {
                    Text("Passwort vergessen? Admin zeigt dir am Display oder in der Admin-Oberfläche einen Code zum Zurücksetzen. Oben scannen oder eintippen.")
                }
                if let fehler {
                    Section { Text(fehler).foregroundStyle(.red) }
                }
                Section {
                    Button {
                        Task { await anmelden() }
                    } label: {
                        HStack {
                            Spacer()
                            if laedt { ProgressView() } else {
                                Text(modus == "einladung" ? "Konto anlegen" : modus == "reset" ? "Passwort setzen und anmelden" : "Anmelden").bold()
                            }
                            Spacer()
                        }
                    }
                    .buttonStyle(.glassProminent)
                    .disabled(laedt || server.isEmpty || passwort.isEmpty || (modus != "reset" && email.isEmpty) || (modus == "einladung" && name.isEmpty))
                    .listRowBackground(Color.clear)
                }
            }
            .navigationTitle("Server verbinden")
            .navigationBarTitleDisplayMode(.inline)
    }

    private func anmelden() async {
        laedt = true
        fehler = nil
        defer { laedt = false }
        let haupt = Konto.normalisieren(server)
        let ts = Konto.normalisieren(tailscale)
        guard let url = await PocketBase.ersteErreichbare([haupt, ts]) else {
            fehler = "Server nicht erreichbar. Adressen, WLAN und Tailscale prüfen."
            return
        }
        if modus != "login" && Verbinden.code(code).isEmpty {
            fehler = "Der Code hat 12 Zeichen."
            return
        }
        do {
            let mail = email.trimmingCharacters(in: .whitespaces)
            let r: PocketBase.Login
            switch modus {
            case "einladung":
                r = try await PocketBase.beitreten(server: url, code: Verbinden.code(code), name: name.trimmingCharacters(in: .whitespaces), email: mail, passwort: passwort)
            case "reset":
                r = try await PocketBase.zuruecksetzen(server: url, code: Verbinden.code(code), passwort: passwort)
            default:
                r = try await PocketBase.login(server: url, email: mail, passwort: passwort)
            }
            Konto.shared.zusammenfuehren = true
            Konto.shared.passwortAendern = r.passwortAendern
            Konto.shared.tailscaleUrl = ts
            Konto.shared.speichern(Anmeldung(serverUrl: haupt.isEmpty ? url : haupt, token: r.token, userId: r.userId, name: r.name,
                                             email: email.trimmingCharacters(in: .whitespaces)))
            await SyncManager.shared.sync()
            dismiss()
        } catch let f as PBFehler {
            fehler = f.status == 400 ? (modus == "login" ? "E-Mail oder Passwort falsch" : f.text) : Fehlertext.aus(f, "Anmelden")
        } catch {
            fehler = "Server nicht erreichbar. Adresse und WLAN prüfen."
        }
    }
}
