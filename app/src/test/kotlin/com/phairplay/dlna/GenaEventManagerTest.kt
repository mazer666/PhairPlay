package com.phairplay.dlna

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GenaEventManagerTest — proves the eventing registry of the renderer.
 *
 * WHY: GENA is what makes a control point show the state of the renderer without
 * polling. Three things have to work: the subscription lifecycle, the SSRF guard on
 * the callback URL and the delivery of the `LastChange` document.
 *
 * WHAT WE TEST:
 * - subscribe returns a SID, unsubscribe removes it,
 * - a callback URL outside the private subnet is refused,
 * - the subscription timeout is clamped into the supported range,
 * - a `NOTIFY` with the nested UPnP `LastChange` reaches the control point,
 * - `stop()` drops every subscription.
 */
class GenaEventManagerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var manager: GenaEventManager

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        manager = GenaEventManager(localHost = LOCAL_HOST, scope = scope)
    }

    @After
    fun tearDown() {
        manager.stop()
        scope.cancel()
    }

    @Test
    fun `subscribe returns SID and unsubscribe removes it`() {
        val subscription = manager.subscribe(
            callbackHeader = "<http://$LOCAL_HOST:1400/event>",
            servicePath = "/upnp/event/AVTransport"
        )

        assertTrue(subscription.sid.startsWith("uuid:"))
        assertTrue(manager.unsubscribe(subscription.sid))
        assertFalse(manager.unsubscribe(subscription.sid))
    }

    @Test
    fun `a callback outside the private subnet is refused`() {
        val failure = runCatching {
            manager.subscribe(
                callbackHeader = "<http://169.254.169.254/latest>",
                servicePath = "/upnp/event/AVTransport"
            )
        }.exceptionOrNull()

        assertNotNull("the subscription must be refused", failure)
        assertEquals(0, manager.activeSubscriptionCount())
    }

    @Test
    fun `a callback with a forbidden scheme is refused`() {
        val failure = runCatching {
            manager.subscribe(
                callbackHeader = "<https://$LOCAL_HOST:1400/event>",
                servicePath = "/upnp/event/AVTransport"
            )
        }.exceptionOrNull()

        assertNotNull("the subscription must be refused", failure)
    }

    @Test
    fun `the subscription timeout is clamped into the supported range`() {
        val tooShort = manager.subscribe(
            callbackHeader = "<http://$LOCAL_HOST:1400/event>",
            servicePath = "/upnp/event/AVTransport",
            requestedTimeoutSeconds = 5
        )
        val tooLong = manager.subscribe(
            callbackHeader = "<http://$LOCAL_HOST:1400/event>",
            servicePath = "/upnp/event/AVTransport",
            requestedTimeoutSeconds = 99_999
        )

        assertEquals(GenaEventManager.MIN_TIMEOUT_SECONDS, tooShort.timeoutSeconds)
        assertEquals(GenaEventManager.MAX_TIMEOUT_SECONDS, tooLong.timeoutSeconds)
    }

    @Test
    fun `a LastChange event reaches the control point`() {
        val received = CountDownLatch(1)
        var request = ""
        ServerSocket(0).use { server ->
            val thread = Thread {
                runCatching {
                    server.soTimeout = 5_000
                    val client = server.accept()
                    client.use {
                        request = readHttpRequest(it.getInputStream())
                        it.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)
                        )
                        it.getOutputStream().flush()
                    }
                }
                received.countDown()
            }
            thread.isDaemon = true
            thread.start()

            manager.subscribe(
                callbackHeader = "<http://$LOCAL_HOST:${server.localPort}/event>",
                servicePath = "/upnp/event/AVTransport"
            )
            manager.publish(
                servicePath = "/upnp/event/AVTransport",
                lastChange = GenaLastChange.avTransport("TransportState" to "PLAYING")
            )

            assertTrue("the control point did not receive a NOTIFY", received.await(10, TimeUnit.SECONDS))
            // Java's HTTP client normalises the case of the header names.
            assertTrue("the NOTIFY is missing the UPnP headers", request.contains("upnp:propchange"))
            assertTrue("the NOTIFY is missing the event header", request.contains("upnp:event"))
            assertTrue("the NOTIFY is missing the SID header", request.contains("SID: uuid:"))
            // The nested UPnP format is escaped inside the property set.
            assertTrue("the NOTIFY is missing the LastChange", request.contains("&lt;EventValue"))
            assertTrue("the NOTIFY is missing the transport state", request.contains("TransportState"))
        }
    }

    @Test
    fun `stop drops every subscription`() {
        manager.subscribe(
            callbackHeader = "<http://$LOCAL_HOST:1400/event>",
            servicePath = "/upnp/event/AVTransport"
        )
        assertEquals(1, manager.activeSubscriptionCount())

        manager.stop()

        assertEquals(0, manager.activeSubscriptionCount())
    }

    @Test
    fun `a subscription for an unknown service path is refused`() {
        val failure = runCatching {
            manager.subscribe(
                callbackHeader = "<http://$LOCAL_HOST:1400/event>",
                servicePath = "/not/an/event/path"
            )
        }.exceptionOrNull()

        assertNotNull("the subscription must be refused", failure)
    }

    @Test
    fun `the callback guard is exposed for the policy tests`() {
        assertTrue(manager.isAllowedCallback(URI("http://$LOCAL_HOST:1400/event")))
        assertFalse(manager.isAllowedCallback(URI("http://127.0.0.1:1400/event")))
    }

    companion object {
        /**
         * A private address of the machine the test runs on.
         *
         * WHY: the callback guard only accepts private addresses of the local subnet,
         * and loopback is rejected on purpose. The event test therefore needs a real
         * private address of the test machine.
         */
        private val LOCAL_HOST: String = DlnaTestNetwork.privateAddress()

        /**
         * Reads a complete HTTP request (header block plus body) from [input].
         *
         * WHY byte by byte: a buffered reader would swallow the body into its buffer
         * and the body would be lost for the caller.
         */
        private fun readHttpRequest(input: java.io.InputStream): String {
            val builder = StringBuilder()
            var current = input.read()
            while (current != -1) {
                builder.append(current.toChar())
                if (builder.endsWith("\r\n\r\n")) break
                current = input.read()
            }
            val contentLength = builder.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
                ?: 0
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = input.read(body, read, contentLength - read)
                if (count < 0) break
                read += count
            }
            return builder.toString() + String(body, 0, read, Charsets.UTF_8)
        }
    }
}
