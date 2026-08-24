package xyz.libravault.core.storage

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure helpers in [CoverArtCache] that don't require
 * a real Android context.
 *
 * BitmapFactory.decodeByteArray requires a real device/emulator because it
 * calls native Skia — those paths are exercised in instrumentation tests.
 * The downsample math, however, is pure and worth pinning here so a
 * regression that allows inSampleSize > 16 (OOM on adversarial inputs)
 * fails the test rather than silently burning the user's RAM.
 */
class CoverArtCacheTest {

    private val cache = CoverArtCache(
        context = mockk(relaxed = true),
        logger  = mockk(relaxed = true),
    )

    // ── calculateSampleSize bounds (review finding #26) ──────────────────────

    @Test
    fun `actual equal to target returns 1`() {
        assertEquals(1, cache.calculateSampleSize(actual = 512, target = 512))
    }

    @Test
    fun `actual just above target returns 1`() {
        assertEquals(1, cache.calculateSampleSize(actual = 513, target = 512))
    }

    @Test
    fun `actual double target returns 2`() {
        assertEquals(2, cache.calculateSampleSize(actual = 1024, target = 512))
    }

    @Test
    fun `actual 4x target returns 4`() {
        assertEquals(4, cache.calculateSampleSize(actual = 2048, target = 512))
    }

    @Test
    fun `actual 8x target returns 8`() {
        assertEquals(8, cache.calculateSampleSize(actual = 4096, target = 512))
    }

    @Test
    fun `actual 16x target returns 16`() {
        assertEquals(16, cache.calculateSampleSize(actual = 8192, target = 512))
    }

    @Test
    fun `actual beyond 16x target is CAPPED at 16`() {
        // 10000 px cover aimed at 512 px target — without the cap this
        // would yield sample size ~32, which allocates fractional buffers
        // and can OOM on large hostile inputs.
        val result = cache.calculateSampleSize(actual = 10000, target = 512)
        assertEquals(16, result, "inSampleSize must be capped at 16 regardless of input size")
    }

    @Test
    fun `actual extremely large is still capped at 16`() {
        val result = cache.calculateSampleSize(actual = 1_000_000, target = 512)
        assertEquals(16, result)
    }

    @Test
    fun `actual zero is treated as 1 (no division by zero)`() {
        // Cover from a corrupt JPEG header — outWidth=0, outHeight=0.
        // Without the WS3.1 guard in decode(), maxOf(0, 0) = 0, and the
        // old `while (0 / (size * 2) >= target) size *= 2` loop fell
        // through with size=1. The new guard returns null earlier in
        // decode(), but calculateSampleSize itself stays defensive.
        assertEquals(1, cache.calculateSampleSize(actual = 0, target = 512))
    }

    @Test
    fun `negative actual is treated as 1`() {
        // Belt and braces — calculateSampleSize should never see negative
        // values from real BitmapFactory output, but defensiveness costs nothing.
        assertEquals(1, cache.calculateSampleSize(actual = -100, target = 512))
    }

    @Test
    fun `actual just below 2x threshold returns 1`() {
        // actual / (1 * 2) >= target → 1023 / 2 = 511 < 512 → loop ends
        assertEquals(1, cache.calculateSampleSize(actual = 1023, target = 512))
    }

    @Test
    fun `target larger than actual returns 1`() {
        assertEquals(1, cache.calculateSampleSize(actual = 100, target = 512))
    }

    @Test
    fun `result is always a power of two between 1 and 16`() {
        for (actual in intArrayOf(0, 1, 100, 511, 512, 513, 1023, 1024, 2047, 2048, 4096, 8191, 8192, 100_000, 1_000_000)) {
            val r = cache.calculateSampleSize(actual = actual, target = 512)
            assertTrue(r in 1..16, "actual=$actual → result=$r out of [1,16]")
            // power of two: r & (r - 1) == 0
            assertEquals(0, r and (r - 1), "actual=$actual → result=$r is not a power of two")
        }
    }

    // ── wouldExceedDecodeBudget (issue #529 — pixel ceiling, independent of
    // and in addition to the inSampleSize cap) ──────────────────────────────

    @Test
    fun `legitimate cover dimensions are well under the decode budget`() {
        // A realistic 3000x4000 cover, sampled down per calculateSampleSize.
        val sampleSize = cache.calculateSampleSize(actual = 4000, target = 512)
        assertTrue(sampleSize in 1..16)
        assertTrue(
            !cache.wouldExceedDecodeBudget(outWidth = 3000, outHeight = 4000, sampleSize = sampleSize),
            "a normal 3000x4000 cover must never be rejected by the decode budget",
        )
    }

    @Test
    fun `a very large but genuine scan still fits after sampling`() {
        // A genuine high-res 10000x10000 scan saturates inSampleSize at 16
        // (same as the adversarial case below) but downsamples to a
        // perfectly reasonable 625x625 buffer — must NOT be rejected.
        val sampleSize = cache.calculateSampleSize(actual = 10_000, target = 512)
        assertEquals(16, sampleSize)
        assertTrue(
            !cache.wouldExceedDecodeBudget(outWidth = 10_000, outHeight = 10_000, sampleSize = sampleSize),
        )
    }

    @Test
    fun `adversarial dimensions that saturate sample size still exceed the pixel budget`() {
        // A hostile header claiming 1,000,000 x 1,000,000 px saturates
        // inSampleSize at 16 (identical to a legitimate 8192px cover), but
        // still decodes to a 62,500 x 62,500 buffer -- the sample-size cap
        // alone cannot catch this; the pixel-budget check must.
        val sampleSize = cache.calculateSampleSize(actual = 1_000_000, target = 512)
        assertEquals(16, sampleSize)
        assertTrue(
            cache.wouldExceedDecodeBudget(outWidth = 1_000_000, outHeight = 1_000_000, sampleSize = sampleSize),
            "a 1,000,000x1,000,000 claimed header must be rejected regardless of the sample-size cap",
        )
    }

    @Test
    fun `extreme aspect ratio is also caught (one huge dimension, one tiny)`() {
        // A pathological 1x500_000_000 header — one axis alone can still
        // force a huge allocation even though the "long edge" logic that
        // picks sampleSize only looks at the max of the two, and sampleSize
        // itself saturates at 16 for any long edge past 16,384 regardless
        // of how much further beyond that it actually goes.
        val sampleSize = cache.calculateSampleSize(actual = 500_000_000, target = 512)
        assertEquals(16, sampleSize)
        assertTrue(
            cache.wouldExceedDecodeBudget(outWidth = 1, outHeight = 500_000_000, sampleSize = sampleSize),
        )
    }

    @Test
    fun `exactly at the pixel budget boundary is allowed`() {
        // 4096 x 4096 at sample size 1 == MAX_DECODE_PIXELS exactly.
        assertTrue(
            !cache.wouldExceedDecodeBudget(outWidth = 4096, outHeight = 4096, sampleSize = 1),
        )
    }

    @Test
    fun `one pixel past the budget boundary is rejected`() {
        assertTrue(
            cache.wouldExceedDecodeBudget(outWidth = 4097, outHeight = 4096, sampleSize = 1),
        )
    }

    @Test
    fun `sampleSize of zero does not divide-by-zero (coerced to 1 pixel minimum)`() {
        // Defensive: calculateSampleSize should never actually return 0,
        // but wouldExceedDecodeBudget must not crash if it ever did.
        assertTrue(!cache.wouldExceedDecodeBudget(outWidth = 10, outHeight = 10, sampleSize = 0))
    }
}