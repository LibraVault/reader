package xyz.libravault.feature.library

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Coverage for [libraryResumeObserver] — the observer that re-scans the library
 * whenever the screen becomes visible again (#96), e.g. after the user adds
 * files to a library folder from outside the app and switches back. Asserted as a
 * plain [androidx.lifecycle.LifecycleEventObserver], no Compose host needed,
 * since it's a pure function of (owner, event) -> Unit.
 *
 * See [LibraryResumeEffectTest] for coverage of *which* lifecycle this observer
 * gets wired to — a Compose-hosted (JUnit4/Robolectric) test, kept separate
 * since this class is JUnit5 and can't host `createComposeRule()`.
 */
class LibraryScreenResumeTest {

    private val owner = mockk<LifecycleOwner>(relaxed = true)

    @Test
    fun `ON_RESUME triggers the callback`() {
        var callCount = 0
        val observer = libraryResumeObserver { callCount++ }

        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)

        assertEquals(1, callCount)
    }

    @Test
    fun `other lifecycle events do not trigger the callback`() {
        var callCount = 0
        val observer = libraryResumeObserver { callCount++ }

        observer.onStateChanged(owner, Lifecycle.Event.ON_CREATE)
        observer.onStateChanged(owner, Lifecycle.Event.ON_START)
        observer.onStateChanged(owner, Lifecycle.Event.ON_PAUSE)
        observer.onStateChanged(owner, Lifecycle.Event.ON_STOP)
        observer.onStateChanged(owner, Lifecycle.Event.ON_DESTROY)

        assertEquals(0, callCount)
    }

    @Test
    fun `fires again on every subsequent resume`() {
        var callCount = 0
        val observer = libraryResumeObserver { callCount++ }

        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)
        observer.onStateChanged(owner, Lifecycle.Event.ON_PAUSE)
        observer.onStateChanged(owner, Lifecycle.Event.ON_RESUME)

        assertEquals(2, callCount)
    }
}
