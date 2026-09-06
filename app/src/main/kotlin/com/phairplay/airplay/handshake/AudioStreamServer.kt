package com.phairplay.airplay.handshake

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import com.phairplay.airplay.StreamStats
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    private val framesPerPacket: Int = DEFAULT_ALAC_FRAMES,   // SETUP spf — ALAC frameLength (352)
) {
    private val key = SecretKeySpec(MirrorCrypto.audioKey(aesKey, ecdhSecret), "AES")
    private val iv = IvParameterSpec(aesIv.copyOf(16))

    // Playback gain (0..1), set from the sender's AirPlay volume. Applied to the AudioTrack and
    // re-applied if the track is recreated. Starts at full.
    @Volatile private var volumeGain = 1f

    // Reused across packets: decryptPacket runs only on the playback thread, so one Cipher
    // instance is safe and avoids a Cipher.getInstance allocation on every packet (~92/s).
    private val cbcCipher = Cipher.getInstance("AES/CBC/NoPadding")

    // Bind to the IPv6 wildcard (dual-stack) — macOS sends the audio RTP over the session's
    // IPv6 link-local address; a default DatagramSocket binds IPv4-only and never receives it.
    private val socket = ipv6Socket()
    private val controlSocket = ipv6Socket()   // realtime-audio control channel (drained)

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var alac: AlacDecoder? = null      // software ALAC decoder (ct=2 system-audio path)
    private var audioTrack: AudioTrack? = null
    private var firstPcm = true

    // Decoded-audio jitter buffer: raw (post-dedup) RTP payloads handed from the receive thread to
    // the playback thread. Bounded so a stalled player can't grow latency unboundedly — if it fills
    // we drop the oldest frame (a brief glitch is better than ever-growing audio lag).
    private val frameQueue = ArrayBlockingQueue<ByteArray>(AUDIO_QUEUE_CAPACITY)

    // RTP duplicate suppression. macOS sends each realtime-audio packet 2–3× for redundancy
    // (same 16-bit sequence number). Decoding every copy feeds the AAC decoder duplicate frames
    // and pushes 2–3× real-time data into AudioTrack — the buffer overflows, chunks get dropped,
    // and playback both glitches and lags video. We process each sequence number exactly once,
    // remembering a sliding window of recent seqs (well under the 65536 wrap and ~11 s deep).
    private val seenSeqs = java.util.ArrayDeque<Int>()
    private val seenSeqSet = HashSet<Int>()

    // ─── Reorder buffer + packet-loss retransmit ─────────────────────────────
    // macOS's `redundantAudio` (each packet sent 2–3×) covers most loss, but a burst that drops
    // all copies leaves a gap. We hold packets in a small seq-keyed reorder buffer and, on a gap,
    // ask the sender to resend the missing range (RAOP control type 0x55) — the resent packet comes
    // back on the control socket (type 0x56) and fills the hole. Common case (in-order) releases
    // immediately with ZERO added latency; only an actual gap briefly holds, bounded by
    // MAX_REORDER_HOLD, so A/V sync is preserved. Touched by both the data-receive and control
    // threads (resend replies), so all access is under [reorderLock].
    private val reorderLock = Any()
    private val sendLock = Any()                       // serialises control-socket resend sends
    private val reorder = HashMap<Int, ByteArray>()   // seq → decrypted-pending RTP payload
    private var nextSeq = -1                            // next seq to release in order (-1 = uninit)
    private var maxSeq = -1                             // highest seq seen (for gap detection)
    private var resendCtr = 0                           // sequence counter for our resend requests
    @Volatile private var senderCtrlAddr: java.net.SocketAddress? = null
    @Volatile private var dupCount = 0
    @Volatile private var qDropCount = 0
    @Volatile private var resendReqCount = 0
    @Volatile private var resendFillCount = 0

    /** UDP port macOS sends the audio RTP stream to (returned in the SETUP response). */
    val dataPort: Int get() = socket.localPort

    /** UDP control port (returned in the SETUP response; macOS won't send audio without it). */
    val controlPort: Int get() = controlSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        StreamStats.audioActive = true
        scope.launch(Dispatchers.IO) { runPlayback() }   // decode + play (may block on AudioTrack)
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
                senderCtrlAddr = pkt.socketAddress   // where to send resend requests
                if (ctrlCount < 6) {
                    Logger.i("Audio CTRL[$ctrlCount] ${pkt.length}B: ${hex(pkt.data, minOf(20, pkt.length))}")
                    ctrlCount++
                }
                // RTP payload type is bits 0–6 of byte 1 (byte 1 = marker<<7 | type).
                val payloadType = pkt.data[1].toInt() and 0x7F
                if (payloadType == RTP_TYPE_RESEND_REPLY && pkt.length > RESEND_REPLY_HEADER + RTP_HEADER) {
                    // bytes [4..] are the original audio RTP packet — feed it through the normal path.
                    resendFillCount++
                    handleRtpPacket(pkt.data, RESEND_REPLY_HEADER, pkt.length - RESEND_REPLY_HEADER)
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
        synchronized(reorderLock) { reorder.clear() }
        // NOTE: codec + audioTrack are deliberately NOT released here. They are owned and released
        // exclusively by the playback thread (see runPlayback's finally). Releasing MediaCodec from
        // this thread races decodeFrame on the playback thread and crashes the whole process with a
        // native SIGABRT ("pthread_mutex_destroy called on a destroyed mutex" inside libstagefright).
        // Flipping `running` makes the playback loop exit within one poll timeout and clean up safely.
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
                handleRtpPacket(packet.data, 0, packet.length)
                StreamStats.audioQueue = frameQueue.size
                if (recv % 500 == 0) {
                    StreamStats.audioDupPct = dupCount * 100 / (recv + dupCount)
                    Logger.i("Audio stats: recv=$recv dup=$dupCount (${StreamStats.audioDupPct}% dup) " +
                        "qDrop=$qDropCount resendReq=$resendReqCount resendFill=$resendFillCount queue=${frameQueue.size}")
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Audio stream error", e)
        }
    }

    /**
     * Parses one RTP audio packet (from the data socket or a resend reply) and routes it through the
     * reorder buffer. [src] may be a reused receive buffer, so the payload is copied out before any
     * cross-thread handoff. Thread-safe: the reorder buffer + dedup are accessed under [reorderLock].
     */
    private fun handleRtpPacket(src: ByteArray, offset: Int, length: Int) {
        if (length <= RTP_HEADER) return
        val seq = ((src[offset + 2].toInt() and 0xFF) shl 8) or (src[offset + 3].toInt() and 0xFF)
        // RAOP RTP: 12-byte header, then AES-128-CBC-encrypted audio payload (copied out of src).
        val payload = src.copyOfRange(offset + RTP_HEADER, offset + length)
        var resend: IntArray? = null
        synchronized(reorderLock) {
            if (isDuplicateSeq(seq)) { dupCount++; return }
            resend = enqueueInOrder(seq, payload)
        }
        // Send the resend request OUTSIDE the reorder lock — never hold it across socket I/O.
        resend?.let { requestResend(it[0], it[1]) }
    }

    /**
     * Inserts [seq]/[payload] into the reorder buffer and releases all now-contiguous packets to the
     * player in order. Returns the [startSeq, count] of a missing range to resend (or null). Under [reorderLock].
     */
    private fun enqueueInOrder(seq: Int, payload: ByteArray): IntArray? {
        if (nextSeq < 0) { nextSeq = seq; maxSeq = seq }      // first packet anchors the stream
        // Ignore packets older than what we've already released (a late resend we gave up on).
        if (seqDiff(seq, nextSeq) < 0) return null
        reorder[seq] = payload
        // New forward gap → ask the sender to resend the packets between the old high-water mark and here.
        val resend = if (maxSeq >= 0 && seqDiff(seq, maxSeq) > 1)
            intArrayOf((maxSeq + 1) and 0xFFFF, seqDiff(seq, maxSeq) - 1) else null
        if (seqDiff(seq, maxSeq) > 0) maxSeq = seq
        releaseContiguous()
        // If a hole stays unfilled and the buffer runs too far ahead, skip past it so playback never
        // stalls (a brief glitch beats indefinite silence).
        if (reorder.isNotEmpty() && seqDiff(maxSeq, nextSeq) > MAX_REORDER_HOLD) {
            while (seqDiff(maxSeq, nextSeq) > MAX_REORDER_HOLD && !reorder.containsKey(nextSeq)) {
                nextSeq = (nextSeq + 1) and 0xFFFF
            }
            releaseContiguous()
        }
        return resend
    }

    /** Hands every packet contiguous from [nextSeq] to the player, advancing [nextSeq]. Under [reorderLock]. */
    private fun releaseContiguous() {
        while (true) {
            val p = reorder.remove(nextSeq) ?: break
            if (!frameQueue.offer(p)) { frameQueue.poll(); frameQueue.offer(p); qDropCount++ }
            nextSeq = (nextSeq + 1) and 0xFFFF
        }
    }

    /**
     * Sends a RAOP resend request (control type 0x55) for [count] packets starting at [startSeq].
     * Guarded by [sendLock] (separate from [reorderLock]) so two receive threads don't send + bump
     * [resendCtr] concurrently. Runs outside the reorder lock — never blocks the decode path on I/O.
     */
    private fun requestResend(startSeq: Int, count: Int) {
        if (count <= 0 || count > MAX_RESEND_RANGE) return
        synchronized(sendLock) {
            val addr = senderCtrlAddr ?: return    // unknown until the sender's first control packet
            val req = ByteArray(8)
            req[0] = 0x80.toByte()
            req[1] = (RTP_TYPE_RESEND_REQUEST or 0x80).toByte()   // marker bit set, per RAOP
            req[2] = (resendCtr ushr 8).toByte(); req[3] = resendCtr.toByte()
            req[4] = (startSeq ushr 8).toByte();   req[5] = startSeq.toByte()
            req[6] = (count ushr 8).toByte();      req[7] = count.toByte()
            resendCtr = (resendCtr + 1) and 0xFFFF
            resendReqCount++
            runCatching { controlSocket.send(DatagramPacket(req, req.size, addr)) }
        }
    }

    /** Signed 16-bit sequence distance a − b in (−32768, 32767], so wraparound compares correctly. */
    private fun seqDiff(a: Int, b: Int): Int = (((a - b) and 0xFFFF) xor 0x8000) - 0x8000

    /**
     * Playback thread: decrypt + decode queued frames and write PCM to AudioTrack. This thread is
     * the SOLE owner of [codec] and [audioTrack] — it creates them here and releases them in the
     * finally block, so no other thread ever touches the codec concurrently (see [stop]).
     */
    private fun runPlayback() {
        // Realtime audio: a late wake-up on this thread drains the AudioTrack and is heard as a gap.
        val previousPriority = Process.getThreadPriority(Process.myTid())
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        try {
            initDecoder()
            initAudioTrack()
            awaitPreroll()
            audioTrack?.play()
            while (running) {
                val payload = frameQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    val decrypted = decryptPacket(payload)
                    if (alac != null) playAlacFrame(decrypted) else decodeFrame(decrypted)
                } catch (e: Exception) {
                    if (running) Logger.e("Audio: frame decode error", e)
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Audio playback error", e)
        } finally {
            // Release on the same thread that used the codec — never cross-thread (avoids SIGABRT).
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { alac?.close() }
            runCatching { audioTrack?.stop() }
            runCatching { audioTrack?.release() }
            codec = null
            alac = null
            audioTrack = null
            runCatching { Process.setThreadPriority(previousPriority) }
            Logger.i("AudioStreamServer stopped")
        }
    }

    /**
     * Holds playback until ~200 ms of frames are queued (or [PREROLL_MAX_WAIT_MS] passes). The sender
     * is realtime, so without this the receiver plays with zero lead and any scheduling hiccup or Wi-Fi
     * jitter burst underruns the AudioTrack (heard as choppy audio). Mirroring audio (AAC-ELD) skips the
     * pre-roll so it stays lined up with the immediately-rendered video.
     */
    private fun awaitPreroll() {
        val target = prerollFramesFor(codecType, sampleRate, framesPerPacket)
        if (target == 0) return
        val deadline = System.currentTimeMillis() + PREROLL_MAX_WAIT_MS
        while (running && frameQueue.size < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(PREROLL_POLL_MS)
        }
        Logger.i("Audio pre-roll: ${frameQueue.size}/$target frames queued before play()")
    }

    /** Decode one decrypted ALAC frame to PCM and write it to AudioTrack (blocking, paces playback). */
    private fun playAlacFrame(frame: ByteArray) {
        val pcm = alac?.decode(frame) ?: return
        if (firstPcm) { Logger.i("Audio: first decoded ALAC PCM (${pcm.size}B) → AudioTrack"); firstPcm = false }
        audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
    }

    /** True if this RTP sequence was already processed (a redundant retransmission). */
    private fun isDuplicateSeq(seq: Int): Boolean {
        if (!seenSeqSet.add(seq)) return true          // add() returns false when already present
        seenSeqs.addLast(seq)
        if (seenSeqs.size > SEQ_WINDOW) seenSeqSet.remove(seenSeqs.removeFirst())
        return false
    }

    /** AES-128-CBC decrypt the whole-block portion; the trailing < 16 bytes stay cleartext. */
    private fun decryptPacket(payload: ByteArray): ByteArray {
        val encryptedLen = (payload.size / 16) * 16
        if (encryptedLen == 0) return payload
        cbcCipher.init(Cipher.DECRYPT_MODE, key, iv)   // fresh IV per packet (RAOP)
        val out = payload.copyOf()
        cbcCipher.doFinal(payload, 0, encryptedLen, out, 0)
        return out
    }

    private fun decodeFrame(aac: ByteArray) {
        val mc = codec ?: return
        val inIdx = mc.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            mc.getInputBuffer(inIdx)!!.apply { clear(); put(aac) }
            mc.queueInputBuffer(inIdx, 0, aac.size, 0, 0)
        }
        val info = MediaCodec.BufferInfo()
        var outIdx = mc.dequeueOutputBuffer(info, 0)
        while (outIdx >= 0) {
            val outBuf: ByteBuffer = mc.getOutputBuffer(outIdx)!!
            val pcm = ByteArray(info.size)
            outBuf.position(info.offset); outBuf.get(pcm)
            if (firstPcm) { Logger.i("Audio: first decoded PCM (${pcm.size}B) → AudioTrack"); firstPcm = false }
            // Blocking write paces playback to the audio clock and drops no PCM. Safe here because
            // this runs on the dedicated playback thread, not the socket-receive thread.
            audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            mc.releaseOutputBuffer(outIdx, false)
            outIdx = mc.dequeueOutputBuffer(info, 0)
        }
    }

    private fun initDecoder() {
        if (codecType == CT_ALAC) {
            // macOS sends ALAC (lossless) for system-audio AirPlay regardless of our advertised
            // formats, and this TV has no hardware ALAC codec — so we decode in software via the
            // bundled Apple ALAC decoder (libalac.so). frameLength comes from the SETUP spf.
            alac = AlacDecoder(sampleRate, channels, framesPerPacket)
            Logger.i("Audio decoder: ALAC ${sampleRate}Hz x$channels spf=$framesPerPacket (ct=2)")
            return
        }
        // ct=8 AAC-ELD (mirroring, spf 480) vs ct=4 AAC-LC (audio-only / Apple Music, spf 1024).
        val isAacLc = codecType == CT_AAC_LC
        val profile = if (isAacLc) MediaCodecInfo.CodecProfileLevel.AACObjectLC
                      else MediaCodecInfo.CodecProfileLevel.AACObjectELD
        val asc = if (isAacLc) buildAacLcAsc(sampleRate, channels) else buildAacEldAsc(sampleRate, channels)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
            setByteBuffer("csd-0", ByteBuffer.wrap(asc))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
        Logger.i("Audio decoder: ${if (isAacLc) "AAC-LC" else "AAC-ELD"} ${sampleRate}Hz x$channels (ct=$codecType)")
    }

    /** Sets playback volume from the sender's AirPlay volume (−30 dB … 0 dB, or ≤ −144 = mute). */
    fun setVolume(airplayVolume: Float) {
        volumeGain = if (airplayVolume <= -144f) 0f else ((airplayVolume + 30f) / 30f).coerceIn(0f, 1f)
        runCatching { audioTrack?.setVolume(volumeGain) }
    }

    private fun initAudioTrack() {
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bytesPerSec = sampleRate * channels * 2
        Logger.i("AudioTrack: minBuf=${minBuf}B (~${minBuf * 1000 / bytesPerSec}ms), " +
            "buffer=${minBuf * 2}B (~${minBuf * 2 * 1000 / bytesPerSec}ms latency)")
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            // minBuf alone (~170 ms) underran on the Pi 4 under scheduling/Wi-Fi jitter. Capacity does
            // not add latency by itself — the fill level is set by how far ahead of playback the stream
            // is — so 2× gives headroom without hurting mirroring lip-sync. play() is called by
            // runPlayback after the (codec-dependent) pre-roll.
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setVolume(volumeGain) }
    }

    companion object {
        const val CT_ALAC = 2      // SETUP ct for ALAC (system-audio AirPlay; decoded in software)
        const val CT_AAC_LC = 4    // SETUP ct for AAC-LC (audio-only / Apple Music)
        const val CT_AAC_ELD = 8   // SETUP ct for AAC-ELD (screen-mirroring realtime audio)

        // ALAC frameLength macOS uses for realtime system-audio AirPlay (SETUP spf). Used to size
        // the ALAC magic cookie + decode buffers when the sender omits spf.
        private const val DEFAULT_ALAC_FRAMES = 352

        private const val RTP_HEADER = 12

        // RAOP control-channel RTP payload types for packet-loss recovery.
        private const val RTP_TYPE_RESEND_REQUEST = 0x55   // we → sender: "resend these seqs"
        private const val RTP_TYPE_RESEND_REPLY = 0x56     // sender → us: a resent audio packet
        private const val RESEND_REPLY_HEADER = 4          // 4-byte resend header before the embedded RTP

        // Max packets to hold while waiting for a gap to fill before skipping it (bounds the worst-case
        // added latency to ~this many frames; in-order traffic adds zero). ~32 ≈ 0.25–0.35 s.
        private const val MAX_REORDER_HOLD = 32

        // Don't ask for an absurd resend range (a huge gap = a real stall, not a few lost packets).
        private const val MAX_RESEND_RANGE = 128

        // Jitter buffer depth between the receive and playback threads (~1 s at 92 frames/s).
        private const val AUDIO_QUEUE_CAPACITY = 96

        // Pre-roll for audio-only streams: how far ahead of playback to get before play(), and how
        // long to wait for that before starting anyway (a slow sender must not stall playback).
        private const val PREROLL_MS = 200
        private const val PREROLL_MAX_WAIT_MS = 600L
        private const val PREROLL_POLL_MS = 5L

        /** Packets needed to hold [PREROLL_MS] of audio, rounded up so the lead is at least that long. */
        internal fun prerollFrameCount(sampleRate: Int, framesPerPacket: Int): Int {
            val samples = PREROLL_MS.toLong() * sampleRate
            return ((samples + framesPerPacket * 1000L - 1) / (framesPerPacket * 1000L)).toInt()
        }

        /** Pre-roll frames for a stream: none for mirroring audio (AAC-ELD, must track the video). */
        internal fun prerollFramesFor(codecType: Int, sampleRate: Int, framesPerPacket: Int): Int =
            if (codecType == CT_AAC_ELD) 0 else prerollFrameCount(sampleRate, framesPerPacket)

        // Sliding window of recently-played RTP sequence numbers for duplicate suppression.
        // ~11 s at 92 packets/s — far longer than any retransmit gap, far shorter than the
        // 65536-packet (~12 min) sequence-number wrap, so no false positives from wraparound.
        private const val SEQ_WINDOW = 1024

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
