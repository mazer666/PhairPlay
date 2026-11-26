package com.phairplay.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress

/**
 * AudioUdpSocketTest — proves that the audio socket receives on **both** address families.
 *
 * WHY: the sender delivers the AirPlay audio to the address it used for the RTSP control
 * connection — IPv4 from an iPhone, the IPv6 link-local address from a Mac. The socket used
 * to listen on the IPv6 wildcard only, so on the Android TV network stacks that do not
 * dual-stack it the iPhone's audio never arrived: the mirror ran, the picture came, the
 * television stayed silent.
 *
 * The test sends a datagram over each family to the one port the SETUP answer names and
 * expects it to arrive. It runs on the host JVM — no television and no sender needed.
 */
class AudioUdpSocketTest {

    @Test
    fun `every socket of the pair announces the same port`() {
        val socket = AudioUdpSocket()
        try {
            assertTrue("the announced port must not be 0", socket.port > 0)
            assertTrue("at least one socket has to be open", socket.sockets.isNotEmpty())
            socket.sockets.forEach { family ->
                assertEquals(
                    "a socket of the pair announces another port than the SETUP answer",
                    socket.port,
                    family.localPort
                )
            }
        } finally {
            socket.close()
        }
    }

    @Test
    fun `a datagram over IPv4 arrives on the announced port`() {
        val socket = AudioUdpSocket()
        try {
            send("127.0.0.1", socket.port)
            assertNotNull(
                "no datagram arrived over IPv4 — an iPhone sends its audio that way",
                receive(socket)
            )
        } finally {
            socket.close()
        }
    }

    @Test
    fun `a datagram over IPv6 arrives on the announced port`() {
        val socket = AudioUdpSocket()
        try {
            assumeTrue(
                "this host has no IPv6 socket — the IPv6 case cannot be measured here",
                socket.sockets.any { it.localAddress is Inet6Address }
            )
            send("::1", socket.port)
            assertNotNull(
                "no datagram arrived over IPv6 — a Mac can send its audio that way",
                receive(socket)
            )
        } finally {
            socket.close()
        }
    }

    @Test
    fun `a closed socket releases the port`() {
        val socket = AudioUdpSocket()
        val port = socket.port
        socket.close()

        // The port has to be free again — the audio stream is restarted when the sender adds
        // or removes the audio of a running mirror, and the new socket needs the port.
        val rebound = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
            }
        } catch (e: Exception) {
            null
        }
        assertNotNull("the port $port was not released", rebound)
        rebound?.close()
    }

    /** Sends a short datagram to the given address and port. */
    private fun send(address: String, port: Int) {
        DatagramSocket().use { sender ->
            val payload = "audio".toByteArray()
            sender.send(DatagramPacket(payload, payload.size, InetAddress.getByName(address), port))
        }
    }

    /**
     * Waits for a datagram on any socket of the pair.
     *
     * @return the payload, or null when nothing arrived within the timeout.
     */
    private fun receive(socket: AudioUdpSocket): ByteArray? {
        val deadline = System.currentTimeMillis() + RECEIVE_TIMEOUT_MS
        val buffer = ByteArray(64)
        while (System.currentTimeMillis() < deadline) {
            for (family in socket.sockets) {
                family.soTimeout = 100
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    family.receive(packet)
                    return packet.data.copyOf(packet.length)
                } catch (_: java.net.SocketTimeoutException) {
                    // try the next socket / the next round
                } catch (_: Exception) {
                    return null
                }
            }
        }
        return null
    }

    companion object {
        /** How long the test waits for a datagram. */
        private const val RECEIVE_TIMEOUT_MS = 3_000L
    }
}
