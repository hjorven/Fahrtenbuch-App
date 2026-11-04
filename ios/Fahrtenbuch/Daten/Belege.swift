import Foundation
import UIKit
import Vision

/// Belegfotos auf dem Handy: ein JPEG pro Tankvorgang unter Application Support/belege/<id>.jpg.
enum Belege {
    static var ordner: URL {
        let basis = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let o = basis.appendingPathComponent("belege", isDirectory: true)
        try? FileManager.default.createDirectory(at: o, withIntermediateDirectories: true)
        return o
    }

    static func datei(_ tankId: String) -> URL { ordner.appendingPathComponent("\(tankId).jpg") }

    static func vorhanden(_ tankId: String) -> Bool { FileManager.default.fileExists(atPath: datei(tankId).path) }

    /// Foto richtig herum und auf höchstens [maxSeite] Pixel verkleinert (reicht für Texterkennung und Beleg).
    static func verkleinern(_ bild: UIImage, maxSeite: CGFloat = 2000) -> UIImage {
        let groesse = bild.size
        let faktor = min(1, maxSeite / max(groesse.width, groesse.height))
        let ziel = CGSize(width: (groesse.width * faktor).rounded(), height: (groesse.height * faktor).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: ziel, format: format).image { _ in
            bild.draw(in: CGRect(origin: .zero, size: ziel))
        }
    }

    static func speichern(_ daten: Data, tankId: String) {
        try? daten.write(to: datei(tankId), options: .atomic)
    }
}

/// Texterkennung mit Vision, komplett auf dem Handy.
enum Texterkennung {

    struct Teil {
        let text: String
        /// Normiert 0...1, Ursprung oben links.
        let links: Double
        let mitte: Double
        let hoehe: Double
    }

    static func lesen(_ bild: UIImage) async -> [Teil] {
        guard let cg = bild.cgImage else { return [] }
        return await Task.detached(priority: .userInitiated) {
            let anfrage = VNRecognizeTextRequest()
            anfrage.recognitionLevel = .accurate
            anfrage.recognitionLanguages = ["de-DE", "en-US"]
            // Sprachkorrektur macht aus Zahlen gern Wörter.
            anfrage.usesLanguageCorrection = false
            try? VNImageRequestHandler(cgImage: cg, orientation: .up).perform([anfrage])
            return (anfrage.results ?? []).compactMap { o -> Teil? in
                guard let t = o.topCandidates(1).first?.string else { return nil }
                let b = o.boundingBox
                return Teil(text: t, links: b.minX, mitte: 1 - b.midY, hoehe: b.height)
            }
        }.value
    }

    /// Zeilen von oben nach unten; Teile auf gleicher Höhe werden zu einer Zeile zusammengefügt.
    static func zeilen(_ teile: [Teil]) -> [String] {
        var reihen: [[Teil]] = []
        for t in teile.sorted(by: { $0.mitte < $1.mitte }) {
            if let v = reihen.last?.first, abs(v.mitte - t.mitte) < 0.5 * min(v.hoehe, t.hoehe) {
                reihen[reihen.count - 1].append(t)
            } else {
                reihen.append([t])
            }
        }
        return reihen.map { $0.sorted { $0.links < $1.links }.map(\.text).joined(separator: "  ") }
    }

    static func tachoTexte(_ teile: [Teil]) -> [ScanLeser.TachoText] {
        teile.map { ScanLeser.TachoText(text: $0.text, hoehe: $0.hoehe) }
    }
}
