import PhotosUI
import SwiftUI
import UIKit
import VisionKit

/// Ergebnis eines Beleg-Scans: verkleinertes Foto (JPEG) und was darauf erkannt wurde.
struct BelegScan {
    let jpeg: Data
    let bild: UIImage
    let erkannt: ScanLeser.Beleg
}

/// Großer Knopf "Tankbeleg fotografieren" plus "Foto aus der Galerie".
/// Für Belege wird der Dokumentenscanner von iOS benutzt: er findet den Bon selbst, schneidet ihn
/// gerade zu und löst automatisch aus.
struct BelegScanKnoepfe: View {
    let elektro: Bool
    /// Roadmap Nr. 9: Beim Öffnen des Formulars gleich die Kamera zeigen.
    var sofortKamera = false
    let fertig: (BelegScan) -> Void

    @State private var scanner = false
    @State private var gestartet = false
    @State private var galerie: PhotosPickerItem?
    @State private var laeuft = false
    @State private var fehler: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                scanner = true
            } label: {
                HStack(spacing: 12) {
                    if laeuft {
                        ProgressView()
                        Text("Beleg wird gelesen ...").font(.headline)
                    } else {
                        Image(systemName: "doc.text.viewfinder").font(.title)
                        VStack(alignment: .leading) {
                            Text(elektro ? "Ladebeleg fotografieren" : "Tankbeleg fotografieren").font(.headline)
                            Text("Menge und Preis werden dann ausgefüllt").font(.caption)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(.borderedProminent)
            .disabled(laeuft)

            PhotosPicker(selection: $galerie, matching: .images) {
                Text("Oder ein Foto aus der Galerie nehmen")
            }
            .buttonStyle(.borderless)
            .disabled(laeuft)

            if let fehler { Text(fehler).foregroundStyle(.red) }
        }
        .onAppear {
            if sofortKamera && !gestartet { gestartet = true; scanner = true }
        }
        .fullScreenCover(isPresented: $scanner) {
            Group {
                if VNDocumentCameraViewController.isSupported {
                    DokumentKamera { bild in
                        scanner = false
                        if let bild { auswerten(bild) }
                    }
                } else {
                    KameraBild { bild in
                        scanner = false
                        if let bild { auswerten(bild) }
                    }
                }
            }
            .ignoresSafeArea()
        }
        .onChange(of: galerie) {
            guard let item = galerie else { return }
            galerie = nil
            Task {
                if let daten = try? await item.loadTransferable(type: Data.self), let bild = UIImage(data: daten) {
                    auswerten(bild)
                } else {
                    fehler = "Das Foto ließ sich nicht öffnen. Bitte noch einmal versuchen."
                }
            }
        }
    }

    private func auswerten(_ roh: UIImage) {
        laeuft = true
        fehler = nil
        Task {
            let bild = Belege.verkleinern(roh)
            let teile = await Texterkennung.lesen(bild)
            let erkannt = ScanLeser.beleg(Texterkennung.zeilen(teile), elektro: elektro)
            laeuft = false
            guard let jpeg = bild.jpegData(compressionQuality: 0.8) else {
                fehler = "Das Foto ließ sich nicht speichern. Bitte noch einmal versuchen."
                return
            }
            fertig(BelegScan(jpeg: jpeg, bild: bild, erkannt: erkannt))
        }
    }
}

/// Knopf "km-Stand vom Tacho fotografieren": erkennt den km-Stand und fragt nach, bevor er übernommen wird.
/// [bisher] = letzter bekannter km-Stand des Autos, hilft beim Aussuchen der richtigen Zahl.
struct TachoScanKnopf: View {
    let bisher: Double
    let uebernehmen: (Int) -> Void

    @State private var kamera = false
    @State private var laeuft = false
    @State private var vorschlaege: [Int]?

    var body: some View {
        Button {
            kamera = true
        } label: {
            HStack {
                if laeuft {
                    ProgressView()
                    Text("Tacho wird gelesen ...")
                } else {
                    Image(systemName: "camera")
                    Text("km-Stand vom Tacho fotografieren")
                }
            }
            .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.bordered)
        .disabled(laeuft)
        .fullScreenCover(isPresented: $kamera) {
            KameraBild { bild in
                kamera = false
                guard let bild else { return }
                laeuft = true
                Task {
                    let teile = await Texterkennung.lesen(Belege.verkleinern(bild, maxSeite: 2400))
                    vorschlaege = ScanLeser.tacho(Texterkennung.tachoTexte(teile), bisher: bisher)
                    laeuft = false
                }
            }
            .ignoresSafeArea()
        }
        .sheet(isPresented: Binding(get: { vorschlaege != nil }, set: { if !$0 { vorschlaege = nil } })) {
            TachoVorschlag(vorschlaege: vorschlaege ?? []) { km in
                vorschlaege = nil
                if let km { uebernehmen(km) }
            } nochmal: {
                vorschlaege = nil
                // Erst das Blatt schließen lassen, dann die Kamera öffnen.
                Task {
                    try? await Task.sleep(for: .milliseconds(700))
                    kamera = true
                }
            }
            .presentationDetents([.medium, .large])
        }
    }
}

/// "Steht das auf dem Tacho?" mit großen Knöpfen.
private struct TachoVorschlag: View {
    let vorschlaege: [Int]
    let fertig: (Int?) -> Void
    let nochmal: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            if let erster = vorschlaege.first {
                Text("Steht das auf dem Tacho?").font(.title2.bold())
                Text(F.kmStand(Double(erster))).font(.system(size: 44, weight: .bold)).monospacedDigit()
                Button {
                    fertig(erster)
                } label: {
                    Text("Ja, stimmt").font(.title3.bold()).frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)
                if vorschlaege.count > 1 {
                    Text("Oder eine dieser Zahlen:")
                    ForEach(vorschlaege.dropFirst(), id: \.self) { km in
                        Button {
                            fertig(km)
                        } label: {
                            Text(F.kmStand(Double(km))).font(.title3).frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.bordered)
                    }
                }
                Button("Nein, selbst eintippen") { fertig(nil) }.padding(.top, 4)
            } else {
                Text("Keine Zahl erkannt").font(.title2.bold())
                Text("Bitte näher an den Tacho gehen, gerade von vorne fotografieren und auf Spiegelungen achten. Der km-Stand muss im Display zu sehen sein (Zündung an).")
                    .multilineTextAlignment(.center)
                Button {
                    nochmal()
                } label: {
                    Text("Nochmal fotografieren").font(.title3.bold()).frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.borderedProminent)
                Button("Selbst eintippen") { fertig(nil) }
            }
        }
        .padding(24)
    }
}

/// Vorschaubild eines Belegfotos; Antippen zeigt es groß.
struct BelegVorschau: View {
    let bild: UIImage
    @State private var gross = false

    var body: some View {
        Image(uiImage: bild)
            .resizable()
            .scaledToFill()
            .frame(width: 72, height: 72)
            .clipShape(.rect(cornerRadius: 8))
            .onTapGesture { gross = true }
            .fullScreenCover(isPresented: $gross) {
                NavigationStack {
                    ScrollView {
                        Image(uiImage: bild).resizable().scaledToFit()
                    }
                    .navigationTitle("Beleg")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) { Button("Schließen") { gross = false } }
                    }
                }
            }
    }
}

// MARK: Kameras

/// Normale Kamera (für den Tacho). Ohne Kamera (Simulator) öffnet sich die Fotomediathek.
struct KameraBild: UIViewControllerRepresentable {
    let fertig: (UIImage?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let p = UIImagePickerController()
        p.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        p.delegate = context.coordinator
        return p
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Koordinator { Koordinator(fertig) }

    final class Koordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let fertig: (UIImage?) -> Void
        init(_ fertig: @escaping (UIImage?) -> Void) { self.fertig = fertig }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            fertig(info[.originalImage] as? UIImage)
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { fertig(nil) }
    }
}

/// Dokumentenscanner von iOS (für Belege): erkennt den Bon, entzerrt ihn und löst selbst aus.
struct DokumentKamera: UIViewControllerRepresentable {
    let fertig: (UIImage?) -> Void

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let v = VNDocumentCameraViewController()
        v.delegate = context.coordinator
        return v
    }

    func updateUIViewController(_ uiViewController: VNDocumentCameraViewController, context: Context) {}

    func makeCoordinator() -> Koordinator { Koordinator(fertig) }

    final class Koordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let fertig: (UIImage?) -> Void
        init(_ fertig: @escaping (UIImage?) -> Void) { self.fertig = fertig }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
            fertig(scan.pageCount > 0 ? scan.imageOfPage(at: 0) : nil)
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) { fertig(nil) }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) { fertig(nil) }
    }
}
