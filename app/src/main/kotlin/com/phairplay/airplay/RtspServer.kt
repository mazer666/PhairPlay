package com.phairplay.airplay

import com.phairplay.airplay.handshake.FairPlay
import com.phairplay.airplay.handshake.PairingKeys
import com.phairplay.airplay.handshake.PairingSession
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * RtspServer — owns the AirPlay RTSP control socket.
 *
 * WHAT: Binds port 7000, accepts one sender at a time, reads requests with [RtspRequestReader],
 * hands them to the [RtspRequestRouter] and writes the answers back to the socket. After a
 * successful RECORD it can switch the same socket over to interleaved RTP.
 *
 * WHY: Socket handling — binding, the single-client rule, partial reads, binary-safe response
 * framing — is a concern of its own and has nothing to do with the AirPlay verbs. Separating it
 * keeps the protocol handlers testable without a real network socket.
 *
 * Example:
 *   val server = RtspServer(context, sessionState, router, onStreamingStopped = { })
 *   scope.launch(Dispatchers.IO) { server.runServer(this) }   // accepts AirPlay senders
 */
internal class RtspServer(
    private val context: android.content.Context,
    private val sessionState: RtspSessionState,
    private val router: RtspRequestRouter,
    private val onStreamingStopped: () -> Unit
) {

    internal var serverSocket: ServerSocket? = null

    @Volatile
    internal var activeClient: Socket? = null

    @Volatile
    internal var running = false

    private var currentCSeq: Int = 0

    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = PhotoHandler.MAX_PHOTO_BYTES
    )

    // ─── Shared session state (see [RtspSessionState]) ──────────────────────

    /** The session negotiated by ANNOUNCE/SDP. */
    private var currentSession: SessionDescription?
        get() = sessionState.currentSession
        set(value) { sessionState.currentSession = value }

    /** How many SETUP requests arrived on this connection. */
    private var setupCount: Int
        get() = sessionState.setupCount
        set(value) { sessionState.setupCount = value }

    /** True while the connection carries an AirPlay 2 mirror session instead of an SDP session. */
    private var isMirrorSession: Boolean
        get() = sessionState.isMirrorSession
        set(value) { sessionState.isMirrorSession = value }

    /** The FairPlay context of this connection (fp-setup + key decrypt). */
    private var fairPlay: FairPlay?
        get() = sessionState.fairPlay
        set(value) { sessionState.fairPlay = value }

    /** The pairing context of this connection (pair-setup / pair-verify). */
    private var pairingSession: PairingSession?
        get() = sessionState.pairingSession
        set(value) { sessionState.pairingSession = value }

    /** The sender's address — needed for AirPlay 2 NTP. */
    private var currentRemoteAddress: java.net.InetAddress?
        get() = sessionState.currentRemoteAddress
        set(value) { sessionState.currentRemoteAddress = value }

    /** The mirror streams that are currently running (96 = audio, 110 = video). */
    private val activeStreamTypes: MutableSet<Int>
        get() = sessionState.activeStreamTypes

    /**
     * Callback for decoded H.264 NAL units from the RTP stream.
     * Set by [AirPlayReceiver] after RECORD — wires to [VideoDecoder.decodeNalUnit].
     * Null for audio-only streams.
     */
    @Volatile
    var onVideoNalUnit: ((nalUnit: ByteArray, ptsUs: Long) -> Unit)? = null

    /**
     * Binds the RTSP port with SO_REUSEADDR, retrying briefly if a just-stopped instance hasn't
     * released it yet. A quick service stop→start (the activity being destroyed and relaunched)
     * could otherwise fail with EADDRINUSE, leaving PhairPlay advertising over mDNS while port 7000
     * was dead — macOS would discover it and try to mirror but nothing could connect ("casting but
     * nothing shows"). SO_REUSEADDR handles TIME_WAIT; the retry covers the close/rebind race.
     */
    private fun bindRtspSocket(): ServerSocket {
        var lastError: java.io.IOException? = null
        repeat(BIND_MAX_ATTEMPTS) { attempt ->
            if (!running) throw java.io.IOException("RTSP server stopped before bind")
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    // Bind explicitly to the IPv4 wildcard: InetSocketAddress(port) resolves to
                    // an IPv6 wildcard on Android, which some devices' network stacks don't
                    // dual-stack onto the LAN's IPv4 interface — IPv4 senders (most phones on a
                    // typical home LAN) then get their SYN silently dropped instead of accepted.
                    bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), RTSP_PORT))
                }
            } catch (e: java.io.IOException) {
                lastError = e
                Logger.w("RTSP port $RTSP_PORT busy (attempt ${attempt + 1}/$BIND_MAX_ATTEMPTS) — retrying in ${BIND_RETRY_MS}ms")
                try { Thread.sleep(BIND_RETRY_MS) } catch (_: InterruptedException) { throw e }
            }
        }
        throw lastError ?: java.io.IOException("RTSP bind to $RTSP_PORT failed")
    }

    internal fun runServer(scope: CoroutineScope) {
        try {
            serverSocket = bindRtspSocket()
            Logger.i("RTSP server listening on port $RTSP_PORT")

            while (running && scope.isActive) {
                val clientSocket = serverSocket!!.accept()
                Logger.i("New client connected: ${clientSocket.inetAddress.hostAddress}")

                if (activeClient != null && !activeClient!!.isClosed) {
                    Logger.w("Rejecting second client — already streaming")
                    sendServiceUnavailable(clientSocket)
                    clientSocket.close()
                    continue
                }

                activeClient = clientSocket
                handleClient(clientSocket)
            }
        } catch (e: Exception) {
            if (running) {
                Logger.e("RTSP server error (unexpected)", e)
            } else {
                Logger.d("RTSP server socket closed (expected during shutdown)")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val inputStream = socket.getInputStream()
        val outputStream = socket.getOutputStream()

        // Fresh pairing + FairPlay state for each control connection.
        pairingSession = PairingSession(PairingKeys.get(context))
        fairPlay = FairPlay()
        // NOTE: legacyPin and pinPaired are deliberately NOT reset here. macOS runs the PIN handshake
        // across SEPARATE TCP connections (/pair-pin-start on one, /pair-setup-pin on the next), so the
        // PIN/verifier and the "paired" flag must survive a reconnect. They live for the receiver's
        // lifetime — replaced by the next /pair-pin-start, set on a successful pairing.
        currentRemoteAddress = socket.inetAddress

        try {
            while (running && !socket.isClosed) {
                val request = requestReader.read(inputStream) ?: break
                currentCSeq = request.headers["CSeq"]?.toIntOrNull() ?: 0
                val response = router.routeRequest(request)
                sendResponse(outputStream, response)

                // After RECORD on a legacy SDP session: a session WITH video switches to interleaved
                // RTP (video arrives $-framed over this TCP socket). An audio-only session (e.g. Apple
                // Music) keeps the RTSP control loop — audio arrives on the UDP port, and macOS sends
                // now-playing metadata / volume / FLUSH / TEARDOWN as RTSP requests here that we must
                // keep handling (switching to interleaved mode would skip them → no metadata).
                if (request.method == "RECORD" && response.statusCode == 200 && !isMirrorSession &&
                    currentSession?.hasVideo == true) {
                    Logger.d("RTSP handshake complete — switching to interleaved RTP (video)")
                    break
                }
            }

            val session = currentSession
            if (session != null && session.hasVideo && running) {
                RtpInterleaved.readLoop(
                    inputStream = inputStream,
                    onVideoNalUnit = { nalUnit, ptsUs ->
                        onVideoNalUnit?.invoke(nalUnit, ptsUs)
                    },
                    onStreamEnded = {
                        Logger.i("RTP stream ended")
                    }
                )
            }
        } catch (e: Exception) {
            if (running) Logger.e("Error handling RTSP client", e)
        } finally {
            Logger.i("Client disconnected")
            socket.close()
            activeClient = null
            currentSession = null
            pairingSession = null
            fairPlay = null
            isMirrorSession = false
            activeStreamTypes.clear()
            setupCount = 0
            onStreamingStopped()
        }
    }

    private fun sendResponse(outputStream: OutputStream, response: RtspResponse) {
        // Binary-safe: build the header block as ASCII, then write the raw body bytes.
        // Content-Length must be the BYTE length (not String.length) so binary plists,
        // FairPlay payloads, and encrypted bodies are framed correctly.
        val wire = response.wireBody()
        val head = StringBuilder()
        head.append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
        if (response.protocol.startsWith("RTSP")) {
            head.append("CSeq: $currentCSeq\r\n")
        }
        head.append("Server: AirTunes/220.68\r\n")
        response.contentType?.let { head.append("Content-Type: $it\r\n") }
        response.headers.forEach { (key, value) ->
            head.append("$key: $value\r\n")
        }
        if (wire.isNotEmpty()) {
            head.append("Content-Length: ${wire.size}\r\n")
        }
        head.append("\r\n")
        outputStream.write(head.toString().toByteArray(Charsets.US_ASCII))
        if (wire.isNotEmpty()) {
            outputStream.write(wire)
        }
        outputStream.flush()
    }

    private fun sendServiceUnavailable(socket: Socket) {
        try {
            val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
            socket.outputStream.write(response.toByteArray())
            socket.outputStream.flush()
        } catch (e: Exception) {
            Logger.e("Error sending 503 response", e)
        }
    }

    companion object {
        private const val RTSP_PORT = 7000

        private const val BIND_MAX_ATTEMPTS = 12      // ~3s total — covers a quick stop→start restart
        private const val BIND_RETRY_MS = 250L
        // iOS/iPadOS mirror-stream SETUP requests carry a large binary plist
        // (observed ~77 KB from iPhone17,1 on iOS 26) that exceeded the previous
        // 64 KB cap, so the reader rejected the video stream SETUP and the sender
        // tore the session down right after audio started. 1 MB gives ample head-
        // room for future senders while staying trivially safe on a LAN.
        private const val MAX_MESSAGE_BYTES = 1024 * 1024
    }
}
