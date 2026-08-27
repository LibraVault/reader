package xyz.libravault.core.ui

import android.os.Build
import android.view.View

/**
 * Hints that this view's next frame needs a high refresh rate, ahead of a
 * transition that would otherwise trigger that switch reactively mid-animation
 * (#686 — Samsung One UI's own idle→high-refresh renegotiation was measured
 * landing concurrently with the Settings→Library back transition's own first
 * frame, stalling it ~800-900ms). No-op below API 35, where
 * [View.setRequestedFrameRate] doesn't exist; `Surface.setFrameRate()` (API 30+)
 * would work on older devices too, but needs a real [android.view.Surface] a
 * plain Activity window doesn't expose without a SurfaceView.
 */
fun View.hintHighRefreshRateForUpcomingFrame(sdkInt: Int = Build.VERSION.SDK_INT) {
    if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH)
    }
}
