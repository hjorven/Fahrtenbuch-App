import SwiftUI

/// Eigenes Passwort festlegen bzw. ändern. Erscheint nach dem Start von selbst, solange das
/// Konto noch das Start-Passwort hat (Server-Feld users.passwort_aendern).
struct PasswortView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var alt = ""
    @State private var neu = ""
    @State private var wieder = ""
    @State private var fehler: String?
    @State private var laeuft = false
    private var noetig: Bool { Konto.shared.passwortAendern }

    var body: some View {
        NavigationStack {
            Form {
                if noetig {
                    Section {
                        Text("Du nutzt noch das Start-Passwort, das alle bekommen haben. Bitte lege jetzt ein eigenes fest.")
                            .font(.body)
                    }
                }
                Section {
                    // Benutzername unsichtbar mitgeben, damit die Passwörter-App weiß, zu welchem Konto das neue Passwort gehört.
                    TextField("E-Mail", text: .constant(Konto.shared.anmeldung?.email ?? ""))
                        .textContentType(.username)
                        .disabled(true)
                        .foregroundStyle(.secondary)
                    SecureField("Bisheriges Passwort", text: $alt)
                        .textContentType(.password)
                    SecureField("Neues Passwort (mind. 8 Zeichen)", text: $neu)
                        .textContentType(.newPassword)
                    SecureField("Neues Passwort wiederholen", text: $wieder)
                        .textContentType(.newPassword)
                } footer: {
                    Text("Bisheriges Passwort vergessen? Admin kann es auf dem Server neu setzen.")
                }
                if let fehler {
                    Section { Text(fehler).foregroundStyle(.red).font(.body) }
                }
                Section {
                    Button {
                        Task { await speichern() }
                    } label: {
                        HStack {
                            Text("Speichern").font(.headline)
                            if laeuft { Spacer(); ProgressView() }
                        }
                        .frame(minHeight: 44)
                    }
                    .disabled(laeuft || alt.isEmpty || neu.isEmpty || wieder.isEmpty)
                }
            }
            .navigationTitle(noetig ? "Eigenes Passwort" : "Passwort ändern")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Später") { dismiss() }.disabled(laeuft)
                }
            }
        }
        .interactiveDismissDisabled(laeuft)
    }

    private func speichern() async {
        fehler = nil
        guard let a = Konto.shared.anmeldung else { fehler = "Nicht mit dem Server verbunden."; return }
        if neu.count < 8 { fehler = "Das neue Passwort braucht mindestens 8 Zeichen."; return }
        if neu != wieder { fehler = "Die beiden neuen Passwörter sind nicht gleich."; return }
        if neu == alt { fehler = "Das neue Passwort muss sich vom bisherigen unterscheiden."; return }
        laeuft = true
        defer { laeuft = false }
        guard let url = await PocketBase.ersteErreichbare([a.serverUrl, Konto.shared.tailscaleUrl]) else {
            fehler = "Keine Verbindung zum Heim-Server. Bitte zuhause im WLAN versuchen."
            return
        }
        do {
            try await PocketBase.passwortAendern(server: url, token: a.token, userId: a.userId, alt: alt, neu: neu)
            // Das alte Token gilt nach der Änderung nicht mehr: gleich mit dem neuen Passwort anmelden.
            let r = try await PocketBase.login(server: url, email: a.email, passwort: neu)
            var b = a
            b.token = r.token
            Konto.shared.speichern(b)
            Konto.shared.passwortAendern = false
            dismiss()
        } catch let f as PBFehler {
            fehler = f.status == 400 ? "Das bisherige Passwort stimmt nicht." : Fehlertext.aus(f, "Passwort ändern")
        } catch {
            fehler = "Keine Verbindung zum Heim-Server. Bitte zuhause im WLAN versuchen."
        }
    }
}
