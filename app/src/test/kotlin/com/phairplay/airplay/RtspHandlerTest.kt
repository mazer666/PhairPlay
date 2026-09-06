package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.phairplay.airplay.handshake.PlistCodec

/**
 * RtspHandlerTest — Unit tests for the RTSP protocol implementation.
 *
 * WHY: The RTSP handler is the most security-critical component of PhairPlay.
 * It processes untrusted data from the network. Every parsing path must be
 * tested with both valid inputs and malformed/malicious inputs.
 *
 * WHAT WE TEST:
 * - Correct responses for each RTSP method (OPTIONS, ANNOUNCE, SETUP, RECORD, TEARDOWN)
 * - SDP body parsing (codec parameters and encryption keys)
 * - Security: malformed/empty input handling (must not crash)
 * - Security: oversized messages (should be rejected)
 * - Correct state machine: RECORD without prior ANNOUNCE returns 455
 */
class RtspHandlerTest {

    // Captures whether the callbacks were invoked
    private var streamingStarted = false
    private var lastSession: SessionDescription? = null
    private var streamingStopped = false
    private var photoReceived = false
    private var photoCleared = false
    private var lastPhotoType: PhotoImageType? = null
    private var audioStopped = false
    private var videoStopped = false

    @Before
    fun setup() {
        streamingStarted = false
        lastSession = null
        streamingStopped = false
        photoReceived = false
        photoCleared = false
        lastPhotoType = null
        audioStopped = false
        videoStopped = false
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
    fun `ANNOUNCE with an unrecoverable rsaaeskey returns 500 and does not start streaming`() {
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

        val handler = createTestHandler()
        val response = handler.handleAnnouncePublic(
            RtspRequest(
                method = "ANNOUNCE",
                uri = "rtsp://192.168.1.185/1",
                headers = mapOf("CSeq" to "2", "Content-Type" to "application/sdp"),
                body = sdp
            )
        )

        assertEquals(500, response.statusCode)
        assertFalse(streamingStarted)
    }

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

    // ─── TEARDOWN ────────────────────────────────────────────────────────────

    @Test
    fun `TEARDOWN triggers onStreamingStopped callback`() {
        val handler = createTestHandler()
        handler.handleTeardownPublic(
            RtspRequest(method = "TEARDOWN", uri = "", headers = emptyMap(), body = "")
        )
        assertTrue("onStreamingStopped should have been called", streamingStopped)
    }

    @Test
    fun `TEARDOWN returns 200`() {
        val response = createTestHandler().handleTeardownPublic(
            RtspRequest(method = "TEARDOWN", uri = "", headers = emptyMap(), body = "")
        )
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `TEARDOWN of audio stream stops audio and keeps video session alive`() {
        val handler = createTestHandler()
        handler.seedActiveStreams(96, 110)
        handler.handleTeardownPublic(teardownRequest(teardownBody(96)))
        assertTrue("audio should be stopped", audioStopped)
        assertFalse("video should NOT be stopped", videoStopped)
        assertFalse("session must stay alive while video remains", streamingStopped)
    }

    @Test
    fun `TEARDOWN of video stream stops video and keeps audio session alive`() {
        val handler = createTestHandler()
        handler.seedActiveStreams(96, 110)
        handler.handleTeardownPublic(teardownRequest(teardownBody(110)))
        assertTrue("video should be stopped", videoStopped)
        assertFalse("audio should NOT be stopped", audioStopped)
        assertFalse("session must stay alive while audio remains", streamingStopped)
    }

    @Test
    fun `TEARDOWN naming all streams ends the session`() {
        val handler = createTestHandler()
        handler.seedActiveStreams(96, 110)
        handler.handleTeardownPublic(teardownRequest(teardownBody(96, 110)))
        assertTrue("audio should be stopped", audioStopped)
        assertTrue("video should be stopped", videoStopped)
        assertTrue("session should end when the last stream is removed", streamingStopped)
    }

    @Test
    fun `TEARDOWN of the last remaining stream ends the session`() {
        val handler = createTestHandler()
        handler.seedActiveStreams(110)
        handler.handleTeardownPublic(teardownRequest(teardownBody(110)))
        assertTrue("video should be stopped", videoStopped)
        assertTrue("session should end when no streams remain", streamingStopped)
    }

    @Test
    fun `TEARDOWN of an unknown stream type leaves the active session untouched`() {
        val handler = createTestHandler()
        handler.seedActiveStreams(110)
        handler.handleTeardownPublic(teardownRequest(teardownBody(200)))
        assertFalse("no known stream named — nothing stopped", videoStopped)
        assertFalse("no known stream named — nothing stopped", audioStopped)
        assertFalse("session with active streams must stay alive", streamingStopped)
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

    // ─── Photo endpoint ─────────────────────────────────────────────────────

    @Test
    fun `PUT photo with valid JPEG triggers photo callback`() {
        val response = createTestHandler().handlePhotoPutPublic(
            RtspRequest(
                method = "PUT",
                uri = "/photo",
                headers = mapOf("Content-Type" to "image/jpeg"),
                body = "",
                bodyBytes = JPEG_BYTES,
                protocol = "HTTP/1.1"
            )
        )

        assertEquals(200, response.statusCode)
        assertEquals("HTTP/1.1", response.protocol)
        assertTrue("photo callback should be called", photoReceived)
        assertEquals(PhotoImageType.JPEG, lastPhotoType)
    }

    @Test
    fun `PUT photo with invalid payload returns 400`() {
        val response = createTestHandler().handlePhotoPutPublic(
            RtspRequest(
                method = "PUT",
                uri = "/photo",
                headers = mapOf("Content-Type" to "image/jpeg"),
                body = "not an image",
                bodyBytes = "not an image".toByteArray(),
                protocol = "HTTP/1.1"
            )
        )

        assertEquals(400, response.statusCode)
    }

    @Test
    fun `DELETE photo triggers clear callback`() {
        val response = createTestHandler().handlePhotoDeletePublic(
            RtspRequest(
                method = "DELETE",
                uri = "/photo",
                headers = emptyMap(),
                body = "",
                protocol = "HTTP/1.1"
            )
        )

        assertEquals(200, response.statusCode)
        assertTrue("photo clear callback should be called", photoCleared)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun createTestHandler(): TestableRtspHandler = TestableRtspHandler(
        onStreamingStarted = { session ->
            streamingStarted = true
            lastSession = session
        },
        onStreamingStopped = { streamingStopped = true }
        ,
        onPhotoReceived = { _, imageType ->
            photoReceived = true
            lastPhotoType = imageType
        },
        onPhotoCleared = { photoCleared = true },
        onMirrorAudioStop = { audioStopped = true },
        onMirrorVideoStop = { videoStopped = true }
    )

    /** Binary-plist TEARDOWN body naming the given stream types, e.g. `{streams:[{type:96}]}`. */
    private fun teardownBody(vararg streamTypes: Int): ByteArray =
        PlistCodec.encode(mapOf("streams" to streamTypes.map { mapOf("type" to it.toLong()) }))

    private fun teardownRequest(bytes: ByteArray) =
        RtspRequest(method = "TEARDOWN", uri = "", headers = emptyMap(), body = "", bodyBytes = bytes)

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

        val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    }
}

/**
 * TestableRtspHandler — Subclass of [RtspHandler] that exposes internal methods for unit testing
 * without requiring a real network socket.
 */
class TestableRtspHandler(
    onStreamingStarted: (SessionDescription) -> Unit,
    onStreamingStopped: () -> Unit,
    onPhotoReceived: (ByteArray, PhotoImageType) -> Unit = { _, _ -> },
    onPhotoCleared: () -> Unit = {},
    onMirrorAudioStop: () -> Unit = {},
    onMirrorVideoStop: () -> Unit = {}
) : RtspHandler(
    context = io.mockk.mockk(relaxed = true),
    videoSurfaceProvider = { null },
    onStreamingStarted = onStreamingStarted,
    onStreamingStopped = onStreamingStopped,
    onPhotoReceived = onPhotoReceived,
    onPhotoCleared = onPhotoCleared,
    onMirrorAudioStop = onMirrorAudioStop,
    onMirrorVideoStop = onMirrorVideoStop
) {
    /** Test seam: mark mirror streams active without driving the full FairPlay SETUP handshake. */
    fun seedActiveStreams(vararg types: Int) { activeStreamTypes.addAll(types.toList()) }

    fun handleOptionsPublic(req: RtspRequest) = handleOptionsInternal(req)
    fun handleAnnouncePublic(req: RtspRequest) = handleAnnounceInternal(req)
    fun handleSetupPublic(req: RtspRequest) = handleSetupInternal(req)
    fun handleRecordPublic(req: RtspRequest) = handleRecordInternal(req)
    fun handleTeardownPublic(req: RtspRequest) = handleTeardownInternal(req)
    fun handleUnknownMethodPublic(req: RtspRequest) = handleUnknownInternal(req)
    fun handlePausePublic(req: RtspRequest) = handlePauseInternal(req)
    fun handlePhotoPutPublic(req: RtspRequest) = handlePhotoPutInternal(req)
    fun handlePhotoDeletePublic(req: RtspRequest) = handlePhotoDeleteInternal(req)
}
