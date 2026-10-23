package com.phairplay.dlna

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.Socket

/**
 * DlnaDescriptionServerTest — tests the HTTP resource limits of the description server.
 *
 * WHY: The server listens on the local network without authentication, so any device on
 * the LAN can talk to it. ADR-004 requires "bounded buffers, strict header length
 * limits, and connection timeouts to prevent resource exhaustion" — these tests pin
 * that down.
 *
 * WHAT WE TEST:
 * - a normal GET of the device description is answered with 200
 * - an over-long request line is rejected with 414 instead of being buffered
 * - an over-long header line is rejected with 431 instead of being buffered
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
            localHost = "127.0.0.1"
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

    /**
     * Sends [request] to the server and returns the status line plus whatever else
     * arrives within the read timeout.
     */
    private fun send(request: String): String {
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            return socket.getInputStream().readStatusAndSome()
        }
    }

    /**
     * Reads the response without waiting for the server to close the connection —
     * the description is served with Content-Length, so the status line and the first
     * part of the body are enough for the assertions.
     */
    private fun InputStream.readStatusAndSome(): String {
        val buffer = ByteArray(512)
        val builder = StringBuilder()
        while (builder.length < 200) {
            val read = read(buffer)
            if (read <= 0) break
            builder.append(String(buffer, 0, read, Charsets.US_ASCII))
        }
        return builder.toString()
    }
}
