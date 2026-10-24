package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RtspHandlerHandshakeTest — proves the answers a sender gets during the RTSP handshake.
 *
 * WHY: The handshake is the part of the RTSP implementation a sender walks through
 * before a single byte of media flows. macOS sends OPTIONS, then ANNOUNCE with its
 * SDP, then SETUP, then RECORD — and it aborts the connection at the first answer it
 * does not understand. A wrong status code here is therefore not a cosmetic bug: it
 * is a sender that never streams.
 *
 * WHAT WE TEST:
 * - OPTIONS advertises every RTSP method the sender may use
 * - ANNOUNCE accepts a valid video+audio SDP and a valid audio-only SDP, and rejects
 *   an empty and a blank body with 400
 * - SETUP returns the Session and the Transport header, and the first SETUP asks for
 *   TCP interleaved transport
 * - RECORD starts the stream, hands the parsed session to the callback, and is refused
 *   with 455 when no ANNOUNCE came before it
 * - A method the handler does not implement is refused with 501
 *
 * HOW: The tests drive the handler through [TestableRtspHandler], which exposes the
 * internal verb handlers without a network socket, and observe the callbacks the
 * handler would call during a real session.
 *
 * Example:
 *   val response = createTestHandler().handleOptionsPublic(request)
 *   assertEquals(200, response.statusCode)
 */
class RtspHandlerHandshakeTest {

    // Captures whether the callbacks were invoked
    private var streamingStarted = false
    private var lastSession: SessionDescription? = null

    @Before
    fun setup() {
        streamingStarted = false
        lastSession = null
    }

    // ─── OPTIONS ─────────────────────────────────────────────────────────────

    /**
     * Test: OPTIONS response includes all required RTSP methods.
     *
     * WHY: macOS reads the OPTIONS response to know what methods it can use.
     * If a required method is missing, the connection attempt will fail.
     */
    @Test
    fun `OPTIONS response includes all required methods`() {
        val response = createTestHandler().handleOptionsPublic(
            RtspRequest(
                method = "OPTIONS",
                uri = "rtsp://192.168.1.1/phairplay",
                headers = mapOf("CSeq" to "1"),
                body = ""
            )
        )

        assertEquals(200, response.statusCode)
        val publicMethods = response.headers["Public"] ?: ""
        assertTrue("OPTIONS missing", "OPTIONS" in publicMethods)
        assertTrue("ANNOUNCE missing", "ANNOUNCE" in publicMethods)
        assertTrue("SETUP missing", "SETUP" in publicMethods)
        assertTrue("RECORD missing", "RECORD" in publicMethods)
        assertTrue("TEARDOWN missing", "TEARDOWN" in publicMethods)
    }

    // ─── ANNOUNCE ────────────────────────────────────────────────────────────

    @Test
    fun `ANNOUNCE with valid video+audio SDP returns 200`() {
        val response = createTestHandler().handleAnnouncePublic(
            RtspRequest(
                method = "ANNOUNCE", uri = "", headers = emptyMap(),
                body = VALID_SDP_VIDEO_AUDIO
            )
        )
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `ANNOUNCE with valid audio-only SDP returns 200`() {
        val response = createTestHandler().handleAnnouncePublic(
            RtspRequest(
                method = "ANNOUNCE", uri = "", headers = emptyMap(),
                body = VALID_SDP_AUDIO_ONLY
            )
        )
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `ANNOUNCE with empty body returns 400`() {
        val response = createTestHandler().handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = "")
        )
        assertEquals(400, response.statusCode)
    }

    @Test
    fun `ANNOUNCE with blank body returns 400`() {
        val response = createTestHandler().handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = "   \n  ")
        )
        assertEquals(400, response.statusCode)
    }

    // ─── SETUP ───────────────────────────────────────────────────────────────

    @Test
    fun `SETUP response includes Session and Transport headers`() {
        val handler = createTestHandler()
        // ANNOUNCE first so session is established
        handler.handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = VALID_SDP_VIDEO_AUDIO)
        )

        val response = handler.handleSetupPublic(
            RtspRequest(method = "SETUP", uri = "", headers = emptyMap(), body = "")
        )

        assertEquals(200, response.statusCode)
        assertNotNull("Session header required", response.headers["Session"])
        assertNotNull("Transport header required", response.headers["Transport"])
    }

    @Test
    fun `first SETUP uses TCP interleaved transport (video)`() {
        val handler = createTestHandler()
        handler.handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = VALID_SDP_VIDEO_AUDIO)
        )

        val response = handler.handleSetupPublic(
            RtspRequest(method = "SETUP", uri = "", headers = emptyMap(), body = "")
        )

        val transport = response.headers["Transport"] ?: ""
        assertTrue("First SETUP should be TCP interleaved", "TCP" in transport || "interleaved" in transport)
    }

    // ─── RECORD ──────────────────────────────────────────────────────────────

    @Test
    fun `RECORD after ANNOUNCE triggers onStreamingStarted`() {
        val handler = createTestHandler()
        handler.handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = VALID_SDP_VIDEO_AUDIO)
        )
        handler.handleRecordPublic(
            RtspRequest(method = "RECORD", uri = "", headers = emptyMap(), body = "")
        )

        assertTrue("onStreamingStarted should be called", streamingStarted)
        assertNotNull("SessionDescription should be passed to callback", lastSession)
    }

    @Test
    fun `RECORD without prior ANNOUNCE returns 455`() {
        // No ANNOUNCE → currentSession is null → must return 455 Method Not Valid in This State
        val response = createTestHandler().handleRecordPublic(
            RtspRequest(method = "RECORD", uri = "", headers = emptyMap(), body = "")
        )
        assertEquals(455, response.statusCode)
    }

    @Test
    fun `RECORD with audio-only SDP sets isAudioOnly on session`() {
        val handler = createTestHandler()
        handler.handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = VALID_SDP_AUDIO_ONLY)
        )
        handler.handleRecordPublic(
            RtspRequest(method = "RECORD", uri = "", headers = emptyMap(), body = "")
        )

        assertTrue("audio-only session flag should be set", lastSession?.isAudioOnly == true)
    }

    // ─── Unknown method ───────────────────────────────────────────────────────

    @Test
    fun `unknown RTSP method returns 501`() {
        val response = createTestHandler().handleUnknownMethodPublic(
            RtspRequest(method = "FOOBAR", uri = "", headers = emptyMap(), body = "")
        )
        assertEquals(501, response.statusCode)
        assertEquals("Not Implemented", response.statusMessage)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun createTestHandler(): TestableRtspHandler = TestableRtspHandler(
        onStreamingStarted = { session ->
            streamingStarted = true
            lastSession = session
        },
        onStreamingStopped = {}
    )

    companion object {
        // Minimal valid SDP with H.264 video + AAC-ELD audio (base64 SPS/PPS included)
        val VALID_SDP_VIDEO_AUDIO = """
            v=0
            o=AirTunes AABBCCDDEEFF 1 IN IP4 192.168.1.10
            s=AirTunes
            t=0 0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;profile-level-id=640020;sprop-parameter-sets=Z2QAKKwbGAoAofjA,aO48gA==
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 mpeg4-generic/44100/2
            a=fmtp:96 streamtype=5;profile-level-id=15;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=F8E85000
            a=rsaaeskey:MTIzNDU2Nzg5MDEyMzQ1Ng==
            a=aesiv:MTYtYnl0ZS1pdmluaXR2
        """.trimIndent()

        // Audio-only SDP (no video section — used for music/podcast streaming)
        val VALID_SDP_AUDIO_ONLY = """
            v=0
            o=AirTunes AABBCCDDEEFF 1 IN IP4 192.168.1.10
            s=AirTunes
            t=0 0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
            a=rsaaeskey:MTIzNDU2Nzg5MDEyMzQ1Ng==
            a=aesiv:MTYtYnl0ZS1pdmluaXR2
        """.trimIndent()
    }
}
