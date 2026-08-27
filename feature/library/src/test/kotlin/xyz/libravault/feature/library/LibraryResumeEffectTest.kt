package xyz.libravault.feature.library

import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage for [LibraryResumeEffect]'s wiring — *which* lifecycle it attaches
 * [libraryResumeObserver] to, as opposed to [LibraryScreenResumeTest]'s
 * coverage of the observer's own ON_RESUME-only filtering logic.
 *
 * This is the actual bug class behind #653's "spinner on every Settings
 * back-nav": the effect used to read `LocalLifecycleOwner.current`, which
 * inside a NavHost `composable()` destination is scoped to that destination's
 * own `NavBackStackEntry` — so it fired on every plain in-app back-navigation,
 * not just when the whole app returned from being backgrounded (#96's actual
 * intent). Asserts both halves: an explicitly-injected lifecycle firing
 * ON_RESUME triggers the callback, and the *ambient* `LocalLifecycleOwner` the
 * Compose test host itself provides does NOT — i.e. the effect really is bound
 * to the lifecycle it was given, not silently falling back to the local one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryResumeEffectTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        init { registry.currentState = Lifecycle.State.CREATED }
    }

    @Test
    fun `ON_RESUME on the injected lifecycle triggers onResume`() {
        val fakeOwner = FakeLifecycleOwner()
        var callCount = 0

        composeTestRule.setContent {
            LibraryResumeEffect(lifecycle = fakeOwner.lifecycle) { callCount++ }
        }

        composeTestRule.runOnIdle {
            fakeOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeTestRule.waitForIdle()

        assertEquals(1, callCount)
    }

    @Test
    fun `resuming the ambient LocalLifecycleOwner does not trigger onResume`() {
        // A fake lifecycle that's never touched — as long as it stays in
        // CREATED, LibraryResumeEffect (bound to it) has no reason to fire.
        // Meanwhile the Compose test host's own LocalLifecycleOwner is driven
        // through a real resume by composeTestRule itself (that's what makes
        // Compose content visible/interactive at all). If the effect were
        // still reading LocalLifecycleOwner instead of the injected parameter,
        // this host-level resume alone would already have fired it once
        // before the assertion — this is exactly the #653 regression shape.
        val fakeOwner = FakeLifecycleOwner()
        var callCount = 0
        var sawLocalOwner: LifecycleOwner? = null

        composeTestRule.setContent {
            sawLocalOwner = LocalLifecycleOwner.current
            LibraryResumeEffect(lifecycle = fakeOwner.lifecycle) { callCount++ }
        }
        composeTestRule.waitForIdle()

        assertFalse(
            "sanity check: the ambient owner must differ from the injected fake, " +
                "or this test can't tell the two apart",
            sawLocalOwner === fakeOwner,
        )
        assertEquals(0, callCount)
    }
}
