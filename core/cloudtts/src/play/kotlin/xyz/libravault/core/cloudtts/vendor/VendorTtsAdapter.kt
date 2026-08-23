package xyz.libravault.core.cloudtts.vendor

/**
 * One vendor's HTTP surface, dispatched to by
 * [xyz.libravault.core.cloudtts.RealCloudTtsProvider]. Internal — not part
 * of `core:cloudtts`'s public API; callers outside this module only ever see
 * [xyz.libravault.core.cloudtts.CloudTtsProvider].
 */
interface VendorTtsAdapter {
    suspend fun synthesize(text: String, voiceId: String, credentials: Map<String, String>): Result<ByteArray>
    suspend fun validateKey(credentials: Map<String, String>): Result<Unit>
}

/** Every adapter's `credentials` map is validated by
 * [xyz.libravault.core.cloudtts.CloudApiKeyStore.saveCredentials] before it's
 * ever persisted, but `validateKey` is explicitly called BEFORE saving (PRD
 * §6: "key is validated ... then stored") — so adapters must not assume a
 * field is present. Throws [IllegalStateException] with a clear message,
 * which the caller's `runCatching { }` wrapper turns into a `Result.failure`
 * rather than a crash. */
internal fun Map<String, String>.field(name: String): String =
    this[name] ?: error("Missing required credential field: $name")

/** Google/Azure voice IDs follow vendor-standard `{locale}-{name}` naming
 * (e.g. Google "en-US-Wavenet-D", Azure "en-US-JennyNeural") — both APIs
 * need the locale/language tag separately from the voice name. Deliberately
 * not a full BCP-47 parser: this only needs the first two hyphen-separated
 * segments, which is what every voice name from both vendors' catalogs
 * actually looks like. */
internal fun localeFromVoiceId(voiceId: String): String =
    voiceId.split("-").take(2).joinToString("-").ifEmpty { "en-US" }
