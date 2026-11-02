package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OutputSizeResolverTest — unit tests for the aspect-fit size decision.
 *
 * WHY: A hardware decoder that scales the picture to the panel reports the scaled size
 * as a crop rectangle. Trusting that number stretches the picture horizontally — the bug
 * reported on the Allwinner H726 (GitHub issue #21). The resolver is the guard against
 * it, so it needs to be proven for the broken device, for healthy devices and for the
 * rotation case.
 *
 * Test naming convention: test_[methodName]_[scenario]_[expectedResult]
 */
class OutputSizeResolverTest {

    /**
     * Test: The Allwinner H726 case — the decoder scaled 498x1080 to 484x712 and
     * reported the scaled size as a crop. The coded aspect must win.
     */
    @Test
    fun `resolve_allwinner_scaled_crop_keeps_coded_aspect`() {
        val result = OutputSizeResolver.resolve(
            coded = PictureSize(498, 1080),
            crop = PictureSize(484, 712),
            reference = PictureSize(498, 1080),
        )

        assertEquals(498, result.width)
        assertEquals(1080, result.height)
        assertFalse(result.cropAccepted)
    }

    /**
     * Test: The reported reason names the symptom, so a log alone identifies the device.
     */
    @Test
    fun `resolve_allwinner_scaled_crop_reason_mentions_scale`() {
        val result = OutputSizeResolver.resolve(
            coded = PictureSize(498, 1080),
            crop = PictureSize(484, 712),
            reference = PictureSize(498, 1080),
        )

        assertTrue(result.reason.contains("scaled"))
    }

    /**
     * Test: A healthy decoder whose crop equals the coded picture is used as reported.
     */
    @Test
    fun `resolve_matching_crop_is_accepted`() {
        val result = OutputSizeResolver.resolve(
            coded = PictureSize(1920, 1080),
            crop = PictureSize(1920, 1080),
            reference = PictureSize(1920, 1080),
        )

        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
        assertTrue(result.cropAccepted)
    }

    /**
     * Test: The padding an H.264 encoder adds is a real crop and must survive — the
     * coded frame is 1088 lines high, the picture is 1080.
     */
    @Test
    fun `resolve_padding_crop_is_accepted`() {
        val result = OutputSizeResolver.resolve(
            coded = PictureSize(1920, 1088),
            crop = PictureSize(1920, 1080),
            reference = PictureSize(1920, 1080),
        )

        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
        assertTrue(result.cropAccepted)
    }

    /**
     * Test: A small trim stays inside the tolerance and is accepted.
     */
    @Test
    fun `resolve_small_trim_is_accepted`() {
        val result = OutputSizeResolver.resolve(
            coded = PictureSize(1920, 1080),
            crop = PictureSize(1918, 1078),
            reference = PictureSize(1920, 1080),
        )

        assertEquals(1918, result.width)
        assertEquals(1078, result.height)
        assertTrue(result.cropAccepted)
    }

    /**
     * Test: A crop that changes the aspect by more than the tolerance is a scaled
     * picture, not a crop, and the coded size is used.
     */
    @Test
    fun `resolve_crop_beyond_tolerance_is_rejected`() {
        val result = OutputSizeResolver.resolve(
            coded = PictureSize(1920, 1080),
            crop = PictureSize(1920, 1000),
            reference = PictureSize(1920, 1080),
        )

        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
        assertFalse(result.cropAccepted)
    }

    /**
     * Test: A decoder that rotates the picture reports the transposed size. The crop is
     * legitimate in that orientation and must be accepted.
     */
    @Test
    fun `resolve_rotated_stream_keeps_rotated_orientation`() {
        val result = OutputSizeResolver.resolve(
            coded = PictureSize(2556, 1179),
            crop = PictureSize(2556, 1179),
            reference = PictureSize(1179, 2556),
        )

        assertEquals(2556, result.width)
        assertEquals(1179, result.height)
        assertTrue(result.cropAccepted)
    }

    /**
     * Test: Without a crop the coded size is used.
     */
    @Test
    fun `resolve_without_crop_uses_coded_size`() {
        val result = OutputSizeResolver.resolve(
            coded = PictureSize(1920, 1080),
            crop = null,
            reference = PictureSize(1920, 1080),
        )

        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
        assertFalse(result.cropAccepted)
    }

    /**
     * Test: An implausible crop (a decoder reporting a thumbnail) is ignored.
     */
    @Test
    fun `resolve_implausible_crop_is_ignored`() {
        val result = OutputSizeResolver.resolve(
            coded = PictureSize(1920, 1080),
            crop = PictureSize(4, 4),
            reference = PictureSize(1920, 1080),
        )

        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
        assertFalse(result.cropAccepted)
    }

    /**
     * Test: Without a parsed SPS the coded size is the only reference — and it is still
     * enough to catch a scaled picture.
     */
    @Test
    fun `resolve_without_reference_uses_coded_size`() {
        val result = OutputSizeResolver.resolve(
            coded = PictureSize(498, 1080),
            crop = PictureSize(484, 712),
            reference = null,
        )

        assertEquals(498, result.width)
        assertEquals(1080, result.height)
        assertFalse(result.cropAccepted)
    }

    /**
     * Test: Neither a plausible coded size nor a plausible crop — the SPS size is the
     * last remaining candidate.
     */
    @Test
    fun `resolve_without_usable_sizes_falls_back_to_sps`() {
        val result = OutputSizeResolver.resolve(
            coded = PictureSize(0, 0),
            crop = PictureSize(0, 0),
            reference = PictureSize(1920, 1080),
        )

        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
    }

    /**
     * Test: A crop that only differs by the padding of the encoder keeps the crop — the
     * picture is 1920x1080 while the coded frame is 1920x1088.
     */
    @Test
    fun `resolve_crop_on_padded_frame_keeps_picture_height`() {
        val result = OutputSizeResolver.resolve(
            coded = PictureSize(1920, 1088),
            crop = PictureSize(1920, 1080),
            reference = PictureSize(1920, 1088),
        )

        assertEquals(1080, result.height)
    }
}
