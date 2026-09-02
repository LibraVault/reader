package xyz.libravault.core.ui

import android.os.Build
import android.view.View
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class FrameRateHintTest {

    @Test
    fun `requests the high frame-rate category on API 35+`() {
        val view = mockk<View>(relaxed = true)

        view.hintHighRefreshRateForUpcomingFrame(sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM)

        verify { view.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH) }
    }

    @Test
    fun `does nothing below API 35`() {
        val view = mockk<View>(relaxed = true)

        view.hintHighRefreshRateForUpcomingFrame(sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)

        verify(exactly = 0) { view.setRequestedFrameRate(any()) }
    }
}
