package com.phairplay.dlna

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 *   advertiser.readvertise()   // after the network came back
 */
class SsdpAdvertiser(
    location: String,
    private val udn: String
) {
    @Volatile private var running = false
    @Volatile private var socket: MulticastSocket? = null

    /**
     * The description URL that the announcements and the search answers carry.
     *
     * WHY it is not final: the URL contains the IP address of the device. When the
     * device comes back with a different address, the renderer has to advertise the
     * new URL — the old one points to an address that does not exist any more.
     */
    @Volatile private var currentLocation: String = location

    /** The answers to a search — rebuilt whenever the location changes. */
    @Volatile private var responder: SsdpResponder = SsdpResponder(location = location, udn = udn)

    /** True while the socket is a member of the SSDP multicast group. */
    @Volatile private var groupJoined = false

    private val groupAddress: InetAddress = InetAddress.getByName(SsdpMessages.MULTICAST_ADDRESS)

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
     * Points the advertiser at another description URL.
     *
     * WHY: the URL contains the IP address of the device. After a network change the
     * device may have a new address, and every announcement and every answer to a
     * search has to carry the new URL — the control point fetches the URL it was
     * given.
     *
     * @param location the new description URL.
     */
    fun updateLocation(location: String) {
        currentLocation = location
        responder = SsdpResponder(location = location, udn = udn)
    }

    /**
     * Repeats the alive announcements after the network came back.
     *
     * WHY the socket is checked first: the multicast socket belongs to the network
     * interface. When the interface goes down, the socket survives as an object but
     * nothing can be sent through it any more ("Network is unreachable"), and the
     * multicast group membership dies with the interface. The renderer would then
     * stay invisible — the control point sends its `M-SEARCH` into the group and
     * nobody answers it.
     */
    fun readvertise() {
        if (!running) {
            Logger.d("DLNA SSDP re-advertisement skipped — the advertiser is not running")
            return
        }
        if (!isSocketHealthy()) {
            Logger.i("SSDP socket did not survive the network change — opening a new one")
            replaceSocket()
        }
        if (!sendAlive()) {
            Logger.w("SSDP announcement failed — the socket is replaced and the announcement repeated")
            replaceSocket()
            sendAlive()
        }
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
            groupJoined = runCatching { joinGroup(groupAddress) }
                .fold(
                    onSuccess = { true },
                    onFailure = {
                        Logger.w("Could not join SSDP multicast group: ${it.message}")
                        false
                    }
                )
        }
    } catch (e: SocketException) {
        Logger.e("Could not open SSDP socket", e)
        null
    }

    /**
     * Closes the current socket and opens a new one.
     *
     * WHY the open logic is reused instead of written a second time: the socket needs
     * the same options as the one from [start] — address reuse, the timeout and the
     * group membership. A second implementation would drift away from the first.
     */
    private fun replaceSocket() {
        val oldSocket = socket
        // The socket is cleared first: the listener must wait for the new socket
        // instead of spinning on the closed one.
        socket = null
        runCatching { oldSocket?.leaveGroup(groupAddress) }
            .onFailure { Logger.d("SSDP multicast group was not joined — nothing to leave") }
        runCatching { oldSocket?.close() }
            .onFailure { Logger.w("Could not close SSDP socket: ${it.message}") }
        socket = openSocket()
    }

    /**
     * Checks whether the socket can still reach the network.
     *
     * WHY the multicast group is joined again: the membership belongs to the network
     * interface. When the interface went down, the membership died with it while the
     * socket object survived — the renderer then neither receives the searches of the
     * control points nor appears in their device list.
     *
     * A device that cannot join the group at all keeps its socket: it still answers
     * the unicast `M-SEARCH` requests a control point sends to the device address.
     */
    private fun isSocketHealthy(): Boolean {
        val activeSocket = socket ?: return false
        if (activeSocket.isClosed) return false
        if (!groupJoined) return true

        // Leaving may fail because the membership died together with the interface —
        // that is exactly the situation this method repairs, so it is not an error.
        runCatching { activeSocket.leaveGroup(groupAddress) }
            .onFailure { Logger.d("SSDP multicast group was not joined — re-joining it") }
        return runCatching { activeSocket.joinGroup(groupAddress) }
            .fold(
                onSuccess = { true },
                onFailure = {
                    Logger.w("Could not re-join SSDP multicast group: ${it.message}")
                    false
                }
            )
    }

    private suspend fun listenLoop(scope: CoroutineScope) {
        val buffer = ByteArray(MAX_PACKET_BYTES)
        while (running && scope.isActive) {
            // The socket is read for every round: a re-advertisement may replace it
            // when the interface came back with a new one.
            val activeSocket = socket
            if (activeSocket == null || activeSocket.isClosed) {
                // The socket is being replaced — wait for the new one instead of
                // spinning on a socket that cannot receive anything.
                delay(SOCKET_RETRY_DELAY_MS)
                continue
            }
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                activeSocket.receive(packet)
                val message = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                if (SsdpMessages.isRendererSearch(message, udn)) {
                    respond(packet.address, packet.port, message)
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Periodic wake-up so stop() can end the loop promptly.
            } catch (e: SocketException) {
                // The socket was closed or replaced — the next round uses the new one.
                if (running && socket === activeSocket) Logger.e("DLNA SSDP receive failed", e)
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
     *
     * @return true when every announcement was sent through a working socket.
     */
    private fun sendAlive(): Boolean {
        var allSent = true
        listOf(
            SsdpMessages.ROOT_DEVICE,
            SsdpMessages.MEDIA_RENDERER,
            udn,
            SsdpMessages.SAMSUNG_MULTI_SCREEN
        ).forEach { nt ->
            val sent = send(
                SsdpMessages.notifyAlive(currentLocation, udn, nt),
                groupAddress,
                SsdpMessages.PORT
            )
            if (!sent) allSent = false
        }
        return allSent
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

    /**
     * Sends one datagram.
     *
     * @return true when the datagram went out, false when the socket refused it.
     */
    private fun send(message: String, address: InetAddress, port: Int): Boolean {
        val activeSocket = socket ?: return false
        val bytes = message.toByteArray(Charsets.US_ASCII)
        return runCatching {
            activeSocket.send(DatagramPacket(bytes, bytes.size, address, port))
        }.fold(
            onSuccess = { true },
            onFailure = {
                if (running) Logger.w("DLNA SSDP send failed: ${it.message}")
                false
            }
        )
    }

    companion object {
        private const val MAX_PACKET_BYTES = 2048
        private const val SOCKET_TIMEOUT_MS = 1_000

        /** How long the listener waits for a socket that is being replaced. */
        private const val SOCKET_RETRY_DELAY_MS = 100L
    }
}
