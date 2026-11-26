package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RtspMirrorSetupTest — proves how the AirPlay 2 mirror SETUP answers a stream that cannot start.
 *
 * WHY: a sender adds the audio stream to a **running** mirror when the user switches the
 * sound on. If the receiver cannot start that stream, the whole SETUP used to be answered
 * with "400 Bad Request" — and the sender tore the session down, so the picture stopped
 * too. The answer must name the streams that work and leave the failed one out.
 *
 * The decision is tested on the host JVM: no socket, no sender, no television.
 */
class RtspMirrorSetupTest {

    @Test
    fun `a failing audio stream does not fail the setup and keeps the video stream`() {
        val sessionState = RtspSessionState()
        val handler = RtspMirrorHandler(
            sessionState = sessionState,
            audioEnabled = true,
            onMirrorSetupKeys = { _, _, _, _, _ -> EVENT_PORT to TIMING_PORT },
            onMirrorStreamStart = { VIDEO_DATA_PORT },
            // The audio socket cannot be opened — the failure the report from a television showed.
            onMirrorAudioStart = { _, _, _, _ -> throw IllegalStateException("the UDP socket could not be opened") },
            onBufferedAudioStart = { 0 }
        )

        val response = handler.handleMirrorSetup(requestWithStreams(110, 96))

        assertEquals("the SETUP of a running mirror must not fail", 200, response.statusCode)
        val streams = streamsOf(response)
        assertTrue("the video stream has to stay in the answer", streams.any { it["type"] == 110L })
        assertFalse("the failed audio stream must not be announced", streams.any { it["type"] == 96L })
        assertTrue("the video stream stays active", sessionState.activeStreamTypes.contains(110))
        assertFalse("the failed audio stream must not be marked active", sessionState.activeStreamTypes.contains(96))
    }

    @Test
    fun `a failing audio stream answers with a port and not with an error`() {
        val handler = RtspMirrorHandler(
            sessionState = RtspSessionState(),
            audioEnabled = true,
            onMirrorSetupKeys = { _, _, _, _, _ -> EVENT_PORT to TIMING_PORT },
            onMirrorStreamStart = { VIDEO_DATA_PORT },
            // The audio server reports "not started" instead of throwing.
            onMirrorAudioStart = { _, _, _, _ -> 0 to 0 },
            onBufferedAudioStart = { 0 }
        )

        val response = handler.handleMirrorSetup(requestWithStreams(110, 96))

        assertEquals(200, response.statusCode)
        val streams = streamsOf(response)
        assertTrue("the video stream has to stay in the answer", streams.any { it["type"] == 110L })
        assertFalse("the failed audio stream must not be announced", streams.any { it["type"] == 96L })
    }

    @Test
    fun `a setup without a single working stream is rejected`() {
        val handler = RtspMirrorHandler(
            sessionState = RtspSessionState(),
            audioEnabled = true,
            onMirrorSetupKeys = { _, _, _, _, _ -> EVENT_PORT to TIMING_PORT },
            onMirrorStreamStart = { 0 },
            onMirrorAudioStart = { _, _, _, _ -> throw IllegalStateException("the UDP socket could not be opened") },
            onBufferedAudioStart = { 0 }
        )

        val response = handler.handleMirrorSetup(requestWithStreams(110, 96))

        assertEquals(400, response.statusCode)
    }

    @Test
    fun `an audio stream that is switched off in the settings is left out without an error`() {
        val handler = RtspMirrorHandler(
            sessionState = RtspSessionState(),
            audioEnabled = false,
            onMirrorSetupKeys = { _, _, _, _, _ -> EVENT_PORT to TIMING_PORT },
            onMirrorStreamStart = { VIDEO_DATA_PORT },
            onMirrorAudioStart = { _, _, _, _ -> VIDEO_DATA_PORT to VIDEO_DATA_PORT },
            onBufferedAudioStart = { 0 }
        )

        val response = handler.handleMirrorSetup(requestWithStreams(110, 96))

        assertEquals("a stream that is ignored on purpose must not fail the SETUP", 200, response.statusCode)
        assertFalse(streamsOf(response).any { it["type"] == 96L })
    }

    /**
     * Builds a mirror SETUP request that asks for the given stream types.
     *
     * @param types the stream types the sender asks for.
     * @return the request with a binary-plist body.
     */
    private fun requestWithStreams(vararg types: Int): RtspRequest {
        val streams = types.map { type ->
            mapOf("type" to type.toLong(), "streamConnectionID" to 1L)
        }
        val body = PlistCodec.encode(mapOf("streams" to streams))
        return RtspRequest(
            method = "SETUP",
            uri = "rtsp://127.0.0.1/live",
            headers = mapOf("Content-Type" to "application/x-apple-binary-plist"),
            body = "",
            bodyBytes = body
        )
    }

    /**
     * Reads the streams out of the answer of a mirror SETUP.
     *
     * @param response the answer of the handler.
     * @return the stream list of the answer.
     */
    @Suppress("UNCHECKED_CAST")
    private fun streamsOf(response: RtspResponse): List<Map<String, Any?>> {
        val decoded = PlistCodec.decode(response.bodyBytes ?: ByteArray(0))
        return decoded["streams"] as? List<Map<String, Any?>> ?: emptyList()
    }

    companion object {
        private const val EVENT_PORT = 49152
        private const val TIMING_PORT = 6002
        private const val VIDEO_DATA_PORT = 49153
    }
}
