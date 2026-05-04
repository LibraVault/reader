package xyz.libravault.core.licensing

/** Billing result translated into domain terms, free of Play Billing API types. */
sealed class PurchaseOutcome {
    data object UserCancelled : PurchaseOutcome()
    data object Error         : PurchaseOutcome()
}
