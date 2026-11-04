import CoreImage.CIFilterBuiltins
import PhotosUI
import SwiftUI
import Vision

/// QR-Code in einem Foto finden (Vision, offline).
func qrLesen(_ bild: UIImage) async -> String? {
    guard let cg = bild.cgImage else { return nil }
    return await withCheckedContinuation { fortsetzen in
        let anfrage = VNDetectBarcodesRequest { req, _ in
            let text = (req.results as? [VNBarcodeObservation])?.first { $0.symbology == .qr }?.payloadStringValue
            fortsetzen.resume(returning: text)
        }
        anfrage.symbologies = [.qr]
        do {
            try VNImageRequestHandler(cgImage: cg).perform([anfrage])
        } catch {
            fortsetzen.resume(returning: nil)
        }
    }
}

/// Knöpfe "QR-Code scannen" (Kamera) und "aus den Fotos"; liefert den Text des QR-Codes.
struct QrScanKnoepfe: View {
    let fertig: (String) -> Void
    @State private var kamera = false
    @State private var foto: PhotosPickerItem?
    @State private var fehler: String?

    var body: some View {
        Button { kamera = true } label: { Label("QR-Code scannen", systemImage: "qrcode.viewfinder") }
            .fullScreenCover(isPresented: $kamera) {
                KameraBild { bild in
                    kamera = false
                    if let bild { Task { await auswerten(bild) } }
                }
                .ignoresSafeArea()
            }
        PhotosPicker(selection: $foto, matching: .images) { Text("QR-Code aus den Fotos") }
            .onChange(of: foto) {
                guard let foto else { return }
                Task {
                    if let d = try? await foto.loadTransferable(type: Data.self), let b = UIImage(data: d) { await auswerten(b) }
                    self.foto = nil
                }
            }
        if let fehler { Text(fehler).foregroundStyle(.red).font(.footnote) }
    }

    private func auswerten(_ bild: UIImage) async {
        if let text = await qrLesen(bild) {
            fehler = nil
            fertig(text)
        } else {
            fehler = "Kein QR-Code erkannt. Näher rangehen oder den Code eintippen."
        }
    }
}

/// QR-Code anzeigen (zum Abscannen mit einem anderen Handy).
struct QrBild: View {
    let text: String
    var body: some View {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        if let bild = f.outputImage.flatMap({ CIContext().createCGImage($0, from: $0.extent) }) {
            return AnyView(Image(decorative: bild, scale: 1).interpolation(.none).resizable().scaledToFit().frame(width: 220, height: 220))
        }
        return AnyView(Text("QR-Code nicht möglich"))
    }
}
