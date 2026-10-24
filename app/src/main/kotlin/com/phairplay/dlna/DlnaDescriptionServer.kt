package com.phairplay.dlna

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * DlnaDescriptionServer — the HTTP server that publishes the UPnP device description,
 * the service descriptions and the control endpoints.
 *
 * WHY: a UPnP control point fetches the `LOCATION` URL the SSDP answer carried, so
 * the renderer needs a small HTTP server. Because it listens on the local network
 * without authentication, it is built defensively:
 *
 * - the request size, the header size and the request duration are bounded
 *   (see [HttpRequestReader]),
 * - only a few connections are served at the same time; further clients get a 503
 *   instead of a thread,
 * - error answers contain a fixed text — no stack trace, no file path, no version.
 *
 * Example:
 *   val server = DlnaDescriptionServer("Living Room TV", "uuid:…", "192.168.1.5")
 *   server.start(scope)
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
    private val dialCatalog = DialAppCatalog()
    private val soapDispatcher = DlnaSoapDispatcher(
        playbackController = playbackController,
        onLastChange = { eventPath, lastChange -> genaEvents.publish(eventPath, lastChange) }
    )
    private val workers = ThreadPoolExecutor(
        2,
        MAX_WORKER_THREADS,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUED_CLIENTS)
    ) { runnable ->
        Thread(runnable, "dlna-http").apply { isDaemon = true }
    }

    /** The TCP port the server listens on. */
    val port: Int
        get() = serverSocket?.localPort ?: 0

    /** The URL a control point has to fetch to learn about this renderer. */
    val descriptionUrl: String
        get() = "http://$localHost:$port/description.xml"

    /**
     * The DIAL application URL a Samsung control point fetches after the discovery.
     *
     * WHY: the device description carries this URL in its `Application-Url` header.
     * The control point then reads the DIAL app list and the app info behind it
     * (see [DialAppCatalog]).
     */
    val applicationUrl: String
        get() = "http://$localHost:$port/dial"

    /** Starts the server. */
    fun start(scope: CoroutineScope) {        if (running) return
        serverSocket = ServerSocket(0)
        running = true
        scope.launch(Dispatchers.IO) { acceptLoop(this) }
        Logger.i("DLNA description server listening on $descriptionUrl")
    }

    /** Stops the server and drops every event subscription. */
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
            .onFailure { Logger.w("Error closing DLNA description server: ${it.message}") }
        serverSocket = null
        genaEvents.stop()
        workers.shutdownNow()
        genaScope.cancel()
    }

    /**
     * Applies a transport command that came from the TV remote.
     *
     * WHY: the remote control of the TV has to move the same transport state machine
     * the network control point uses — otherwise the control point keeps showing
     * "PLAYING" while the TV is paused.
     *
     * @param command one of the [DlnaRemoteCommand] values.
     */
    fun applyRemoteCommand(command: String) {
        soapDispatcher.onRemoteCommand(command)
    }

    private fun acceptLoop(scope: CoroutineScope) {
        val socket = serverSocket ?: return
        while (running && scope.isActive) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) Logger.e("DLNA description server accept failed", e)
                continue
            }
            try {
                workers.execute { handleClient(client) }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                // Too many clients — answer with a fixed text and close the socket.
                runCatching {
                    DlnaHttpResponses.write(client.outputStream, 503, "Service Unavailable", "text/plain", "Server busy")
                }
                runCatching { client.close() }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = READ_TIMEOUT_MS
            val deadline = System.currentTimeMillis() + REQUEST_DEADLINE_MS
            val request = HttpRequestReader(client, deadline).read()
            if (!request.isValid) {
                respondToFailure(client, request.failure)
                drainQuietly(client)
                return
            }
            when (request.method) {
                "GET", "HEAD" -> serveRead(client, request.path)
                "POST" -> serveControl(client, request)
                "SUBSCRIBE" -> handleSubscribe(client, request)
                "UNSUBSCRIBE" -> handleUnsubscribe(client, request)
                else -> DlnaHttpResponses.write(
                    client.outputStream,
                    405,
                    "Method Not Allowed",
                    "text/plain",
                    "Method Not Allowed"
                )
            }
        }
    }

    /** Answers a failed request with a status that says nothing about the internals. */
    private fun respondToFailure(client: Socket, failure: HttpFailure?) {
        when (failure) {
            HttpFailure.REQUEST_LINE_TOO_LONG -> DlnaHttpResponses.write(
                client.outputStream, 414, "URI Too Long", "text/plain", "Request line too long"
            )
            HttpFailure.HEADERS_TOO_LARGE -> DlnaHttpResponses.write(
                client.outputStream, 431, "Request Header Fields Too Large", "text/plain", "Headers too large"
            )
            HttpFailure.BODY_TOO_LARGE -> DlnaHttpResponses.write(
                client.outputStream, 413, "Payload Too Large", "text/plain", "Request body too large"
            )
            HttpFailure.TIMEOUT -> DlnaHttpResponses.write(
                client.outputStream, 408, "Request Timeout", "text/plain", "Request timeout"
            )
            else -> DlnaHttpResponses.write(
                client.outputStream, 400, "Bad Request", "text/plain", "Bad request"
            )
        }
    }

    /**
     * Serves a read request.
     *
     * WHY the DIAL documents come first: a Samsung control point asks for `/dial/`
     * and `/dial/<AppId>` before it asks for the device description. Every other
     * path belongs to the UPnP description.
     */
    private fun serveRead(client: Socket, path: String) {
        val cleanPath = path.substringBefore('?')
        val dialPath = DialAppCatalog.BASE_PATH
        if (cleanPath == dialPath || cleanPath == "$dialPath/") {
            DlnaHttpResponses.write(
                client.outputStream,
                200,
                "OK",
                DIAL_CONTENT_TYPE,
                dialCatalog.appsListXml()
            )
            return
        }
        if (cleanPath.startsWith("$dialPath/")) {
            serveDialApp(client, cleanPath.removePrefix("$dialPath/"))
            return
        }
        serveDescription(client, cleanPath)
    }

    /**
     * Serves the DIAL app info of one app.
     *
     * WHY an unknown id is answered with 404: the app id is the only value that
     * comes from the request. An id that is not the app of this renderer has no
     * document — and the answer says so instead of returning an empty one.
     */
    private fun serveDialApp(client: Socket, appId: String) {
        val body = dialCatalog.appInfoXml(appId)
        if (body == null) {
            DlnaHttpResponses.write(
                client.outputStream,
                404,
                "Not Found",
                "text/plain",
                "Not Found"
            )
        } else {
            DlnaHttpResponses.write(client.outputStream, 200, "OK", DIAL_CONTENT_TYPE, body)
        }
    }

    private fun serveDescription(client: Socket, path: String) {
        val body = bodyFor(path)
        if (body == null) {
            DlnaHttpResponses.write(client.outputStream, 404, "Not Found", "text/plain", "Not Found")
        } else {
            DlnaHttpResponses.write(
                client.outputStream,
                200,
                "OK",
                "text/xml; charset=\"utf-8\"",
                body,
                descriptionHeaders(path)
            )
        }
    }

    /**
     * Returns the headers of the device description.
     *
     * WHY the `Application-Url` header: a Samsung control point learns from it
     * where the DIAL documents are. Without the header the TV finds the renderer
     * but opens an empty app list.
     */
    private fun descriptionHeaders(path: String): Map<String, String> =
        if (path == "/" || path == "/description.xml") {
            mapOf("Application-Url" to applicationUrl)
        } else {
            emptyMap()
        }

    private fun serveControl(client: Socket, request: HttpRequest) {
        val result = soapDispatcher.dispatch(
            controlPath = request.path.substringBefore('?'),
            soapAction = request.headers["soapaction"],
            body = request.body
        )
        DlnaHttpResponses.write(
            client.outputStream,
            result.statusCode,
            result.statusMessage,
            "text/xml; charset=\"utf-8\"",
            result.body
        )
    }

    private fun handleSubscribe(client: Socket, request: HttpRequest) {
        val subscription = runCatching {
            genaEvents.subscribe(
                callbackHeader = request.headers["callback"],
                servicePath = request.path.substringBefore('?'),
                requestedTimeoutSeconds = parseTimeoutHeader(request.headers["timeout"])
            )
        }
        subscription.onSuccess { created ->
            DlnaHttpResponses.writeHeaders(
                client.outputStream,
                200,
                "OK",
                mapOf(
                    "SID" to created.sid,
                    "TIMEOUT" to "Second-${created.timeoutSeconds}",
                    // The UPnP eventing specification asks for the EXT header in the
                    // answer to a SUBSCRIBE — control points use it to recognise a
                    // device that speaks GENA.
                    "EXT" to ""
                )
            )
        }.onFailure {
            DlnaHttpResponses.write(
                client.outputStream,
                412,
                "Precondition Failed",
                "text/plain",
                "Invalid subscription"
            )
        }
    }

    private fun handleUnsubscribe(client: Socket, request: HttpRequest) {
        if (genaEvents.unsubscribe(request.headers["sid"])) {
            DlnaHttpResponses.writeHeaders(client.outputStream, 200, "OK", emptyMap())
        } else {
            DlnaHttpResponses.write(
                client.outputStream,
                412,
                "Precondition Failed",
                "text/plain",
                "Unknown SID"
            )
        }
    }

    /**
     * Parses the `TIMEOUT` header of a SUBSCRIBE request.
     *
     * @return the requested timeout in seconds, or null when the header is absent.
     */
    private fun parseTimeoutHeader(value: String?): Int? {
        val raw = value?.trim()?.removePrefix("Second-") ?: return null
        if (raw.equals("infinite", ignoreCase = true)) return GenaEventManager.MAX_TIMEOUT_SECONDS
        return raw.toIntOrNull()
    }

    /**
     * Returns the document for [path], or null when the path is unknown.
     */
    private fun bodyFor(path: String): String? =
        when (path.substringBefore('?')) {
            "/", "/description.xml" -> DlnaDescriptionBuilder.deviceDescription(
                friendlyName = friendlyName,
                udn = udn,
                presentationUrl = descriptionUrl
            )
            "/ConnectionManager.xml" -> DlnaDescriptionBuilder.serviceDescription("ConnectionManager")
            "/AVTransport.xml" -> DlnaDescriptionBuilder.serviceDescription("AVTransport")
            "/RenderingControl.xml" -> DlnaDescriptionBuilder.serviceDescription("RenderingControl")
            else -> null
        }

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

    companion object {
        /** The content type of the DIAL documents. */
        private const val DIAL_CONTENT_TYPE = "text/xml; charset=\"utf-8\""

        /** Time a single socket read may block. */
        private const val READ_TIMEOUT_MS = 2_000

        /** Total time a client may take to send a complete request. */
        private const val REQUEST_DEADLINE_MS = 10_000L

        /** Maximum number of clients that are served at the same time. */
        private const val MAX_WORKER_THREADS = 8

        /** Maximum number of clients that wait for a worker. */
        private const val MAX_QUEUED_CLIENTS = 16

        /** Maximum number of bytes discarded from a rejected request before closing. */
        private const val MAX_DRAIN_BYTES = 256 * 1024
    }
}
