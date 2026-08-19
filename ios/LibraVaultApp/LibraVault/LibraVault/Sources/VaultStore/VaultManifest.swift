import Foundation

/// A user highlight/annotation on a vault document — kept out of the app's
/// existing plaintext persistence entirely, embedded in the same encrypted
/// manifest as the document's own metadata (the leak-closure guarantee
/// #304's test suite verifies end-to-end). Field shapes mirror Android's
/// `core.database.entity.HighlightEntity` deliberately, same as Android's own
/// `VaultHighlight` does, so a future UI layer can reuse the same rendering
/// code for both folder and vault highlights — only the storage backing
/// differs.
struct VaultHighlight: Equatable {
    let id: Int64
    /// CFI range for EPUB, "page:N:x1,y1,x2,y2" for PDF — same convention as
    /// the plaintext `HighlightEntity`.
    let positionRef: String
    let highlightedText: String
    var colorHex: String = "#FFE066"
    var note: String? = nil
    let createdAtEpochMillis: Int64
}

/// A saved reading position on a vault document — same "keep it out of
/// plaintext persistence" reasoning as `VaultHighlight`. Field shapes mirror
/// Android's `core.database.entity.BookmarkEntity`.
struct VaultBookmark: Equatable {
    let id: Int64
    /// CFI/Locator JSON for EPUB, "page:N" for PDF — same convention as the
    /// plaintext `BookmarkEntity`.
    let positionRef: String
    var label: String? = nil
    var note: String? = nil
    let createdAtEpochMillis: Int64
}

/// One imported document's metadata: title/author/format live in the
/// encrypted manifest, never in the app's plaintext persistence — the leak
/// this whole type exists to close.
///
/// `fileId` is the 16-byte id under which the actual content is stored — it
/// is NOT a filesystem-visible name: on-disk filenames inside a vault are
/// opaque, so this manifest is the only place the mapping from a
/// human-meaningful title to a file exists at all, and it's as encrypted as
/// the content itself.
///
/// `coverArtFileId`, when present, names a second vault-internal file
/// (stored and encrypted exactly like `fileId`'s own content) holding the
/// cover image bytes. This type does NOT decode/downsample/compress cover
/// art itself — that's a separate, hardened concern deliberately not
/// duplicated here (mirrors Android's own `core.storage.CoverArtCache`
/// split). Callers must run cover bytes through that same hardened path
/// before storing them under `coverArtFileId`.
struct VaultManifestEntry: Equatable {
    let fileId: Data
    var title: String
    var author: String?
    let format: String
    let sizeBytes: Int64
    let addedAtEpochMillis: Int64
    var coverArtFileId: Data? = nil
    var highlights: [VaultHighlight] = []
    var bookmarks: [VaultBookmark] = []
}

// MARK: - Wire format (JSON, inside the encrypted blob)

/// Field names deliberately mirror Android's `VaultManifestDto`/etc. exactly
/// (`fileIdHex`, not e.g. `fileId_hex`) even though nothing currently reads
/// one platform's manifest from the other — there is no golden fixture
/// pinning this the way `testdata/vault-format` pins the crypto/chunk layer
/// (see that README), so it isn't a hard requirement today. Matching it
/// anyway costs nothing now and avoids a real migration if vault portability
/// across platforms is ever built.
private struct VaultHighlightDto: Codable {
    let id: Int64
    let positionRef: String
    let highlightedText: String
    let colorHex: String
    let note: String?
    let createdAtEpochMillis: Int64
}

private struct VaultBookmarkDto: Codable {
    let id: Int64
    let positionRef: String
    let label: String?
    let note: String?
    let createdAtEpochMillis: Int64
}

private struct VaultManifestEntryDto: Codable {
    let fileIdHex: String
    let title: String
    let author: String?
    let format: String
    let sizeBytes: Int64
    let addedAtEpochMillis: Int64
    let coverArtFileIdHex: String?
    let highlights: [VaultHighlightDto]
    let bookmarks: [VaultBookmarkDto]

    /// Custom decoding (rather than relying on synthesized `Decodable`) only
    /// for `highlights`/`bookmarks`: both must default to `[]` when the key
    /// is absent entirely, not just `null` — manifests written before a
    /// field existed have no key for it at all. Swift's synthesized decoder
    /// would otherwise throw `keyNotFound` for those older manifests, same
    /// bug class the fresh-fileId-per-write fix below guards against for a
    /// different reason: an old manifest must keep reading back correctly
    /// forever, with no special-case migration step.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        fileIdHex = try container.decode(String.self, forKey: .fileIdHex)
        title = try container.decode(String.self, forKey: .title)
        author = try container.decodeIfPresent(String.self, forKey: .author)
        format = try container.decode(String.self, forKey: .format)
        sizeBytes = try container.decode(Int64.self, forKey: .sizeBytes)
        addedAtEpochMillis = try container.decode(Int64.self, forKey: .addedAtEpochMillis)
        coverArtFileIdHex = try container.decodeIfPresent(String.self, forKey: .coverArtFileIdHex)
        highlights = try container.decodeIfPresent([VaultHighlightDto].self, forKey: .highlights) ?? []
        bookmarks = try container.decodeIfPresent([VaultBookmarkDto].self, forKey: .bookmarks) ?? []
    }

    init(
        fileIdHex: String,
        title: String,
        author: String?,
        format: String,
        sizeBytes: Int64,
        addedAtEpochMillis: Int64,
        coverArtFileIdHex: String?,
        highlights: [VaultHighlightDto],
        bookmarks: [VaultBookmarkDto]
    ) {
        self.fileIdHex = fileIdHex
        self.title = title
        self.author = author
        self.format = format
        self.sizeBytes = sizeBytes
        self.addedAtEpochMillis = addedAtEpochMillis
        self.coverArtFileIdHex = coverArtFileIdHex
        self.highlights = highlights
        self.bookmarks = bookmarks
    }
}

private struct VaultManifestDto: Codable {
    let entries: [VaultManifestEntryDto]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        entries = try container.decodeIfPresent([VaultManifestEntryDto].self, forKey: .entries) ?? []
    }

    init(entries: [VaultManifestEntryDto]) {
        self.entries = entries
    }
}

// MARK: - Model <-> DTO mapping

private extension VaultManifestEntry {
    /// - Throws: `VaultCryptoError.malformedHeader` if `fileIdHex`/
    ///   `coverArtFileIdHex` isn't valid hex — a corrupted or hand-edited
    ///   manifest should fail the decode cleanly, not crash.
    static func fromDto(_ dto: VaultManifestEntryDto) throws -> VaultManifestEntry {
        guard let fileId = Data(hexEncoded: dto.fileIdHex) else {
            throw VaultCryptoError.malformedHeader("Manifest entry has invalid fileIdHex: \(dto.fileIdHex)")
        }
        let coverArtFileId: Data?
        if let hex = dto.coverArtFileIdHex {
            guard let decoded = Data(hexEncoded: hex) else {
                throw VaultCryptoError.malformedHeader("Manifest entry has invalid coverArtFileIdHex: \(hex)")
            }
            coverArtFileId = decoded
        } else {
            coverArtFileId = nil
        }
        return VaultManifestEntry(
            fileId: fileId,
            title: dto.title,
            author: dto.author,
            format: dto.format,
            sizeBytes: dto.sizeBytes,
            addedAtEpochMillis: dto.addedAtEpochMillis,
            coverArtFileId: coverArtFileId,
            highlights: dto.highlights.map {
                VaultHighlight(
                    id: $0.id, positionRef: $0.positionRef, highlightedText: $0.highlightedText,
                    colorHex: $0.colorHex, note: $0.note, createdAtEpochMillis: $0.createdAtEpochMillis
                )
            },
            bookmarks: dto.bookmarks.map {
                VaultBookmark(
                    id: $0.id, positionRef: $0.positionRef, label: $0.label,
                    note: $0.note, createdAtEpochMillis: $0.createdAtEpochMillis
                )
            }
        )
    }

    var dto: VaultManifestEntryDto {
        VaultManifestEntryDto(
            fileIdHex: fileId.hexEncodedString,
            title: title,
            author: author,
            format: format,
            sizeBytes: sizeBytes,
            addedAtEpochMillis: addedAtEpochMillis,
            coverArtFileIdHex: coverArtFileId?.hexEncodedString,
            highlights: highlights.map {
                VaultHighlightDto(
                    id: $0.id, positionRef: $0.positionRef, highlightedText: $0.highlightedText,
                    colorHex: $0.colorHex, note: $0.note, createdAtEpochMillis: $0.createdAtEpochMillis
                )
            },
            bookmarks: bookmarks.map {
                VaultBookmarkDto(
                    id: $0.id, positionRef: $0.positionRef, label: $0.label,
                    note: $0.note, createdAtEpochMillis: $0.createdAtEpochMillis
                )
            }
        )
    }
}

private extension Data {
    var hexEncodedString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    /// Inverse of `hexEncodedString`. `nil` for an odd-length or non-hex
    /// string, rather than trapping — see the `.malformedHeader` callers.
    init?(hexEncoded string: String) {
        guard string.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(string.count / 2)
        var index = string.startIndex
        while index < string.endIndex {
            let next = string.index(index, offsetBy: 2)
            guard let byte = UInt8(string[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self = Data(bytes)
    }
}

// MARK: - VaultManifest

/// The manifest is stored as just another file in the vault, at the fixed
/// path `manifestFileName` — reusing `ChunkedVaultWriter`/`VaultFileReader`
/// directly rather than exposing new internal API from VaultCrypto for a
/// second encryption path. Direct Swift port of Android's `VaultManifest.kt`,
/// including the security-critical fix documented below.
///
/// **Security-critical — read before touching `write`.** `write` runs on
/// every content mutation (add book, add/remove highlight, add/remove
/// bookmark — see #304's `VaultStore`), so unlike a real imported file
/// (encrypted exactly once, under its own fresh fileId, for life), the
/// manifest is re-encrypted many times over a vault's life while its own key
/// (derived from the fileId it's encrypted under) and the vault's VMK (which
/// never rotates) would otherwise stay fixed. `deriveNonce` is deliberately
/// deterministic in exactly those two things — safe ONLY under the
/// assumption that a given fileId is written exactly once. Reusing one fixed
/// fileId across many manifest rewrites breaks that assumption: it would
/// derive the identical (key, nonce) sequence for every single write while
/// encrypting DIFFERENT plaintext each time — catastrophic AES-GCM nonce
/// reuse, on the single most sensitive asset in the app (see SECURITY.md's
/// asset table). Android's original manifest implementation had exactly this
/// bug before it was fixed; this port starts from the fixed version.
///
/// `write` draws a **fresh random fileId on every call** instead — the same
/// one-fresh-id-per-encryption every other vault file already relies on. The
/// fresh id is never stored separately: `ChunkedVaultWriter` already embeds
/// it unencrypted (but AEAD-authenticated) in the blob's own header, which is
/// exactly what lets `read` open it back up with no external bookkeeping, via
/// `VaultFileReader`'s `expectedFileId: nil` mode (added alongside this file
/// — see that type's doc comment). That also makes this self-migrating: a
/// vault whose manifest predates this (there is no such vault yet on iOS,
/// unlike Android, but the logic is kept identical for the same reason
/// Android keeps it) still reads back unchanged under the legacy all-zero
/// id; the very next `write` silently rotates it onto a fresh one.
///
/// `legacyManifestFileId` is kept only as a reserved sentinel so a future
/// `VaultStore.newFileId()` (#304) keeps excluding it from real
/// content/cover fileIds — still necessary as long as a not-yet-migrated
/// manifest might still be encrypted under it.
enum VaultManifest {

    /// Legacy sentinel: the fixed fileId a manifest would be encrypted under
    /// if it predated the fresh-fileId-per-write fix (see the type doc).
    /// `write` never encrypts under this — kept only so a future
    /// `VaultStore.newFileId()` continues to exclude it.
    static let legacyManifestFileId = Data(repeating: 0, count: VaultFormat.fileIdSizeBytes)

    private static let manifestFileName = "manifest.enc"

    static func manifestPath(vaultDir: URL) -> URL {
        vaultDir.appendingPathComponent(manifestFileName)
    }

    static func write(vaultDir: URL, vmk: Data, entries: [VaultManifestEntry]) throws {
        let dto = VaultManifestDto(entries: entries.map(\.dto))
        let plainBytes = try JSONEncoder().encode(dto)

        // A fresh fileId every write — see the type doc: this is what stops
        // the manifest's many rewrites from reusing an AES-GCM nonce.
        // Excludes the legacy all-zero sentinel for the same cheap-insurance
        // reason a future VaultStore.newFileId() will.
        var fileId: Data
        repeat {
            fileId = try SecureRandom.bytes(count: VaultFormat.fileIdSizeBytes)
        } while fileId == legacyManifestFileId

        // Encrypt to a temp file, then atomically replace — a crash mid-write
        // must never leave a half-written (and therefore unreadable, per the
        // truncation defense in VaultCrypto) manifest behind.
        let tmpURL = vaultDir.appendingPathComponent("\(manifestFileName).tmp")
        // Explicit pre-create, not left to OutputStream(url:) itself — mirrors
        // the working pattern VaultCryptoTestSupport.encryptToTempFile already
        // establishes elsewhere in this codebase for the same open-for-writing shape.
        guard FileManager.default.createFile(atPath: tmpURL.path, contents: nil) else {
            throw VaultCryptoError.ioError("Could not create temp manifest file at \(tmpURL.path)")
        }
        guard let output = OutputStream(url: tmpURL, append: false) else {
            throw VaultCryptoError.ioError("Could not open temp manifest file for writing at \(tmpURL.path)")
        }
        output.open()
        defer { output.close() }
        let input = InputStream(data: plainBytes)
        input.open()
        defer { input.close() }

        try ChunkedVaultWriter.encrypt(
            vmk: vmk, fileId: fileId, totalPlaintextLength: Int64(plainBytes.count),
            input: input, output: output
        )

        do {
            _ = try FileManager.default.replaceItemAt(manifestPath(vaultDir: vaultDir), withItemAt: tmpURL)
        } catch {
            throw VaultCryptoError.ioError("Failed to atomically replace manifest: \(error.localizedDescription)")
        }
    }

    /// Returns an empty list if no manifest exists yet (a brand-new vault).
    static func read(vaultDir: URL, vmk: Data) throws -> [VaultManifestEntry] {
        let path = manifestPath(vaultDir: vaultDir)
        guard FileManager.default.fileExists(atPath: path.path) else { return [] }

        // expectedFileId: nil — the manifest's fileId now varies per write
        // (see the type doc), so there's nothing external to cross-check
        // against; trust whichever id is embedded in this blob's own
        // AEAD-authenticated header. Transparently handles both a
        // freshly-written manifest and a legacy one still under the
        // all-zero sentinel.
        let reader = try VaultFileReader(fileURL: path, vmk: vmk, expectedFileId: nil)
        defer { reader.close() }

        var plainBytes = Data(capacity: Int(reader.plainSize))
        var offset: Int64 = 0
        while offset < reader.plainSize {
            let chunk = try reader.readAt(offset: offset, length: VaultFormat.defaultChunkSize)
            if chunk.isEmpty { break }
            plainBytes.append(chunk)
            offset += Int64(chunk.count)
        }

        let dto = try JSONDecoder().decode(VaultManifestDto.self, from: plainBytes)
        return try dto.entries.map(VaultManifestEntry.fromDto)
    }
}
