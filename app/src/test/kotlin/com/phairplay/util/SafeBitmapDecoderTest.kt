package com.phairplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SafeBitmapDecoderTest — proves the budget rules of the picture decoder.
 *
 * WHY: a picture that is small on the wire can be enormous once it is decoded. A
 * JPEG that announces 20000 × 20000 pixels is far below the 25 MB payload limit of
 * the AirPlay `/photo` endpoint but decodes into 1.6 GB — enough to kill the
 * receiver with an `OutOfMemoryError` (audit finding SEC-02). The decoder therefore
 * checks the announced size against a budget before a single pixel is allocated,
 * and these tests pin that budget down.
 *
 * NOTE: the pure arithmetic and the limit rules are tested here. `BitmapFactory` is
 * part of the Android framework and is not available in the pure JVM test module, so
 * the real decode path (the `BitmapFactory` calls inside [SafeBitmapDecoder.decode])
 * is NOT covered by this test — it needs a device or an instrumented test.
 */
class SafeBitmapDecoderTest {

    @Test
    fun `a full HD picture is inside the limits`() {
        assertTrue(SafeBitmapDecoder.isWithinLimits(1920, 1080))
    }

    @Test
    fun `a 4K picture is inside the limits`() {
        assertTrue(SafeBitmapDecoder.isWithinLimits(3840, 2160))
    }

    @Test
    fun `a picture beyond the edge limit is refused`() {
        assertFalse(SafeBitmapDecoder.isWithinLimits(8000, 6000))
        assertFalse(SafeBitmapDecoder.isWithinLimits(4097, 100))
        assertFalse(SafeBitmapDecoder.isWithinLimits(100, 4097))
    }

    @Test
    fun `a decompression bomb is refused`() {
        // 20000 × 20000 is the payload of the audit: 1.6 GB as ARGB_8888.
        assertFalse(SafeBitmapDecoder.isWithinLimits(20000, 20000))
    }

    @Test
    fun `an empty picture is refused`() {
        assertFalse(SafeBitmapDecoder.isWithinLimits(0, 0))
        assertFalse(SafeBitmapDecoder.isWithinLimits(0, 1080))
        assertFalse(SafeBitmapDecoder.isWithinLimits(1920, 0))
    }

    @Test
    fun `a negative size is refused`() {
        // A header that could not be parsed reports -1 for both dimensions.
        assertFalse(SafeBitmapDecoder.isWithinLimits(-1, -1))
        assertFalse(SafeBitmapDecoder.isWithinLimits(-1, 1080))
        assertFalse(SafeBitmapDecoder.isWithinLimits(1920, -1))
    }

    @Test
    fun `the pixel budget is a second, independent limit`() {
        // 4000 × 4000 fits the default edge limit but not a smaller pixel budget.
        assertTrue(SafeBitmapDecoder.isWithinLimits(4000, 4000))
        assertFalse(SafeBitmapDecoder.isWithinLimits(4000, 4000, maxEdge = 4096, maxPixels = 1_000_000))
    }

    @Test
    fun `the pixel check does not overflow`() {
        // 100000 × 100000 = 10^10 does not fit into an Int; with 32-bit arithmetic
        // the product would wrap around and the picture would pass the check.
        assertFalse(SafeBitmapDecoder.isWithinLimits(100000, 100000))
    }

    @Test
    fun `a broken budget refuses every picture`() {
        assertFalse(SafeBitmapDecoder.isWithinLimits(1920, 1080, maxEdge = 0))
        assertFalse(SafeBitmapDecoder.isWithinLimits(1920, 1080, maxEdge = 4096, maxPixels = 0))
    }

    @Test
    fun `the default budget is the 4K screen`() {
        assertEquals(4096, SafeBitmapDecoder.MAX_EDGE_PX)
        assertEquals(4096 * 4096, SafeBitmapDecoder.MAX_PIXELS)
    }

    @Test
    fun `a small picture is not downsampled`() {
        assertEquals(1, SafeBitmapDecoder.calculateInSampleSize(800, 600, 1920))
        assertEquals(1, SafeBitmapDecoder.calculateInSampleSize(1920, 1080, 1920))
    }

    @Test
    fun `a large picture is downsampled by a power of two`() {
        assertEquals(2, SafeBitmapDecoder.calculateInSampleSize(3840, 2160, 1920))
        assertEquals(4, SafeBitmapDecoder.calculateInSampleSize(7680, 4320, 1920))
    }

    @Test
    fun `a portrait picture is scaled by its longest edge`() {
        assertEquals(2, SafeBitmapDecoder.calculateInSampleSize(2160, 3840, 1920))
    }

    @Test
    fun `the sample size never scales the picture far below the limit`() {
        val sampleSize = SafeBitmapDecoder.calculateInSampleSize(12000, 8000, 1920)

        // The decoder halves the picture until the next halving would fall below the
        // limit — the result stays within a factor of two of the limit.
        assertEquals(4, sampleSize)
        assertTrue(12000 / sampleSize <= 2 * 1920)
    }

    @Test
    fun `a picture inside the default budget needs no downsampling`() {
        assertEquals(1, SafeBitmapDecoder.calculateInSampleSize(3840, 2160, SafeBitmapDecoder.MAX_EDGE_PX))
    }

    @Test
    fun `invalid dimensions do not crash the calculation`() {
        assertEquals(1, SafeBitmapDecoder.calculateInSampleSize(0, 0, 1920))
        assertEquals(1, SafeBitmapDecoder.calculateInSampleSize(100, 100, 0))
    }
}
