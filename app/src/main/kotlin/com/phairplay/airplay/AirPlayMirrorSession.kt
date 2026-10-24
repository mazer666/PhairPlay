package com.phairplay.airplay

import android.view.Surface
import com.phairplay.airplay.handshake.AirPlayNtpClient
import com.phairplay.airplay.handshake.AudioStreamServer
import com.phairplay.airplay.handshake.BufferedAudioServer
import com.phairplay.airplay.handshake.MirrorStreamServer
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket

/**
 * AirPlayMirrorSession — Runs the AirPlay 2 mirroring streams of a session.
 *
 * WHAT: Owns the AirPlay 2 building blocks — the decrypted FairPlay/ECDH keys, the event
 * channel, the NTP client, the H.264 mirror data server, the mirror audio server and the
 * buffered audio-only server (type 103).
 *
 * WHY: AirPlay 2 mirroring is a protocol of its own: it needs its own keys, its own sockets
 * and its own lifecycle (a mirror session can add and drop single streams while it stays
 * alive). Bundling that in one object keeps the teardown of a mirroring session in one place.
 *
 * HOW: The owner hands over the scope, the Surface provider, the mirror resolution, the shared
 * now-playing state and the callbacks that report a session to the UI:
 *   val mirror = AirPlayMirrorSession(scope, videoSurfaceProvider, 1920, 1080, nowPlaying,
 *                                     onSenderNameChanged, onVideoSessionChanged, onStateChanged)
 *   mirror.startMirrorKeys(aesKey, ecdhSecret, aesIv, remoteAddress, timingPort)
 *
 * Example:
 *   val (eventPort, timingPort) = mirror.startMirrorKeys(key, secret, iv, addr, port)
 */
internal class AirPlayMirrorSession(
    /** Scope of the owning receiver — the event channel and the servers run in it. */
    private val scope: CoroutineScope,
    /** Lazy Surface provider — used by the mirror data server to render onto the Surface. */
    private val videoSurfaceProvider: () -> Surface?,
    /** Advertised mirroring width (from the "high resolution" setting). */
    private val mirrorWidth: Int,
    /** Advertised mirroring height (from the "high resolution" setting). */
    private val mirrorHeight: Int,
    /** Shared now-playing state — the mirror streams report their kind into it. */
    private val nowPlaying: NowPlayingState,
    /** Reports the sender name to the UI (called before the state change). */
    private val onSenderNameChanged: (String) -> Unit,
    /** Reports whether the session carries video (an audio-only session must not steal the screen). */
    private val onVideoSessionChanged: (hasVideo: Boolean) -> Unit,
    /** Publishes the protocol state of the session. */
    private val onStateChanged: (ProtocolState) -> Unit
) {

    // AirPlay 2 mirroring: data stream server + event channel + keys (set during SETUP).
    @Volatile private var mirrorServer: MirrorStreamServer? = null
    @Volatile private var audioServer: AudioStreamServer? = null
    @Volatile private var bufferedAudioServer: BufferedAudioServer? = null
    @Volatile private var ntpClient: AirPlayNtpClient? = null
    @Volatile private var eventSocket: ServerSocket? = null
    @Volatile private var eventClientSocket: java.net.Socket? = null
    @Volatile private var mirrorAesKey: ByteArray? = null
    @Volatile private var mirrorEcdhSecret: ByteArray? = null
    @Volatile private var mirrorAesIv: ByteArray? = null

    /**
     * Mirror SETUP msg 1: stash the decrypted AES key + pairing secret, open the event
     * channel (macOS connects to it), and switch the UI to the streaming surface.
     * @return the event channel's TCP port.
     */
    internal fun startMirrorKeys(
        aesKey: ByteArray,
        ecdhSecret: ByteArray,
        aesIv: ByteArray,
        remoteAddress: java.net.InetAddress,
        senderTimingPort: Int,
    ): Pair<Int, Int> {
        mirrorAesKey = aesKey
        mirrorEcdhSecret = ecdhSecret
        mirrorAesIv = aesIv
        // Explicit IPv4 wildcard — see RtspHandler.bindRtspSocket() for why.
        val event = ServerSocket(0, 50, java.net.InetAddress.getByName("0.0.0.0"))
        eventSocket = event
        // Accept + drain the event connection. We don't act on events yet, but macOS expects
        // the advertised event port to be connectable, so keep it open and readable.
        scope.launch(Dispatchers.IO) {
            try {
                event.accept().use { s ->
                    eventClientSocket = s
                    Logger.i("Event channel: macOS connected from ${s.inetAddress.hostAddress}")
                    val buf = ByteArray(4096)
                    val input = s.getInputStream()
                    while (isActive && input.read(buf) != -1) { /* drain */ }
                }
            } catch (e: Exception) {
                if (eventSocket != null) Logger.d("Event channel closed")
            } finally {
                eventClientSocket = null
            }
        }
        // AirPlay 2 NTP is receiver-initiated: poll the sender's timing port so macOS proceeds.
        val ntp = AirPlayNtpClient(remoteAddress, senderTimingPort).also { ntpClient = it; it.start(scope) }
        onSenderNameChanged("AirPlay")
        onVideoSessionChanged(true)   // screen mirroring always needs the Surface
        onStateChanged(ProtocolState.CONNECTED)
        Logger.i("Mirror keys set; eventPort=${event.localPort} timingPort=${ntp.localPort}")
        return event.localPort to ntp.localPort
    }

    /**
     * Mirror SETUP msg 2: start the data-stream server for the requested stream.
     * @return the data server's TCP port (macOS connects here to send H.264).
     */
    internal fun startMirrorStream(streamConnectionId: Long): Int {
        val aesKey = mirrorAesKey ?: run { Logger.e("mirror stream start before keys set"); return 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0
        return MirrorStreamServer(aesKey, ecdhSecret, streamConnectionId, videoSurfaceProvider, mirrorWidth, mirrorHeight)
            .also { mirrorServer = it; it.start(scope); nowPlaying.videoPlaying = true; nowPlaying.emitNowPlaying() }
            .dataPort
            .also { Logger.i("Mirror data server started on port $it") }
    }

    /** Mirror SETUP audio stream (type 96): start the AAC-ELD / AAC-LC / ALAC audio server. @return (dataPort, controlPort). */
    internal fun startMirrorAudio(sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int): Pair<Int, Int> {
        val aesKey = mirrorAesKey ?: run { Logger.e("audio start before keys set"); return 0 to 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0 to 0
        val aesIv = mirrorAesIv ?: return 0 to 0
        val server = AudioStreamServer(aesKey, ecdhSecret, aesIv, sampleRate, channels, codecType, framesPerPacket)
            .also { audioServer = it; it.start(scope) }
        nowPlaying.audioPlaying = true
        nowPlaying.emitNowPlaying()
        Logger.i("Mirror audio server started: dataPort=${server.dataPort} controlPort=${server.controlPort}")
        return server.dataPort to server.controlPort
    }

    /** Stops ONLY the mirror audio stream (macOS dynamic-stream TEARDOWN) — video keeps running. */
    internal fun stopMirrorAudio() {
        audioServer?.stop()
        audioServer = null
        nowPlaying.audioPlaying = false
        nowPlaying.clearNowPlayingMetadata()
        nowPlaying.emitNowPlaying()
        Logger.i("Mirror audio stream stopped (video mirroring continues)")
    }

    /** Stops ONLY the mirror video stream (macOS dynamic-stream TEARDOWN) — audio keeps playing. */
    internal fun stopMirrorVideo() {
        mirrorServer?.stop()
        mirrorServer = null
        nowPlaying.videoPlaying = false
        nowPlaying.emitNowPlaying()   // audio may still be playing → now-playing card can take over
        Logger.i("Mirror video stream stopped (audio playback continues)")
    }

    /** Starts the AirPlay 2 buffered audio-only stream (type 103, Apple Music → TV); returns its TCP port. */
    internal fun startBufferedAudio(): Int {
        bufferedAudioServer?.stop()
        val server = BufferedAudioServer().also { bufferedAudioServer = it; it.start(scope) }
        nowPlaying.audioPlaying = true   // buffered audio (type 103) is always audio-only
        nowPlaying.emitNowPlaying()
        Logger.i("Buffered audio server started: dataPort=${server.dataPort}")
        return server.dataPort
    }

    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    internal fun stopBufferedAudio() {
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        nowPlaying.audioPlaying = false
        nowPlaying.clearNowPlayingMetadata()
        nowPlaying.emitNowPlaying()
        Logger.i("Buffered audio stream stopped")
    }

    /** Applies the volume the sender set (AirPlay dB) to the mirror audio server. */
    internal fun setVolume(volume: Float) {
        audioServer?.setVolume(volume)
    }

    // ─── Teardown ────────────────────────────────────────────────────────────

    /** Stops the mirror data server, the mirror audio server and the buffered audio server. */
    internal fun releaseStreams() {
        mirrorServer?.stop()
        mirrorServer = null
        audioServer?.stop()
        audioServer = null
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
    }

    /**
     * Releases the session-wide mirror resources: the NTP client, the event channel and the
     * FairPlay/ECDH keys.
     *
     * Clear the FairPlay/ECDH keys on FULL teardown only. This method runs on a genuine session
     * end (last-stream / session TEARDOWN, or control-connection close) — NOT on a per-stream
     * teardown, which goes through stopMirrorAudio/stopMirrorVideo and leaves the keys intact so
     * macOS can re-add a dynamic stream on the same live session without re-sending keys (that
     * dynamic-readd path is why the keys must survive a stream stop). Clearing here prevents a
     * brand-new control connection from reusing a previous session's stale keys.
     */
    internal fun releaseSession() {
        ntpClient?.stop()
        ntpClient = null
        try { eventClientSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        eventClientSocket = null
        try { eventSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        eventSocket = null
        mirrorAesKey = null
        mirrorEcdhSecret = null
        mirrorAesIv = null
    }
}
