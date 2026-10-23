package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Verify that a live receive stream cannot masquerade as successful video presentation. */
class StreamStatsTest {
    @Before fun reset() = StreamStats.resetStreams()

    @Test fun `receive activity and audio duplicates remain separate from presentation`() {
        StreamStats.videoReceived.set(600)
        StreamStats.videoSubmitted.set(1)
        StreamStats.videoOutput.set(1)
        StreamStats.videoPresented.set(1)
        StreamStats.audioActive = true
        StreamStats.audioDupPct = 40
        val hud = StreamStats.summary()
        assertTrue(hud.contains("RX 0fps/600  IN 0fps/1"))
        assertTrue(hud.contains("OUT 0fps/1  PRESENT 0fps/1"))
        assertTrue(hud.contains("AUDIO on  q 0  dup 40%"))
    }

    @Test fun `new video session resets errors without clearing audio`() {
        StreamStats.audioActive = true
        StreamStats.audioDupPct = 40
        StreamStats.videoError("codec failed", IllegalStateException("stalled"))
        StreamStats.videoInputMiss.incrementAndGet()
        StreamStats.videoReceived.set(99)
        StreamStats.resetVideo()
        assertEquals(0L, StreamStats.videoErrors.get())
        assertEquals(0L, StreamStats.videoInputMiss.get())
        assertEquals(0L, StreamStats.videoReceived.get())
        assertTrue(StreamStats.audioActive)
        assertEquals(40, StreamStats.audioDupPct)
    }

    @Test fun `last codec error survives subsequent receive activity`() {
        StreamStats.videoError("decode error", IllegalStateException("buffer\ninvalid"))
        StreamStats.videoReceived.incrementAndGet()
        assertEquals(1L, StreamStats.videoErrors.get())
        assertTrue(StreamStats.summary().contains("decode error: IllegalStateException: buffer invalid"))
    }
}
