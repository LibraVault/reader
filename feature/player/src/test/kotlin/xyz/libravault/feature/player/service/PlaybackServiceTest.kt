package xyz.libravault.feature.player.service

import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlaybackService] was 159 lines at 0% coverage (docs/TEST_COVERAGE_PRD.md, S2)
 * — the foreground media service, i.e. the class behind the lockscreen and
 * notification bug class that produced issue #12.
 *
 * It had no tests for a concrete reason rather than neglect: it is an
 * `@AndroidEntryPoint` [androidx.media3.session.MediaSessionService], and this
 * repo had **no Hilt test infrastructure at all**. Without a
 * [HiltTestApplication] the generated `Hilt_PlaybackService` cannot inject its
 * `lateinit` dependencies, so the service could not even be constructed under
 * Robolectric. This file adds that infrastructure (`hilt-android-testing` +
 * `kspTest`), which is reusable by any future service or `@AndroidEntryPoint`
 * test.
 *
 * ## What is asserted, and why these
 *
 * The service is mostly wiring — its collaborators ([LibravaultMediaCallback],
 * [PlaybackStateHolder], [SkipDurationPreference]) already have their own
 * tests. What is *not* covered anywhere else is the lifecycle contract, and one
 * clause of it is load-bearing:
 *
 * > Do NOT release the singleton ExoPlayer here — it is @Singleton scoped and
 * > shared with SleepTimer for volume fade-out. Releasing it would cause
 * > IllegalStateException("Player is released") on any subsequent sleep timer
 * > operation or new PlayerViewModel instance.
 *
 * That comment is the only thing standing between the current code and a
 * crash that reproduces as "stop playback, then set a sleep timer". A comment
 * is not a gate. The `onDestroy` test below is.
 */
@HiltAndroidTest
@UninstallModules(PlayerModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class PlaybackServiceTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    /**
     * The player must come from the Hilt graph, not from assigning
     * `service.player` after construction.
     *
     * That was the first attempt, and it produced a **vacuous test**: the
     * generated `Hilt_PlaybackService.onCreate()` injects before the service
     * body runs, overwriting any field set beforehand. The service then used a
     * graph-provided ExoPlayer while the assertions inspected an untouched
     * mock, so `verify(exactly = 0) { player.release() }` passed no matter what
     * `onDestroy` did — confirmed by reintroducing `player.release()` and
     * watching the suite stay green.
     *
     * [UninstallModules] + [BindValue] replaces the real binding, so the
     * service genuinely receives this instance.
     *
     * It is a **spy over a real ExoPlayer**, not a plain mock: Media3's
     * `MediaSession.Builder` validates the player it is given (application
     * looper among other things) and rejects a mock outright with a bare
     * `IllegalArgumentException`. The spy delegates to the real object while
     * still recording calls, which is what makes `verify` usable here.
     */
    @BindValue
    @JvmField
    val player: ExoPlayer = spyk(
        ExoPlayer.Builder(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
        ).setLooper(Looper.getMainLooper()).build(),
    )

    /**
     * Uninstalling [PlayerModule] removes everything it provided, so the
     * MediaController future has to be re-bound too or the component graph will
     * not compile. Nothing under test resolves it.
     */
    @BindValue
    @JvmField
    val mediaControllerFuture: ListenableFuture<MediaController> = mockk(relaxed = true)

    private var controller: org.robolectric.android.controller.ServiceController<PlaybackService>? = null

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    /**
     * Media3 rejects a second [MediaSession] built while an earlier one with the
     * same id is still alive ("Session ID must be unique"), and every session
     * here is built with the default empty id. Tests share a JVM, so a test that
     * left its service alive broke whichever test ran next — which is what
     * happened before this teardown existed. Destroying here rather than relying
     * on each test to do it keeps that coupling out of the test bodies.
     */
    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
    }

    private fun buildService(): PlaybackService {
        val c = Robolectric.buildService(PlaybackService::class.java)
        controller = c
        // Dependencies arrive through Hilt during create() — see the [player]
        // doc comment for why they must not be assigned by hand.
        c.create()
        return c.get()
    }

    @Test
    fun `onCreate builds a media session`() {
        val service = buildService()
        val session = service.onGetSession(mockk(relaxed = true))
        assertNotNull(
            "onCreate must leave a MediaSession available — without one the service " +
                "cannot show a notification or a lockscreen tile",
            session,
        )
    }

    @Test
    fun `onGetSession returns the same session instance on repeated calls`() {
        val service = buildService()
        val first = service.onGetSession(mockk(relaxed = true))
        val second = service.onGetSession(mockk(relaxed = true))
        assertSame("each controller connection must get the same session", first, second)
    }

    /**
     * The load-bearing one. Releasing the shared `@Singleton` ExoPlayer here
     * crashes any later sleep-timer operation or new PlayerViewModel with
     * `IllegalStateException("Player is released")`.
     */
    @Test
    fun `onDestroy does not release the shared singleton ExoPlayer`() {
        buildService()
        controller!!.destroy()
        controller = null

        // A spy over a REAL ExoPlayer, not a mock: Media3's MediaSession.Builder
        // validates the player (application looper and more) and rejects a plain
        // mock outright, but the spy delegates to the real object while still
        // recording calls.
        //
        // Asserting the symptom instead — "the player still works afterwards" —
        // was tried first and was silently vacuous: ExoPlayer.playbackState does
        // not throw after release(), so the assertion held either way. This
        // version is mutation-checked: reintroducing player.release() in
        // onDestroy makes it fail.
        verify(exactly = 0) {
            player.release()
        }
    }

    /** After teardown the service must stop handing out a dead session. */
    @Test
    fun `onDestroy clears the session so later connections get null`() {
        val service = buildService()
        controller!!.destroy()
        controller = null

        assertNull(
            "a released session must not be handed to a new controller",
            service.onGetSession(mockk(relaxed = true)),
        )
    }

    /**
     * #493, scope decision 2 — stop-on-lock is required for correctness, not
     * opinionated: [xyz.libravault.core.vaultcontent.VaultDataSource] has no
     * cross-thread signal from a locking `VaultStore` to an already-playing
     * `MediaSource`, so the service must pause proactively when the app
     * backgrounds ([androidx.lifecycle.ProcessLifecycleOwner]'s `onStop()`) while
     * a vault item is loaded, before `VaultSessionManager`'s own observer can
     * zero the VMK out from under a mid-stream read.
     *
     * Robolectric doesn't drive a real app-background transition through
     * [androidx.lifecycle.ProcessLifecycleOwner] from a plain service unit test
     * (that needs a full Activity lifecycle simulation), so this invokes the
     * service's own registered observer directly via reflection — the same unit
     * `ProcessLifecycleOwner`'s real dispatch would call.
     */
    @Test
    fun `backgrounding the app pauses playback when a vault item is loaded`() {
        val service = buildService()
        service.playbackStateHolder.updateVault(
            vaultEntry = xyz.libravault.core.domain.model.ContentSource.VaultEntry(
                vaultId = "vault-1",
                fileIdHex = "aa",
                format = xyz.libravault.core.domain.model.MediaFormat.MP3,
            ),
            title = "Title", author = "Author", coverArtPath = null, isPlaying = true,
        )

        vaultAutoStopObserverOf(service).onStop(mockk(relaxed = true))

        verify(exactly = 1) { player.pause() }
    }

    /** A no-op for a real-file (non-vault) `PlaybackStateHolder` state. */
    @Test
    fun `backgrounding the app does not pause a real-file item`() {
        val service = buildService()
        service.playbackStateHolder.update(
            itemId = 1L, vaultFolderId = 1L, filePath = "content://x",
            title = "Title", author = "Author", coverArtPath = null, isPlaying = true,
        )

        vaultAutoStopObserverOf(service).onStop(mockk(relaxed = true))

        verify(exactly = 0) { player.pause() }
    }

    private fun vaultAutoStopObserverOf(service: PlaybackService): androidx.lifecycle.DefaultLifecycleObserver {
        val field = PlaybackService::class.java.getDeclaredField("vaultAutoStopObserver")
        field.isAccessible = true
        return field.get(service) as androidx.lifecycle.DefaultLifecycleObserver
    }
}
