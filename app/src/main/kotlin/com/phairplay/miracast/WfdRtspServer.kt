package com.phairplay.miracast

import com.phairplay.airplay.RtspRequest
import com.phairplay.airplay.RtspRequestReader
import com.phairplay.airplay.RtspResponse
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * WfdRtspServer — The WFD RTSP control server a Miracast sender talks to.
 *
 * WHAT: Accepts one WFD RTSP client on port 7236, answers the WFD-specific RTSP methods
 * (OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN) and reports the
 * start and the end of a mirroring session.
 *
 * WHY: The WFD RTSP control channel is a protocol of its own — its methods and its sink
 * parameters differ from AirPlay's RTSP — and it had ended up in the same file as the
 * Wi-Fi Direct advertisement of [MiracastReceiver]. Advertisement and session control
 * are easier to read, review and change on their own once the server owns its file.
 *
 * HOW: [MiracastReceiver] creates one server, starts it on its coroutine scope as soon
 * as the P2P service is advertised and stops it when the receiver stops. The two
 * callbacks report the session transitions back to the receiver.
 *
 * Example:
 *   val server = WfdRtspServer(onSessionStarted = { connect() }, onSessionStopped = { disconnect() })
 *   server.start(scope)   // opens port 7236
 *   server.stop()         // closes the socket and reports the end of the session
 */
internal class WfdRtspServer(
    private val onSessionStarted: () -> Unit,
    private val onSessionStopped: () -> Unit
) {
    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = MAX_MESSAGE_BYTES
    )

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeClient: Socket? = null
    private var currentCSeq = 0
    private var sessionStarted = false

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) {
            runServer(this)
        }
    }

    fun stop() {
        running = false
        try {
            activeClient?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing WFD RTSP sockets (non-fatal)", e)
        }
        activeClient = null
        serverSocket = null
        if (sessionStarted) {
            sessionStarted = false
            onSessionStopped()
        }
    }

    private fun runServer(scope: CoroutineScope) {
        try {
            serverSocket = ServerSocket(MiracastReceiver.WFD_RTSP_PORT)
            Logger.i("WFD RTSP server listening on port ${MiracastReceiver.WFD_RTSP_PORT}")
            while (running && scope.isActive) {
                val client = serverSocket!!.accept()
                if (activeClient != null && !activeClient!!.isClosed) {
                    sendServiceUnavailable(client)
                    client.close()
                    continue
                }
                activeClient = client
                handleClient(client)
            }
        } catch (e: Exception) {
            if (running) Logger.e("WFD RTSP server error", e)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            while (running && !socket.isClosed) {
                val request = requestReader.read(input) ?: break
                currentCSeq = request.headers["CSeq"]?.toIntOrNull() ?: 0
                val response = routeRequest(request)
                sendResponse(output, response)
                if (request.method == "TEARDOWN") break
            }
        } catch (e: Exception) {
            if (running) Logger.e("Error handling WFD RTSP client", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Logger.e("Error closing WFD RTSP client socket (non-fatal)", e)
            }
            activeClient = null
            if (sessionStarted) {
                sessionStarted = false
                onSessionStopped()
            }
        }
    }

    internal fun routeRequest(request: RtspRequest): RtspResponse {
        Logger.d("WFD RTSP ${request.method} ${request.uri}")
        return when (request.method) {
            "OPTIONS" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Public" to "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER")
            )
            "GET_PARAMETER" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Content-Type" to "text/parameters"),
                body = sinkParameters()
            )
            "SET_PARAMETER" -> RtspResponse(statusCode = 200, statusMessage = "OK")
            "SETUP" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf(
                    "Session" to WFD_SESSION_ID,
                    "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"
                )
            )
            "PLAY" -> {
                if (!sessionStarted) {
                    sessionStarted = true
                    onSessionStarted()
                }
                RtspResponse(
                    statusCode = 200,
                    statusMessage = "OK",
                    headers = mapOf("Session" to WFD_SESSION_ID)
                )
            }
            "PAUSE" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Session" to WFD_SESSION_ID)
            )
            "TEARDOWN" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Session" to WFD_SESSION_ID)
            )
            else -> RtspResponse(statusCode = 501, statusMessage = "Not Implemented")
        }
    }

    private fun sinkParameters(): String =
        listOf(
            "wfd_audio_codecs: LPCM 00000003 00",
            "wfd_video_formats: 00 00 02 10 0001FFFF 00000000 00000000 00 0000 0000 00 none none",
            "wfd_client_rtp_ports: RTP/AVP/TCP;unicast 0 0 mode=play",
            "wfd_content_protection: none",
            "wfd_display_edid: none",
            "wfd_coupled_sink: none",
            "wfd_connector_type: 05"
        ).joinToString(separator = "\r\n", postfix = "\r\n")

    private fun sendResponse(outputStream: OutputStream, response: RtspResponse) {
        val sb = StringBuilder()
        sb.append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
        sb.append("CSeq: $currentCSeq\r\n")
        sb.append("Server: PhairPlay/1.0\r\n")
        response.headers.forEach { (key, value) -> sb.append("$key: $value\r\n") }
        if (response.body.isNotEmpty()) {
            sb.append("Content-Length: ${response.body.toByteArray(Charsets.UTF_8).size}\r\n")
        }
        sb.append("\r\n")
        sb.append(response.body)
        outputStream.write(sb.toString().toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }

    private fun sendServiceUnavailable(socket: Socket) {
        val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
        socket.outputStream.write(response.toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 65536
        private const val WFD_SESSION_ID = "PhairPlayWfdSession"
    }
}
