package com.phairplay.airplay.handshake

import android.media.AudioManager
import com.phairplay.airplay.StreamStats
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.util.concurrent.ArrayBlockingQueue

/**
 * AudioStreamServer — receives and plays the AirPlay mirroring/realtime audio stream (type 96).
 *
 * macOS sends AES-128-CBC-encrypted AAC-ELD audio as RTP/UDP. We decrypt each packet (whole
 * 16-byte blocks; the trailing partial block is cleartext — the RAOP scheme), decode AAC-ELD via
 * MediaCodec, and play the PCM through AudioTrack.
 *
 * Architecture — the receiver and the player run on SEPARATE threads, decoupled by a bounded
 * queue (same pattern as [MirrorStreamServer] for video):
 *
 *   • Receive thread: socket.receive → dedup by RTP sequence → enqueue. Never blocks on playback,
 *     so the UDP socket is always drained promptly. (A blocking AudioTrack.write on the receive
 *     thread stalls the socket drain, which destabilises the whole mirror session.)
 *   • Playback thread: dequeue → decrypt → decode → AudioTrack.write(BLOCKING). Blocking here only
 *     paces playback to the audio clock and drops no PCM; it cannot stall the network.
 *
 * The three parts live in three focused classes:
 *   • this class          — the sockets, the three threads and the public API of the server
 *   • [AudioReorderBuffer] — duplicate suppression, reordering and packet-loss retransmit
 *   • [AudioPlaybackEngine] — decryption, decoding and the AudioTrack
 *
 * Reference: RPiPlay lib/raop_rtp.c + lib/raop_buffer.c (audio key = SHA-512(aesKey‖ecdh)[:16],
 * IV = SETUP eiv, AES-128-CBC per packet).
 */
class AudioStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    aesIv: ByteArray,
    private val sampleRate: Int,
    private val channels: Int,
    private val codecType: Int = CT_AAC_ELD,   // SETUP ct: 8 = AAC-ELD (mirror), 4 = AAC-LC (audio-only)
    framesPerPacket: Int = DEFAULT_ALAC_FRAMES,   // SETUP spf — ALAC frameLength (352)
) {

    // Bind to the IPv6 wildcard (dual-stack) — macOS sends the audio RTP over the session's
    // IPv6 link-local address; a default DatagramSocket binds IPv4-only and never receives it.
    private val socket = ipv6Socket()
    private val controlSocket = ipv6Socket()   // realtime-audio control channel (drained)

    @Volatile private var running = false

    // Decoded-audio jitter buffer: raw (post-dedup) RTP payloads handed from the receive thread to
    // the playback thread. Bounded so a stalled player can't grow latency unboundedly — if it fills
    // we drop the oldest frame (a brief glitch is better than ever-growing audio lag).
    private val frameQueue = ArrayBlockingQueue<ByteArray>(AUDIO_QUEUE_CAPACITY)

    /**
     * Sends one RAOP control datagram over the control socket.
     *
     * Used by the [AudioReorderBuffer] to ask the sender to resend lost audio packets.
     */
    private inner class SocketControlChannel : RaopControlChannel {
        override fun send(payload: ByteArray, address: SocketAddress) {
            runCatching { controlSocket.send(DatagramPacket(payload, payload.size, address)) }
        }
    }

    // RTP ordering, duplicate suppression and packet-loss retransmit.
    private val reorderBuffer = AudioReorderBuffer(frameQueue, SocketControlChannel())

    // Decoder + AudioTrack + crypto — owned and released by the playback thread alone.
    private val playback = AudioPlaybackEngine(
        aesKey = aesKey,
        ecdhSecret = ecdhSecret,
        aesIv = aesIv,
        sampleRate = sampleRate,
        channels = channels,
        codecType = codecType,
        framesPerPacket = framesPerPacket,
        frameQueue = frameQueue,
        isRunning = { running }
    )

    /** UDP port macOS sends the audio RTP stream to (returned in the SETUP response). */
    val dataPort: Int get() = socket.localPort

    /** UDP control port (returned in the SETUP response; macOS won't send audio without it). */
    val controlPort: Int get() = controlSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        StreamStats.audioActive = true
        scope.launch(Dispatchers.IO) { playback.runPlayback() }   // decode + play (may block on AudioTrack)
        scope.launch(Dispatchers.IO) { runReceive() }    // drain socket fast (never blocks on audio)
        scope.launch(Dispatchers.IO) { runControl() }    // capture sender addr + handle resend replies
    }

    /**
     * Control channel: the sender posts periodic timing/sync packets here (RTP type 0x54, marker →
     * 0xD4), and — after we ask — resent audio packets (RTP type 0x56 → 0xD6). We learn the sender's
     * control address from whatever arrives (resend requests go back to it) and splice any resent
     * audio packet back into the reorder buffer.
     */
    private fun runControl() {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        var ctrlCount = 0
        try {
            while (running) {
                pkt.length = buf.size     // reset capacity before each receive (see runReceive)
                controlSocket.receive(pkt)
                reorderBuffer.senderCtrlAddr = pkt.socketAddress   // where to send resend requests
                if (ctrlCount < 6) {
                    Logger.i("Audio CTRL[$ctrlCount] ${pkt.length}B: ${hex(pkt.data, minOf(20, pkt.length))}")
                    ctrlCount++
                }
                // RTP payload type is bits 0–6 of byte 1 (byte 1 = marker<<7 | type).
                val payloadType = pkt.data[1].toInt() and 0x7F
                if (payloadType == RTP_TYPE_RESEND_REPLY &&
                    pkt.length > RESEND_REPLY_HEADER + AudioReorderBuffer.RTP_HEADER) {
                    // bytes [4..] are the original audio RTP packet — feed it through the normal path.
                    reorderBuffer.resendFillCount++
                    reorderBuffer.handleRtpPacket(pkt.data, RESEND_REPLY_HEADER, pkt.length - RESEND_REPLY_HEADER)
                }
            }
        } catch (_: Exception) { /* closed */ }
    }

    fun stop() {
        running = false
        StreamStats.audioActive = false
        runCatching { socket.close() }
        runCatching { controlSocket.close() }
        frameQueue.clear()
        reorderBuffer.clear()
        // NOTE: codec + audioTrack are deliberately NOT released here. They are owned and released
        // exclusively by the playback thread (see AudioPlaybackEngine.runPlayback's finally).
        // Releasing MediaCodec from this thread races decodeFrame on the playback thread and crashes
        // the whole process with a native SIGABRT ("pthread_mutex_destroy called on a destroyed
        // mutex" inside libstagefright). Flipping `running` makes the playback loop exit within one
        // poll timeout and clean up safely.
    }

    /** Receive thread: pull RTP packets off the data socket and feed them to the reorder buffer. */
    private fun runReceive() {
        try {
            Logger.i("AudioStreamServer listening on UDP $dataPort (ct=$codecType ${sampleRate}Hz x$channels)")
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            var rtpCount = 0
            var recv = 0
            while (running) {
                packet.length = buf.size      // reset capacity — receive() shrinks length to the last datagram
                socket.receive(packet)
                recv++
                if (rtpCount < 6) {
                    Logger.i("Audio RTP[$rtpCount] ${packet.length}B hdr: ${hex(packet.data, minOf(20, packet.length))}")
                    rtpCount++
                }
                reorderBuffer.handleRtpPacket(packet.data, 0, packet.length)
                StreamStats.audioQueue = frameQueue.size
                if (recv % 500 == 0) {
                    val dup = reorderBuffer.dupCount
                    StreamStats.audioDupPct = dup * 100 / (recv + dup)
                    Logger.i("Audio stats: recv=$recv dup=$dup (${StreamStats.audioDupPct}% dup) " +
                        "qDrop=${reorderBuffer.qDropCount} resendReq=${reorderBuffer.resendReqCount} " +
                        "resendFill=${reorderBuffer.resendFillCount} queue=${frameQueue.size}")
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Audio stream error", e)
        }
    }

    /** Sets playback volume from the sender's AirPlay volume (−30 dB … 0 dB, or ≤ −144 = mute). */
    fun setVolume(airplayVolume: Float) {
        playback.setVolume(airplayVolume)
    }

    companion object {
        const val CT_ALAC = 2      // SETUP ct for ALAC (system-audio AirPlay; decoded in software)
        const val CT_AAC_LC = 4    // SETUP ct for AAC-LC (audio-only / Apple Music)
        const val CT_AAC_ELD = 8   // SETUP ct for AAC-ELD (screen-mirroring realtime audio)

        // ALAC frameLength macOS uses for realtime system-audio AirPlay (SETUP spf). Used to size
        // the ALAC magic cookie + decode buffers when the sender omits spf.
        private const val DEFAULT_ALAC_FRAMES = 352

        // RAOP control-channel RTP payload types for packet-loss recovery.
        private const val RTP_TYPE_RESEND_REPLY = 0x56     // sender → us: a resent audio packet
        private const val RESEND_REPLY_HEADER = 4          // 4-byte resend header before the embedded RTP

        // Jitter buffer depth between the receive and playback threads (~1 s at 92 frames/s).
        private const val AUDIO_QUEUE_CAPACITY = 96

        private fun hex(b: ByteArray, len: Int): String =
            (0 until minOf(len, b.size)).joinToString(" ") { "%02x".format(b[it]) }

        /** A UDP socket bound to the IPv6 wildcard (dual-stack), OS-assigned port. */
        private fun ipv6Socket(): DatagramSocket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("::"), 0))
        }

        @Suppress("unused")
        private val AUDIO_MANAGER_HINT = AudioManager.STREAM_MUSIC

        /**
         * Builds the AAC-ELD AudioSpecificConfig (csd-0) for the negotiated [sampleRate] and
         * [channels], instead of hardcoding 44.1 kHz/stereo. Layout: AOT escape(5)=31 + ext(6)=7
         * (AOT 39 = ELD), samplingFrequencyIndex(4), channelConfiguration(4), then the fixed
         * ELDSpecificConfig tail (frameLengthFlag=1 for 480 samples; resilience/SBR flags 0;
         * ELDEXT_TERM). For 44.1 kHz stereo this yields the canonical bytes F8 E8 50 00.
         */
        /** ISO 14496-3 sampling-frequency index for an AAC AudioSpecificConfig. */
        private fun freqIndexFor(sampleRate: Int): Int = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; 7350 -> 12
            else -> 4   // default to 44.1 kHz
        }

        /**
         * Builds the AAC-LC AudioSpecificConfig (csd-0): AOT(5)=2 (LC), samplingFrequencyIndex(4),
         * channelConfiguration(4), GASpecificConfig flags(3)=0. For 44.1 kHz stereo → bytes 12 10.
         */
        fun buildAacLcAsc(sampleRate: Int, channels: Int): ByteArray {
            val freqIndex = freqIndexFor(sampleRate)
            var bits = 0
            var n = 0
            fun put(value: Int, width: Int) { bits = (bits shl width) or (value and ((1 shl width) - 1)); n += width }
            put(2, 5); put(freqIndex, 4); put(channels, 4); put(0, 3)   // 16 bits total
            bits = bits shl (16 - n)
            return byteArrayOf((bits ushr 8).toByte(), bits.toByte())
        }

        fun buildAacEldAsc(sampleRate: Int, channels: Int): ByteArray {
            val freqIndex = freqIndexFor(sampleRate)
            var bits = 0L
            var n = 0
            fun put(value: Int, width: Int) {
                bits = (bits shl width) or (value.toLong() and ((1L shl width) - 1))
                n += width
            }
            put(31, 5); put(7, 6)                 // AOT escape → 39 (ELD)
            put(freqIndex, 4); put(channels, 4)
            put(1, 1); put(0, 4); put(0, 4)       // frameLengthFlag=1, resilience/SBR=0, ELDEXT_TERM=0
            bits = bits shl (32 - n)              // left-align into 4 bytes
            return byteArrayOf(
                (bits ushr 24).toByte(),
                (bits ushr 16).toByte(),
                (bits ushr 8).toByte(),
                bits.toByte()
            )
        }
    }
}
