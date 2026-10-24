package com.phairplay.dlna

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.Socket

/**
 * DlnaDescriptionServerTest — tests the HTTP surface of the renderer.
 *
 * WHY: the server listens on the local network without authentication, so any device
 * on the LAN can talk to it. ADR-004 requires "bounded buffers, strict header length
 * limits, and connection timeouts to prevent resource exhaustion" — these tests pin
 * that down.
 *
 * WHAT WE TEST:
 * - the device description and the service descriptions are served,
 * - a SOAP control request is answered with a well-formed envelope,
 * - an over-long request line is rejected with 414,
 * - an over-long header line is rejected with 431,
 * - an over-long body is rejected with 413,
 * - a SUBSCRIBE with a forbidden callback is rejected with 412,
 * - a SUBSCRIBE with a valid callback is answered with SID, TIMEOUT and EXT,
 * - the error answers carry no stack trace, no path and no version.
 */
class DlnaDescriptionServerTest {

    private lateinit var server: DlnaDescriptionServer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        server = DlnaDescriptionServer(
            friendlyName = "Test TV",
            udn = "uuid:test-udn",
            localHost = DlnaTestNetwork.privateAddress()
        )
        server.start(scope)
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    @Test
    fun `serves the device description`() {
        val response = send("GET /description.xml HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

        assertTrue("expected HTTP 200, got: $response", response.contains("200 OK"))
        assertTrue(response.contains("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(response.contains("<friendlyName>Test TV</friendlyName>"))
        assertTrue(response.contains("Content-Type: text/xml; charset=\"utf-8\""))
    }

    @Test
    fun `serves the AVTransport service description with its actions`() {
        val response = send("GET /AVTransport.xml HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

        assertTrue("expected HTTP 200, got: $response", response.contains("200 OK"))
        assertTrue(response.contains("<name>SetAVTransportURI</name>"))
        assertTrue(response.contains("<name>GetPositionInfo</name>"))
        assertTrue(response.contains("<name>CurrentURI</name>"))
    }

    @Test
    fun `serves the ConnectionManager service description`() {
        val response = send("GET /ConnectionManager.xml HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

        assertTrue(response.contains("<name>GetProtocolInfo</name>"))
    }

    @Test
    fun `answers a SOAP control request`() {
        val body = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:GetProtocolInfo xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1"/>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val response = send(
            "POST /upnp/control/ConnectionManager HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "SOAPACTION: \"urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo\"\r\n" +
                "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                body
        )

        assertTrue("expected HTTP 200, got: $response", response.contains("200 OK"))
        assertTrue(response.contains("<Source>http-get:*:video/mp4:*"))
        assertTrue(response.contains("Content-Type: text/xml; charset=\"utf-8\""))
    }

    @Test
    fun `refuses an XXE payload in a SOAP request`() {
        val body = """
            <?xml version="1.0"?>
            <!DOCTYPE s:Envelope [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>&xxe;</s:Body>
            </s:Envelope>
        """.trimIndent()
        val response = send(
            "POST /upnp/control/AVTransport HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                body
        )

        assertTrue("expected HTTP 400, got: $response", response.contains("400"))
        assertFalse("the file content must not leak", response.contains("root:"))
    }

    @Test
    fun `rejects an over-long request line`() {
        // A client that never sends a newline must not make the server buffer it all.
        val response = send("A".repeat(64 * 1024) + "\r\n\r\n")

        assertTrue("expected HTTP 414, got: $response", response.contains("414"))
    }

    @Test
    fun `rejects an over-long header line`() {
        val response = send(
            "GET /description.xml HTTP/1.1\r\n" +
                "X-Filler: ${"B".repeat(64 * 1024)}\r\n" +
                "\r\n"
        )

        assertTrue("expected HTTP 431, got: $response", response.contains("431"))
    }

    @Test
    fun `rejects an over-long body`() {
        val response = send(
            "POST /upnp/control/AVTransport HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Length: 1048576\r\n\r\n"
        )

        assertTrue("expected HTTP 413, got: $response", response.contains("413"))
    }

    @Test
    fun `a SUBSCRIBE with a forbidden callback is refused`() {
        val response = send(
            "SUBSCRIBE /upnp/event/AVTransport HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "CALLBACK: <http://169.254.169.254/latest>\r\n" +
                "NT: upnp:event\r\n" +
                "TIMEOUT: Second-1800\r\n\r\n"
        )

        assertTrue("expected HTTP 412, got: $response", response.contains("412"))
    }

    @Test
    fun `a SUBSCRIBE with a valid callback is answered with SID, TIMEOUT and EXT`() {
        val address = DlnaTestNetwork.privateAddress()
        val response = send(
            "SUBSCRIBE /upnp/event/AVTransport HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "CALLBACK: <http://$address:1400/event>\r\n" +
                "NT: upnp:event\r\n" +
                "TIMEOUT: Second-300\r\n\r\n"
        )

        assertTrue("expected HTTP 200, got: $response", response.contains("200 OK"))
        assertTrue("the SID header is missing", response.contains("SID: uuid:"))
        assertTrue("the TIMEOUT header is missing", response.contains("TIMEOUT: Second-"))
        assertTrue("the EXT header is missing", response.contains("EXT:"))
    }

    @Test
    fun `an UNSUBSCRIBE with an unknown SID is refused`() {
        val response = send(
            "UNSUBSCRIBE /upnp/event/AVTransport HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "SID: uuid:does-not-exist\r\n\r\n"
        )

        assertTrue("expected HTTP 412, got: $response", response.contains("412"))
    }

    @Test
    fun `an unknown path is answered with 404`() {
        val response = send("GET /not-here.xml HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

        assertTrue("expected HTTP 404, got: $response", response.contains("404"))
    }

    @Test
    fun `error answers leak neither stack traces nor paths nor versions`() {
        val responses = listOf(
            send("GET /not-here.xml HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"),
            send("POST /upnp/control/AVTransport HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 3\r\n\r\n<a>"),
            send("A".repeat(64 * 1024) + "\r\n\r\n")
        )

        responses.forEach { response ->
            assertFalse("stack trace leaked", response.contains("at com.phairplay"))
            assertFalse("exception class leaked", response.contains("Exception"))
            assertFalse("file path leaked", response.contains("/workspace"))
            assertFalse("version leaked", response.contains("PhairPlay/1."))
        }
    }

    /**
     * Sends [request] to the server and returns the status line plus whatever else
     * arrives within the read timeout.
     */
    private fun send(request: String): String {
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            return socket.getInputStream().readFullResponse()
        }
    }

    /**
     * Reads the whole response.
     *
     * WHY: the server announces the body length and closes the connection
     * afterwards, so reading until the end of the stream gives the complete answer —
     * including the parts of the body that lie behind the first 200 bytes.
     */
    private fun InputStream.readFullResponse(): String {
        val builder = StringBuilder()
        val buffer = ByteArray(4096)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            builder.append(String(buffer, 0, read, Charsets.ISO_8859_1))
        }
        return builder.toString()
    }
}
