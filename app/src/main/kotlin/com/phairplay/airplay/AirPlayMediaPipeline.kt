package com.phairplay.airplay

import android.view.Surface
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * AirPlayMediaPipeline — Runs the classic AirPlay media pipeline of a streaming session.
 *
 * WHAT: Creates and releases the [VideoDecoder] (H.264 → Surface) and the [AudioPlayer]
 * (AES-128-CTR decrypt + AAC/ALAC → AudioTrack) and pumps the sender's audio RTP packets
 * from a UDP socket into the player.
 *
 * WHY: The media pipeline has a life of its own — it is started when RECORD arrives and torn
 * down when the session ends. Keeping it out of the orchestrator makes both readable: the
 * orchestrator decides *when* media runs, this class knows *how*.
 *
 * HOW: The owner hands over the coroutine scope and the (lazy) Surface provider and keeps a
 * reference to the [RtspHandler], whose NAL units are routed into the decoder:
 *   val pipeline = AirPlayMediaPipeline(scope, videoSurfaceProvider)
 *   pipeline.rtspHandler = rtspHandler
 *   pipeline.startVideoDecoder(session)
 *   pipeline.releaseDecoders()
 *
 * Example:
 *   pipeline.startAudioPlayer(session)   // opens the audio UDP receiver
 */
internal class AirPlayMediaPipeline(
    /** Scope of the owning receiver — the UDP receiver runs in it. */
    private val scope: CoroutineScope,
    /** Lazy Surface provider — called only for video streams when RECORD arrives. */
    private val videoSurfaceProvider: () -> Surface?
) {

    /**
     * The RTSP handler whose interleaved NAL units feed the video decoder.
     *
     * Set by the owner right after the handler was created — the pipeline wires
     * [RtspHandler.onVideoNalUnit] to the decoder and detaches it again on teardown.
     */
    internal var rtspHandler: RtspHandler? = null

    // Video decoder — created when RECORD arrives, released when the session ends.
    private var videoDecoder: VideoDecoder? = null

    // Audio player — created when RECORD arrives, released when the session ends.
    private var audioPlayer: AudioPlayer? = null

    // UDP socket for receiving audio RTP packets — opened after RECORD, closed on TEARDOWN
    @Volatile private var audioSocket: DatagramSocket? = null

    /**
     * Initializes [VideoDecoder] with SPS/PPS from the [SessionDescription].
     *
     * Resolution hint: AirPlay SDP does not include width/height — the actual
     * resolution is embedded in the SPS NAL unit. We pass [DEFAULT_VIDEO_WIDTH] ×
     * [DEFAULT_VIDEO_HEIGHT] as a hint; MediaCodec reads the real size from SPS.
     *
     * [RtspHandler.onVideoNalUnit] is wired here so RTP interleaved NAL units
     * flow directly into [VideoDecoder.decodeNalUnit].
     */
    internal fun startVideoDecoder(session: SessionDescription) {
        val surface = videoSurfaceProvider() ?: run {
            Logger.w("VideoDecoder: no surface available — skipping video pipeline")
            return
        }
        val sps = session.spsBytes ?: run {
            Logger.w("VideoDecoder: no SPS in SDP — skipping")
            return
        }
        val pps = session.ppsBytes ?: run {
            Logger.w("VideoDecoder: no PPS in SDP — skipping")
            return
        }

        videoDecoder = VideoDecoder(surface).also { decoder ->
            decoder.initialize(sps, pps, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT)
            rtspHandler?.onVideoNalUnit = { nalUnit, ptsUs ->
                decoder.decodeNalUnit(nalUnit, ptsUs)
            }
        }
        Logger.i("VideoDecoder started (${DEFAULT_VIDEO_WIDTH}x${DEFAULT_VIDEO_HEIGHT} hint)")
    }

    /**
     * Initializes [AudioPlayer] with codec and encryption params from [SessionDescription].
     *
     * When the SDP contains no AES key/IV (unencrypted or missing keys), null is passed —
     * [AudioPlayer.initialize] skips cipher setup entirely and writes audio payload directly.
     * This prevents a zero-key cipher from producing garbage audio (S6-4 fix).
     */
    internal fun startAudioPlayer(session: SessionDescription) {
        audioPlayer = AudioPlayer().also { player ->
            player.initialize(
                aesKey     = session.aesKey.takeIf { session.isAudioEncrypted },
                aesIv      = session.aesIv.takeIf  { session.isAudioEncrypted },
                sampleRate = session.sampleRate,
                channels   = session.channels,
                codec      = session.audioCodec,
                alacFramesPerPacket = session.alacFramesPerPacket
            )
        }
        Logger.i("AudioPlayer started (${session.sampleRate}Hz × ${session.channels}ch, " +
                 "codec=${session.audioCodec}, encrypted=${session.isAudioEncrypted})")

        startAudioUdpReceiver()
    }

    /**
     * Opens a UDP socket on [AUDIO_RTP_PORT] and feeds every received packet to
     * [AudioPlayer.playAudioPacket].
     *
     * WHY UDP: AirPlay audio is sent as RTP over UDP — low latency is more important
     * than guaranteed delivery. A missing packet produces a brief audio glitch,
     * which is far less disruptive than the buffering delays that TCP would introduce.
     *
     * The socket is closed in [closeAudioSocket] when streaming ends.
     */
    private fun startAudioUdpReceiver() {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(AUDIO_RTP_PORT)
                audioSocket = socket
                Logger.i("Audio UDP receiver listening on port $AUDIO_RTP_PORT")

                val buf    = ByteArray(MAX_AUDIO_PACKET_BYTES)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive) {
                    socket.receive(packet)
                    // copyOf trims to actual packet length before passing to the player
                    audioPlayer?.playAudioPacket(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                // SocketException thrown when audioSocket.close() is called — expected
                if (audioSocket != null) {
                    Logger.e("Audio UDP receiver error (unexpected)", e)
                } else {
                    Logger.d("Audio socket closed (expected during shutdown)")
                }
            }
        }
    }

    // ─── Teardown ────────────────────────────────────────────────────────────

    /** Detaches the NAL callback from the RTSP handler — no further frames reach the decoder. */
    internal fun stopVideoFeed() {
        rtspHandler?.onVideoNalUnit = null
    }

    /** Closes the UDP socket that receives the audio RTP packets. */
    internal fun closeAudioSocket() {
        try { audioSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        audioSocket = null
    }

    /** Releases the video decoder and the audio player (MediaCodec / AudioTrack resources). */
    internal fun releaseDecoders() {
        videoDecoder?.release()
        videoDecoder = null
        audioPlayer?.release()
        audioPlayer = null
    }

    companion object {
        // Hint dimensions for MediaCodec configuration.
        // Real resolution is encoded in the H.264 SPS NAL unit.
        private const val DEFAULT_VIDEO_WIDTH  = 1920
        private const val DEFAULT_VIDEO_HEIGHT = 1080

        /**
         * UDP port for receiving audio RTP packets.
         * Advertised in the RTSP SETUP response so the sender knows where to send audio.
         * Must not conflict with the RTSP port (7000) or timing port ([TimingHandler.TIMING_PORT]).
         */
        internal const val AUDIO_RTP_PORT = 6001

        /**
         * Maximum UDP audio packet size in bytes.
         * ALAC frames are typically ≤ 8 KB. 16 KB is a safe upper bound.
         */
        private const val MAX_AUDIO_PACKET_BYTES = 16 * 1024
    }
}
