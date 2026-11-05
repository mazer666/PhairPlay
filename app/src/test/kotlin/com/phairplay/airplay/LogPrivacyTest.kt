package com.phairplay.airplay

import com.phairplay.util.LogRedactionMode
import com.phairplay.util.Logger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * LogPrivacyTest — proves that the log does not carry the personal data of the
 * household.
 *
 * WHY: the log of a television ends up in public places. The user copies an excerpt
 * into a bug tracker, a chat message or a forum thread, and from that moment on it is
 * public and permanent. The addresses are removed by the central reduction
 * (`com.phairplay.util.LogRedactor`), but the reduction cannot recognise free text:
 * the name of the television, the name of the sender and — the worst of them — the
 * title, the artist and the album of the track that is playing right now. Those must
 * therefore never reach a log statement in the first place.
 *
 * WHAT WE TEST:
 * - the now-playing metadata of a sender does not appear in the log,
 * - the body of a request does not appear in the log,
 * - the name of the television is removed even if a log statement names it — the
 *   central reduction knows the name and removes it from every message,
 * - the reduction still works, and the log still says something useful.
 */
class LogPrivacyTest {

    /**
     * A Timber tree that keeps the formatted messages instead of writing them to logcat.
     * The app itself never installs such a tree — this one exists for the test only.
     */
    private class CapturingTree : Timber.Tree() {
        val messages = mutableListOf<String>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages.add(message)
        }
    }

    private val tree = CapturingTree()

    @Before
    fun setUp() {
        Timber.plant(tree)
        Logger.setRedactionMode(LogRedactionMode.REDACT)
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
        Logger.setRedactionMode(LogRedactionMode.REDACT)
        com.phairplay.util.LogRedactor.clearPersonalStrings()
    }

    @Test
    fun `the title the artist and the album of the playing track are not logged`() {
        var seenTitle: String? = null
        var seenArtist: String? = null
        var seenAlbum: String? = null
        val parameters = RtspParameterHandler(
            onVolume = { },
            onNowPlayingMetadata = { title, artist, album ->
                seenTitle = title
                seenArtist = artist
                seenAlbum = album
            },
            onArtwork = { }
        )
        // The binary DMAP body that macOS sends: four ASCII bytes of the tag, four
        // bytes of the big-endian length, then the payload.
        val body = dmapTag("mlit", dmapTag("minm", "Living Room Radio".toByteArray()) +
            dmapTag("asar", "Dire Straits".toByteArray()) +
            dmapTag("asal", "Brothers in Arms".toByteArray()))

        parameters.handleSetParameter(
            RtspRequest(
                method = "SET_PARAMETER",
                uri = "/now-playing",
                headers = mapOf("Content-Type" to "application/x-dmap-tag"),
                body = "",
                bodyBytes = body
            )
        )

        // The control: the metadata really was parsed, so there was something to leak.
        assertEquals("Living Room Radio", seenTitle)
        assertEquals("Dire Straits", seenArtist)
        assertEquals("Brothers in Arms", seenAlbum)

        val logged = tree.messages.joinToString("\n")
        assertFalse("the title is in the log", logged.contains("Living Room Radio"))
        assertFalse("the artist is in the log", logged.contains("Dire Straits"))
        assertFalse("the album is in the log", logged.contains("Brothers in Arms"))
        assertTrue("the log does not say that metadata arrived", logged.contains("now-playing"))
    }

    /** Encodes one DMAP tag: four ASCII bytes, four bytes of big-endian length, payload. */
    private fun dmapTag(name: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(name.toByteArray(Charsets.US_ASCII))
        out.write(
            byteArrayOf(
                (payload.size ushr 24).toByte(),
                (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte()
            )
        )
        out.write(payload)
        return out.toByteArray()
    }

    @Test
    fun `the body of a request is not logged`() {
        val parameters = RtspParameterHandler(
            onVolume = { },
            onNowPlayingMetadata = { _, _, _ -> },
            onArtwork = { }
        )

        parameters.handleGetParameter(
            RtspRequest(
                method = "GET_PARAMETER",
                uri = "/volume",
                headers = emptyMap(),
                body = "volume: -12.5 and a secret the sender chose"
            )
        )

        val logged = tree.messages.joinToString("\n")
        assertFalse("the body of the request is in the log", logged.contains("a secret the sender chose"))
    }

    @Test
    fun `the name of the television is removed by the central reduction`() {
        // The call sites do not log the name — this proves the second line of defence:
        // a log statement that names the television anyway does not leak it.
        com.phairplay.util.LogRedactor.registerPersonalString("Wohnzimmer Fernseher")

        Logger.i("AirPlay receiver started (displayName='Wohnzimmer Fernseher')")

        val logged = tree.messages.single()
        assertFalse("the name of the television is in the log", logged.contains("Wohnzimmer Fernseher"))
        assertTrue("the log does not say what was replaced", logged.contains("<name>"))
    }

    @Test
    fun `a short name is not replaced because it would mangle every message`() {
        com.phairplay.util.LogRedactor.registerPersonalString("TV")

        Logger.i("AirPlay TV session started")

        assertTrue(
            "the message was mangled by a two-letter name",
            tree.messages.single() == "AirPlay TV session started"
        )
    }

    @Test
    fun `the reduction still removes the address of the network`() {
        Logger.i("New client connected: 192.168.1.42")

        assertTrue("the address is in the log", tree.messages.single().contains("192.168.1.x"))
    }
}
