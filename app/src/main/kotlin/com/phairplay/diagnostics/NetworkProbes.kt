package com.phairplay.diagnostics

import com.phairplay.dlna.SsdpMessages
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException

/**
 * SsdpProbeResult — what the SSDP probe measured on the device.
 *
 * @param joined         True when the multicast group could be joined.
 * @param answered       True when the renderer answered the search of the device.
 * @param descriptionPort The port of the description server named in the answer, or null.
 * @param failure        The reason the probe failed, or null when it did not fail.
 *
 * Example:
 *   val probe = SsdpProbe.probe(timeoutMillis = 2000)
 *   probe.descriptionPort   // 49152
 */
internal data class SsdpProbeResult(
    val joined: Boolean,
    val answered: Boolean,
    val descriptionPort: Int?,
    val failure: String?
)

/**
 * SsdpProbe — asks the network whether this device sees itself as a renderer.
 *
 * WHY the device searches for itself: a UPnP control point finds the renderer through
 * an `M-SEARCH` to the multicast group. The very same search from the device itself
 * proves the whole chain at once — the multicast group can be joined, the socket
 * works, the renderer is advertising and the answer names the description server.
 * A second socket is opened for the probe instead of reusing the socket of the
 * advertiser, because the production classes must not be changed for a screen that
 * only reports.
 *
 * HOW: the socket binds an ephemeral port (the port 1900 belongs to the advertiser),
 * joins `239.255.255.250` and sends one `M-SEARCH`. The renderer answers with three
 * datagrams (root device, device type, UDN); every answer carries the same
 * `LOCATION` header with the address of the description server.
 *
 * Example:
 *   val result = SsdpProbe.probe(timeoutMillis = 2000)
 */
internal object SsdpProbe {

    /** The largest datagram that is read — an SSDP answer is far below this. */
    private const val MAX_DATAGRAM_BYTES = 2048

    /**
     * Joins the SSDP multicast group and searches for this renderer.
     *
     * @param timeoutMillis how long the probe waits for an answer.
     * @return the measured result; a failed probe never throws.
     */
    fun probe(timeoutMillis: Int): SsdpProbeResult {
        val group = runCatching { InetAddress.getByName(SsdpMessages.MULTICAST_ADDRESS) }
            .getOrNull()
            ?: return SsdpProbeResult(
                joined = false,
                answered = false,
                descriptionPort = null,
                failure = "the multicast group cannot be resolved"
            )

        val socket = MulticastSocket(null as SocketAddress?)
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = timeoutMillis
            socket.joinGroup(group)
            sendSearch(socket, group)
            val answer = awaitAnswer(socket, timeoutMillis)
            if (answer == null) {
                SsdpProbeResult(
                    joined = true,
                    answered = false,
                    descriptionPort = null,
                    failure = null
                )
            } else {
                SsdpProbeResult(
                    joined = true,
                    answered = true,
                    descriptionPort = portOf(locationOf(answer)),
                    failure = null
                )
            }
        } catch (e: Exception) {
            SsdpProbeResult(
                joined = false,
                answered = false,
                descriptionPort = null,
                failure = e.message ?: e.javaClass.simpleName
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Sends the `M-SEARCH` that asks for every device of the network. */
    private fun sendSearch(socket: MulticastSocket, group: InetAddress) {
        val request = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: ${SsdpMessages.MULTICAST_ADDRESS}:${SsdpMessages.PORT}\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 1\r\n")
            append("ST: ssdp:all\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.send(DatagramPacket(request, request.size, group, SsdpMessages.PORT))
    }

    /**
     * Waits for the answer of the renderer.
     *
     * WHY the loop: the multicast loopback delivers the search of the device to its
     * own socket as well. Every datagram that is not a `200 OK` is ignored until the
     * answer of the renderer arrives.
     */
    private fun awaitAnswer(socket: MulticastSocket, timeoutMillis: Int): String? {
        val buffer = ByteArray(MAX_DATAGRAM_BYTES)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val packet = DatagramPacket(buffer, buffer.size)
            val text = try {
                socket.receive(packet)
                String(buffer, 0, packet.length, Charsets.US_ASCII)
            } catch (e: SocketTimeoutException) {
                return null
            }
            if (text.startsWith("HTTP/1.1 200")) return text
        }
        return null
    }

    /** Reads the `LOCATION` header of an answer. */
    private fun locationOf(answer: String): String? =
        answer.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Reads the port out of a `LOCATION` URL such as `http://192.168.1.42:49152/description.xml`. */
    private fun portOf(location: String?): Int? {
        if (location == null) return null
        val authority = location.substringAfter("://", location).substringBefore('/')
        return authority.substringAfter(':', "").toIntOrNull()
    }
}

/**
 * HttpProbeResult — the answer of the local description server.
 *
 * @param connected True when the port answered at all.
 * @param statusCode The HTTP status code, 0 when nothing answered.
 * @param bodyBytes The number of bytes of the answer body.
 *
 * Example:
 *   val answer = HttpProbe.fetchDescription(port = 49152, timeoutMillis = 2000)
 */
internal data class HttpProbeResult(
    val connected: Boolean,
    val statusCode: Int,
    val bodyBytes: Int
)

/**
 * HttpProbe — asks the local description server for its device description.
 *
 * WHY a raw socket instead of `HttpURLConnection`: the request goes to the device
 * itself over cleartext HTTP. The platform's cleartext policy is meant for traffic
 * that leaves the device, and a request that never leaves the loopback interface
 * must not depend on it. A raw socket also keeps this package free of Android.
 *
 * HOW: one `GET /description.xml` with the header `Connection: close`. The answer is
 * read with a hard limit, so a broken server cannot make the probe read forever.
 *
 * Example:
 *   val answer = HttpProbe.fetchDescription(port = 49152, timeoutMillis = 2000)
 */
internal object HttpProbe {

    /** The largest answer that is read — the device description is far below this. */
    private const val MAX_RESPONSE_BYTES = 128 * 1024

    /**
     * Fetches the device description from the description server.
     *
     * @param port the port of the description server.
     * @param timeoutMillis how long the probe waits for the answer.
     * @return the measured answer; a failed request never throws.
     */
    fun fetchDescription(port: Int, timeoutMillis: Int): HttpProbeResult {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(DiagnosticsLoopback.HOST, port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            val request = "GET /description.xml HTTP/1.1\r\n" +
                "Host: ${DiagnosticsLoopback.HOST}:$port\r\n" +
                "Connection: close\r\n" +
                "Accept: */*\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val response = readBounded(socket.getInputStream())
            HttpProbeResult(
                connected = true,
                statusCode = statusCodeOf(response),
                bodyBytes = bodyBytesOf(response)
            )
        } catch (e: IOException) {
            HttpProbeResult(connected = false, statusCode = 0, bodyBytes = 0)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Reads at most [MAX_RESPONSE_BYTES] bytes of the answer. */
    private fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (total < MAX_RESPONSE_BYTES) {
            val read = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    /** Reads the status code out of the status line of the answer. */
    private fun statusCodeOf(response: ByteArray): Int {
        val text = String(response, Charsets.ISO_8859_1)
        val statusLine = text.substringBefore("\r\n")
        return statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
    }

    /** Counts the bytes of the body — everything behind the empty line of the header. */
    private fun bodyBytesOf(response: ByteArray): Int {
        val text = String(response, Charsets.ISO_8859_1)
        val headerEnd = text.indexOf("\r\n\r\n")
        return if (headerEnd < 0) 0 else response.size - (headerEnd + 4)
    }
}

/**
 * TcpProbe — checks whether something listens on a port of the device.
 *
 * Example:
 *   TcpProbe.isListening(host = "127.0.0.1", port = 7000, timeoutMillis = 2000)
 */
internal object TcpProbe {

    /**
     * Opens a TCP connection to [host]:[port].
     *
     * WHY connecting is enough: a listening socket accepts the connection, a port
     * that nothing serves refuses it. The connection is closed again right away, so
     * no data is sent to the receiver.
     *
     * @param host the address to connect to.
     * @param port the port to connect to.
     * @param timeoutMillis how long the connection may take.
     * @return true when the connection was accepted.
     */
    fun isListening(host: String, port: Int, timeoutMillis: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            true
        }
    } catch (e: IOException) {
        false
    }
}

/**
 * DiagnosticsLoopback — the loopback address every local check of the self test uses.
 *
 * WHY: the checks must not depend on the network of the household. The loopback
 * interface is always there, and a check that fails on the loopback interface means
 * that the receiver itself is not running — not that the Wi-Fi is broken.
 *
 * Example:
 *   DiagnosticsLoopback.HOST   // "127.0.0.1"
 */
internal object DiagnosticsLoopback {
    /** The loopback address of the device. */
    const val HOST = "127.0.0.1"
}
