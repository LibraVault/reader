package xyz.libravault.feature.reader.epub

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Instrumentation test for the Readium 3 EPUB opening pipeline.
 *
 * Runs on device / emulator so Readium's native ZIP and XML parsers execute
 * against a real EPUB asset. This verifies the end-to-end integration of
 * [AssetRetriever] → [EpubParser] → [PublicationOpener] without mocking.
 */
@RunWith(AndroidJUnit4::class)
class ReadiumIntegrationTest {

    private lateinit var appContext: Context
    private lateinit var readiumProvider: ReadiumProvider

    @Before
    fun setup() {
        appContext = ApplicationProvider.getApplicationContext()
        // Direct instantiation — avoids needing Hilt instrumentation runner
        readiumProvider = ReadiumProvider(appContext)
    }

    @Test
    fun openDemoEpub_returnsSuccessWithCorrectTitle(): Unit = runBlocking {
        // androidTest assets live in the *test* APK; copy to app filesDir first
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val assetFile = File(appContext.filesDir, "test_demo.epub")

        testContext.assets.open("demo.epub").use { input ->
            FileOutputStream(assetFile).use { output ->
                input.copyTo(output)
            }
        }

        val uri = Uri.fromFile(assetFile)
        val result = readiumProvider.open(uri)

        assertTrue("Expected success but got $result", result.isSuccess)

        val publication = result.getOrThrow()
        assertEquals("Libravault Test EPUB", publication.metadata.title)

        // Clean up native parser resources
        publication.close()
    }
}
