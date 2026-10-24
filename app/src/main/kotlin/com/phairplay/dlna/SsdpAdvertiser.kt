package com.phairplay.dlna

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketException

/**
 * SsdpAdvertiser — announces PhairPlay as a UPnP MediaRenderer and answers searches.
 *
 * WHY: the advertiser owns the network side of the discovery phase. The protocol
 * side (which answer belongs to which search target) lives in [SsdpResponder], so
 * the answer set can be tested without a socket — and the socket code stays small
 * enough to be obviously correct.
 *
 * Example:
 *   val advertiser = SsdpAdvertiser(location = "http://192.168.1.5:49152/description.xml", udn = "uuid:…")
 *   advertiser.start(scope)
 */
class SsdpAdvertiser(
    private val location: String,
    private val udn: String
) {
    @Volatile private var running = false
    @Volatile private var socket: MulticastSocket? = null
    private val groupAddress: InetAddress = InetAddress.getByName(SsdpMessages.MULTICAST_ADDRESS)
    private val responder = SsdpResponder(location = location, udn = udn)

    /** Starts the listener and announces the device. */
    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        socket = openSocket()
        sendAlive()
        scope.launch(Dispatchers.IO) { listenLoop(this) }
        Logger.i("DLNA SSDP advertiser started")
    }

    /** Stops the listener and announces the goodbye. */
    fun stop() {
        running = false
        sendByebye()
        runCatching { socket?.leaveGroup(groupAddress) }
            .onFailure { Logger.w("Could not leave SSDP multicast group: ${it.message}") }
        runCatching { socket?.close() }
            .onFailure { Logger.w("Could not close SSDP socket: ${it.message}") }
        socket = null
    }

    /**
     * Opens the SSDP socket.
     *
     * WHY the multicast group is optional: on some devices and virtual networks the
     * group cannot be joined. The socket still receives the unicast `M-SEARCH`
     * requests a control point sends to the device address, so the renderer stays
     * usable instead of disappearing completely.
     */
    private fun openSocket(): MulticastSocket? = try {
        MulticastSocket(SsdpMessages.PORT).apply {
            reuseAddress = true
            soTimeout = SOCKET_TIMEOUT_MS
            runCatching { joinGroup(groupAddress) }
                .onFailure { Logger.w("Could not join SSDP multicast group: ${it.message}") }
        }
    } catch (e: SocketException) {
        Logger.e("Could not open SSDP socket", e)
        null
    }

    private fun listenLoop(scope: CoroutineScope) {
        val buffer = ByteArray(MAX_PACKET_BYTES)
        val activeSocket = socket ?: return
        while (running && scope.isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                activeSocket.receive(packet)
                val message = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                if (SsdpMessages.isRendererSearch(message, udn)) {
                    respond(packet.address, packet.port, message)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Periodic wake-up so stop() can end the loop promptly.
            } catch (e: Exception) {
                if (running) Logger.e("DLNA SSDP receive failed", e)
            }
        }
    }

    /**
     * Sends the answer to a search.
     *
     * WHY one datagram per target: `ST: ssdp:all` has to be answered with three
     * separate responses (rootdevice, MediaRenderer, UDN) — a control point that
     * expects three answers and receives one treats the device as incomplete.
     */
    private fun respond(address: InetAddress, port: Int, message: String) {
        responder.responsesFor(message).forEach { response ->
            send(response, address, port)
        }
    }

    /**
     * Announces the device to the network.
     *
     * WHY the Samsung target is announced as well: a Samsung TV does not only send
     * an `M-SEARCH`, it also listens for the passive announcement of a receiver.
     * The announcement is cheap and does not change the answers of a search.
     */
    private fun sendAlive() {
        listOf(
            SsdpMessages.ROOT_DEVICE,
            SsdpMessages.MEDIA_RENDERER,
            udn,
            SsdpMessages.SAMSUNG_MULTI_SCREEN
        ).forEach { nt ->
            send(SsdpMessages.notifyAlive(location, udn, nt), groupAddress, SsdpMessages.PORT)
        }
    }

    private fun sendByebye() {
        listOf(
            SsdpMessages.ROOT_DEVICE,
            SsdpMessages.MEDIA_RENDERER,
            udn,
            SsdpMessages.SAMSUNG_MULTI_SCREEN
        ).forEach { nt ->
            send(SsdpMessages.notifyByebye(udn, nt), groupAddress, SsdpMessages.PORT)
        }
    }

    private fun send(message: String, address: InetAddress, port: Int) {
        val bytes = message.toByteArray(Charsets.US_ASCII)
        runCatching {
            socket?.send(DatagramPacket(bytes, bytes.size, address, port))
        }.onFailure {
            if (running) Logger.w("DLNA SSDP send failed: ${it.message}")
        }
    }

    companion object {
        private const val MAX_PACKET_BYTES = 2048
        private const val SOCKET_TIMEOUT_MS = 1_000
    }
}
