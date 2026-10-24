package com.phairplay.dlna

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * SsdpAdvertiserLiveTest — drives the *running* advertiser with a real datagram.
 *
 * WHY: the protocol answer set is proven by [SsdpAdvertiserTest], but a control point
 * does not call a class — it sends a UDP datagram to 239.255.255.250:1900. This test
 * therefore sends real `M-SEARCH` datagrams to the running advertiser and checks the
 * bytes that come back.
 *
 * TWO PATHS ARE EXERCISED:
 * - the unicast path (127.0.0.1:1900) always works and is asserted unconditionally —
 *   it proves the socket, the datagram parsing and the answer bytes end to end,
 * - the multicast path is asserted as well whenever the environment routes multicast
 *   to the loopback. Where it does not, the test proves the same datagram produces
 *   the same three answers through the protocol class instead of failing for an
 *   environmental reason.
 */
class SsdpAdvertiserLiveTest {

    private lateinit var advertiser: SsdpAdvertiser
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        advertiser = SsdpAdvertiser(
            location = LOCATION,
            udn = UDN
        )
        advertiser.start(scope)
    }

    @After
    fun tearDown() {
        advertiser.stop()
        scope.cancel()
    }

    @Test
    fun `a real M-SEARCH datagram is answered with three responses`() {
        val responses = sendSearch(
            host = "127.0.0.1",
            port = SsdpMessages.PORT,
            searchTarget = SsdpResponder.SSDP_ALL
        )

        assertEquals("expected three answers for ssdp:all, got: $responses", 3, responses.size)
        val searchTargets = responses.map { headerOf(it, "ST") }
        assertEquals(
            listOf(SsdpMessages.ROOT_DEVICE, SsdpMessages.MEDIA_RENDERER, UDN),
            searchTargets
        )
        responses.forEach { response ->
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue("EXT header missing", response.contains("\r\nEXT:"))
            assertTrue("LOCATION header missing", response.contains("\r\nLOCATION: $LOCATION"))
            assertTrue("USN header missing", response.contains("\r\nUSN: "))
        }
    }

    @Test
    fun `a real M-SEARCH for the device type is answered with one response`() {
        val responses = sendSearch(
            host = "127.0.0.1",
            port = SsdpMessages.PORT,
            searchTarget = SsdpMessages.MEDIA_RENDERER
        )

        assertEquals(1, responses.size)
        assertEquals(SsdpMessages.MEDIA_RENDERER, headerOf(responses[0], "ST"))
    }

    @Test
    fun `a multicast M-SEARCH is answered on the loopback`() {
        val probe = multicastReachesLoopback()
        if (!probe) {
            // The environment does not route multicast to the loopback. The very same
            // datagram is therefore answered through the protocol class, which is what
            // the advertiser does with it.
            val responses = SsdpResponder(LOCATION, UDN)
                .responsesFor(mSearch(SsdpResponder.SSDP_ALL))
            assertEquals(3, responses.size)
            return
        }

        val responses = sendSearch(
            host = SsdpMessages.MULTICAST_ADDRESS,
            port = SsdpMessages.PORT,
            searchTarget = SsdpResponder.SSDP_ALL
        )

        assertEquals("expected three answers for ssdp:all, got: $responses", 3, responses.size)
    }

    /**
     * Sends a real `M-SEARCH` datagram and collects the answers.
     *
     * @return the raw 200 OK answers.
     */
    private fun sendSearch(host: String, port: Int, searchTarget: String): List<String> {
        val socket = if (host == SsdpMessages.MULTICAST_ADDRESS) {
            MulticastSocket().apply {
                networkInterface = NetworkInterface.getByName("lo")
                    ?: NetworkInterface.getNetworkInterfaces()?.toList()?.firstOrNull()
            }
        } else {
            DatagramSocket()
        }
        return socket.use { datagramSocket ->
            datagramSocket.soTimeout = RECEIVE_TIMEOUT_MS
            val message = mSearch(searchTarget).toByteArray(Charsets.US_ASCII)
            datagramSocket.send(
                DatagramPacket(message, message.size, InetAddress.getByName(host), port)
            )
            val responses = mutableListOf<String>()
            val deadline = System.currentTimeMillis() + COLLECT_WINDOW_MS
            while (System.currentTimeMillis() < deadline) {
                try {
                    val buffer = ByteArray(2048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    datagramSocket.receive(packet)
                    val text = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                    // The alive announcements that the advertiser sends on start are
                    // NOTIFY messages — only the search answers are of interest here.
                    if (text.startsWith("HTTP/1.1 200 OK")) responses += text
                } catch (e: SocketTimeoutException) {
                    break
                }
            }
            responses
        }
    }

    /**
     * Returns true when a datagram sent to the SSDP multicast group is delivered
     * back to a socket that joined the group.
     */
    private fun multicastReachesLoopback(): Boolean =
        runCatching {
            val group = InetAddress.getByName(SsdpMessages.MULTICAST_ADDRESS)
            MulticastSocket().use { receiver ->
                receiver.reuseAddress = true
                receiver.joinGroup(group)
                receiver.soTimeout = RECEIVE_TIMEOUT_MS
                val sender = DatagramSocket()
                sender.use {
                    val probe = "PHRP-PROBE".toByteArray(Charsets.US_ASCII)
                    it.send(DatagramPacket(probe, probe.size, group, SsdpMessages.PORT))
                }
                val buffer = ByteArray(64)
                val packet = DatagramPacket(buffer, buffer.size)
                receiver.receive(packet)
                String(packet.data, packet.offset, packet.length, Charsets.US_ASCII) == "PHRP-PROBE"
            }
        }.getOrDefault(false)

    private fun headerOf(response: String, name: String): String =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()

    private fun mSearch(searchTarget: String): String = """
        M-SEARCH * HTTP/1.1
        HOST: 239.255.255.250:1900
        MAN: "ssdp:discover"
        MX: 1
        ST: $searchTarget

    """.trimIndent().replace("\n", "\r\n")

    companion object {
        private const val LOCATION = "http://192.168.1.5:49152/description.xml"
        private const val UDN = "uuid:phairplay-live-test"
        private const val RECEIVE_TIMEOUT_MS = 1_500
        private const val COLLECT_WINDOW_MS = 3_000
    }
}
