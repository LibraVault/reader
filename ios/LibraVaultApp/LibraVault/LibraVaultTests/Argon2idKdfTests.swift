import XCTest
@testable import LibraVault

/// Mirrors Android's `Argon2idKdfTest.kt`, against the vendored reference C
/// implementation instead of BouncyCastle.
final class Argon2idKdfTests: XCTestCase {

    // Small params - these tests only need to prove correctness properties, not
    // benchmark real-world latency (that's a separate on-device task - see the
    // TODO on Argon2Params.defaultParams).
    private let fastParams = Argon2Params(memoryKiB: 8 * 1024, iterations: 1, parallelism: 1)
    private let salt = Data((0..<UInt8(VaultFormat.argon2SaltSizeBytes)).map { $0 })

    func testIsDeterministicSamePinSaltAndParamsDeriveTheSameKey() throws {
        let k1 = try Argon2idKdf.deriveKey(pin: Array("1234".utf8), salt: salt, params: fastParams)
        let k2 = try Argon2idKdf.deriveKey(pin: Array("1234".utf8), salt: salt, params: fastParams)
        XCTAssertEqual(k1, k2)
    }

    func testDifferentPinsDeriveDifferentKeys() throws {
        let k1 = try Argon2idKdf.deriveKey(pin: Array("1234".utf8), salt: salt, params: fastParams)
        let k2 = try Argon2idKdf.deriveKey(pin: Array("4321".utf8), salt: salt, params: fastParams)
        XCTAssertNotEqual(k1, k2)
    }

    func testDifferentSaltsDeriveDifferentKeysForTheSamePin() throws {
        let salt2 = Data((0..<UInt8(VaultFormat.argon2SaltSizeBytes)).map { $0 + 1 })
        let k1 = try Argon2idKdf.deriveKey(pin: Array("1234".utf8), salt: salt, params: fastParams)
        let k2 = try Argon2idKdf.deriveKey(pin: Array("1234".utf8), salt: salt2, params: fastParams)
        XCTAssertNotEqual(k1, k2)
    }

    func testRespectsRequestedOutputLength() throws {
        let key = try Argon2idKdf.deriveKey(pin: Array("1234".utf8), salt: salt, params: fastParams, outputLengthBytes: 16)
        XCTAssertEqual(16, key.count)
    }
}
