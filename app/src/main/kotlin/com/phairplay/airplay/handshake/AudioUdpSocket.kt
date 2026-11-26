package com.phairplay.airplay.handshake

import com.phairplay.util.Logger
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * AudioUdpSocket — the UDP socket the AirPlay audio RTP arrives on, in **both** address families.
 *
 * WHAT: Opens the socket pair that receives the mirror audio (type 96) and the realtime-audio
 * control channel, and exposes the one port the SETUP answer has to name.
 *
 * WHY both families: the sender delivers the audio to the address it used for the RTSP control
 * connection. An iPhone uses the IPv4 address of the television, a Mac can use the IPv6
 * link-local one. A socket bound to the IPv4 wildcard never sees IPv6 traffic, and a socket
 * bound to the IPv6 wildcard never sees IPv4 traffic on the Android TV network stacks that do
 * not dual-stack it — the same failure the RTSP server hit, which is why every other socket of
 * this project binds the IPv4 wildcard explicitly (see
 * [com.phairplay.airplay.RtspServer.bindRtspSocket]). An audio socket that only listened on the
 * IPv6 wildcard therefore received nothing from an iPhone: the mirror ran, the picture came,
 * the audio never arrived.
 *
 * HOW: the IPv6 socket is opened first and carries the port. The IPv4 socket is then opened on
 * **that same port**:
 * - if it succeeds, the kernel keeps the two families apart and both sockets are read,
 * - if it fails because the port is taken, the IPv6 socket is dual-stack and already receives
 *   the IPv4 traffic — the IPv4 socket is not needed.
 *
 * If the IPv6 socket cannot be opened at all (a device without IPv6), the socket falls back to
 * the IPv4 wildcard alone.
 *
 * Example:
 *   val socket = AudioUdpSocket()
 *   socket.port                 // the port to announce in the SETUP answer
 *   socket.sockets.forEach { s -> /* one receive loop per socket */ }
 */
internal class AudioUdpSocket {

    /** The sockets to read from — one per address family that is in use. */
    val sockets: List<DatagramSocket>

    /** The UDP port the sender has to address. */
    val port: Int

    init {
        val ipv6 = open(IPV6_WILDCARD, 0)
        if (ipv6 != null) {
            port = ipv6.localPort
            // WHY the same port: the SETUP answer names exactly one port. A second socket on a
            // second port would never be addressed by the sender.
            val ipv4 = open(IPV4_WILDCARD, port)
            if (ipv4 != null) {
                sockets = listOf(ipv6, ipv4)
                Logger.i("Audio UDP socket: port $port on the IPv6 and the IPv4 wildcard")
            } else {
                sockets = listOf(ipv6)
                Logger.i(
                    "Audio UDP socket: port $port on the IPv6 wildcard only — it is dual-stack " +
                        "and receives the IPv4 traffic as well"
                )
            }
        } else {
            val ipv4 = open(IPV4_WILDCARD, 0)
                ?: throw IOException("No UDP socket could be opened for the AirPlay audio")
            port = ipv4.localPort
            sockets = listOf(ipv4)
            Logger.i("Audio UDP socket: port $port on the IPv4 wildcard (no IPv6 on this device)")
        }
    }

    /** Closes every socket of the pair. */
    fun close() {
        sockets.forEach { socket ->
            runCatching { socket.close() }
                .onFailure { Logger.w("Could not close the audio UDP socket: ${it.message}") }
        }
    }

    /**
     * Opens one UDP socket on the given wildcard address and port.
     *
     * @param wildcard the address to bind to — the IPv4 or the IPv6 wildcard.
     * @param port the port to bind to, 0 for an OS-assigned one.
     * @return the socket, or null when the bind failed.
     */
    private fun open(wildcard: String, port: Int): DatagramSocket? = try {
        DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(wildcard), port))
        }
    } catch (e: Exception) {
        Logger.w("Audio UDP socket on $wildcard:$port could not be opened: ${e.message}")
        null
    }

    companion object {
        /** The IPv4 wildcard — the address family an iPhone uses. */
        private const val IPV4_WILDCARD = "0.0.0.0"

        /** The IPv6 wildcard — the address family a Mac can use. */
        private const val IPV6_WILDCARD = "::"
    }
}
