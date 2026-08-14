package xyz.libravault.core.vaultstore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One Encrypted Vault as known to [VaultRegistry] — everything needed to find
 * and label it *before* it's unlocked. [id] is an opaque directory name (a
 * random UUID, not derived from [displayName]), matching the same
 * "opaque id, never the real name, on disk" shape [VaultStore.contentFile]
 * uses for individual files (PRD §8.2 point 6) — though here the *registry's*
 * own file legitimately stores [displayName] in the clear, since a vault
 * picker has to show something before the vault is unlocked. This is a
 * deliberate, accepted residual leak (a vault's existence, count, and chosen
 * name — same category as file sizes/counts elsewhere), not an oversight;
 * see `docs/threat-model.md`.
 */
@Serializable
data class VaultRegistryEntryDto(
    val id: String,
    val displayName: String,
    val createdAtEpochMillis: Long,
)

@Serializable
private data class VaultRegistryDto(val entries: List<VaultRegistryEntryDto> = emptyList())

/**
 * Tracks the set of Encrypted Vaults that exist on this device, independent
 * of any single [VaultStore] instance (which only knows about *one* vault
 * directory at a time). Persisted as JSON directly under [baseDir] — a
 * sibling of the per-vault subdirectories it lists, not inside any of them.
 *
 * Deliberately plain functions over a [baseDir] parameter, not a class
 * reading `Context` directly — same JVM-testability reasoning as
 * [VaultConfig] and [VaultStore] (see their doc comments).
 *
 * Crash-consistency: [add] is only ever called by [VaultSessionManager]
 * *after* [VaultStore.create] has already succeeded, so the worst case a
 * crash between the two leaves behind is an orphaned, unregistered vault
 * directory (wasted space, never a broken reference) — the same
 * orphan-over-broken-reference tradeoff [VaultStore.importFile] makes.
 */
object VaultRegistry {

    private const val FILE_NAME = "vaults.json"
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    /** On-disk path for a given [id]'s own vault directory, under [baseDir]. */
    fun vaultDir(baseDir: File, id: String): File = File(baseDir, id)

    fun list(baseDir: File): List<VaultRegistryEntryDto> {
        val file = File(baseDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return json.decodeFromString(VaultRegistryDto.serializer(), file.readText()).entries
    }

    /** @throws IllegalStateException [entry]'s id is already registered. */
    fun add(baseDir: File, entry: VaultRegistryEntryDto) {
        val current = list(baseDir)
        check(current.none { it.id == entry.id }) { "Vault id ${entry.id} is already registered" }
        writeAll(baseDir, current + entry)
    }

    /** Removes [id] from the registry. Does not touch the vault directory
     * itself or delete anything — callers that mean to delete a vault must
     * do that separately, and should call this FIRST (see class doc: an
     * orphaned directory is a better failure mode than a registry entry
     * pointing at nothing). */
    fun remove(baseDir: File, id: String) {
        writeAll(baseDir, list(baseDir).filterNot { it.id == id })
    }

    fun rename(baseDir: File, id: String, newDisplayName: String) {
        writeAll(baseDir, list(baseDir).map { if (it.id == id) it.copy(displayName = newDisplayName) else it })
    }

    /** Write-to-temp-then-rename, same reasoning as [VaultConfig.writeAtomically]. */
    private fun writeAll(baseDir: File, entries: List<VaultRegistryEntryDto>) {
        baseDir.mkdirs()
        val target = File(baseDir, FILE_NAME)
        val tmp = File(baseDir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(VaultRegistryDto.serializer(), VaultRegistryDto(entries)))
        check(tmp.renameTo(target)) { "Failed to atomically replace ${target.path}" }
    }
}
