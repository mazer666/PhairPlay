package com.phairplay.media

import android.media.AudioFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MediaCapabilitiesTest — proves which codecs the device reports as supported.
 *
 * WHY: the reported capabilities decide what a sender is allowed to stream. A codec
 * that is claimed but not decodable breaks the session in the middle of the stream,
 * a codec that is missing is never offered in the first place.
 *
 * Example:
 *   MediaCapabilities.from(decoderMimeTypes = setOf("video/avc")).supports(VideoCodec.H264)
 */
class MediaCapabilitiesTest {

    @Test
    fun `maps mandatory Cast decoder mimes to baseline support`() {
        val capabilities = MediaCapabilities.from(
            decoderMimeTypes = setOf(
                "video/avc",
                "video/x-vnd.on2.vp8",
                "audio/mp4a-latm",
                "audio/mpeg"
            ),
            outputEncodings = emptySet()
        )

        assertTrue(capabilities.supports(VideoCodec.H264))
        assertTrue(capabilities.supports(VideoCodec.VP8))
        assertTrue(capabilities.supports(AudioCodec.AAC))
        assertTrue(capabilities.supports(AudioCodec.MP3))
        assertTrue(capabilities.supports(AudioCodec.LPCM))
        assertTrue(capabilities.supportsCastBaseline)
    }

    @Test
    fun `does not report optional codecs without matching decoder`() {
        val capabilities = MediaCapabilities.from(
            decoderMimeTypes = setOf("video/avc", "audio/mp4a-latm"),
            outputEncodings = emptySet()
        )

        assertFalse(capabilities.supports(VideoCodec.HEVC))
        assertFalse(capabilities.supports(VideoCodec.VP9))
        assertFalse(capabilities.supports(VideoCodec.AV1))
        assertFalse(capabilities.supports(AudioCodec.OPUS))
        assertFalse(capabilities.supports(AudioCodec.FLAC))
    }

    @Test
    fun `maps output encodings to surround passthrough support`() {
        val capabilities = MediaCapabilities.from(
            decoderMimeTypes = emptySet(),
            outputEncodings = setOf(
                AudioFormat.ENCODING_AC3,
                AudioFormat.ENCODING_E_AC3
            )
        )

        assertTrue(capabilities.supports(PassthroughCodec.AC3))
        assertTrue(capabilities.supports(PassthroughCodec.E_AC3))
        assertFalse(capabilities.supports(PassthroughCodec.E_AC3_JOC))
    }

    @Test
    fun `Miracast baseline requires H264 and LPCM`() {
        val supported = MediaCapabilities.from(
            decoderMimeTypes = setOf("video/avc"),
            outputEncodings = emptySet()
        )
        val unsupported = MediaCapabilities.from(
            decoderMimeTypes = setOf("video/hevc"),
            outputEncodings = emptySet()
        )

        assertTrue(supported.supportsMiracastBaseline)
        assertFalse(unsupported.supportsMiracastBaseline)
    }
}
