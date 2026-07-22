package xyz.libravault.core.licensing

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class IosProGate : IProGate {
    override val isPro: StateFlow<Boolean> = MutableStateFlow(false)

    override fun activateWithKey(licenseKey: String): Boolean {
        // Phase C stub: implement with iOS app-level encryption and local file storage
        // Avoid Keychain per project policy; use app sandbox directory instead
        return false
    }

    override suspend fun refresh() {
        // Phase C: Implement App Store Server API validation or local cache check
    }

    override val supportsKeyEntry: Boolean = true

    override val purchaseOutcomes: SharedFlow<PurchaseOutcome> = MutableSharedFlow()
}
