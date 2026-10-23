package com.phairplay.dlna

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal bounded HTTP server for UPnP device and SCPD description documents.
 */
class DlnaDescriptionServer(
    private val friendlyName: String,
    private val udn: String,
    private val localHost: String,
    playbackController: DlnaPlaybackController = DlnaPlaybackController.NOOP
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val genaScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val genaEvents = GenaEventManager(localHost, genaScope)
    private val soapDispatcher = DlnaSoapDispatcher(
        playbackController = playbackController,
        onLastChange = { eventPath, lastChange -> genaEvents.publish(eventPath, lastChange) }
    )

    val port: Int
        get() = serverSocket?.localPort ?: 0

    val descriptionUrl: String
        get() = "http://$localHost:$port/description.xml"

    fun start(scope: CoroutineScope) {
        if (running) return
        serverSocket = ServerSocket(0)
        running = true
        scope.launch(Dispatchers.IO) { acceptLoop(this) }
        Logger.i("DLNA description server listening on $descriptionUrl")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
            .onFailure { Logger.w("Error closing DLNA description server: ${it.message}") }
        serverSocket = null
        genaScope.cancel()
    }

    private fun acceptLoop(scope: CoroutineScope) {
        val socket = serverSocket ?: return
        while (running && scope.isActive) {
            try {
                handleClient(socket.accept())
            } catch (e: Exception) {
                if (running) Logger.e("DLNA description server accept failed", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = READ_TIMEOUT_MS
            val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
            val requestLine = readBoundedLine(reader)
            if (requestLine.tooLong) {
                writeResponse(client.outputStream, 414, "URI Too Long", "text/plain", "Request line too long")
                drainQuietly(client)
                return
            }
            val line = requestLine.value ?: return
            val parts = line.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1) ?: "/"
            val headers = readHeaders(reader)
            if (headers == null) {
                writeResponse(client.outputStream, 431, "Request Header Fields Too Large", "text/plain", "Headers too large")
                drainQuietly(client)
                return
            }
            when (method) {
                "GET", "HEAD" -> {
                    val body = bodyFor(path)
                    if (body == null) {
                        writeResponse(client.outputStream, 404, "Not Found", "text/plain", "Not Found")
                    } else {
                        writeResponse(client.outputStream, 200, "OK", "text/xml; charset=\"utf-8\"", body)
                    }
                }
                "POST" -> {
                    val length = headers["content-length"]?.toIntOrNull()?.coerceAtMost(MAX_BODY_BYTES) ?: 0
                    val bodyChars = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val count = reader.read(bodyChars, read, length - read)
                        if (count < 0) break
                        read += count
                    }
                    val result = soapDispatcher.dispatch(
                        controlPath = path.substringBefore('?'),
                        soapAction = headers["soapaction"],
                        body = String(bodyChars, 0, read)
                    )
                    writeResponse(
                        client.outputStream,
                        result.statusCode,
                        result.statusMessage,
                        "text/xml; charset=\"utf-8\"",
                        result.body
                    )
                }
                "SUBSCRIBE" -> {
                    handleSubscribe(client.outputStream, path.substringBefore('?'), headers)
                }
                "UNSUBSCRIBE" -> {
                    handleUnsubscribe(client.outputStream, headers)
                }
                else -> writeResponse(client.outputStream, 405, "Method Not Allowed", "text/plain", "Method Not Allowed")
            }
        }
    }

    private fun handleSubscribe(output: OutputStream, path: String, headers: Map<String, String>) {
        runCatching {
            val subscription = genaEvents.subscribe(headers["callback"], path)
            writeHeaders(
                output,
                200,
                "OK",
                mapOf(
                    "SID" to subscription.sid,
                    "TIMEOUT" to "Second-${subscription.timeoutSeconds}"
                )
            )
        }.onFailure {
            writeResponse(output, 412, "Precondition Failed", "text/plain", it.message ?: "Invalid subscription")
        }
    }

    private fun handleUnsubscribe(output: OutputStream, headers: Map<String, String>) {
        if (genaEvents.unsubscribe(headers["sid"])) {
            writeHeaders(output, 200, "OK", emptyMap())
        } else {
            writeResponse(output, 412, "Precondition Failed", "text/plain", "Unknown SID")
        }
    }

    /**
     * Reads the request headers, bounded in length.
     *
     * @return the headers, or null when the header block exceeds its budget.
     */
    private fun readHeaders(reader: java.io.BufferedReader): Map<String, String>? {
        val headers = mutableMapOf<String, String>()
        var totalBytes = 0
        while (true) {
            val line = readBoundedLine(reader)
            if (line.tooLong) return null
            val value = line.value ?: break
            totalBytes += value.length
            if (totalBytes > MAX_HEADER_BYTES) return null
            if (value.isEmpty()) break
            val key = value.substringBefore(':', missingDelimiterValue = "").lowercase()
            val headerValue = value.substringAfter(':', missingDelimiterValue = "").trim()
            if (key.isNotEmpty()) headers[key] = headerValue
        }
        return headers
    }

    /**
     * Reads a single line with a hard length limit.
     *
     * WHY NOT `BufferedReader.readLine()`: that method materialises the whole line in
     * memory before the caller can look at it. A client on the LAN that sends a long
     * line without a newline would make the server allocate all of it — the byte budget
     * checked afterwards would come too late. Reading character by character (still
     * buffered underneath) stops at [MAX_LINE_BYTES] instead.
     *
     * @return the line without its terminator, or null at end of stream; [tooLong] marks
     *   a line that exceeded the limit.
     */
    private fun readBoundedLine(reader: java.io.BufferedReader): BoundedLine {
        val builder = StringBuilder(128)
        while (true) {
            val c = reader.read()
            if (c == -1) {
                return if (builder.isEmpty()) BoundedLine(null) else BoundedLine(builder.toString())
            }
            if (c == '\n'.code) {
                val line = builder.toString()
                return BoundedLine(if (line.endsWith("\r")) line.dropLast(1) else line)
            }
            builder.append(c.toChar())
            if (builder.length > MAX_LINE_BYTES) {
                return BoundedLine(null, tooLong = true)
            }
        }
    }

    /** Result of [readBoundedLine]. */
    private data class BoundedLine(val value: String?, val tooLong: Boolean = false)

    /**
     * Discards the request bytes the client has not sent yet.
     *
     * WHY: a client that is still sending when the server answers gets a TCP reset on
     * close — the unread data in the receive buffer makes the kernel abort the
     * connection — and the client never sees the error response it was sent. Draining a
     * bounded amount first lets the response arrive and the connection close cleanly.
     */
    private fun drainQuietly(socket: Socket) {
        runCatching {
            val buffer = ByteArray(4096)
            val input = socket.getInputStream()
            var total = 0
            while (total < MAX_DRAIN_BYTES) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
            }
        }
    }

    private fun bodyFor(path: String): String? =
        when (path.substringBefore('?')) {
            "/", "/description.xml" -> DlnaDescriptionBuilder.deviceDescription(
                friendlyName = friendlyName,
                udn = udn,
                presentationUrl = descriptionUrl
            )
            "/ConnectionManager.xml" -> DlnaDescriptionBuilder.serviceDescription(
                "ConnectionManager",
                listOf("GetProtocolInfo", "GetCurrentConnectionIDs")
            )
            "/AVTransport.xml" -> DlnaDescriptionBuilder.serviceDescription(
                "AVTransport",
                listOf("SetAVTransportURI", "Play", "Pause", "Stop", "GetTransportInfo", "GetPositionInfo")
            )
            "/RenderingControl.xml" -> DlnaDescriptionBuilder.serviceDescription(
                "RenderingControl",
                listOf("GetVolume", "SetVolume", "GetMute", "SetMute")
            )
            else -> null
        }

    private fun writeResponse(
        output: OutputStream,
        statusCode: Int,
        statusMessage: String,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = listOf(
            "HTTP/1.1 $statusCode $statusMessage",
            "Connection: close",
            "Content-Type: $contentType",
            "Content-Length: ${bytes.size}",
            "",
            ""
        ).joinToString("\r\n")
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun writeHeaders(
        output: OutputStream,
        statusCode: Int,
        statusMessage: String,
        headers: Map<String, String>
    ) {
        val baseHeaders = mutableMapOf(
            "Connection" to "close",
            "Content-Length" to "0"
        )
        baseHeaders.putAll(headers)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusMessage\r\n")
            baseHeaders.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    companion object {
        private const val READ_TIMEOUT_MS = 2_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        /**
         * Maximum length of a single request line or header line.
         *
         * The total header budget is checked only after a line has been read, so without
         * this per-line limit a single long line would be materialised in full first.
         */
        private const val MAX_LINE_BYTES = 8 * 1024
        /** Maximum number of bytes discarded from a rejected request before closing. */
        private const val MAX_DRAIN_BYTES = 256 * 1024
        private const val MAX_BODY_BYTES = 64 * 1024
    }
}
