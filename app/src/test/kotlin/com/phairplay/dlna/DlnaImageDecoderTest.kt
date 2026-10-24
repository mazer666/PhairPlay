package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DlnaImageDecoderTest — proves the downsampling arithmetic of the photo pipeline.
 *
 * WHY: a photo payload of up to 20 MB can decode into hundreds of megabytes of
 * pixels — enough to kill the app with an `OutOfMemoryError`. The decoder therefore
 * computes an `inSampleSize` before the pixels are allocated.
 *
 * NOTE: the pure arithmetic is tested here. The `BitmapFactory` call itself needs a
 * real Android runtime and is exercised on the device.
 */
class DlnaImageDecoderTest {

    @Test
    fun `a small image is not downsampled`() {
        assertEquals(1, DlnaImageDecoder.calculateInSampleSize(800, 600, 1920))
    }

    @Test
    fun `a large image is downsampled by a power of two`() {
        assertEquals(2, DlnaImageDecoder.calculateInSampleSize(3840, 2160, 1920))
        assertEquals(4, DlnaImageDecoder.calculateInSampleSize(7680, 4320, 1920))
    }

    @Test
    fun `the sample size never scales the image far below the limit`() {
        val sampleSize = DlnaImageDecoder.calculateInSampleSize(12000, 8000, 1920)

        // The decoder halves the image until the next halving would fall below the
        // limit — the result stays within a factor of two of the limit.
        assertEquals(4, sampleSize)
        assertTrue(12000 / sampleSize <= 2 * 1920)
    }

    @Test
    fun `a portrait image is scaled by its longest edge`() {
        assertEquals(2, DlnaImageDecoder.calculateInSampleSize(2160, 3840, 1920))
    }

    @Test
    fun `invalid dimensions do not crash the calculation`() {
        assertEquals(1, DlnaImageDecoder.calculateInSampleSize(0, 0, 1920))
        assertEquals(1, DlnaImageDecoder.calculateInSampleSize(100, 100, 0))
    }

    @Test
    fun `the payload limit is 20 MB`() {
        assertEquals(20 * 1024 * 1024, DlnaImageDecoder.MAX_IMAGE_BYTES)
        assertTrue(DlnaImageDecoder.isWithinPayloadLimit(1024))
        assertFalse(DlnaImageDecoder.isWithinPayloadLimit(DlnaImageDecoder.MAX_IMAGE_BYTES + 1))
    }
}
