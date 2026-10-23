package com.phairplay.airplay

import java.util.concurrent.atomic.AtomicLong

/**
 * StreamStats — live counters for the optional on-screen debug overlay (Settings → "Debug overlay").
 *
 * The mirror video server ([com.phairplay.airplay.handshake.MirrorStreamServer]) and audio server
 * ([com.phairplay.airplay.handshake.AudioStreamServer]) write these volatile fields as they run;
 * [com.phairplay.ui.StreamingScreen] polls [summary] a few times a second to render the HUD. Plain
 * volatile ints keep the hot path allocation-free — no flows or locks on the per-frame path.
 *
 * [overlayEnabled] is set from [com.phairplay.settings.AppSettings.showDebugOverlay] when the AirPlay
 * receiver starts, so the HUD appears for the next mirroring session after the toggle is flipped.
 */
object StreamStats {
    /** Master switch, mirrored from the user setting; the overlay only draws when true. */
    @Volatile var overlayEnabled = false

    // ─── Video (MirrorStreamServer) ──────────────────────────────────────────
    @Volatile var videoRes = ""        // e.g. "1920x1080"
    @Volatile var videoFps = 0         // frames/sec over the last sample window
    @Volatile var videoQueue = 0       // current decode-queue depth
    @Volatile var videoDropPct = 0     // cumulative % of frames dropped under load

    // Actual decoded video dimensions (from the SPS, so portrait phone streams are portrait here).
    // StreamingScreen reads these to aspect-fit the Surface instead of stretching to 16:9.
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0
    @Volatile var displayRefreshHz = 0f

    // Separate transport, codec submission, output release and hardware presentation.
    val videoReceived = AtomicLong()
    val videoSubmitted = AtomicLong()
    val videoOutput = AtomicLong()
    val videoPresented = AtomicLong()
    val videoTextureUpdates = AtomicLong()
    val videoInputMiss = AtomicLong()
    val videoInputRetries = AtomicLong()
    val videoRebuilds = AtomicLong()
    val videoKeyframeRetries = AtomicLong()
    val videoSkipped = AtomicLong()
    val videoInvalid = AtomicLong()
    val videoConfigs = AtomicLong()
    val videoErrors = AtomicLong()
    @Volatile var videoStage = "waiting"
    @Volatile var videoCodec = "—"
    @Volatile var videoConfig = "—"
    @Volatile var videoSurface = "—"
    @Volatile var videoLastError = "—"
    private var rateSampleNs = 0L
    private var rateCounts = LongArray(5)
    private var rates = LongArray(5)
    @Volatile private var resetRateSample = true

    /** Preserve the last error on screen even if the network keeps receiving packets. */
    fun videoError(stage: String, error: Throwable) {
        videoErrors.incrementAndGet()
        videoStage = stage
        videoLastError = "$stage: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            .replace('\n', ' ').take(160)
    }

    private fun videoCounters() = longArrayOf(videoReceived.get(), videoSubmitted.get(),
        videoOutput.get(), videoPresented.get(), videoTextureUpdates.get())

    /** Called by the HUD on the UI thread; sample all stages over the same interval. */
    private fun sampleRates() {
        val now = System.nanoTime()
        val counts = videoCounters()
        if (resetRateSample || rateSampleNs == 0L) {
            resetRateSample = false; rates = LongArray(5)
            rateSampleNs = now; rateCounts = counts
        } else if (now - rateSampleNs >= 1_000_000_000L) {
            rates = LongArray(5) { ((counts[it] - rateCounts[it]).coerceAtLeast(0) *
                1_000_000_000L / (now - rateSampleNs)) }
            rateSampleNs = now; rateCounts = counts
        }
    }

    // ─── Audio (AudioStreamServer) ───────────────────────────────────────────
    @Volatile var audioActive = false  // true while an audio stream is running
    @Volatile var audioQueue = 0       // current playback-queue depth
    @Volatile var audioDupPct = 0      // % of RTP packets that were redundant duplicates

    /** Clears per-stream counters (call when a mirror session ends). Keeps [overlayEnabled]. */
    fun resetStreams() {
        resetVideo()
        audioActive = false; audioQueue = 0; audioDupPct = 0
    }

    /** A new video session must not clear an audio stream that is already running. */
    fun resetVideo() {
        videoRes = ""; videoFps = 0; videoQueue = 0; videoDropPct = 0
        videoWidth = 0; videoHeight = 0
        listOf(videoReceived, videoSubmitted, videoOutput, videoPresented, videoTextureUpdates, videoInputMiss,
            videoInputRetries, videoRebuilds, videoKeyframeRetries,
            videoSkipped, videoInvalid, videoConfigs, videoErrors).forEach { it.set(0) }
        videoStage = "waiting"; videoCodec = "—"; videoConfig = "—"
        videoSurface = "—"; videoLastError = "—"
        resetRateSample = true
    }

    /** Human-readable multi-line HUD text. */
    fun summary(): String {
        sampleRates()
        val c = videoCounters()
        return "PhairPlay · diagnostics\n" +
            "RX ${rates[0]}fps/${c[0]}  IN ${rates[1]}fps/${c[1]}\n" +
            "OUT ${rates[2]}fps/${c[2]}  PRESENT ${rates[3]}fps/${c[3]} (callback)\n" +
            "TEXTURE ${rates[4]}fps/${c[4]}  DISPLAY ${displayRefreshHz}Hz\n" +
            "q $videoQueue  queueDrop $videoDropPct%  inputMiss ${videoInputMiss.get()}\n" +
            "inputRetry ${videoInputRetries.get()}  rebuild ${videoRebuilds.get()}  IDRretry ${videoKeyframeRetries.get()}\n" +
            "skip ${videoSkipped.get()}  invalid ${videoInvalid.get()}  config ${videoConfigs.get()}\n" +
            "state $videoStage  codec $videoCodec\n" +
            "SPS $videoConfig  OUT ${videoWidth}x$videoHeight\n" +
            "surface $videoSurface\n" +
            "errors ${videoErrors.get()}  $videoLastError\n" +
            "AUDIO " + (if (audioActive) "on  q $audioQueue  dup $audioDupPct%" else "off")
    }
}
