package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * HttpRequestReaderTest — the DLNA control server parses SOAP/GENA requests from raw sockets.
 *
 * WHY: Every byte from the network passes this gate (RULE 4): body caps, malformed lines and EOF must
 * each map to a distinct outcome so the server can answer 413/400 or just close.
 */
class HttpRequestReaderTest {

    private fun stream(text: String, body: ByteArray = ByteArray(0)) =
        ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1) + body)

    private val reader = HttpRequestReader(maxBodyBytes = 1024)

    @Test
    fun `reads method, path, headers and body`() {
        val body = "<x/>".toByteArray()
        val parsed = reader.read(stream(
            "POST /control/AVTransport?x=1 HTTP/1.1\r\nHost: tv\r\nSOAPACTION: \"a#Play\"\r\n" +
                "Content-Length: ${body.size}\r\n\r\n", body
        ))
        val request = (parsed as HttpParse.Ok).request
        assertEquals("POST", request.method)
        assertEquals("/control/AVTransport", request.path)
        assertEquals("\"a#Play\"", request.header("soapaction"))
        assertEquals("\"a#Play\"", request.header("SoapAction"))
        assertEquals("<x/>", request.bodyText)
    }

    @Test
    fun `request without body parses with empty body`() {
        val parsed = reader.read(stream("GET /description.xml HTTP/1.1\r\nHost: tv\r\n\r\n"))
        assertTrue(parsed is HttpParse.Ok)
        assertEquals(0, (parsed as HttpParse.Ok).request.body.size)
    }

    @Test
    fun `body above the cap is TooLarge`() {
        val parsed = reader.read(stream("POST /x HTTP/1.1\r\nContent-Length: 1025\r\n\r\n", ByteArray(1025)))
        assertTrue(parsed is HttpParse.TooLarge)
    }

    @Test
    fun `missing HTTP version is Malformed`() {
        assertTrue(reader.read(stream("GET /x\r\n\r\n")) is HttpParse.Malformed)
    }

    @Test
    fun `truncated body is Malformed`() {
        assertTrue(reader.read(stream("POST /x HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc")) is HttpParse.Malformed)
    }

    @Test
    fun `empty stream is Eof`() {
        assertTrue(reader.read(ByteArrayInputStream(ByteArray(0))) is HttpParse.Eof)
    }

    @Test
    fun `unparseable Content-Length is Malformed`() {
        val parsed = reader.read(stream("POST /x HTTP/1.1\r\nContent-Length: abc\r\n\r\n"))
        assertTrue(parsed is HttpParse.Malformed)
    }

    @Test
    fun `Content-Length overflowing an Int is TooLarge rather than silently zero`() {
        val parsed = reader.read(stream("POST /x HTTP/1.1\r\nContent-Length: 99999999999999\r\n\r\n"))
        assertTrue(parsed is HttpParse.TooLarge)
    }

    @Test
    fun `chunked Transfer-Encoding is Malformed`() {
        val parsed = reader.read(stream("POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertTrue(parsed is HttpParse.Malformed)
    }

    @Test
    fun `an over-long request line is TooLarge`() {
        val requestLine = "GET /" + "a".repeat(20 * 1024) + " HTTP/1.1"
        val parsed = reader.read(stream("$requestLine\r\n\r\n"))
        assertTrue(parsed is HttpParse.TooLarge)
    }

    @Test
    fun `an over-long header line is TooLarge`() {
        val headerLine = "X-Long: " + "a".repeat(20 * 1024)
        val parsed = reader.read(stream("GET /x HTTP/1.1\r\n$headerLine\r\n\r\n"))
        assertTrue(parsed is HttpParse.TooLarge)
    }
}
