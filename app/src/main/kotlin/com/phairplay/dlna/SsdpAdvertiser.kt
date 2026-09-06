package com.phairplay.dlna

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketException
import kotlin.random.Random

/**
 * SsdpAdvertiser — announces the renderer over SSDP and answers M-SEARCH queries.
 *
 * WHY: UPnP discovery is how Windows, BubbleUPnP and VLC find the TV. Alive NOTIFYs make it appear
 * immediately; M-SEARCH replies make it appear when a control point starts later; byebye removes it.
 *
 * HOW: `SsdpAdvertiser(scope, udn) { locationUrl }.start()` / `stop()`. The caller must hold a
 * `WifiManager.MulticastLock` (done in [DlnaReceiver]) or Wi-Fi drops the multicast traffic.
 */
class SsdpAdvertiser(
    private val scope: CoroutineScope,
    private val udn: String,
    private val location: () -> String
) {
    private var socket: MulticastSocket? = null
    private val group: InetAddress = InetAddress.getByName(DlnaConstants.SSDP_ADDRESS)
    private val jobs = mutableListOf<Job>()

    fun start() {
        val multicast = MulticastSocket(DlnaConstants.SSDP_PORT).apply {
            reuseAddress = true
            @Suppress("DEPRECATION")
            joinGroup(group)
        }
        socket = multicast
        jobs += scope.launch { receiveLoop(multicast) }
        jobs += scope.launch {
            while (isActive) {
                sendAlive(multicast)
                delay(DlnaConstants.ALIVE_INTERVAL_MS)
            }
        }
        Logger.i("SSDP advertising $udn at ${location()}")
    }

    fun stop() {
        socket?.let { runCatching { sendByeBye(it) }.onFailure { e -> Logger.w("SSDP byebye failed: ${e.message}") } }
        jobs.forEach { it.cancel() }
        jobs.clear()
        socket?.let { s ->
            @Suppress("DEPRECATION")
            runCatching { s.leaveGroup(group) }
            s.close()
        }
        socket = null
        Logger.i("SSDP advertising stopped")
    }

    private suspend fun receiveLoop(multicast: MulticastSocket) {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                multicast.receive(packet)
            } catch (e: SocketException) {
                break   // socket closed by stop()
            }
            val request = SsdpMessages.parse(String(packet.data, 0, packet.length, Charsets.ISO_8859_1)) ?: continue
            if (!SsdpMessages.isSearch(request)) continue
            val targets = SsdpMessages.matchingTargets(request.header("ST"), udn)
            if (targets.isEmpty()) continue
            // UPnP asks responders to spread replies over 0..MX seconds so a search does not get a burst.
            val delayMs = Random.nextLong(0L, SsdpMessages.mxSeconds(request) * MS_PER_SECOND)
            val replyTo = packet.address
            val replyPort = packet.port
            scope.launch {
                delay(delayMs)
                for ((st, usn) in targets) {
                    send(multicast, SsdpMessages.searchResponse(st, usn, location()), replyTo, replyPort)
                }
            }
        }
    }

    private fun sendAlive(multicast: MulticastSocket) {
        // Sent twice per UPnP DA recommendation (UDP may drop one).
        repeat(ALIVE_REPEATS) {
            for ((nt, usn) in SsdpMessages.targets(udn)) {
                send(multicast, SsdpMessages.notifyAlive(nt, usn, location()), group, DlnaConstants.SSDP_PORT)
            }
        }
    }

    private fun sendByeBye(multicast: MulticastSocket) {
        for ((nt, usn) in SsdpMessages.targets(udn)) {
            send(multicast, SsdpMessages.notifyByeBye(nt, usn), group, DlnaConstants.SSDP_PORT)
        }
    }

    private fun send(multicast: MulticastSocket, text: String, address: InetAddress, port: Int) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        try {
            multicast.send(DatagramPacket(bytes, bytes.size, address, port))
        } catch (e: Exception) {
            Logger.w("SSDP send to $address:$port failed: ${e.message}")
        }
    }

    companion object {
        private const val RECEIVE_BUFFER_BYTES = 2048
        private const val ALIVE_REPEATS = 2
        private const val MS_PER_SECOND = 1000L
    }
}
