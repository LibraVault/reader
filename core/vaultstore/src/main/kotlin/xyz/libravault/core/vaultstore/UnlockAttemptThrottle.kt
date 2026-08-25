package xyz.libravault.core.vaultstore

/**
 * Exponential backoff on failed unlock attempts (PRD §7 "anti-brute-force
 * throttling"). Deliberately a speed-bump, not a hard limit — there is no
 * server to enforce a real limit — and deliberately **no auto-wipe**: an
 * attacker holding the device could otherwise trip a wipe deliberately to
 * destroy the vault, turning a confidentiality feature into a destruction
 * tool (PRD §7).
 *
 * Persisted state (see [VaultConfig]) so restarting the process doesn't reset
 * the counter — the state living in app-private storage has a pleasant
 * property noted in the PRD: the only way to reset the throttle from outside
 * the app ("Clear storage") also deletes the vault being attacked.
 *
 * Pure function of ([failedAttempts], [lastAttemptEpochMillis], [nowEpochMillis])
 * — no I/O, fully unit-testable.
 *
 * Wall-clock based, not monotonic: an attacker with physical access to the
 * device could roll the system clock forward to skip the backoff entirely.
 * Accepted deliberately — the hardware Keystore/TEE binding (PRD §7.1) is
 * the real gate here, this throttle is only ever a speed-bump on top of it
 * (see the class doc above), so a clock-rollback bypass doesn't defeat the
 * actual security boundary.
 */
object UnlockAttemptThrottle {

    /** No delay for the first few attempts — typos shouldn't feel punitive. */
    private const val FREE_ATTEMPTS = 3

    private const val BASE_DELAY_MILLIS = 1_000L
    private const val MAX_DELAY_MILLIS = 5 * 60 * 1_000L // cap at 5 minutes

    /**
     * @return milliseconds the caller must wait before the next attempt is
     *   allowed, or 0 if an attempt is allowed right now.
     */
    fun remainingDelayMillis(failedAttempts: Int, lastAttemptEpochMillis: Long, nowEpochMillis: Long): Long {
        if (failedAttempts < FREE_ATTEMPTS) return 0L
        val exponent = (failedAttempts - FREE_ATTEMPTS).coerceAtMost(20) // avoid overflow in the shift
        val delay = (BASE_DELAY_MILLIS shl exponent).coerceAtMost(MAX_DELAY_MILLIS)
        val elapsed = nowEpochMillis - lastAttemptEpochMillis
        return (delay - elapsed).coerceAtLeast(0L)
    }

    fun isThrottled(failedAttempts: Int, lastAttemptEpochMillis: Long, nowEpochMillis: Long): Boolean =
        remainingDelayMillis(failedAttempts, lastAttemptEpochMillis, nowEpochMillis) > 0L
}
