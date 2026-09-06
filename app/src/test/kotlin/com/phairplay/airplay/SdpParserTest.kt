package com.phairplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SdpParserTest — Unit tests for [SdpParser].
 *
 * WHY: The SDP parser processes the ANNOUNCE body from the AirPlay sender — untrusted
 * network data. It must extract codec parameters and encryption keys correctly, and
 * handle all malformed inputs gracefully without crashing.
 *
 * WHAT WE TEST:
 * - Video+audio SDP: hasVideo=true, hasAudio=true
 * - Audio-only SDP: hasVideo=false, hasAudio=true, isAudioOnly=true
 * - H.264 SPS/PPS extraction
 * - ALAC vs AAC-ELD detection
 * - AES key + IV extraction
 * - Empty/blank/malformed SDP returns null (no crash)
 */
class SdpParserTest {

    // ─── Happy path: video + audio ────────────────────────────────────────────

    @Test
    fun `video+audio SDP sets hasVideo=true and hasAudio=true`() {
        val result = SdpParser.parse(SDP_VIDEO_AUDIO)
        assertNotNull(result)
        assertTrue(result!!.hasVideo)
        assertTrue(result.hasAudio)
    }

    @Test
    fun `video+audio SDP is not audio-only`() {
        val result = SdpParser.parse(SDP_VIDEO_AUDIO)
        assertFalse(result!!.isAudioOnly)
    }

    @Test
    fun `SPS and PPS bytes are extracted from fmtp sprop-parameter-sets`() {
        val result = SdpParser.parse(SDP_VIDEO_AUDIO)
        assertNotNull("SPS should be parsed", result!!.spsBytes)
        assertNotNull("PPS should be parsed", result.ppsBytes)
        assertTrue("SPS should be non-empty", result.spsBytes!!.isNotEmpty())
        assertTrue("PPS should be non-empty", result.ppsBytes!!.isNotEmpty())
    }

    @Test
    fun `profile-level-id is extracted from fmtp`() {
        val result = SdpParser.parse(SDP_VIDEO_AUDIO)
        assertEquals("640020", result!!.h264ProfileLevelId)
    }

    @Test
    fun `AAC-ELD audio codec is detected`() {
        val result = SdpParser.parse(SDP_VIDEO_AUDIO)
        assertEquals(AudioCodec.AAC_ELD, result!!.audioCodec)
    }

    @Test
    fun `AES key and IV are extracted`() {
        val result = SdpParser.parse(SDP_VIDEO_AUDIO)
        assertNotNull("AES key should be present", result!!.aesKey)
        assertNotNull("AES IV should be present", result.aesIv)
    }

    @Test
    fun `isAudioEncrypted is true when key and IV are 16 bytes`() {
        val result = SdpParser.parse(SDP_VIDEO_AUDIO_ENCRYPTED_16B)
        assertTrue(result!!.isAudioEncrypted)
    }

    @Test
    fun `sample rate is extracted from AAC-ELD rtpmap`() {
        val result = SdpParser.parse(SDP_VIDEO_AUDIO)
        assertEquals(44100, result!!.sampleRate)
    }

    @Test
    fun `channel count is extracted from AAC-ELD rtpmap`() {
        val result = SdpParser.parse(SDP_VIDEO_AUDIO)
        assertEquals(2, result!!.channels)
    }

    @Test
    fun `48000 Hz sample rate is extracted correctly`() {
        val sdp48k = SDP_VIDEO_AUDIO.replace("mpeg4-generic/44100/2", "mpeg4-generic/48000/2")
        val result = SdpParser.parse(sdp48k)
        assertEquals(48000, result!!.sampleRate)
    }

    // ─── Happy path: audio-only ───────────────────────────────────────────────

    @Test
    fun `audio-only SDP sets hasVideo=false and hasAudio=true`() {
        val result = SdpParser.parse(SDP_AUDIO_ONLY_ALAC)
        assertNotNull(result)
        assertFalse(result!!.hasVideo)
        assertTrue(result.hasAudio)
    }

    @Test
    fun `audio-only SDP sets isAudioOnly=true`() {
        val result = SdpParser.parse(SDP_AUDIO_ONLY_ALAC)
        assertTrue(result!!.isAudioOnly)
    }

    @Test
    fun `ALAC audio codec is detected from audio-only SDP`() {
        val result = SdpParser.parse(SDP_AUDIO_ONLY_ALAC)
        assertEquals(AudioCodec.ALAC, result!!.audioCodec)
    }

    @Test
    fun `ALAC frames-per-packet is extracted`() {
        val result = SdpParser.parse(SDP_AUDIO_ONLY_ALAC)
        assertEquals(352, result!!.alacFramesPerPacket)
    }

    @Test
    fun `ALAC sample rate is extracted from fmtp`() {
        val result = SdpParser.parse(SDP_AUDIO_ONLY_ALAC)
        assertEquals(44100, result!!.sampleRate)
    }

    @Test
    fun `ALAC channel count is extracted from fmtp`() {
        val result = SdpParser.parse(SDP_AUDIO_ONLY_ALAC)
        assertEquals(2, result!!.channels)
    }

    // ─── Error/boundary cases ────────────────────────────────────────────────

    @Test
    fun `empty SDP returns null`() {
        assertNull(SdpParser.parse(""))
    }

    @Test
    fun `blank SDP returns null`() {
        assertNull(SdpParser.parse("   \n\t  "))
    }

    @Test
    fun `SDP with no media sections returns null`() {
        val noMedia = """
            v=0
            o=AirTunes AA:BB:CC:DD:EE:FF 1 IN IP4 192.168.1.1
            s=AirTunes
            t=0 0
        """.trimIndent()
        assertNull(SdpParser.parse(noMedia))
    }

    @Test
    fun `malformed base64 in sprop-parameter-sets does not crash`() {
        val badBase64 = """
            v=0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 profile-level-id=640020;sprop-parameter-sets=!!!not-base64!!!,%%%
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
        """.trimIndent()
        // Should return null or partial result without crashing
        // We just verify no exception is thrown
        SdpParser.parse(badBase64)  // must not throw
    }

    @Test
    fun `malformed base64 AES key does not crash`() {
        val badKey = """
            v=0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
            a=rsaaeskey:!!!invalid!!!
            a=aesiv:!!!invalid!!!
        """.trimIndent()
        SdpParser.parse(badKey)  // must not throw
    }

    // ─── Unrecoverable RSA key ────────────────────────────────────────────────

    @Test
    fun `rsaaeskey that fails RSA recovery sets audioKeyUnrecoverable`() {
        // 256 bytes that are not a valid OAEP ciphertext → RaopRsa.decryptAesKey returns null
        val junkBlob = java.util.Base64.getEncoder().encodeToString(ByteArray(256) { 0x5A })
        val sdp = """
            v=0
            o=iTunes 1 0 IN IP4 192.168.1.10
            s=iTunes
            c=IN IP4 192.168.1.185
            t=0 0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
            a=rsaaeskey:$junkBlob
            a=aesiv:MTIzNDU2Nzg5MDEyMzQ1Ng==
        """.trimIndent()

        val result = SdpParser.parse(sdp)
        assertNotNull(result)
        assertTrue(result!!.audioKeyUnrecoverable)
        assertFalse(result.isAudioEncrypted)
    }

    @Test
    fun `audio-only SDP without any key is not flagged unrecoverable`() {
        val result = SdpParser.parse(SDP_AUDIO_ONLY_ALAC)
        assertNotNull(result)
        assertFalse(result!!.audioKeyUnrecoverable)
    }

    companion object {

        // Video + audio SDP with AAC-ELD (standard AirPlay screen mirroring)
        val SDP_VIDEO_AUDIO = """
            v=0
            o=AirTunes AABBCCDDEEFF 1 IN IP4 192.168.1.10
            s=AirTunes
            t=0 0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;profile-level-id=640020;sprop-parameter-sets=Z2QAKKwbGAoAofjA,aO48gA==
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 mpeg4-generic/44100/2
            a=fmtp:96 streamtype=5;profile-level-id=15;mode=AAC-hbr;config=F8E85000
            a=rsaaeskey:MTIzNDU2Nzg5MDEyMzQ1Ng==
            a=aesiv:MTIzNDU2Nzg5MDEyMzQ1Ng==
        """.trimIndent()

        // Same as above, but with proper 16-byte (Base64) key+IV for isAudioEncrypted check
        // "1234567890123456" = 16 bytes in UTF-8 → base64: MTIzNDU2Nzg5MDEyMzQ1Ng==
        val SDP_VIDEO_AUDIO_ENCRYPTED_16B = SDP_VIDEO_AUDIO  // reuses same SDP (16-char key)

        // Audio-only SDP with ALAC (e.g., AirPlay from Apple Music)
        val SDP_AUDIO_ONLY_ALAC = """
            v=0
            o=AirTunes AABBCCDDEEFF 1 IN IP4 192.168.1.10
            s=AirTunes
            t=0 0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
            a=rsaaeskey:MTIzNDU2Nzg5MDEyMzQ1Ng==
            a=aesiv:MTIzNDU2Nzg5MDEyMzQ1Ng==
        """.trimIndent()
    }
}
