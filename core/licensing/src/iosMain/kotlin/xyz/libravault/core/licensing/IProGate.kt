package xyz.libravault.core.licensing

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// iOS stub implementation of Pro licensing gate
// Full implementation deferred to Phase C (after iOS UI requirements are clear)

class iOSProGate : IProGate {
    override val isPro: StateFlow<Boolean> = MutableStateFlow(false)
    override val supportsKeyEntry: Boolean = false
    override val purchaseOutcomes: SharedFlow<PurchaseOutcome> = MutableSharedFlow()

    override fun activateWithKey(licenseKey: String): Boolean = false
    override suspend fun refresh() {}
}
