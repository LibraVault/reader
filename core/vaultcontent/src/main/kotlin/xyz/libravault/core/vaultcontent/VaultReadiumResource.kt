package xyz.libravault.core.vaultcontent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.resource.Resource
import xyz.libravault.core.vaultcrypto.VaultCryptoException
import xyz.libravault.core.vaultcrypto.VaultFileReader

/**
 * Exposes a [VaultFileReader] as a Readium [Resource], so EPUBs stored in an
 * Encrypted Vault can be opened via `AssetRetriever.retrieve(Resource,
 * MediaType)` — a Readium entry point that skips its normal URL/
 * `ResourceFactory` resolution path entirely (implementation plan Phase 3).
 * An EPUB is itself a ZIP archive; Readium's own `ZipArchiveOpener` reads it
 * via ranged [read] calls against whatever `Resource` it's handed, so this
 * class only needs to forward those ranges to [VaultFileReader.readAt] — the
 * same seekable primitive the PDF and audio adapters use, doing exactly the
 * job the chunked format was designed for (PRD §8.2).
 *
 * **Deliberately not wired into [ReadiumProvider][
 * xyz.libravault.feature.reader.epub.ReadiumProvider]'s existing `open(Uri)`
 * entry point in this phase.** That method resolves plaintext SAF/file URIs
 * through Readium's own `ResourceFactory`/`AssetRetriever` machinery, and
 * making it "transparently" also handle vault content would need a URI
 * scheme and a registry of currently-open vaults to resolve it against —
 * infrastructure that only makes sense once real UI (Phase 5) exists to
 * populate that registry. This class is the tested, ready-to-use adapter;
 * `ReadiumProvider` should grow a separate, explicit `openVaultFile(reader)`
 * entry point when Phase 5 wires it in, rather than overloading `open(Uri)`.
 *
 * **Not thread-safe** — matches [VaultFileReader]'s own constraint, which
 * this class simply delegates to.
 */
class VaultReadiumResource(
    private val reader: VaultFileReader,
    fileIdHex: String,
) : Resource {

    override val sourceUrl: AbsoluteUrl = requireNotNull(AbsoluteUrl("vault://$fileIdHex")) {
        "Failed to construct a vault:// AbsoluteUrl for fileId $fileIdHex"
    }

    /** No extra properties (MIME type, filename, etc.) to report — Readium
     * determines those from the EPUB content itself once it can read it. */
    override suspend fun properties(): Try<Resource.Properties, ReadError> =
        Try.Success(Resource.Properties(emptyMap()))

    override suspend fun length(): Try<Long, ReadError> = Try.Success(reader.plainSize)

    override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> = withContext(Dispatchers.IO) {
        try {
            // A null range means "the whole resource" per the Resource contract.
            val start = range?.first?.coerceAtLeast(0L) ?: 0L
            val count = if (range != null) {
                (range.last - start + 1).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            } else {
                reader.plainSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            Try.Success(reader.readAt(start, count))
        } catch (e: VaultCryptoException) {
            // Wrong VMK, tampered data, truncation, or a malformed header all land
            // here — deliberately not distinguished further, same anti-oracle
            // reasoning as core:vaultcrypto's own VaultAuthenticationException.
            Try.Failure(ReadError.Decoding(e))
        }
    }

    override fun close() = reader.close()
}
