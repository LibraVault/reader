package xyz.libravault.core.tts

import dagger.MapKey
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

enum class TtsEngineType {
    ANDROID,
    POCKET_TTS,

    /** Premium Cloud TTS Voices (BYOK) — docs/cloud-tts-premium-prd.md. Bound
     * from `core:cloudtts` (unflavored `CloudTtsEngine`), not this module —
     * see [TtsEngineTypeKey]'s doc for why `core:tts` itself never depends
     * on `core:cloudtts`. */
    CLOUD,
}

/** Dagger multibinding key for [TtsEngineType] — lets [TtsEngineFactory]
 * resolve an engine by type without `core:tts` (unflavored, shared by
 * `feature:reader`) needing a compile-time dependency on `core:cloudtts`
 * (flavored: fdroid/play). `core:cloudtts`'s own Hilt module contributes the
 * [TtsEngineType.CLOUD] entry to this same map from outside this module —
 * the map is assembled at `:app`'s `SingletonComponent`, where every module
 * that contributes an entry is already on the classpath regardless of
 * flavor. The alternative (giving `core:tts` its own flavor dimension so it
 * could depend on `core:cloudtts` directly) was rejected: it would force
 * `feature:reader` to gain flavor dimensions it has no other reason to
 * need, for a module with nothing to do with distribution channel. */
@MapKey
annotation class TtsEngineTypeKey(val value: TtsEngineType)

/**
 * Resolves a [TtsEngine] by [TtsEngineType] via Dagger multibinding — see
 * [TtsEngineTypeKey] for why this is a map injection rather than a plain
 * `when` over constructor-injected concrete engines (the shape this class
 * had before `core:cloudtts`/[TtsEngineType.CLOUD] existed).
 */
@Singleton
class TtsEngineFactory @Inject constructor(
    private val engines: Map<TtsEngineType, @JvmSuppressWildcards Provider<TtsEngine>>,
) {
    fun create(type: TtsEngineType): TtsEngine =
        engines[type]?.get()
            ?: error("No TtsEngine bound for $type — is its module @InstallIn(SingletonComponent::class)?")
}
