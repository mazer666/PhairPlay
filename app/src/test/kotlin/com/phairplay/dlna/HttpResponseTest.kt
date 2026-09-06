package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HttpResponseTest — responses must be valid HTTP/1.1 with Content-Length and Connection: close.
 *
 * WHY: We never keep connections alive; a wrong Content-Length makes control points hang.
 */
class HttpResponseTest {

    @Test
    fun `toBytes writes status line, mandatory headers and body`() {
        val text = String(HttpResponse.xml(200, "<a/>").toBytes(), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("CONTENT-LENGTH: 4\r\n"))
        assertTrue(text.contains("CONNECTION: close\r\n"))
        assertTrue(text.contains("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n"))
        assertTrue(text.contains("DATE: "))
        assertTrue(text.endsWith("\r\n\r\n<a/>"))
    }

    @Test
    fun `a header value containing CRLF cannot inject or split the response`() {
        val response = HttpResponse.empty(200, mapOf("SID" to "uuid:1\r\nX-Injected: 1"))
        val text = String(response.toBytes(), Charsets.ISO_8859_1)
        assertTrue(text.contains("SID: uuid:1X-Injected: 1\r\n"))
        assertFalse(text.contains("\r\nX-Injected"))
        assertEquals(1, Regex("CONTENT-LENGTH:").findAll(text).count())
    }

    @Test
    fun `a header name containing CRLF cannot smuggle in a duplicate CONTENT-LENGTH`() {
        val response = HttpResponse.empty(200, mapOf("CONTENT-LENGTH\r\n" to "5"))
        val text = String(response.toBytes(), Charsets.ISO_8859_1)
        assertEquals(1, Regex("CONTENT-LENGTH:").findAll(text).count())
    }

    @Test
    fun `reason phrases cover the codes we emit`() {
        assertEquals("Precondition Failed", HttpResponse.reason(412))
        assertEquals("Payload Too Large", HttpResponse.reason(413))
        assertEquals("Method Not Allowed", HttpResponse.reason(405))
        assertEquals("Unknown", HttpResponse.reason(299))
    }

    @Test
    fun `empty response has zero content length`() {
        val text = String(HttpResponse.empty(404).toBytes(), Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 404 Not Found\r\n"))
        assertTrue(text.contains("CONTENT-LENGTH: 0\r\n"))
    }
}
