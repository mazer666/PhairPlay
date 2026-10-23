package com.phairplay.airplay

import android.media.MediaCodec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** A lost codec input must be visible to the caller so it can wait for a new keyframe. */
class VideoSubmissionTest {
    private fun decoder(vararg indexes: Int): VideoDecoder {
        val codec = mockk<MediaCodec>(relaxed = true)
        every { codec.dequeueInputBuffer(any()) } returnsMany indexes.toList()
        every { codec.dequeueOutputBuffer(any(), any()) } returns MediaCodec.INFO_TRY_AGAIN_LATER
        every { codec.getInputBuffer(any()) } returns ByteBuffer.allocate(32)
        val decoder = VideoDecoder(mockk(relaxed = true))
        VideoDecoder::class.java.getDeclaredField("mediaCodec").apply { isAccessible = true }
            .set(decoder, codec)
        return decoder
    }

    @Test fun `input starvation reports loss instead of pretending to submit`() {
        StreamStats.resetVideo()
        val decoder = decoder(-1)
        assertFalse(decoder.decodeNalUnit(byteArrayOf(0, 0, 0, 1, 0x65), 0))
        assertFalse(decoder.isHealthy)
        assertEquals(1L, StreamStats.videoInputMiss.get())
        assertEquals(0L, StreamStats.videoSubmitted.get())
    }

    @Test fun `available buffer reports a successful submission`() {
        StreamStats.resetVideo()
        assertTrue(decoder(0).decodeNalUnit(byteArrayOf(0, 0, 0, 1, 0x65), 0))
        assertEquals(0L, StreamStats.videoInputMiss.get())
        assertEquals(1L, StreamStats.videoSubmitted.get())
    }

    @Test fun `temporary starvation preserves the first keyframe and retries input`() {
        StreamStats.resetVideo()
        val decoder = decoder(-1, 0)
        assertTrue(decoder.decodeNalUnit(byteArrayOf(0, 0, 0, 1, 0x65), 0))
        assertTrue(decoder.isHealthy)
        assertEquals(0L, StreamStats.videoInputMiss.get())
        assertEquals(1L, StreamStats.videoInputRetries.get())
        assertEquals(1L, StreamStats.videoSubmitted.get())
    }

    @Test fun `output is released while waiting so an input buffer can become available`() {
        StreamStats.resetVideo()
        val codec = mockk<MediaCodec>(relaxed = true)
        every { codec.dequeueInputBuffer(any()) } returnsMany listOf(-1, 0)
        every { codec.dequeueOutputBuffer(any(), any()) } returnsMany listOf(-1, 0, -1, -1)
        every { codec.getInputBuffer(any()) } returns ByteBuffer.allocate(32)
        val decoder = VideoDecoder(mockk(relaxed = true))
        VideoDecoder::class.java.getDeclaredField("mediaCodec").apply { isAccessible = true }
            .set(decoder, codec)
        assertTrue(decoder.decodeNalUnit(byteArrayOf(0, 0, 0, 1, 0x65), 0))
        verify(exactly = 1) { codec.releaseOutputBuffer(0, true) }
        assertEquals(1L, StreamStats.videoOutput.get())
        assertEquals(0L, StreamStats.videoInputMiss.get())
    }

    @Test fun `codec is disposed even when stopping it throws`() {
        val decoder = decoder(0)
        val codec = VideoDecoder::class.java.getDeclaredField("mediaCodec").apply { isAccessible = true }
            .get(decoder) as MediaCodec
        every { codec.stop() } throws IllegalStateException("codec not started")
        decoder.release()
        verify(exactly = 1) { codec.release() }
    }
}
