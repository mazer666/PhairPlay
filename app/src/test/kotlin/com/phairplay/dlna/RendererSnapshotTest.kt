package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RendererSnapshotTest — the derived fields ([RendererSnapshot.hasMedia], [RendererSnapshot.numberOfTracks],
 * [RendererSnapshot.transportActions]) that AvTransportService and LastChangeEncoder read directly.
 *
 * WHY: These are plain derivations with no test of their own elsewhere; getting STOPPED's actions wrong
 * (missing `Stop`) breaks Windows' stop button right after a track finishes.
 */
class RendererSnapshotTest {

    @Test
    fun `hasMedia and numberOfTracks follow currentUri`() {
        assertFalse(RendererSnapshot().hasMedia)
        assertEquals(0, RendererSnapshot().numberOfTracks)
        val withMedia = RendererSnapshot(currentUri = "http://x/a.mp4")
        assertTrue(withMedia.hasMedia)
        assertEquals(1, withMedia.numberOfTracks)
    }

    @Test
    fun `STOPPED offers Play Stop and Seek, plus Next when a next item is queued`() {
        val stopped = RendererSnapshot(state = TransportState.STOPPED, currentUri = "http://x/a.mp4")
        assertEquals("Play,Stop,Seek", stopped.transportActions)
        val withNext = stopped.copy(nextUri = "http://x/b.mp4")
        assertEquals("Play,Stop,Seek,Next", withNext.transportActions)
    }

    @Test
    fun `TRANSITIONING only offers Stop`() {
        val transitioning = RendererSnapshot(state = TransportState.TRANSITIONING, currentUri = "http://x/a.mp4")
        assertEquals("Stop", transitioning.transportActions)
        // Next is not offered mid-transition even with a next item queued.
        assertEquals("Stop", transitioning.copy(nextUri = "http://x/b.mp4").transportActions)
    }

    @Test
    fun `NO_MEDIA_PRESENT offers nothing`() {
        assertEquals("", RendererSnapshot().transportActions)
    }
}
