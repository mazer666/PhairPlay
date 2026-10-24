package com.phairplay.airplay.handshake

import android.view.Surface
import com.phairplay.airplay.StreamStats
import com.phairplay.airplay.VideoDecoder
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * MirrorStreamServer — receives and decodes the AirPlay 2 mirroring video stream.
 *
 * macOS connects to [dataPort] and sends [128-byte header][payload] packets:
 *  - payload_size = little-endian int at header offset 0
 *  - payload_type = little-endian short at header offset 4, low byte
 *      • type 1: unencrypted avcC (SPS/PPS) → (re)configure the decoder
 *      • type 0: AES-CTR-encrypted H.264 (AVCC) → decrypt, convert to Annex-B, decode
 *
 * Architecture: a network thread reads + decrypts packets (keeping the AES-CTR keystream strictly
 * ordered) and pushes work onto a bounded queue; a separate decoder thread consumes it. This way
 * the socket is always drained fast — if the decoder can't keep up (this SoC is modest, and the
 * audio codec competes for resources), frames are dropped from the queue instead of stalling the
 * socket, which previously caused macOS to drop the whole session.
 *
 * The queue is bounded twice: by item count (latency) and by total bytes (memory) — see
 * [MirrorFrameQueue] and the security review 2026-10-24, §3.3, finding 2.
 *
 * Reference: RPiPlay lib/raop_rtp_mirror.c (raop_rtp_mirror_thread).
 */
class MirrorStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    streamConnectionId: Long,
    private val surfaceProvider: () -> Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val maxQueueBytes: Long = DEFAULT_MAX_QUEUE_BYTES,
    private val maxQueueItems: Int = DEFAULT_MAX_QUEUE_ITEMS,
) {
    private val cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    // OS-assigned free port, explicit IPv4 wildcard — see RtspHandler.bindRtspSocket() for why.
    private val serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("0.0.0.0"))
    private val queue = MirrorFrameQueue(maxItems = maxQueueItems, maxBytes = maxQueueBytes)

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null   // owned by the decoder thread
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    // The Surface the current decoder was built against. The SurfaceView destroys its Surface when
    // the app backgrounds and creates a NEW one on return, so we watch for the identity changing
    // and rebuild the decoder — otherwise video stays black after foregrounding.
    @Volatile private var configuredSurface: Surface? = null
    private var framePtsUs = 0L
    private var framesIn = 0
    private var framesDropped = 0
    private var lastStatMs = 0L
    // Set by the reader thread when a frame is dropped under load; the decoder thread then skips
    // frames until the next keyframe (IDR) so it never decodes a reference-broken, corrupt stream.
    @Volatile private var awaitingKeyframe = false

    /** The OS-assigned TCP port macOS should connect to (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    fun start(scope: CoroutineScope) {
        StreamStats.resetVideo()
        StreamStats.videoStage = "waiting connection"
        running = true
        scope.launch(Dispatchers.IO) { runReader() }
        scope.launch(Dispatchers.IO) { runDecoder() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        queue.clear()
        Logger.i("MirrorStreamServer stopped")
    }

    // ─── Network reader thread: read + decrypt (ordered), enqueue, never block on decode ──────
    private fun runReader() {
        try {
            Logger.i("MirrorStreamServer listening on data port $dataPort")
            val socket = serverSocket.accept().also { client = it }
            StreamStats.videoStage = "waiting config"
            Logger.i("Mirror data connection from ${socket.inetAddress.hostAddress}")
            val input = socket.getInputStream()
            val header = ByteArray(128)
            while (running && !socket.isClosed) {
                if (!readFully(input, header, 128)) break
                val payloadSize = leInt(header, 0)
                val payloadType = leShort(header, 4) and 0xFF
                if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD) {
                    Logger.w("Mirror: bad payloadSize=$payloadSize type=$payloadType — stopping")
                    break
                }
                val payload = ByteArray(payloadSize)
                if (!readFully(input, payload, payloadSize)) break
                when (payloadType) {
                    0 -> {
                        StreamStats.videoReceived.incrementAndGet()
                        // ALWAYS advance the AES-CTR keystream, in order, for every video payload —
                        // skipping any packet desyncs the keystream and corrupts all later frames.
                        val annexB = MirrorCrypto.avccToAnnexB(cipher.update(payload))
                        if (annexB.isNotEmpty()) {
                            enqueue(MirrorFrameQueue.Frame(annexB))
                        } else {
                            StreamStats.videoInvalid.incrementAndGet()
                        }
                    }
                    1 -> parseConfig(payload)?.let { enqueue(it) }
                    else -> Logger.v("Mirror: ignoring payload type $payloadType ($payloadSize B)")
                }
            }
        } catch (e: Exception) {
            if (running) {
                StreamStats.videoError("reader failed", e)
                Logger.e("Mirror reader error", e)
            }
        } finally {
            running = false
            Logger.i("Mirror data connection ended")
        }
    }

    /**
     * Enqueues an item for the decoder thread.
     *
     * TWO BOUNDS ARE ENFORCED BY THE QUEUE (see [MirrorFrameQueue]):
     *  - the item count (latency: ~1.5 s of video), and
     *  - the total number of bytes held in the queue (memory).
     *
     * WHY THE BYTE BOUND: the item count alone is not enough. A sender on the LAN may push
     * payloads of up to [MAX_PAYLOAD] (8 MB) each; 90 such frames would keep ~720 MB alive and
     * OOM the app process. The byte budget caps the memory a hostile or broken sender can make
     * the receiver hold, independently of how many frames that is.
     *
     * The reader thread NEVER blocks here: if the decoder is behind, the oldest frames are
     * dropped so that the socket keeps being drained (macOS would drop the whole session
     * otherwise).
     */
    private fun enqueue(item: MirrorFrameQueue.Item) {
        framesIn++
        val outcome = queue.offer(item)
        if (!outcome.accepted) {
            // The item alone is bigger than the whole memory budget — it can never be queued.
            framesDropped++
            awaitingKeyframe = true
            Logger.w("Mirror: dropped an oversized item (${item.sizeBytes} B > queue budget)")
        } else if (outcome.dropped > 0) {
            framesDropped += outcome.dropped
            awaitingKeyframe = true      // a frame was lost — resync the decoder at the next IDR
        }
        StreamStats.videoQueue = queue.size
        if (framesIn % 300 == 0) {
            val now = System.currentTimeMillis()
            if (lastStatMs != 0L) StreamStats.videoFps = (300_000L / (now - lastStatMs).coerceAtLeast(1)).toInt()
            lastStatMs = now
            StreamStats.videoDropPct = framesDropped * 100 / framesIn
            Logger.i("Video stats: in=$framesIn dropped=$framesDropped " +
                "(${StreamStats.videoDropPct}%) queue=${queue.size}/$maxQueueItems " +
                "${queue.byteSize}B/${maxQueueBytes}B ${StreamStats.videoFps}fps")
        }
    }

    private fun parseConfig(payload: ByteArray): MirrorFrameQueue.Config? = try {
        StreamStats.videoConfigs.incrementAndGet()
        val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        val sps = payload.copyOfRange(8, 8 + spsSize)
        val ppsLenOffset = 8 + spsSize + 1                   // skip the 1-byte PPS count
        val ppsSize = ((payload[ppsLenOffset].toInt() and 0xFF) shl 8) or
            (payload[ppsLenOffset + 1].toInt() and 0xFF)
        MirrorFrameQueue.Config(
            sps,
            payload.copyOfRange(ppsLenOffset + 2, ppsLenOffset + 2 + ppsSize)
        )
    } catch (e: Exception) {
        StreamStats.videoError("config parse failed", e)
        Logger.e("Mirror: failed to parse SPS/PPS", e); null
    }

    // ─── Decoder thread: consume the queue; the only thread that touches the decoder ──────────
    private fun runDecoder() {
        try {
            while (running) {
                val item = queue.poll(20)
                decoder?.drainPendingOutput()
                if (item == null) continue
                StreamStats.videoQueue = queue.size
                when (item) {
                    is MirrorFrameQueue.Config -> configureDecoder(item.sps, item.pps)
                    is MirrorFrameQueue.Frame -> decodeFrame(item.annexB)
                }
            }
        } catch (e: Exception) {
            if (running) {
                StreamStats.videoError("decoder thread failed", e)
                Logger.e("Mirror decoder thread error", e)
            }
        } finally {
            decoder?.release()
            decoder = null
        }
    }

    private fun configureDecoder(sps: ByteArray, pps: ByteArray) {
        // New SPS/PPS (or first config) — cache it and (re)build against the current surface.
        val d = decoder
        val surface = awaitSurface()
        if (d != null && d.isHealthy && sps.contentEquals(lastSps) && pps.contentEquals(lastPps) &&
            surface === configuredSurface) return
        lastSps = sps
        lastPps = pps
        rebuildDecoder(surface)
    }

    /**
     * (Re)creates the decoder for [surface] from the cached SPS/PPS. Safe to call on a surface
     * change (app background→foreground): releases the old decoder and resyncs at the next keyframe.
     * If the surface or config isn't available yet, leaves the decoder null and retries on a later
     * frame (frames are dropped until then).
     */
    private fun rebuildDecoder(surface: Surface?) {
        decoder?.release()
        decoder = null
        configuredSurface = surface
        val sps = lastSps ?: return
        val pps = lastPps ?: return
        if (surface == null) {
            StreamStats.videoStage = "waiting surface"
            return
        }
        val sc = MirrorCrypto.START_CODE
        StreamStats.videoRebuilds.incrementAndGet()
        decoder = VideoDecoder(surface).also { it.initialize(sc + sps, sc + pps, width, height) }
        awaitingKeyframe = true                                // a fresh decoder must start at an IDR
        StreamStats.videoRes = "${width}x${height}"
        Logger.i("Mirror decoder (re)built for surface (sps=${sps.size}B pps=${pps.size}B)")
    }

    private fun decodeFrame(annexB: ByteArray) {
        // Re-attach to the live Surface if it changed (the app was backgrounded and returned, so the
        // SurfaceView made a new Surface). Without this, video stays black after foregrounding.
        val liveSurface = surfaceProvider()
        StreamStats.videoSurface = if (liveSurface == null) "missing" else
            "valid=${liveSurface.isValid} id=${System.identityHashCode(liveSurface)}"
        if (liveSurface !== configuredSurface) {
            Logger.i("Mirror: surface ${if (liveSurface == null) "lost" else "changed"} — re-attaching decoder")
            rebuildDecoder(liveSurface)
        }
        var d = decoder ?: run {
            StreamStats.videoSkipped.incrementAndGet()
            return
        }
        if (!d.isHealthy) {                                    // error state — drop, await next config
            if (!isKeyframe(annexB)) {
                StreamStats.videoStage = "unhealthy: waiting IDR"
                StreamStats.videoSkipped.incrementAndGet()
                return
            }
            StreamStats.videoStage = "unhealthy: rebuilding"
            StreamStats.videoSkipped.incrementAndGet()
            Logger.w("Mirror: decoder unhealthy — rebuilding with cached SPS/PPS")
            rebuildDecoder(liveSurface)
            d = decoder ?: return
        }
        if (awaitingKeyframe) {
            // After a dropped frame the stream is reference-broken; skip until the next IDR so we
            // don't feed the decoder predicted frames with missing references (which smear/blocky).
            if (!isKeyframe(annexB)) {
                StreamStats.videoStage = "waiting IDR"
                StreamStats.videoSkipped.incrementAndGet()
                return
            }
            awaitingKeyframe = false
            Logger.i("Mirror: resynced on keyframe after a dropped frame")
        }
        if (framePtsUs == 0L) Logger.i("Mirror: first video frame fed to decoder (${annexB.size}B)")
        if (d.decodeNalUnit(annexB, framePtsUs)) {
            framePtsUs += FRAME_INTERVAL_US
        } else {
            awaitingKeyframe = true
            StreamStats.videoStage = "input dropped: waiting IDR"
            // Losing a new configuration's first IDR can freeze until the next rotation.
            // Retry it once on a fresh codec, with the current cached configuration.
            if (isKeyframe(annexB)) {
                StreamStats.videoKeyframeRetries.incrementAndGet()
                rebuildDecoder(liveSurface)
                val fresh = decoder
                if (fresh != null && fresh.decodeNalUnit(annexB, framePtsUs)) {
                    awaitingKeyframe = false
                    framePtsUs += FRAME_INTERVAL_US
                }
            }
        }
    }

    /** True if the Annex-B frame contains an IDR NAL unit (type 5) — a decodable resync point. */
    private fun isKeyframe(annexB: ByteArray): Boolean {
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                if ((annexB[i + 3].toInt() and 0x1F) == 5) return true   // IDR slice
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /** The streaming Surface appears shortly after CONNECTED is emitted; poll briefly. */
    private fun awaitSurface(): Surface? {
        repeat(SURFACE_WAIT_TRIES) {
            if (!running) return null
            surfaceProvider()?.let { return it }
            try { Thread.sleep(SURFACE_WAIT_MS) } catch (_: InterruptedException) { return null }
        }
        return surfaceProvider()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n == -1) return false
            read += n
        }
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val MAX_PAYLOAD = 8 * 1024 * 1024        // 8 MB sanity cap per frame
        private const val FRAME_INTERVAL_US = 1_000_000L / 60  // monotonic PTS hint (~60fps)
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L

        /**
         * Default memory budget of the frame queue.
         *
         * WHY 32 MB: a real 1080p–1440p frame is typically 50–500 KB, so 32 MB holds more frames
         * than the item limit — a healthy stream is never affected by the budget, only
         * pathological payloads are. It is also far below the 150 MB process budget of NFR-01.
         * The previous count-only bound allowed ~720 MB.
         */
        const val DEFAULT_MAX_QUEUE_BYTES = MirrorFrameQueue.DEFAULT_MAX_BYTES

        /** Default item limit of the frame queue (~1.5 s of video at 60 fps). */
        const val DEFAULT_MAX_QUEUE_ITEMS = MirrorFrameQueue.DEFAULT_MAX_ITEMS
    }
}
