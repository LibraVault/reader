package xyz.libravault.core.tts

enum class TtsStatus {
    UNINITIALIZED,
    INITIALIZING,
    IDLE,
    PLAYING,
    PAUSED,
    ERROR,
}

data class TtsVoiceInfo(
    val id: String,
    val displayName: String,
    val locale: String,
    val requiresNetwork: Boolean = false,
)

data class TtsState(
    val status: TtsStatus = TtsStatus.UNINITIALIZED,
    val speechRate: Float = 1.0f,
    val selectedVoiceId: String? = null,
    val availableVoices: List<TtsVoiceInfo> = emptyList(),
    val error: String? = null,
)
