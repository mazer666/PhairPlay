package com.phairplay.dlna

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * DlnaHttpServer — minimal HTTP/1.1 server (Connection: close) for description, SOAP control and GENA.
 *
 * WHY: UPnP control points speak plain HTTP; a raw ServerSocket keeps us in control of the verbs
 * (SUBSCRIBE/UNSUBSCRIBE are not HTTP-library friendly) and mirrors the AirPlay RTSP server's approach.
 *
 * HOW: `val port = server.start()`; `server.stop()`. All request handling is delegated to [handler]
 * (the [DlnaRouter]); parse failures are answered here with 400/413.
 */
class DlnaHttpServer(
    private val scope: CoroutineScope,
    private val handler: (HttpRequest) -> HttpResponse
) {
    private var serverSocket: ServerSocket? = null
    private val reader = HttpRequestReader()

    /** Binds [DlnaConstants.DEFAULT_HTTP_PORT], falling back to an OS-assigned port. Returns the bound port. */
    fun start(): Int {
        val socket = try {
            ServerSocket(DlnaConstants.DEFAULT_HTTP_PORT)
        } catch (e: IOException) {
            Logger.w("DLNA HTTP port ${DlnaConstants.DEFAULT_HTTP_PORT} busy (${e.message}) — using an ephemeral port")
            ServerSocket(0)
        }
        serverSocket = socket
        scope.launch { acceptLoop(socket) }
        Logger.i("DLNA HTTP server listening on ${socket.localPort}")
        return socket.localPort
    }

    fun stop() {
        runCatching { serverSocket?.close() }.onFailure { Logger.w("DLNA HTTP close failed: ${it.message}") }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break   // closed by stop()
            }
            scope.launch { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        var afterSend: (() -> Unit)? = null
        try {
            client.use { connection ->
                connection.soTimeout = DlnaConstants.HTTP_READ_TIMEOUT_MS
                val response = when (val parsed = reader.read(connection.getInputStream())) {
                    is HttpParse.Ok -> respond(parsed.request)
                    HttpParse.TooLarge -> HttpResponse.empty(413)
                    HttpParse.Malformed -> HttpResponse.empty(400)
                    HttpParse.Eof -> return
                }
                connection.getOutputStream().apply { write(response.toBytes()); flush() }
                afterSend = response.afterSend
            }
        } catch (e: IOException) {
            Logger.d("DLNA HTTP connection ${client.inetAddress} ended: ${e.message}")
        }
        // Runs after the reply is on the wire (GENA initial event) and after the socket is closed.
        afterSend?.let { scope.launch { it() } }
    }

    private fun respond(request: HttpRequest): HttpResponse = try {
        Logger.d("DLNA HTTP ${request.method} ${request.path}")
        handler(request)
    } catch (e: Exception) {
        Logger.e("DLNA HTTP handler failed for ${request.method} ${request.path}", e)
        HttpResponse.empty(500)
    }
}
