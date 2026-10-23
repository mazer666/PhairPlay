package com.phairplay.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ensure the portrait-to-landscape transition fits both frames into a fixed 1080p display. */
class VideoAspectFitTest {
    @Test fun `portrait and landscape keep their aspect ratio on the same display`() {
        val portrait = VideoAspectFit.scale(498, 1080, 1920, 1080)
        assertEquals(498f / 1920, portrait.first, 0.0001f)
        assertEquals(1f, portrait.second, 0.0001f)
        val landscape = VideoAspectFit.scale(1920, 1080, 1920, 1080)
        assertEquals(1f, landscape.first, 0.0001f)
        assertEquals(1f, landscape.second, 0.0001f)
    }
    @Test fun `wider video fits without cropping`() {
        val wide = VideoAspectFit.scale(2560, 1080, 1920, 1080)
        assertEquals(1f, wide.first, 0.0001f)
        assertEquals(0.75f, wide.second, 0.0001f)
    }
    @Test fun `unknown dimensions retain full view until metadata arrives`() {
        assertEquals(1f to 1f, VideoAspectFit.scale(0, 0, 1920, 1080))
        assertEquals(1f to 1f, VideoAspectFit.scale(498, 1080, 0, 0))
    }
}
