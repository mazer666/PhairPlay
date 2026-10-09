package com.phairplay.airplay

import com.phairplay.airplay.handshake.FairPlay
import com.phairplay.airplay.handshake.InfoResponder
import com.phairplay.airplay.handshake.PairingKeys
import com.phairplay.airplay.handshake.PairingSession
import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * RtspHandler — Manages the RTSP session with the AirPlay sender (macOS).
 *
 * AirPlay uses RTSP to negotiate codecs, ports, and encryption before media flows.
 * The handler accepts one sender at a time, parses ANNOUNCE SDP, acknowledges SETUP
 * and RECORD, then hands binary interleaved RTP frames to [RtpInterleaved].
 */
open class RtspHandler(
    private val context: android.content.Context,
    private val displayWidth: Int = 1920,
    private val displayHeight: Int = 1080,
    private val audioEnabled: Boolean = false,
    private val videoSurfaceProvider: () -> android.view.Surface?,
    private val onStreamingStarted: (session: SessionDescription) -> Unit,
    private val onStreamingStopped: () -> Unit,
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    private val onPhotoCleared: () -> Unit = {},
    /**
     * AirPlay 2 mirror SETUP msg 1: supply decrypted AES key + pairing secret + the sender's
     * address and timing port (so the receiver can start NTP). Returns (eventPort, timingPort).
     */
    private val onMirrorSetupKeys: (
        aesKey: ByteArray, ecdhSecret: ByteArray, aesIv: ByteArray,
        remoteAddress: java.net.InetAddress, senderTimingPort: Int
    ) -> Pair<Int, Int> = { _, _, _, _, _ -> 0 to 0 },
    /** AirPlay 2 mirror SETUP: start the video data server (type 110); returns its data port. */
    private val onMirrorStreamStart: (streamConnectionId: Long) -> Int = { 0 },
    /** AirPlay 2 SETUP: start the audio server (type 96; ct 8 AAC-ELD mirror / 4 AAC-LC / 2 ALAC). spf = samples/frame. */
    private val onMirrorAudioStart: (sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int) -> Pair<Int, Int> = { _, _, _, _ -> 0 to 0 },
    /** AirPlay 2 mirror TEARDOWN of just the audio stream (type 96) — stop audio, keep video. */
    private val onMirrorAudioStop: () -> Unit = {},
    /** AirPlay 2 mirror TEARDOWN of just the video stream (type 110) — stop video, keep audio. */
    private val onMirrorVideoStop: () -> Unit = {},
    /** AirPlay 2 buffered audio-only SETUP (type 103, Apple Music → TV); returns the TCP data port. */
    private val onBufferedAudioStart: () -> Int = { 0 },
    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private val onBufferedAudioStop: () -> Unit = {},
    /** Sender volume change (AirPlay dB: −30…0, or ≤ −144 = mute) via SET_PARAMETER. */
    private val onVolume: (Float) -> Unit = {},
    /** Now-playing track metadata (DMAP) from SET_PARAMETER — any field may be null. */
    private val onNowPlayingMetadata: (title: String?, artist: String?, album: String?) -> Unit = { _, _, _ -> },
    /** Album artwork (JPEG/PNG bytes) from SET_PARAMETER; empty bytes = artwork cleared. */
    private val onArtwork: (ByteArray) -> Unit = {},
    /** AirPlay video URL mode: POST /play with a media URL + start fraction (0..1). */
    private val onVideoPlay: (url: String, startFraction: Double) -> Unit = { _, _ -> },
    /** AirPlay video transport: POST /rate (≤0 pause, >0 resume). */
    private val onVideoRate: (rate: Float) -> Unit = {},
    /** AirPlay video transport: POST /scrub — seek to position (seconds). */
    private val onVideoScrub: (positionSec: Double) -> Unit = {},
    /** AirPlay video transport: POST /stop — stop URL playback. */
    private val onVideoStop: () -> Unit = {},
    /** Current URL-video playback snapshot for GET /playback-info and GET /scrub. */
    private val onPlaybackInfo: () -> com.phairplay.airplay.PlaybackInfo? = { null },
    /** Sender's DACP reverse-control identity from RTSP headers (DACP-ID + Active-Remote token). */
    private val onRemoteControlInfo: (dacpId: String?, activeRemote: String?) -> Unit = { _, _ -> },
    /** When true, require HomeKit-style SRP PIN pairing before streaming (gated by AppSettings). */
    private val pinAuthEnabled: Boolean = false,
    /** Persistent store of paired controllers' Ed25519 keys (for pair-verify). */
    private val pairingStore: com.phairplay.airplay.handshake.PairingStore? = null,
    /** Shows ([pin]) or hides (null) the on-screen pairing PIN during SRP pair-setup. */
    private val onShowPin: (pin: String?) -> Unit = {}
) {

    // ─── Legacy AirPlay SRP PIN pairing (only used when pinAuthEnabled) ───────
    @Volatile private var legacyPin: com.phairplay.airplay.handshake.LegacyPairSetupPin? = null
    // True once a controller has completed SRP PIN pairing. Until then, with PIN auth on, we reject
    // pair-verify — which is what makes macOS fall back to the /pair-pin-start + /pair-setup-pin PIN
    // flow (an accepted pair-verify means "already trusted, no PIN needed").
    @Volatile private var pinPaired = false

    /** Last volume the sender set (AirPlay dB); returned to GET_PARAMETER volume queries. */
    @Volatile private var currentVolume: Float = 0f

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeClient: Socket? = null

    @Volatile
    private var running = false

    private var currentCSeq: Int = 0

    @Volatile
    private var currentSession: SessionDescription? = null

    /** Per-connection AirPlay pairing state (pair-setup / pair-verify). */
    @Volatile
    private var pairingSession: PairingSession? = null

    /** Per-connection FairPlay state (fp-setup handshake + stream-key decrypt). */
    @Volatile
    private var fairPlay: FairPlay? = null

    /** Remote (sender) address of the active control connection — needed for AirPlay 2 NTP. */
    @Volatile
    private var currentRemoteAddress: java.net.InetAddress? = null

    /** True once an AirPlay 2 mirroring SETUP has run on this connection (no ANNOUNCE/SDP). */
    @Volatile
    private var isMirrorSession = false

    /** Mirror stream types currently active (96 = audio, 110 = video). Drives TEARDOWN routing.
     *  `protected` so tests can seed it without driving the full FairPlay SETUP handshake. */
    protected val activeStreamTypes = mutableSetOf<Int>()

    private var setupCount = 0

    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = PhotoHandler.MAX_PHOTO_BYTES
    )

    /**
     * Callback for decoded H.264 NAL units from the RTP stream.
     * Set by [AirPlayReceiver] after RECORD — wires to [VideoDecoder.decodeNalUnit].
     * Null for audio-only streams.
     */
    @Volatile
    var onVideoNalUnit: ((nalUnit: ByteArray, ptsUs: Long) -> Unit)? = null

    /** Starts the RTSP server. */
    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) {
            runServer(this)
        }
    }

    /** Stops the RTSP server. */
    fun stop() {
        running = false
        try {
            activeClient?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing RTSP sockets (non-fatal)", e)
        }
        activeClient = null
        serverSocket = null
        Logger.i("RTSP handler stopped")
    }

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
                    bind(java.net.InetSocketAddress(RTSP_PORT))
                }
            } catch (e: java.io.IOException) {
                lastError = e
                Logger.w("RTSP port $RTSP_PORT busy (attempt ${attempt + 1}/$BIND_MAX_ATTEMPTS) — retrying in ${BIND_RETRY_MS}ms")
                try { Thread.sleep(BIND_RETRY_MS) } catch (_: InterruptedException) { throw e }
            }
        }
        throw lastError ?: java.io.IOException("RTSP bind to $RTSP_PORT failed")
    }

    private fun runServer(scope: CoroutineScope) {
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
                val response = routeRequest(request)
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

    private fun routeRequest(request: RtspRequest): RtspResponse {
        Logger.d("RTSP ${request.method} ${request.uri}")
        // Senders attach their DACP reverse-control identity to most requests — capture it so the TV
        // remote can drive playback (DacpClient dedups, so this is cheap to call repeatedly).
        request.headers["Active-Remote"]?.let { onRemoteControlInfo(request.headers["DACP-ID"], it) }
        return when (request.method) {
            "OPTIONS"       -> handleOptionsInternal(request)
            "ANNOUNCE"      -> handleAnnounceInternal(request)
            // AirPlay 2 mirroring SETUP carries a binary plist; legacy audio SETUP carries SDP-ish text.
            "SETUP"         -> if (request.isPlistBody()) handleMirrorSetup(request) else handleSetupInternal(request)
            "RECORD"        -> handleRecordInternal(request)
            "TEARDOWN"      -> handleTeardownInternal(request)
            "GET_PARAMETER" -> handleGetParameter(request)
            "SET_PARAMETER" -> handleSetParameter(request)
            "FLUSH"         -> handleFlush(request)
            "PAUSE"         -> handlePauseInternal(request)
            // AirPlay 2 buffered-audio control verbs. Acknowledge them (a 501 would abort audio-only
            // playback) and log their bodies so the anchor/rate/peer formats can be implemented.
            "SETRATEANCHORTIME", "SETRATEANCHORTIM" -> handleBufferedControl(request, "SETRATEANCHORTIME")
            "SETPEERS", "SETPEERSX"                 -> handleBufferedControl(request, "SETPEERS")
            "FLUSHBUFFERED"                         -> handleBufferedControl(request, "FLUSHBUFFERED")
            "PUT"           -> handlePhotoPutInternal(request)
            "DELETE"        -> handlePhotoDeleteInternal(request)
            // AirPlay 2 handshake is HTTP-style (GET/POST with bodies) over the RTSP socket.
            "GET"           -> routeGet(request)
            "POST"          -> routePost(request)
            else            -> handleUnknownInternal(request)
        }
    }

    /** Routes AirPlay 2 GET requests by URI path. */
    private fun routeGet(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/info"          -> handleInfo(request)
        "/playback-info" -> handlePlaybackInfo(request)
        "/scrub"         -> handleScrubGet(request)
        "/server-info"   -> handleServerInfo(request)
        else             -> handleUnknownInternal(request)
    }

    /** Routes AirPlay 2 POST requests by URI path. */
    private fun routePost(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/pair-setup"  -> handlePairSetup(request)
        "/pair-setup-pin" -> handleLegacyPairSetupPin(request)   // legacy AirPlay PIN SRP (plist)
        "/pair-pin-start" -> handlePairPinStart(request)
        "/pair-verify" -> handlePairVerify(request)
        "/fp-setup"    -> handleFpSetup(request)
        "/feedback"    -> handleFeedback(request)
        "/audioMode"   -> RtspResponse(200, "OK", protocol = request.responseProtocol())
        // AirPlay video URL mode (non-mirroring): play a URL + drive transport.
        "/play"        -> handleVideoPlay(request)
        "/rate"        -> handleVideoRate(request)
        "/scrub"       -> handleVideoScrubPost(request)
        "/stop"        -> handleVideoStop(request)
        else           -> handleUnknownInternal(request)
    }

    // ─── AirPlay video URL mode (POST /play, /rate, /scrub, /stop; GET /playback-info, /scrub) ──

    /** POST /play — a media URL to play (binary/XML plist or legacy text body). */
    private fun handleVideoPlay(request: RtspRequest): RtspResponse {
        val (url, start) = parsePlayBody(request)
        if (url.isNullOrBlank()) {
            Logger.w("POST /play with no Content-Location")
            return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
        Logger.i("POST /play url=$url start=$start")
        onVideoPlay(url, start)
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** Extracts the media URL + start fraction from a /play body (plist `Content-Location` or text). */
    private fun parsePlayBody(request: RtspRequest): Pair<String?, Double> {
        if (request.isPlistBody()) {
            val p = runCatching { PlistCodec.decode(request.bodyBytes) }.getOrNull() ?: return null to 0.0
            val url = p["Content-Location"] as? String
            val start = (p["Start-Position"] as? Double) ?: 0.0
            return url to start
        }
        // Legacy text body: "Content-Location: <url>\r\nStart-Position: <float>\r\n"
        var url: String? = null
        var start = 0.0
        request.body.lineSequence().forEach { line ->
            when {
                line.startsWith("Content-Location:", true) -> url = line.substringAfter(":").trim()
                line.startsWith("Start-Position:", true) -> start = line.substringAfter(":").trim().toDoubleOrNull() ?: 0.0
            }
        }
        return url to start
    }

    /** POST /rate?value=X — X=0 pause, X≥1 resume. */
    private fun handleVideoRate(request: RtspRequest): RtspResponse {
        val rate = queryParam(request.uri, "value")?.toFloatOrNull() ?: 1f
        Logger.d("POST /rate value=$rate")
        onVideoRate(rate)
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** POST /scrub?position=N — seek to N seconds. */
    private fun handleVideoScrubPost(request: RtspRequest): RtspResponse {
        queryParam(request.uri, "position")?.toDoubleOrNull()?.let {
            Logger.d("POST /scrub position=$it")
            onVideoScrub(it)
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /scrub — current position + duration as text/parameters. */
    private fun handleScrubGet(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val body = "duration: %.6f\r\nposition: %.6f\r\n".format(info?.durationSec ?: 0.0, info?.positionSec ?: 0.0)
        return RtspResponse(200, "OK", body = body, contentType = "text/parameters", protocol = request.responseProtocol())
    }

    /** POST /stop — stop URL playback. */
    private fun handleVideoStop(request: RtspRequest): RtspResponse {
        Logger.i("POST /stop (video URL)")
        onVideoStop()
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /playback-info — XML plist describing current position/duration/rate/ready state. */
    private fun handlePlaybackInfo(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val plist: Map<String, Any?> = if (info == null || !info.readyToPlay) {
            mapOf("readyToPlay" to false)
        } else {
            val ranges = listOf(mapOf("start" to 0.0, "duration" to info.durationSec))
            mapOf(
                "duration" to info.durationSec,
                "position" to info.positionSec,
                "rate" to info.rate,
                "readyToPlay" to true,
                "playbackBufferEmpty" to false,
                "playbackBufferFull" to true,
                "playbackLikelyToKeepUp" to true,
                "loadedTimeRanges" to ranges,
                "seekableTimeRanges" to ranges,
            )
        }
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(plist),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** GET /server-info — legacy XML plist of receiver identity for AirPlay video senders. */
    private fun handleServerInfo(request: RtspRequest): RtspResponse {
        val info = mapOf(
            "deviceid" to com.phairplay.util.NetworkUtils.getMacAddress(),
            "features" to 0x1E5A7FFFF7L,
            "model" to "AppleTV5,3",
            "protovers" to "1.1",
            "srcvers" to "220.68",
        )
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(info),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** Extracts a query-string parameter (`?k=v&...`) from a request URI. */
    private fun queryParam(uri: String, key: String): String? =
        uri.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")

    /** POST /feedback — macOS health-checks the session every ~2 s; acknowledge with 200 OK. */
    private fun handleFeedback(request: RtspRequest): RtspResponse {
        val n = request.bodyBytes.size
        if (n > 0) {
            runCatching {
                val p = PlistCodec.decode(request.bodyBytes)
                Logger.d("/feedback body ($n B): " + p.entries.joinToString { (k, v) ->
                    "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                })
            }.onFailure { Logger.d("/feedback body ($n B, non-plist)") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /**
     * Acknowledges an AirPlay 2 buffered-audio control verb (SETRATEANCHORTIME / SETPEERS /
     * FLUSHBUFFERED). Returning 200 keeps an audio-only session alive (a 501 would make macOS abort).
     */
    private fun handleBufferedControl(request: RtspRequest, label: String): RtspResponse {
        val n = request.bodyBytes.size
        if (n > 0) {
            runCatching {
                val p = PlistCodec.decode(request.bodyBytes)
                Logger.d("$label body ($n B): " + p.entries.joinToString { (k, v) ->
                    "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                })
            }.onFailure { Logger.d("$label body ($n B, non-plist)") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /info — advertises receiver identity + capabilities (binary plist). */
    private fun handleInfo(request: RtspRequest): RtspResponse = RtspResponse(
        statusCode = 200,
        statusMessage = "OK",
        bodyBytes = InfoResponder.build(context, displayWidth, displayHeight, pinRequired = pinAuthEnabled),
        contentType = "application/x-apple-binary-plist",
        protocol = request.responseProtocol()
    )

    /**
     * POST /pair-setup. With PIN auth off (default) this is the anonymous Ed25519 exchange. With PIN
     * auth on, it runs the HomeKit-style SRP pair-setup (TLV8) — showing a PIN on the TV that the
     * user types on the Mac — so only someone with screen access can pair.
     */
    private fun handlePairSetup(request: RtspRequest): RtspResponse {
        // /pair-setup is the anonymous key exchange; PIN access control runs on /pair-setup-pin.
        return try {
            val body = pairingSession!!.pairSetup(request.bodyBytes)
            Logger.i("pair-setup OK (returned ${body.size}-byte public key)")
            RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
        } catch (e: Exception) {
            Logger.e("pair-setup failed", e)
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
    }

    /**
     * POST /pair-verify — the anonymous ECDH handshake. AirPlay uses this same raw exchange even with
     * PIN access control on (the PIN is a SEPARATE /pair-pin-start + /pair-setup-pin layer), so this
     * is never the HomeKit TLV8 variant.
     */
    private fun handlePairVerify(request: RtspRequest): RtspResponse {
        // PIN access control: refuse pair-verify until the controller has PIN-paired this connection.
        // macOS responds to the rejection by starting the PIN flow (/pair-pin-start → /pair-setup-pin).
        if (pinAuthEnabled && !pinPaired) {
            Logger.i("pair-verify rejected — PIN pairing required first (triggers /pair-pin-start)")
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        return try {
            val body = pairingSession!!.pairVerify(request.bodyBytes)
            Logger.i("pair-verify ${if (request.bodyBytes.firstOrNull()?.toInt() == 1) "M1" else "M2"} OK (returned ${body.size} bytes)")
            RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
        } catch (e: Exception) {
            Logger.e("pair-verify failed", e)
            RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
    }

    /**
     * POST /pair-pin-start — macOS asks the receiver to begin PIN pairing and display the code. We
     * generate + show the PIN and prime the SRP session, then reply 200; the SRP exchange follows on
     * /pair-setup-pin. (This precedes pair-setup in the AirPlay PIN flow — see the logs.)
     */
    private fun handlePairPinStart(request: RtspRequest): RtspResponse {
        if (!pinAuthEnabled) return handleUnknownInternal(request)
        if ((pairingStore?.failedAttempts() ?: 0) >= MAX_PAIR_ATTEMPTS) {
            Logger.w("pair-pin-start blocked — PIN auth locked ($MAX_PAIR_ATTEMPTS failed attempts)")
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        newSrpSession()
        Logger.i("pair-pin-start — PIN shown, SRP session primed")
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** Generates a fresh 4-digit PIN, shows it on the TV, and primes the legacy SRP session. */
    private fun newSrpSession() {
        val pin = "%0${PIN_DIGITS}d".format(java.security.SecureRandom().nextInt(PIN_SPACE))
        onShowPin(pin)
        legacyPin = com.phairplay.airplay.handshake.LegacyPairSetupPin(pin, PairingKeys.get(context).edPublic)
    }

    /**
     * POST /pair-setup-pin — the legacy AirPlay plist SRP exchange. Step 1 ({method,user}) returns
     * {pk,salt}; step 2 ({pk,proof}) verifies the PIN and returns {proof}. On success the controller
     * is allowed past pair-verify (→ streaming). Bounded by the failed-attempt lockout.
     */
    private fun handleLegacyPairSetupPin(request: RtspRequest): RtspResponse {
        if (!pinAuthEnabled) return handleUnknownInternal(request)
        if ((pairingStore?.failedAttempts() ?: 0) >= MAX_PAIR_ATTEMPTS) {
            Logger.w("pair-setup-pin blocked — PIN auth locked ($MAX_PAIR_ATTEMPTS failed attempts)")
            onShowPin(null)
            return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
        return try {
            val plist = PlistCodec.decode(request.bodyBytes)
            if (legacyPin == null) newSrpSession()   // step 1 may arrive without a prior /pair-pin-start
            val result = legacyPin!!.handle(plist)
            if (result.failed) {
                val n = pairingStore?.recordFailedAttempt() ?: 0
                Logger.w("pair-setup-pin attempt failed ($n/$MAX_PAIR_ATTEMPTS)")
                onShowPin(null); legacyPin = null
                return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
            }
            if (result.complete) {
                pairingStore?.resetFailedAttempts()   // legitimate pairing clears the lockout counter
                pinPaired = true                       // now allow pair-verify → streaming proceeds
                onShowPin(null); legacyPin = null
                Logger.i("PIN pairing complete — pair-verify now permitted")
            }
            RtspResponse(
                200, "OK",
                bodyBytes = PlistCodec.encode(result.reply!!),
                contentType = "application/x-apple-binary-plist",
                protocol = request.responseProtocol()
            )
        } catch (e: Exception) {
            Logger.e("pair-setup-pin failed", e)
            onShowPin(null); legacyPin = null
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
    }

    /** POST /fp-setup — FairPlay: 16-byte phase 1 → 142-byte reply; 164-byte phase 2 → 32-byte reply. */
    private fun handleFpSetup(request: RtspRequest): RtspResponse = try {
        val fp = fairPlay!!
        val b = request.bodyBytes
        // Diagnostics: byte 4 is the FairPlay version (0x03 mirroring/Safari, 0x02 Apple Music audio);
        // for phase 1, byte 14 is the mode (0..3). Confirms which path a given sender uses.
        val verMode = if (b.size >= 16) " v=0x%02x mode=%d".format(b[4].toInt() and 0xFF, b[14].toInt() and 0xFF)
                      else if (b.size >= 5) " v=0x%02x".format(b[4].toInt() and 0xFF) else ""
        val body = when (b.size) {
            16 -> fp.setup(b)
            164 -> fp.handshake(b)
            else -> throw IllegalArgumentException("unexpected fp-setup size ${b.size}")
        }
        Logger.i("fp-setup phase (${b.size}B in → ${body.size}B out)$verMode OK")
        RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
    } catch (e: Exception) {
        Logger.e("fp-setup failed", e)
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }

    /**
     * AirPlay 2 mirroring SETUP (binary plist). Two messages arrive on one connection:
     *  - msg 1 carries `ekey`+`eiv`+`timingPort` → FairPlay-decrypt the AES key, hand it
     *    (with the pairing secret) to the receiver, reply with event/timing ports.
     *  - msg 2 carries `streams`[type 110] → start the mirror data server, reply with its port.
     */
    private fun handleMirrorSetup(request: RtspRequest): RtspResponse = try {
        val req = PlistCodec.decode(request.bodyBytes)
        Logger.i("mirror SETUP plist: " + req.entries.joinToString { (k, v) ->
            "$k=" + when (v) {
                is ByteArray -> "${v.size}B"
                is List<*> -> "list[${v.size}]"
                else -> v.toString()
            }
        })
        val response = mutableMapOf<String, Any?>()

        isMirrorSession = true
        val ekey = req["ekey"] as? ByteArray
        if (ekey != null) {
            val aesKey = fairPlay!!.decrypt(ekey)
            val ecdhSecret = pairingSession?.sharedSecret ?: error("mirror SETUP before pair-verify")
            val aesIv = (req["eiv"] as? ByteArray) ?: ByteArray(16)
            val senderTimingPort = (req["timingPort"] as? Long)?.toInt() ?: 0
            val remoteAddr = currentRemoteAddress ?: error("mirror SETUP without remote address")
            val (eventPort, timingPort) = onMirrorSetupKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            response["eventPort"] = eventPort.toLong()
            response["timingPort"] = timingPort.toLong()
            Logger.i("mirror SETUP keys OK — eventPort=$eventPort timingPort=$timingPort (sender timing $senderTimingPort)")
        }

        val streams = req["streams"] as? List<*>
        if (streams != null) {
            val resStreams = streams.mapNotNull { s ->
                val stream = s as? Map<*, *> ?: return@mapNotNull null
                when ((stream["type"] as? Long)?.toInt()) {
                    110 -> {
                        val scid = (stream["streamConnectionID"] as? Long) ?: 0L
                        val dataPort = onMirrorStreamStart(scid)
                        activeStreamTypes.add(110)
                        Logger.i("mirror stream type=110 streamConnectionID=$scid dataPort=$dataPort")
                        mapOf("type" to 110L, "dataPort" to dataPort.toLong())
                    }
                    96 -> {
                        // Realtime-audio stream fields (codec type ct, samples-per-frame spf, latencies, …).
                        Logger.d("mirror stream type=96 dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                        })
                        if (!audioEnabled) {
                            Logger.i("mirror stream type=96 ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val sr = (stream["sr"] as? Long)?.toInt() ?: 44100
                        val ch = (stream["channels"] as? Long)?.toInt() ?: 2
                        val ct = (stream["ct"] as? Long)?.toInt() ?: 8   // 8 = AAC-ELD (mirror), 4 = AAC-LC, 2 = ALAC
                        val spf = (stream["spf"] as? Long)?.toInt() ?: 352   // ALAC frameLength (samples/frame)
                        val (dataPort, controlPort) = onMirrorAudioStart(sr, ch, ct, spf)
                        activeStreamTypes.add(96)
                        Logger.i("audio stream type=96 (ct=$ct ${sr}Hz x$ch spf=$spf) dataPort=$dataPort controlPort=$controlPort")
                        mapOf("type" to 96L, "dataPort" to dataPort.toLong(), "controlPort" to controlPort.toLong())
                    }
                    103 -> {
                        // Buffered (audio-only) AirPlay 2 — accepted + instrumented, but the macOS
                        // Music stream stays FairPlay-encrypted (undecryptable), so playback is not
                        // wired. Stream fields (codec ct, audioFormat, shk/shiv, latencies) logged for ref.
                        Logger.d("buffered audio stream type=103 dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                        })
                        if (!audioEnabled) {
                            Logger.i("buffered audio (type=103) ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val dataPort = onBufferedAudioStart()
                        activeStreamTypes.add(103)
                        Logger.i("buffered audio stream type=103 dataPort=$dataPort")
                        mapOf("type" to 103L, "dataPort" to dataPort.toLong())
                    }
                    else -> {
                        Logger.i("mirror SETUP stream dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; else -> v.toString() }
                        })
                        null
                    }
                }
            }
            response["streams"] = resStreams
        }

        RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encode(response),
            contentType = "application/x-apple-binary-plist",
            protocol = request.responseProtocol()
        )
    } catch (e: Exception) {
        Logger.e("mirror SETUP failed", e)
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }

    /** Handles OPTIONS — macOS asks what RTSP methods are supported. */
    open fun handleOptionsInternal(request: RtspRequest): RtspResponse {
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf(
                "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
            )
        )
    }

    /** Handles ANNOUNCE — macOS/iOS sends SDP describing codecs, ports, and encryption. */
    open fun handleAnnounceInternal(request: RtspRequest): RtspResponse {
        Logger.d("ANNOUNCE body (${request.body.length} bytes)")
        val parsed = SdpParser.parse(request.body)

        if (parsed == null) {
            Logger.e("ANNOUNCE: SDP parsing returned no usable session — rejecting")
            return RtspResponse(statusCode = 400, statusMessage = "Bad Request")
        }

        currentSession = parsed.copy(senderName = extractSenderName(request.headers["User-Agent"]))
        val s = currentSession!!
        Logger.i("Session: hasVideo=${s.hasVideo} hasAudio=${s.hasAudio} " +
                 "codec=${s.audioCodec} encrypted=${s.isAudioEncrypted} sender='${s.senderName}'")

        setupCount = 0
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    private fun extractSenderName(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return DEFAULT_SENDER_NAME
        val name = userAgent.substringBefore("/").trim()
        return name.ifEmpty { DEFAULT_SENDER_NAME }
    }

    /** Handles SETUP — allocates a media channel. */
    open fun handleSetupInternal(request: RtspRequest): RtspResponse {
        setupCount++
        val session = currentSession

        val isVideoSetup = setupCount == 1 && session?.hasVideo == true

        val transport = if (isVideoSetup) {
            "RTP/AVP/TCP;unicast;interleaved=0-1"
        } else {
            "RTP/AVP/UDP;unicast;" +
            "client_port=$AUDIO_RTP_PORT-${AUDIO_RTP_PORT + 1};" +
            "server_port=$AUDIO_RTP_PORT-${AUDIO_RTP_PORT + 1};" +
            "timing-port=${TimingHandler.TIMING_PORT}"
        }

        Logger.d("SETUP #$setupCount — transport: $transport")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Session" to SESSION_ID, "Transport" to transport)
        )
    }

    /** Handles RECORD — macOS/iOS says start sending media now. */
    open fun handleRecordInternal(request: RtspRequest): RtspResponse {
        // AirPlay 2 mirroring has no ANNOUNCE/SDP — RECORD just acknowledges the session.
        if (isMirrorSession) {
            Logger.i("RECORD (mirror session) — OK")
            return RtspResponse(
                statusCode = 200, statusMessage = "OK",
                headers = mapOf("Audio-Latency" to "0"),
                protocol = request.responseProtocol()
            )
        }
        var session = currentSession
        if (session == null) {
            Logger.e("RECORD received but no session from ANNOUNCE — rejecting")
            return RtspResponse(statusCode = 455, statusMessage = "Method Not Valid in This State")
        }
        // RAOP audio (Apple Music) wraps the AES key with FairPlay (SDP `fpaeskey`). Unwrap it via the
        // fp-setup session into the real 16-byte key so the AudioPlayer can AES-CBC-decrypt the stream.
        val fpKey = session.fpAesKey
        if (fpKey != null && session.aesKey == null) {
            val realKey = runCatching { fairPlay?.decrypt(fpKey) }
                .onFailure { Logger.w("RAOP FairPlay audio-key decrypt failed (${fpKey.size}B): ${it.message}") }
                .getOrNull()
            if (realKey != null) {
                Logger.i("RAOP FairPlay (v0x%02x) audio key decrypted → ${realKey.size}B AES key, iv=${session.aesIv?.size ?: 0}B"
                    .format(fairPlay?.negotiatedVersion ?: 0))
                session = session.copy(aesKey = realKey)
                currentSession = session
            }
        }
        Logger.i("RECORD — streaming starting (audioOnly=${session.isAudioOnly}, encrypted=${session.isAudioEncrypted})")
        onStreamingStarted(session)
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles TEARDOWN. A TEARDOWN may target SPECIFIC streams (AirPlay 2 dynamic stream removal —
     * e.g. macOS drops the audio stream when playback stops) or the whole session. If the body lists
     * streams and they're audio-only, we stop just the audio and KEEP the mirror running; otherwise
     * we tear the whole session down. (Previously any TEARDOWN killed the mirror, so stopping audio
     * on the Mac ended screen mirroring entirely.)
     */
    open fun handleTeardownInternal(request: RtspRequest): RtspResponse {
        val streamTypes = parseTeardownStreamTypes(request.bodyBytes)
        if (streamTypes != null && streamTypes.isNotEmpty()) {
            // Stream-level teardown: stop ONLY the listed streams. Keep the session (keys, NTP,
            // event channel) alive so the remaining stream keeps running and a stopped one can be
            // re-added later — e.g. audio keeps playing with video gone, or video keeps mirroring
            // with audio stopped. But if this removes the LAST active stream (e.g. macOS names both
            // 96 and 110 to end the session), fall through to a full teardown so cleanup isn't left
            // to the eventual socket close.
            if (streamTypes.contains(96)) { onMirrorAudioStop(); activeStreamTypes.remove(96) }
            if (streamTypes.contains(110)) { onMirrorVideoStop(); activeStreamTypes.remove(110) }
            if (streamTypes.contains(103)) { onBufferedAudioStop(); activeStreamTypes.remove(103) }
            if (activeStreamTypes.isNotEmpty()) {
                Logger.i("TEARDOWN streams=$streamTypes — stopped those, session continues (active=$activeStreamTypes)")
                return RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
            }
            Logger.i("TEARDOWN streams=$streamTypes — last stream removed, ending session")
        } else {
            Logger.i("TEARDOWN (session, body=${request.bodyBytes.size}B) — streaming stopping")
        }
        activeStreamTypes.clear()
        onStreamingStopped()
        return RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
    }

    /** Parses the `streams` list from a TEARDOWN body, returning the stream `type`s, or null. */
    private fun parseTeardownStreamTypes(body: ByteArray): List<Int>? = runCatching {
        if (body.isEmpty()) return null
        val streams = PlistCodec.decode(body)["streams"] as? List<*> ?: return null
        streams.mapNotNull { ((it as? Map<*, *>)?.get("type") as? Long)?.toInt() }
    }.getOrNull()

    private fun handleGetParameter(request: RtspRequest): RtspResponse {
        val query = request.body.trim()
        Logger.i("GET_PARAMETER body='$query'")
        // macOS queries "volume" during setup and aborts if it gets no value back. Report the
        // last value the sender set so its volume slider reflects the receiver.
        return if (query.startsWith("volume")) {
            RtspResponse(
                statusCode = 200, statusMessage = "OK",
                body = "volume: %.6f\r\n".format(currentVolume),
                contentType = "text/parameters",
                protocol = request.responseProtocol()
            )
        } else {
            RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
        }
    }

    private fun handleSetParameter(request: RtspRequest): RtspResponse {
        val body = request.body
        val contentType = request.headers["Content-Type"]?.lowercase() ?: ""
        // Text bodies carry "volume: <dB>"; binary bodies carry DMAP now-playing metadata or artwork.
        when {
            body.startsWith("volume") -> {
                body.substringAfter(":").trim().toFloatOrNull()?.let { v ->
                    currentVolume = v
                    onVolume(v)
                    Logger.d("SET_PARAMETER volume=$v")
                }
            }
            contentType.startsWith("image/") -> {
                // Album artwork (image/jpeg, image/png). A zero-length body clears it.
                onArtwork(request.bodyBytes)
                Logger.i("SET_PARAMETER artwork (${request.bodyBytes.size}B, $contentType)")
            }
            contentType.contains("dmap") || looksLikeDmap(request.bodyBytes) -> {
                val meta = DmapParser.parseNowPlaying(request.bodyBytes)
                onNowPlayingMetadata(meta.title, meta.artist, meta.album)
                Logger.i("SET_PARAMETER now-playing: title='${meta.title}' artist='${meta.artist}' album='${meta.album}'")
            }
            else -> Logger.d("SET_PARAMETER (${request.bodyBytes.size}B, $contentType, unhandled)")
        }
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Heuristic: a DMAP body starts with the `mlit` listing-item container tag. */
    private fun looksLikeDmap(body: ByteArray): Boolean =
        body.size >= 8 && String(body, 0, 4, Charsets.US_ASCII) == "mlit"

    /** Handles any unrecognized RTSP method. */
    open fun handleUnknownInternal(request: RtspRequest): RtspResponse {
        Logger.w("Unknown/unhandled RTSP: ${request.method} ${request.uri} (${request.bodyBytes.size}B body)")
        return RtspResponse(statusCode = 501, statusMessage = "Not Implemented", protocol = request.responseProtocol())
    }

    /** Handles FLUSH — macOS requests we discard buffered media data (seek/pause). */
    private fun handleFlush(@Suppress("UNUSED_PARAMETER") request: RtspRequest): RtspResponse {
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Handles PAUSE — suspends media delivery. Responds 200 OK; resume arrives as RECORD. */
    open fun handlePauseInternal(request: RtspRequest): RtspResponse {
        Logger.d("PAUSE received")
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Handles AirPlay photo sharing: HTTP `PUT /photo` with a JPEG/PNG body. */
    open fun handlePhotoPutInternal(request: RtspRequest): RtspResponse {
        if (!request.isPhotoRequest()) {
            return handleUnknownInternal(request)
        }

        return when (val validation = PhotoHandler.validatePhoto(
            request.bodyBytes,
            request.headers["Content-Type"]
        )) {
            is PhotoValidation.Valid -> {
                onPhotoReceived(request.bodyBytes, validation.imageType)
                Logger.i("Photo received (${validation.imageType.mimeType}, ${request.bodyBytes.size} bytes)")
                RtspResponse(
                    statusCode = 200,
                    statusMessage = "OK",
                    protocol = request.responseProtocol()
                )
            }
            is PhotoValidation.Invalid -> {
                Logger.w("Photo rejected: ${validation.reason}")
                RtspResponse(
                    statusCode = 400,
                    statusMessage = "Bad Request",
                    protocol = request.responseProtocol()
                )
            }
        }
    }

    /** Handles AirPlay photo clearing: HTTP `DELETE /photo`. */
    open fun handlePhotoDeleteInternal(request: RtspRequest): RtspResponse {
        if (!request.isPhotoRequest()) {
            return handleUnknownInternal(request)
        }

        onPhotoCleared()
        Logger.i("Photo cleared")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            protocol = request.responseProtocol()
        )
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

        // SRP PIN access control. macOS's AirPlay code-entry field is exactly 4 digits, so the PIN
        // must be 4 digits to be enterable. A 4-digit space is low-entropy, so the load-bearing
        // defense is the MAX_PAIR_ATTEMPTS lockout below (uniform random + hard attempt cap, NOT
        // length). The PIN is still uniformly random — no biased truncation.
        private const val PIN_DIGITS = 4
        private const val PIN_SPACE = 10_000        // 10^PIN_DIGITS
        private const val MAX_PAIR_ATTEMPTS = 10
        private const val BIND_MAX_ATTEMPTS = 12      // ~3s total — covers a quick stop→start restart
        private const val BIND_RETRY_MS = 250L
        // iOS/iPadOS mirror-stream SETUP requests carry a large binary plist
        // (observed ~77 KB from iPhone17,1 on iOS 26) that exceeded the previous
        // 64 KB cap, so the reader rejected the video stream SETUP and the sender
        // tore the session down right after audio started. 1 MB gives ample head-
        // room for future senders while staying trivially safe on a LAN.
        private const val MAX_MESSAGE_BYTES = 1024 * 1024
        private const val OCTET_STREAM = "application/octet-stream"
        private const val TIMING_PORT = 6002   // matches TimingHandler's UDP NTP port
        private const val SESSION_ID = "PhairPlaySession"
        private const val AUDIO_RTP_PORT = 6001
        private const val DEFAULT_SENDER_NAME = "AirPlay Sender"
    }
}

private fun RtspRequest.isPhotoRequest(): Boolean =
    uri.substringBefore("?") == PhotoHandler.PHOTO_PATH

private fun RtspRequest.responseProtocol(): String =
    if (protocol.startsWith("HTTP/")) protocol else "RTSP/1.0"

/** True if the body is an Apple binary plist (AirPlay 2 mirroring SETUP), vs legacy SDP. */
private fun RtspRequest.isPlistBody(): Boolean =
    bodyBytes.size >= 8 && String(bodyBytes, 0, 8, Charsets.US_ASCII) == "bplist00"
