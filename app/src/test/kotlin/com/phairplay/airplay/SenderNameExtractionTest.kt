package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SenderNameExtractionTest — Tests for S6-1: sender name extraction from RTSP User-Agent.
 *
 * WHY: The sender name shown in the notification and home screen comes from the RTSP
 * ANNOUNCE `User-Agent` header. If parsing is wrong, users see a generic "AirPlay Sender"
 * even when the actual sender is identifiable — and a crash in parsing could break
 * the entire streaming session.
 *
 * WHAT WE TEST:
 * - Standard AirPlay User-Agent ("AirPlay/376.1.1") → senderName = "AirPlay"
 * - iTunes User-Agent ("iTunes/12.12 CFNetwork/1") → senderName = "iTunes"
 * - Missing User-Agent header → fallback non-empty name
 * - Blank User-Agent header → fallback non-empty name
 * - User-Agent with no slash → whole string used as name
 * - senderName flows through ANNOUNCE → RECORD into [SessionDescription]
 * - [SessionDescription.senderName] defaults to "" when not set
 *
 * HOW: We use [TestableRtspHandler] (defined in TestableRtspHandler.kt) to exercise
 * the full ANNOUNCE → RECORD path and inspect the [SessionDescription] delivered
 * to [onStreamingStarted].
 */
class SenderNameExtractionTest {

    // ─── SessionDescription defaults ─────────────────────────────────────────

    @Test
    fun `SessionDescription senderName defaults to empty string`() {
        val session = SessionDescription(hasVideo = false, hasAudio = true)
        assertEquals("", session.senderName)
    }

    @Test
    fun `SessionDescription copy preserves senderName`() {
        val original = SessionDescription(hasVideo = false, hasAudio = true, senderName = "AirPlay")
        val copy = original.copy(audioCodec = AudioCodec.ALAC)
        assertEquals("AirPlay", copy.senderName)
    }

    // ─── senderName extraction via ANNOUNCE → RECORD ─────────────────────────

    @Test
    fun `AirPlay slash User-Agent extracts application name before slash`() {
        val session = announceAndRecord(userAgent = "AirPlay/376.1.1")
        assertEquals("AirPlay", session?.senderName)
    }

    @Test
    fun `iTunes User-Agent extracts iTunes as sender name`() {
        val session = announceAndRecord(userAgent = "iTunes/12.12.4 CFNetwork/1327.0.4")
        assertEquals("iTunes", session?.senderName)
    }

    @Test
    fun `User-Agent with no slash uses entire string as sender name`() {
        val session = announceAndRecord(userAgent = "CustomSender")
        assertEquals("CustomSender", session?.senderName)
    }

    @Test
    fun `missing User-Agent header results in non-empty fallback sender name`() {
        val session = announceAndRecord(userAgent = null)
        assertNotNull(session)
        assertTrue("Fallback must be non-empty", session!!.senderName.isNotEmpty())
    }

    @Test
    fun `blank User-Agent header results in non-empty fallback sender name`() {
        val session = announceAndRecord(userAgent = "   ")
        assertNotNull(session)
        assertTrue("Blank UA fallback must be non-empty", session!!.senderName.isNotEmpty())
    }

    @Test
    fun `senderName is preserved in SessionDescription after full ANNOUNCE-RECORD cycle`() {
        val session = announceAndRecord(userAgent = "AirPlay/420.0")
        assertNotNull("Session must not be null after valid ANNOUNCE+RECORD", session)
        assertEquals("AirPlay", session!!.senderName)
    }

    // ─── Helper ──────────────────────────────────────────────────────────────

    /**
     * Drives a minimal ANNOUNCE → RECORD cycle and returns the [SessionDescription]
     * delivered to [onStreamingStarted], or null if RECORD did not fire the callback.
     */
    private fun announceAndRecord(userAgent: String?): SessionDescription? {
        var captured: SessionDescription? = null
        val handler = TestableRtspHandler(
            onStreamingStarted = { session -> captured = session },
            onStreamingStopped = {}
        )

        val announceHeaders = buildMap {
            put("CSeq", "3")
            if (userAgent != null) put("User-Agent", userAgent)
        }

        handler.handleAnnouncePublic(
            RtspRequest(
                method  = "ANNOUNCE",
                uri     = "rtsp://192.168.1.1/phairplay",
                headers = announceHeaders,
                body    = MINIMAL_AUDIO_SDP
            )
        )
        handler.handleRecordPublic(
            RtspRequest(
                method  = "RECORD",
                uri     = "rtsp://192.168.1.1/phairplay",
                headers = mapOf("CSeq" to "4"),
                body    = ""
            )
        )
        return captured
    }

    companion object {
        /** Audio-only SDP that [SdpParser] accepts — no H.264 SPS required. */
        private val MINIMAL_AUDIO_SDP = """
            v=0
            o=AirTunes AA:BB:CC:DD:EE:FF 1 IN IP4 192.168.1.10
            s=AirTunes
            t=0 0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
        """.trimIndent()
    }
}
