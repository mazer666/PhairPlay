package com.phairplay.dlna

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * SSDP announcer/listener for the DLNA MediaRenderer discovery phase.
 */
class SsdpAdvertiser(
    private val location: String,
    private val udn: String
) {
    @Volatile private var running = false
    @Volatile private var socket: MulticastSocket? = null
    private val groupAddress: InetAddress = InetAddress.getByName(SsdpMessages.MULTICAST_ADDRESS)

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        socket = MulticastSocket(SsdpMessages.PORT).apply {
            reuseAddress = true
            soTimeout = SOCKET_TIMEOUT_MS
            joinGroup(groupAddress)
        }
        sendAlive()
        scope.launch(Dispatchers.IO) { listenLoop(this) }
        Logger.i("DLNA SSDP advertiser started")
    }

    fun stop() {
        running = false
        sendByebye()
        runCatching { socket?.leaveGroup(groupAddress) }
            .onFailure { Logger.w("Could not leave SSDP multicast group: ${it.message}") }
        runCatching { socket?.close() }
            .onFailure { Logger.w("Could not close SSDP socket: ${it.message}") }
        socket = null
    }

    private fun listenLoop(scope: CoroutineScope) {
        val buffer = ByteArray(MAX_PACKET_BYTES)
        val activeSocket = socket ?: return
        while (running && scope.isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                activeSocket.receive(packet)
                val message = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                if (SsdpMessages.isRendererSearch(message)) {
                    respond(packet.address, packet.port, requestedTarget(message))
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Periodic wake-up so stop() can end the loop promptly.
            } catch (e: Exception) {
                if (running) Logger.e("DLNA SSDP receive failed", e)
            }
        }
    }

    private fun respond(address: InetAddress, port: Int, searchTarget: String) {
        val body = SsdpMessages.searchResponse(location, udn, searchTarget)
        send(body, address, port)
    }

    private fun sendAlive() {
        listOf(SsdpMessages.ROOT_DEVICE, SsdpMessages.MEDIA_RENDERER, udn).forEach { nt ->
            send(SsdpMessages.notifyAlive(location, udn, nt), groupAddress, SsdpMessages.PORT)
        }
    }

    private fun sendByebye() {
        listOf(SsdpMessages.ROOT_DEVICE, SsdpMessages.MEDIA_RENDERER, udn).forEach { nt ->
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

    private fun requestedTarget(message: String): String {
        val requested = message.lineSequence()
            .firstOrNull { it.startsWith("ST:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?: SsdpMessages.MEDIA_RENDERER
        return if (requested.equals("ssdp:all", ignoreCase = true)) {
            SsdpMessages.MEDIA_RENDERER
        } else {
            requested
        }
    }

    companion object {
        private const val MAX_PACKET_BYTES = 2048
        private const val SOCKET_TIMEOUT_MS = 1_000
    }
}
