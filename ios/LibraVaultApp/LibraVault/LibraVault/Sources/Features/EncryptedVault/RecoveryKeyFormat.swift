import Foundation

/// Encodes/decodes a vault's 256-bit recovery key (`VaultStore.create`'s
/// return value) as human-typeable text — RFC 4648 Base32 (`A–Z`, `2–7`, no
/// padding), grouped into 4-character blocks for display. Swift port of
/// Android's `RecoveryKeyFormat.kt`.
///
/// Base32 rather than hex specifically because its alphabet never contains
/// the digits `0`/`1` at all — nothing for a handwritten `0` to be confused
/// with an `O`, or a `1` with an `I`/`l`, the way a hex dump's digits can be.
/// A raw 32-byte key encodes to exactly 52 Base32 characters (`⌈256/5⌉`); no
/// padding character is appended since the input length is always fixed and
/// known at decode time.
enum RecoveryKeyFormat {

    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
    private static let alphabetSet = Set(alphabet)
    private static let groupSize = 4
    private static let separator: Character = "-"

    /// `data` → grouped, uppercase display text, e.g. `"ABCD-EFGH-...-WXYZ"`.
    static func toDisplayString(_ data: Data) -> String {
        group(encode(data))
    }

    /// Lenient inverse of `toDisplayString`: case-insensitive, tolerates any
    /// separator/whitespace the user typed (or none at all) by simply
    /// dropping every character that isn't in the Base32 alphabet before
    /// decoding — so `"abcd efgh"`, `"ABCD-EFGH"`, and `"ABCDEFGH"` all parse
    /// identically. Returns `nil` if what's left after stripping isn't valid
    /// Base32 (odd bit-count leftover, or simply empty).
    static func parse(_ string: String) -> Data? {
        let cleaned = string.uppercased().filter { alphabetSet.contains($0) }
        return decode(cleaned)
    }

    private static func group(_ encoded: String) -> String {
        encoded
            .enumerated()
            .map { index, char in
                index > 0 && index % groupSize == 0 ? "\(separator)\(char)" : String(char)
            }
            .joined()
    }

    private static func encode(_ data: Data) -> String {
        var result = ""
        var buffer: UInt32 = 0
        var bitsInBuffer = 0

        for byte in data {
            buffer = (buffer << 8) | UInt32(byte)
            bitsInBuffer += 8
            while bitsInBuffer >= 5 {
                bitsInBuffer -= 5
                let index = Int((buffer >> UInt32(bitsInBuffer)) & 0x1F)
                result.append(alphabet[index])
            }
        }
        if bitsInBuffer > 0 {
            let index = Int((buffer << UInt32(5 - bitsInBuffer)) & 0x1F)
            result.append(alphabet[index])
        }
        return result
    }

    private static func decode(_ text: String) -> Data? {
        guard !text.isEmpty else { return nil }
        var result = [UInt8]()
        var buffer: UInt32 = 0
        var bitsInBuffer = 0

        for char in text {
            guard let value = alphabet.firstIndex(of: char) else { return nil }
            buffer = (buffer << 5) | UInt32(value)
            bitsInBuffer += 5
            if bitsInBuffer >= 8 {
                bitsInBuffer -= 8
                result.append(UInt8((buffer >> UInt32(bitsInBuffer)) & 0xFF))
            }
        }
        // Any leftover bits must be trailing zero-padding, not real data —
        // a non-zero leftover means the input was corrupt/truncated
        // mid-symbol, not just missing its optional padding character.
        if bitsInBuffer > 0 {
            let leftoverMask = (UInt32(1) << UInt32(bitsInBuffer)) - 1
            guard (buffer & leftoverMask) == 0 else { return nil }
        }
        return Data(result)
    }
}
