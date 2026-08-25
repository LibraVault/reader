package xyz.libravault.feature.reader

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Auto-scroll (#5) pacing shared by every reader format. Two distinct mechanisms,
 * depending on what the underlying renderer actually exposes:
 *
 *  - [autoScroll] — continuous pixel-level scrolling, for any screen with a real
 *    [ScrollableState].
 *  - [autoAdvancePages] — timed discrete page turns, for formats with no
 *    continuous scroll API (e.g. Readium's EpubNavigatorFragment, which only
 *    exposes page-granular `goForward(animated)`/`goBackward(animated)`
 *    navigation — its own "scroll" mode is per-chapter continuous scroll
 *    stitched together with a chapter-to-chapter pager internally, with no
 *    public pixel-offset scroll API).
 *
 * Wiring either of these into a specific reader screen is out of scope here —
 * see the per-format follow-up issues split from #5.
 */
internal const val AUTO_SCROLL_BASE_PX_PER_SECOND = 30f
internal const val AUTO_SCROLL_TICK_MS = 16L

/** Baseline seconds between page turns at 1.0x auto-scroll speed. */
internal const val AUTO_SCROLL_BASE_PAGE_INTERVAL_MS = 5_000L

/**
 * Continuously scrolls this [ScrollableState] forward at
 * `[AUTO_SCROLL_BASE_PX_PER_SECOND] * speed` pixels/second until cancelled (caller
 * stops recomposing this effect) or the end of the content is reached.
 *
 * A real user drag takes scroll priority over this call automatically — Compose's
 * [ScrollableState.scrollBy] runs at `MutatePriority.Default`, so a higher-priority
 * user gesture preempts it mid-tick, surfacing as a [CancellationException] at that
 * call rather than at this coroutine's own cancellation point. [onFinished] is called
 * (and the loop stops rather than trying to silently resume) whenever that happens or
 * the end of content is reached, so the caller can flip its own "auto-scroll enabled"
 * state back off — leaving that state on while nothing is actually scrolling any more
 * would be a silently broken toggle, not a paused one. [ensureActive] distinguishes a
 * genuine cancellation of this coroutine itself (screen left, auto-scroll toggled off
 * — which must keep propagating) from the preemption case above.
 */
internal suspend fun ScrollableState.autoScroll(speed: Float, onFinished: () -> Unit) {
    val pxPerTick = AUTO_SCROLL_BASE_PX_PER_SECOND * speed * (AUTO_SCROLL_TICK_MS / 1000f)
    if (pxPerTick <= 0f) return
    while (coroutineContext.isActive) {
        val consumed = try {
            scrollBy(pxPerTick)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            onFinished()
            return
        }
        if (consumed < pxPerTick * 0.5f) {
            onFinished()
            return
        }
        delay(AUTO_SCROLL_TICK_MS)
    }
}

/**
 * Ticks every `[AUTO_SCROLL_BASE_PAGE_INTERVAL_MS] / speed` milliseconds, calling
 * [advancePage] each time, until cancelled or [advancePage] returns `false` (nothing
 * further to advance to — [onFinished] is called in that case, same rationale as
 * [autoScroll]'s).
 */
internal suspend fun autoAdvancePages(speed: Float, onFinished: () -> Unit, advancePage: () -> Boolean) {
    if (speed <= 0f) return
    while (true) {
        delay((AUTO_SCROLL_BASE_PAGE_INTERVAL_MS / speed).toLong())
        if (!advancePage()) {
            onFinished()
            return
        }
    }
}
