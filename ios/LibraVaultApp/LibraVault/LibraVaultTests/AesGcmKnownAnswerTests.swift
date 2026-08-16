import XCTest
@testable import LibraVault

/// AES-256-GCM known-answer test vector - proves `AesGcmCipher` wraps CryptoKit
/// correctly against a published, independent source of truth, not just
/// "round-trips with itself." Mirrors Android's `AesGcmKnownAnswerTest.kt` -
/// same vector, same source, so both platforms are checked against the exact
/// same ground truth.
///
/// Vector: Project Wycheproof (C2SP/wycheproof), `testvectors_v1/aes_gcm_test.json`,
/// tcId 91 - 256-bit key / 96-bit IV / 128-bit tag group, `result: "valid"`.
/// Fetched and verified against the live file at
/// https://raw.githubusercontent.com/C2SP/wycheproof/main/testvectors_v1/aes_gcm_test.json
/// on 2026-08-16 - do not hand-edit these constants without re-verifying
/// against the source; a fabricated "known answer" is worse than no test at all.
final class AesGcmKnownAnswerTests: XCTestCase {

    private func hex(_ s: String) -> Data {
        var data = Data(capacity: s.count / 2)
        var index = s.startIndex
        while index < s.endIndex {
            let next = s.index(index, offsetBy: 2)
            data.append(UInt8(s[index..<next], radix: 16)!)
            index = next
        }
        return data
    }

    func testMatchesWycheproofAes256GcmKnownAnswerVector() throws {
        let key = hex("92ace3e348cd821092cd921aa3546374299ab46209691bc28b8752d17f123c20")
        let iv = hex("00112233445566778899aabb")
        let aad = hex("00000000ffffffff")
        let msg = hex("00010203040506070809")
        let expectedCiphertext = hex("e27abdd2d2a53d2f136b")
        let expectedTag = hex("9a4a2579529301bcfb71c78d4060f52c")

        let ciphertextWithTag = try AesGcmCipher.encrypt(key: key, nonce: iv, aad: aad, plaintext: msg)

        XCTAssertEqual(expectedCiphertext, ciphertextWithTag.prefix(msg.count))
        XCTAssertEqual(expectedTag, ciphertextWithTag.suffix(from: ciphertextWithTag.index(ciphertextWithTag.startIndex, offsetBy: msg.count)))

        let decrypted = try AesGcmCipher.decrypt(key: key, nonce: iv, aad: aad, ciphertextWithTag: ciphertextWithTag)
        XCTAssertEqual(msg, decrypted)
    }
}
