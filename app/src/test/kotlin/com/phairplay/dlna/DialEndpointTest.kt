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
 * DialEndpointTest — tests the DIAL endpoints of the running description server.
 *
 * WHY: a control point does not call a class — it sends an HTTP request to the
 * renderer. These tests therefore drive the real server over a real socket and
 * look at the bytes that come back.
 *
 * WHAT WE TEST:
 * - `GET /dial/` answers with the app list that carries PhairPlay,
 * - `GET /dial/PhairPlay` answers with the app info that carries the state,
 * - `GET /dial/Unbekannt` answers with 404,
 * - the device description carries the `Application-Url` header,
 * - an app id that tries to escape the path answers with 404.
 */
class DialEndpointTest {

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
    fun `the dial directory answers with the app list`() {
        val response = send("GET /dial/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

        assertTrue("expected HTTP 200, got: $response", response.contains("200 OK"))
        assertTrue(
            "the DIAL namespace is missing",
            response.contains("xmlns=\"urn:dial-multiscreen-org:schemas:dial\"")
        )
        assertTrue("the app is missing", response.contains("name=\"PhairPlay\""))
        assertTrue("the DIAL version is missing", response.contains("dialVer=\"2.2\""))
    }

    @Test
    fun `the app info of PhairPlay is answered`() {
        val response = send("GET /dial/PhairPlay HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

        assertTrue("expected HTTP 200, got: $response", response.contains("200 OK"))
        assertTrue("the app name is missing", response.contains("<name>PhairPlay</name>"))
        assertTrue("the state is missing", response.contains("<state>stopped</state>"))
        assertTrue("the DIAL version is missing", response.contains("dialVer=\"2.2\""))
        assertTrue("the version is missing", response.contains("<version>1.0.0</version>"))
    }

    @Test
    fun `an unknown app is answered with 404`() {
        val response = send("GET /dial/Unbekannt HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

        assertTrue("expected HTTP 404, got: $response", response.contains("404"))
        assertFalse("no app document may be sent", response.contains("<service"))
    }

    @Test
    fun `an app id that tries to escape the path is answered with 404`() {
        val response = send("GET /dial/../../etc/passwd HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

        assertTrue("expected HTTP 404, got: $response", response.contains("404"))
        assertFalse("no file content may leak", response.contains("root:"))
    }

    @Test
    fun `the device description carries the Application-Url header`() {
        val response = send("GET /description.xml HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

        assertTrue("expected HTTP 200, got: $response", response.contains("200 OK"))
        assertTrue(
            "the Application-Url header is missing",
            response.contains("Application-Url: ${server.applicationUrl}")
        )
        assertTrue(
            "the Application-Url has to point at the dial directory",
            server.applicationUrl.endsWith("/dial")
        )
    }

    @Test
    fun `the Application-Url header is not sent with the app documents`() {
        val response = send("GET /dial/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

        assertFalse(
            "the app list carries no Application-Url header",
            response.contains("Application-Url")
        )
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
     * afterwards, so reading until the end of the stream gives the complete answer.
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
