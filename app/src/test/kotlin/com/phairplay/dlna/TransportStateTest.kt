package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TransportStateTest — [TransportState.isActive] drives the CONNECTED card state.
 *
 * WHY: A control point should see the renderer as "in use" exactly while it is transitioning, playing or
 * paused; getting this wrong leaves a stale card on the home screen or hides one still in use.
 */
class TransportStateTest {

    @Test
    fun `isActive is true for transitioning, playing and paused`() {
        assertTrue(TransportState.TRANSITIONING.isActive)
        assertTrue(TransportState.PLAYING.isActive)
        assertTrue(TransportState.PAUSED_PLAYBACK.isActive)
    }

    @Test
    fun `isActive is false for no media and stopped`() {
        assertFalse(TransportState.NO_MEDIA_PRESENT.isActive)
        assertFalse(TransportState.STOPPED.isActive)
    }

    @Test
    fun `MediaClass has exactly three values`() {
        assertEquals(3, MediaClass.entries.size)
        assertTrue(MediaClass.entries.containsAll(listOf(MediaClass.VIDEO, MediaClass.AUDIO, MediaClass.IMAGE)))
    }
}
