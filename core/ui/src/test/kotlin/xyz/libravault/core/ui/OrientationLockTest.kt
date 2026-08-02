package xyz.libravault.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class OrientationLockTest {

    @Test
    fun `findActivity returns the receiver when it is already an Activity`() {
        val activity = mockk<Activity>()

        assertSame(activity, activity.findActivity())
    }

    @Test
    fun `findActivity unwraps a single ContextWrapper layer`() {
        val activity = mockk<Activity>()
        val wrapper = mockk<ContextWrapper> { every { baseContext } returns activity }

        assertSame(activity, wrapper.findActivity())
    }

    @Test
    fun `findActivity unwraps nested ContextWrapper layers`() {
        val activity = mockk<Activity>()
        val inner = mockk<ContextWrapper> { every { baseContext } returns activity }
        val outer = mockk<ContextWrapper> { every { baseContext } returns inner }

        assertSame(activity, outer.findActivity())
    }

    @Test
    fun `findActivity returns null when no Activity is found in the chain`() {
        val nonActivityBase = mockk<Context>()
        val wrapper = mockk<ContextWrapper> { every { baseContext } returns nonActivityBase }

        assertNull(wrapper.findActivity())
    }
}
