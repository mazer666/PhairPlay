package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.phairplay.airplay.handshake.PlistCodec

/**
 * RtspHandlerSessionTest — proves the answers a sender gets while it controls a session.
 *
 * WHY: The handshake tests prove that a session comes up; these tests prove that it
 * also goes down again the way it is supposed to. A TEARDOWN that leaves a decoder
 * running drains the battery and keeps the sender blocked, and a TEARDOWN that ends
 * the whole session when only one of two streams was closed breaks the other stream.
 * The `/photo` endpoint is the second control surface of the same handler: a sender
 * pushes a still image over it and clears it again, so its validation belongs here
 * too.
 *
 * WHAT WE TEST:
 * - TEARDOWN ends the session and reports it through the onStreamingStopped callback
 * - TEARDOWN of one stream stops exactly that stream and keeps the other alive
 * - TEARDOWN of the last remaining stream ends the session
 * - TEARDOWN naming an unknown stream type changes nothing
 * - PUT /photo delivers a valid JPEG to the photo callback and rejects an invalid
 *   payload with 400
 * - DELETE /photo clears the current photo
 *
 * HOW: The tests drive the handler through [TestableRtspHandler], which exposes the
 * internal verb handlers without a network socket. The active mirror streams are
 * seeded through [TestableRtspHandler.seedActiveStreams] so that the test does not
 * have to drive the full FairPlay SETUP handshake first.
 *
 * Example:
 *   val handler = createTestHandler()
 *   handler.seedActiveStreams(96, 110)
 *   handler.handleTeardownPublic(teardownRequest(teardownBody(96)))
 *   assertTrue("audio should be stopped", audioStopped)
 */
class RtspHandlerSessionTest {

    // Captures whether the callbacks were invoked
    private var streamingStopped = false
    private var photoReceived = false
    private var photoCleared = false
    private var lastPhotoType: PhotoImageType? = null
    private var audioStopped = false
    private var videoStopped = false

    @Before
    fun setup() {
        streamingStopped = false
        photoReceived = false
        photoCleared = false
        lastPhotoType = null
        audioStopped = false
        videoStopped = false
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
        onStreamingStarted = { },
        onStreamingStopped = { streamingStopped = true },
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
        val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    }
}
