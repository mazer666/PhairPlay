package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RtspVideoUrlHandlerTest — proves which media URLs the AirPlay URL mode accepts.
 *
 * WHY: in the URL mode the sender names the address and the *television* fetches it.
 * A device in the network that may send one `POST /play` can therefore aim the receiver
 * at any host it can reach — a router admin page, a NAS, a printer, a camera — and see
 * the answer on the screen. A `file://` URL goes one step further and turns the receiver
 * into a file reader for everything the app's own UID may read. The handler must refuse
 * such a URL *before* the player is asked to fetch anything.
 *
 * WHAT WE TEST:
 * - `POST /play` with a `file://` URL is refused with 400 and the player is never asked
 * - `POST /play` with a `file://` URL in a binary plist body is refused the same way
 * - `POST /play` with a link-local address is refused with 400
 * - `POST /play` with an ordinary `http` URL is accepted and reaches the player
 *
 * HOW: The handler is driven directly with an [RtspRequest]; the `onVideoPlay` callback
 * stands in for the player and records what it was asked to fetch.
 *
 * Example:
 *   val response = createHandler().handleVideoPlay(playRequest("file:///etc/passwd"))
 *   assertEquals(400, response.statusCode)
 *   assertTrue(playRequests.isEmpty())
 */
class RtspVideoUrlHandlerTest {

    /** What the player was asked to fetch — empty means the handler refused the URL. */
    private val playRequests = mutableListOf<Pair<String, Double>>()

    @Before
    fun setup() {
        playRequests.clear()
    }

    // ─── The refusal ──────────────────────────────────────────────────────────

    @Test
    fun `POST play refuses a file url and never asks the player to fetch it`() {
        val response = createHandler().handleVideoPlay(
            playRequest("file:///data/data/com.phairplay/shared_prefs/phairplay_prefs.xml")
        )

        assertEquals(400, response.statusCode)
        assertEquals("Bad Request", response.statusMessage)
        assertTrue("the player must never be asked to fetch the URL", playRequests.isEmpty())
    }

    @Test
    fun `POST play refuses a file url in a plist body`() {
        val body = PlistCodec.encode(mapOf("Content-Location" to "file:///etc/passwd"))
        val response = createHandler().handleVideoPlay(
            RtspRequest(method = "POST", uri = "/play", headers = mapOf("CSeq" to "1"), body = "", bodyBytes = body)
        )

        assertEquals(400, response.statusCode)
        assertTrue("the player must never be asked to fetch the URL", playRequests.isEmpty())
    }

    @Test
    fun `POST play refuses the cloud metadata service of the router`() {
        val response = createHandler().handleVideoPlay(
            playRequest("http://169.254.169.254/latest/meta-data/")
        )

        assertEquals(400, response.statusCode)
        assertTrue("the player must never be asked to fetch the URL", playRequests.isEmpty())
    }

    @Test
    fun `POST play refuses the loopback of the receiver`() {
        val response = createHandler().handleVideoPlay(playRequest("http://127.0.0.1:8080/secret"))

        assertEquals(400, response.statusCode)
        assertTrue("the player must never be asked to fetch the URL", playRequests.isEmpty())
    }

    // ─── The normal case ──────────────────────────────────────────────────────

    @Test
    fun `POST play accepts an ordinary http url and starts the player`() {
        val response = createHandler().handleVideoPlay(playRequest("http://192.168.1.5:8080/movie.mp4"))

        assertEquals(200, response.statusCode)
        assertEquals(1, playRequests.size)
        assertEquals("http://192.168.1.5:8080/movie.mp4", playRequests[0].first)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** A `POST /play` request with the legacy text body a sender sends. */
    private fun playRequest(url: String): RtspRequest = RtspRequest(
        method = "POST",
        uri = "/play",
        headers = mapOf("CSeq" to "1"),
        body = "Content-Location: $url\r\nStart-Position: 0.0\r\n"
    )

    /** The handler under test; the callback stands in for the player. */
    private fun createHandler(): RtspVideoUrlHandler = RtspVideoUrlHandler(
        onVideoPlay = { url, start -> playRequests.add(url to start) },
        onVideoRate = {},
        onVideoScrub = {},
        onVideoStop = {},
        onPlaybackInfo = { null }
    )
}
