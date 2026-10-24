package com.phairplay.airplay

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * RtspHandler — the public entry point of the AirPlay RTSP control protocol.
 *
 * WHAT: Starts and stops the RTSP control socket and forwards every RTSP verb to the class that
 * implements it — the classic RTSP verbs, the AirPlay 2 pairing, the mirroring SETUP, the media
 * parameters and the URL-video mode.
 *
 * WHY: AirPlay uses RTSP to negotiate codecs, ports and encryption before media flows, and that
 * protocol is large: classic verbs, the AirPlay 2 handshake, mirroring, parameters and URL mode in
 * a single class produced a 1000+ line file. This class stays the one stable API that
 * [AirPlayReceiver] talks to, while the protocol details live in small classes that each own
 * exactly one responsibility.
 *
 * Example:
 *   val handler = RtspHandler(context, onStreamingStarted = { s -> start(s) }, onStreamingStopped = { stop() })
 *   handler.start(scope)                              // listens for senders on port 7000
 *   handler.onVideoNalUnit = { nal, pts -> decode(nal, pts) }
 *   handler.stop()                                    // closes the control socket
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

    /** The mutable state of the current control connection (session, FairPlay, pairing, mirror). */
    private val sessionState = RtspSessionState()

    /** The classic RTSP verbs: OPTIONS, ANNOUNCE, SETUP, RECORD, TEARDOWN, PAUSE and photo. */
    private val verbHandler: RtspVerbHandler by lazy {
        RtspVerbHandler(
            sessionState = sessionState,
            onStreamingStarted = onStreamingStarted,
            onStreamingStopped = onStreamingStopped,
            onPhotoReceived = onPhotoReceived,
            onPhotoCleared = onPhotoCleared,
            onMirrorAudioStop = onMirrorAudioStop,
            onMirrorVideoStop = onMirrorVideoStop,
            onBufferedAudioStop = onBufferedAudioStop
        )
    }

    /** The AirPlay 2 pairing endpoints: pair-setup, pair-verify and the legacy PIN flow. */
    private val pairingHandler: RtspPairingHandler by lazy {
        RtspPairingHandler(
            verbs = this,
            context = context,
            sessionState = sessionState,
            pinAuthEnabled = pinAuthEnabled,
            pairingStore = pairingStore,
            onShowPin = onShowPin
        )
    }

    /** The AirPlay 2 mirroring endpoints: the binary-plist SETUP and the FairPlay fp-setup. */
    private val mirrorHandler: RtspMirrorHandler by lazy {
        RtspMirrorHandler(
            sessionState = sessionState,
            audioEnabled = audioEnabled,
            onMirrorSetupKeys = onMirrorSetupKeys,
            onMirrorStreamStart = onMirrorStreamStart,
            onMirrorAudioStart = onMirrorAudioStart,
            onBufferedAudioStart = onBufferedAudioStart
        )
    }

    /** The media parameters: volume, DMAP now-playing metadata and album artwork. */
    private val parameterHandler: RtspParameterHandler by lazy {
        RtspParameterHandler(
            onVolume = onVolume,
            onNowPlayingMetadata = onNowPlayingMetadata,
            onArtwork = onArtwork
        )
    }

    /** The AirPlay URL-video mode: /play, /rate, /scrub, /stop and /playback-info. */
    private val videoUrlHandler: RtspVideoUrlHandler by lazy {
        RtspVideoUrlHandler(
            onVideoPlay = onVideoPlay,
            onVideoRate = onVideoRate,
            onVideoScrub = onVideoScrub,
            onVideoStop = onVideoStop,
            onPlaybackInfo = onPlaybackInfo
        )
    }

    /** The receiver identity and session acknowledgement endpoints. */
    private val deviceInfoHandler: RtspDeviceInfoHandler by lazy {
        RtspDeviceInfoHandler(
            context = context,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            pinAuthEnabled = pinAuthEnabled
        )
    }

    /** Maps an incoming request to the handler that answers it. */
    private val router: RtspRequestRouter by lazy {
        RtspRequestRouter(
            verbs = this,
            mirrorHandler = mirrorHandler,
            pairingHandler = pairingHandler,
            parameterHandler = parameterHandler,
            videoUrlHandler = videoUrlHandler,
            deviceInfoHandler = deviceInfoHandler,
            onRemoteControlInfo = onRemoteControlInfo
        )
    }

    /** The TCP control socket: accept loop, request framing and response serialization. */
    private val server: RtspServer by lazy {
        RtspServer(
            context = context,
            sessionState = sessionState,
            router = router,
            onStreamingStopped = onStreamingStopped
        )
    }

    // ─── Transport state (owned by [RtspServer]) ────────────────────────────

    /** True while the RTSP server accepts senders. */
    private var running: Boolean
        get() = server.running
        set(value) { server.running = value }

    /** The socket of the sender that is currently connected. */
    private var activeClient: java.net.Socket?
        get() = server.activeClient
        set(value) { server.activeClient = value }

    /** The listening socket of the RTSP server. */
    private var serverSocket: java.net.ServerSocket?
        get() = server.serverSocket
        set(value) { server.serverSocket = value }

    /**
     * Callback for decoded H.264 NAL units from the RTP stream.
     * Set by [AirPlayReceiver] after RECORD — wires to [VideoDecoder.decodeNalUnit].
     * Null for audio-only streams.
     */
    var onVideoNalUnit: ((nalUnit: ByteArray, ptsUs: Long) -> Unit)?
        get() = server.onVideoNalUnit
        set(value) { server.onVideoNalUnit = value }

    /** Mirror stream types currently active (96 = audio, 110 = video). Drives TEARDOWN routing.
     *  `protected` so tests can seed it without driving the full FairPlay SETUP handshake. */
    protected val activeStreamTypes: MutableSet<Int>
        get() = sessionState.activeStreamTypes

    /** Starts the RTSP server. */
    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) {
            server.runServer(this)
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

    // ─── Classic RTSP verbs (implemented by [RtspVerbHandler]) ──────────────

    /** Handles OPTIONS — macOS asks what RTSP methods are supported. */
    open fun handleOptionsInternal(request: RtspRequest): RtspResponse =
        verbHandler.handleOptionsInternal(request)

    /** Handles ANNOUNCE — macOS/iOS sends SDP describing codecs, ports, and encryption. */
    open fun handleAnnounceInternal(request: RtspRequest): RtspResponse =
        verbHandler.handleAnnounceInternal(request)

    /** Handles SETUP — allocates a media channel. */
    open fun handleSetupInternal(request: RtspRequest): RtspResponse =
        verbHandler.handleSetupInternal(request)

    /** Handles RECORD — macOS/iOS says start sending media now. */
    open fun handleRecordInternal(request: RtspRequest): RtspResponse =
        verbHandler.handleRecordInternal(request)

    /** Handles TEARDOWN — stops one or all streams of the session. */
    open fun handleTeardownInternal(request: RtspRequest): RtspResponse =
        verbHandler.handleTeardownInternal(request)

    /** Handles any unrecognized RTSP method. */
    open fun handleUnknownInternal(request: RtspRequest): RtspResponse =
        verbHandler.handleUnknownInternal(request)

    /** Handles PAUSE — suspends media delivery. Responds 200 OK; resume arrives as RECORD. */
    open fun handlePauseInternal(request: RtspRequest): RtspResponse =
        verbHandler.handlePauseInternal(request)

    /** Handles AirPlay photo sharing: HTTP `PUT /photo` with a JPEG/PNG body. */
    open fun handlePhotoPutInternal(request: RtspRequest): RtspResponse =
        verbHandler.handlePhotoPutInternal(request)

    /** Handles AirPlay photo clearing: HTTP `DELETE /photo`. */
    open fun handlePhotoDeleteInternal(request: RtspRequest): RtspResponse =
        verbHandler.handlePhotoDeleteInternal(request)
}
