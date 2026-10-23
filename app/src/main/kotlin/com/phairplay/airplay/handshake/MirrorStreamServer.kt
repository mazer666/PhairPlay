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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

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
 * Reference: RPiPlay lib/raop_rtp_mirror.c (raop_rtp_mirror_thread).
 */
class MirrorStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    streamConnectionId: Long,
    private val surfaceProvider: () -> Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080,
) {
    private sealed class Item
    private class Config(val sps: ByteArray, val pps: ByteArray) : Item()
    private class Frame(val annexB: ByteArray) : Item()

    private val cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    // OS-assigned free port, explicit IPv4 wildcard — see RtspHandler.bindRtspSocket() for why.
    private val serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("0.0.0.0"))
    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)

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
    // Total size of the items currently held in the queue — the queue's memory budget.
    // Touched by the reader thread (enqueue) and the decoder thread (dequeue).
    @Volatile private var queuedBytes = 0
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
                            enqueue(Frame(annexB))
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
     * Bounded enqueue — if the decoder is behind, drop the oldest item to keep latency bounded.
     *
     * TWO BOUNDS ARE ENFORCED:
     *  - the item count ([QUEUE_CAPACITY]), and
     *  - the total number of bytes held in the queue ([MAX_QUEUE_BYTES]).
     *
     * WHY THE BYTE BOUND: the item count alone is not enough. A sender on the LAN may
     * push payloads of up to [MAX_PAYLOAD] (8 MB) each; 90 such frames would keep ~720 MB
     * alive and OOM the app process. The byte budget caps the memory a hostile or broken
     * sender can make the receiver hold, independently of how many frames that is.
     */
    private fun enqueue(item: Item) {
        framesIn++
        val itemBytes = sizeOf(item)

        // Make room for the new item: drop the oldest entries until it fits.
        while (queuedBytes + itemBytes > MAX_QUEUE_BYTES) {
            val dropped = queue.poll() ?: break
            queuedBytes -= sizeOf(dropped)
            framesDropped++
            awaitingKeyframe = true      // a frame was lost — resync the decoder at the next IDR
        }

        if (!queue.offer(item)) {
            // The queue is full by count even though the byte budget allowed it.
            queue.poll()
            queue.offer(item)
            framesDropped++
            awaitingKeyframe = true        // a frame was lost — resync the decoder at the next IDR
        } else {
            queuedBytes += itemBytes
        }
        StreamStats.videoQueue = queue.size
        if (framesIn % 300 == 0) {
            val now = System.currentTimeMillis()
            if (lastStatMs != 0L) StreamStats.videoFps = (300_000L / (now - lastStatMs).coerceAtLeast(1)).toInt()
            lastStatMs = now
            StreamStats.videoDropPct = framesDropped * 100 / framesIn
            Logger.i("Video stats: in=$framesIn dropped=$framesDropped " +
                "(${StreamStats.videoDropPct}%) queue=${queue.size}/$QUEUE_CAPACITY ${StreamStats.videoFps}fps")
        }
    }

    /** Size of a queued item in bytes — used for the queue's memory budget. */
    private fun sizeOf(item: Item): Int = when (item) {
        is Config -> item.sps.size + item.pps.size
        is Frame -> item.annexB.size
    }

    private fun parseConfig(payload: ByteArray): Config? = try {
        StreamStats.videoConfigs.incrementAndGet()
        val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        val sps = payload.copyOfRange(8, 8 + spsSize)
        val ppsLenOffset = 8 + spsSize + 1                   // skip the 1-byte PPS count
        val ppsSize = ((payload[ppsLenOffset].toInt() and 0xFF) shl 8) or
            (payload[ppsLenOffset + 1].toInt() and 0xFF)
        Config(sps, payload.copyOfRange(ppsLenOffset + 2, ppsLenOffset + 2 + ppsSize))
    } catch (e: Exception) {
        StreamStats.videoError("config parse failed", e)
        Logger.e("Mirror: failed to parse SPS/PPS", e); null
    }

    // ─── Decoder thread: consume the queue; the only thread that touches the decoder ──────────
    private fun runDecoder() {
        try {
            while (running) {
                val item = queue.poll(20, TimeUnit.MILLISECONDS)
                decoder?.drainPendingOutput()
                if (item == null) continue
                // The item left the queue — release its share of the memory budget.
                queuedBytes = (queuedBytes - sizeOf(item)).coerceAtLeast(0)
                StreamStats.videoQueue = queue.size
                when (item) {
                    is Config -> configureDecoder(item.sps, item.pps)
                    is Frame -> decodeFrame(item.annexB)
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
        private const val QUEUE_CAPACITY = 90                  // ~1.5s @60fps before dropping

        /**
         * Maximum number of bytes the frame queue may hold.
         *
         * The item count alone does not bound the memory: a sender on the LAN can push
         * payloads of up to [MAX_PAYLOAD] (8 MB) each, so 90 items could keep ~720 MB
         * alive and OOM the receiver. 64 MB is far above what a real 4K stream needs
         * (a 1440p frame is typically 100–500 KB) and far below what would hurt the
         * process.
         */
        private const val MAX_QUEUE_BYTES = 64 * 1024 * 1024    // 64 MB
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L
    }
}
