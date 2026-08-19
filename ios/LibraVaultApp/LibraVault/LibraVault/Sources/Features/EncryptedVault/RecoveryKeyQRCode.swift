import CoreImage.CIFilterBuiltins
import SwiftUI

/// Renders a recovery key's display text as a QR code, via Core Image's
/// built-in generator — no third-party QR library needed (there wasn't one
/// in this codebase already, unlike Android's `com.google.zxing` dependency).
enum RecoveryKeyQRCode {

    /// - Returns: `nil` if `text` can't be encoded (should not happen for a
    ///   fixed-length Base32 recovery-key string in practice).
    static func image(for text: String) -> Image? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage else { return nil }
        // The raw generated image is only a handful of pixels per module —
        // scale up with nearest-neighbor (not interpolated) so module edges
        // stay crisp rather than blurring into an unscannable smudge.
        let scale: CGFloat = 10
        let scaled = outputImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))

        let context = CIContext()
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return Image(decorative: cgImage, scale: 1)
    }
}
