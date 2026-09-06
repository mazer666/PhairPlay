package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * RtspRequestReaderTest — body-size limits on the AirPlay control socket.
 *
 * WHY: Music.app sends album artwork as a SET_PARAMETER whose body is hundreds of KB. Applying
 * the 64 KB control-message limit to it closed the connection 190 ms after RECORD, which macOS
 * showed as the device "dropping". Artwork gets its own limit; everything else keeps the
 * defensive 64 KB cap.
 */
class RtspRequestReaderTest {

    private val controlLimit = 64 * 1024
    private val artworkLimit = 4 * 1024 * 1024

    private fun request(method: String, contentType: String, body: ByteArray): ByteArrayInputStream {
        val head = "$method rtsp://192.168.1.185/1 RTSP/1.0\r\n" +
            "CSeq: 7\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "\r\n"
        return ByteArrayInputStream(head.toByteArray(Charsets.ISO_8859_1) + body)
    }

    private fun reader() = RtspRequestReader(
        maxMessageBytes = controlLimit,
        maxPhotoBytes = 25 * 1024 * 1024,
        maxArtworkBytes = artworkLimit
    )

    @Test
    fun `SET_PARAMETER artwork larger than the control limit is accepted`() {
        val artwork = ByteArray(432_829) { 0x42 }
        val parsed = reader().read(request("SET_PARAMETER", "image/jpeg", artwork))
        assertNotNull("artwork must not be rejected", parsed)
        assertEquals(432_829, parsed!!.bodyBytes.size)
        assertEquals("image/jpeg", parsed.headers["Content-Type"])
    }

    @Test
    fun `SET_PARAMETER artwork above the artwork limit is still rejected`() {
        val huge = ByteArray(artworkLimit + 1)
        assertNull(reader().read(request("SET_PARAMETER", "image/png", huge)))
    }

    @Test
    fun `non-artwork body above the control limit is rejected`() {
        val big = ByteArray(controlLimit + 1) { 0x61 }
        assertNull(reader().read(request("ANNOUNCE", "application/sdp", big)))
    }

    @Test
    fun `small text SET_PARAMETER still parses`() {
        val body = "volume: -20.0\r\n".toByteArray()
        val parsed = reader().read(request("SET_PARAMETER", "text/parameters", body))
        assertNotNull(parsed)
        assertEquals("volume: -20.0\r\n", parsed!!.body)
    }
}
