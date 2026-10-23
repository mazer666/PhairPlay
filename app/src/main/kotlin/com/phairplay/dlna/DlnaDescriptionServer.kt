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
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1) ?: "/"
            val headers = readHeaders(reader)
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

    private fun readHeaders(reader: java.io.BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var totalBytes = 0
        while (true) {
            val line = reader.readLine() ?: break
            totalBytes += line.length
            require(totalBytes <= MAX_HEADER_BYTES) { "HTTP headers too large" }
            if (line.isEmpty()) break
            val key = line.substringBefore(':', missingDelimiterValue = "").lowercase()
            val value = line.substringAfter(':', missingDelimiterValue = "").trim()
            if (key.isNotEmpty()) headers[key] = value
        }
        return headers
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
        private const val MAX_BODY_BYTES = 64 * 1024
    }
}
