package com.phairplay.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.phairplay.util.Logger

/**
 * VideoDecoder — Hardware H.264 video decoder using Android's MediaCodec API.
 *
 * WHY: AirPlay screen mirroring sends video as a stream of H.264-encoded frames.
 * To display these frames on screen, we need to decode the H.264 bitstream.
 * MediaCodec is Android's API to the hardware video decoder (GPU), which is
 * much faster and more power-efficient than any software decoder.
 *
 * HOW: Initialize with a [Surface] (from StreamingScreen) and the codec parameters
 * from the SDP (received in the RTSP ANNOUNCE). Then call [decodeNalUnit] for each
 * video chunk received from the RTP stream. MediaCodec outputs decoded frames directly
 * to the Surface — no intermediate buffer copies.
 *
 * AirPlay video data flow:
 *   RTP packet → RtspHandler strips RTP header → NAL unit bytes → VideoDecoder.decodeNalUnit()
 *   → MediaCodec input buffer → GPU hardware decode → Surface (displayed on TV)
 *
 * Example:
 *   val decoder = VideoDecoder(surface)
 *   decoder.initialize(spsBytes, ppsBytes, width, height)  // call once with SDP params
 *   decoder.decodeNalUnit(nalUnitBytes)                    // call for each video chunk
 *   decoder.release()                                       // call when done
 */
class VideoDecoder(private val outputSurface: Surface) {

    // The underlying hardware decoder — null until initialize() is called
    @Volatile private var mediaCodec: MediaCodec? = null
    private var renderCallbackThread: HandlerThread? = null

    // Track whether the decoder has been initialized (to prevent double-init)
    @Volatile
    private var isInitialized = false

    /**
     * False once MediaCodec has thrown (entered an unrecoverable error state). The caller should
     * drop this decoder and create a fresh one — error state cannot be cleared by reconfigure.
     */
    @Volatile
    var isHealthy = true
        private set

    /**
     * Initializes the MediaCodec decoder with the video stream parameters from the SDP.
     *
     * This must be called ONCE before any calls to [decodeNalUnit].
     * The parameters (SPS, PPS, width, height) come from the SDP body of the
     * RTSP ANNOUNCE message.
     *
     * What is SPS/PPS?
     *   H.264 requires two special "configuration" NAL units before the first frame:
     *   - SPS (Sequence Parameter Set): describes the video resolution, profile, level
     *   - PPS (Picture Parameter Set): describes encoding parameters for each frame
     *   MediaCodec needs these to configure the hardware decoder correctly.
     *
     * RULE 5: If initialization fails, the exception propagates to the caller
     * (RtspHandler) which will handle it gracefully (log + return to WAITING state).
     *
     * @param spsBytes  The SPS NAL unit bytes (from SDP "sprop-parameter-sets" field)
     * @param ppsBytes  The PPS NAL unit bytes (from SDP "sprop-parameter-sets" field)
     * @param width     Video width in pixels (from SDP)
     * @param height    Video height in pixels (from SDP)
     */
    fun initialize(spsBytes: ByteArray, ppsBytes: ByteArray, width: Int, height: Int) {
        if (isInitialized) {
            Logger.w("VideoDecoder.initialize() called twice — ignoring second call")
            return
        }

        // Extract the resolution from the SPS, accepting the Annex-B start code used by mirroring.
        // Validate the result and fall back to the hint if the sender's SPS is malformed; the
        // hardware decoder reads the true size from the SPS (csd-0) itself and reports it via
        // INFO_OUTPUT_FORMAT_CHANGED, which we use to refine the size for aspect-fit.
        val parsed = H264SpsParser.parseResolution(spsBytes)
        StreamStats.videoConfig = "parsed=$parsed hint=${width}x$height"
        StreamStats.videoStage = "codec init"
        val (actualWidth, actualHeight) = parsed?.takeIf { isPlausibleSize(it.first, it.second) } ?: run {
            Logger.w("SPS resolution $parsed implausible/failed — using hint ${width}x${height}")
            Pair(width, height)
        }

        Logger.i("Initializing H.264 decoder: ${actualWidth}x${actualHeight} " +
                 "(hint was ${width}x${height})")

        // Provisional size for StreamingScreen aspect-fit; replaced by the decoder's reported size.
        StreamStats.videoWidth = actualWidth
        StreamStats.videoHeight = actualHeight

        // Create the MediaFormat that describes the H.264 stream to the hardware decoder
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,  // AVC = Advanced Video Coding = H.264
            actualWidth,
            actualHeight
        ).apply {
            // Provide SPS and PPS so MediaCodec can configure the hardware decoder.
            // These are wrapped in ByteBuffers as required by the MediaCodec API.
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(spsBytes))  // SPS
            setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(ppsBytes))  // PPS
            // NOTE: do NOT set KEY_MAX_WIDTH/HEIGHT here — this SoC's MStar decoder rejects
            // adaptive playback (BadParameter / buffer-count failures) and produces banding.
            // Resolution changes are handled by recreating the decoder in MirrorStreamServer.
        }

        // Create the hardware H.264 decoder.
        // "video/avc" is the MIME type for H.264. Android will pick the best
        // available hardware decoder for this format.
        mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        StreamStats.videoCodec = mediaCodec!!.name

        // Configure the decoder:
        // - format: what the input will look like (H.264, width, height, SPS/PPS)
        // - outputSurface: where decoded frames go (directly to screen — no intermediate copy)
        // - crypto: null (we handle decryption before this point, if needed)
        // - flags: 0 (0 = decoder mode; CONFIGURE_FLAG_ENCODE would be for encoding)
        try {
            mediaCodec!!.configure(format, outputSurface, null, 0)
        } catch (e: Exception) {
            release()
            throw e
        }
        // A vendor that rejects this diagnostic callback should still be able to decode.
        runCatching {
            val thread = HandlerThread("PhairPlay-frame-events").apply { start() }
            renderCallbackThread = thread
            mediaCodec!!.setOnFrameRenderedListener({ codec, _, _ ->
                if (codec === mediaCodec) StreamStats.videoPresented.incrementAndGet()
            }, Handler(thread.looper))
        }.onFailure {
            renderCallbackThread?.quitSafely()
            renderCallbackThread = null
            StreamStats.videoError("render callback unavailable", it)
        }
        try {
            mediaCodec!!.start()
        } catch (e: Exception) {
            release()
            throw e
        }

        isInitialized = true
        StreamStats.videoStage = "codec ready"
        Logger.i("H.264 decoder initialized successfully")
    }

    /**
     * Decodes a single H.264 NAL unit and sends it to the display.
     *
     * A NAL unit (Network Abstraction Layer Unit) is the basic building block of H.264.
     * Each RTP packet from AirPlay contains one or more NAL units.
     * Some NAL units are full frames (IDR frames), others are partial updates.
     *
     * PERFORMANCE: This method runs on the IO coroutine dispatcher (network thread).
     * MediaCodec handles the actual decoding on its own internal thread.
     * The decoded frame appears on the Surface without any UI thread involvement.
     *
     * SECURITY: The caller (RtspHandler) is responsible for validating the byte array
     * length before passing it here.
     *
     * @param nalUnit The raw NAL unit bytes (without the RTP header).
     * @param presentationTimeUs Presentation timestamp in microseconds (for A/V sync).
     */
    fun decodeNalUnit(nalUnit: ByteArray, presentationTimeUs: Long): Boolean {
        val codec = mediaCodec ?: run {
            Logger.w("decodeNalUnit() called but decoder not initialized")
            return false
        }

        var submitted = false
        try {
            // Drain finished frames FIRST — renders them and frees the pipeline so an input
            // buffer becomes available. Dropping NAL units corrupts H.264 (loses reference
            // frames) and causes a black screen until the next keyframe, so we avoid it.
            releaseOutputBuffers(codec)

            // Keep draining while waiting: one long input wait can block the output that must
            // be released before the codec can accept its first frame after reconfiguration.
            val deadline = System.nanoTime() + INPUT_BUFFER_WAIT_NS
            var inputBufferIndex = codec.dequeueInputBuffer(INPUT_BUFFER_TIMEOUT_US)
            while (inputBufferIndex < 0 && System.nanoTime() < deadline) {
                StreamStats.videoInputRetries.incrementAndGet()
                releaseOutputBuffers(codec)
                inputBufferIndex = codec.dequeueInputBuffer(INPUT_BUFFER_TIMEOUT_US)
            }

            if (inputBufferIndex >= 0) {
                // We got an input buffer — fill it with the NAL unit bytes
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                inputBuffer.clear()
                inputBuffer.put(nalUnit)

                // Tell MediaCodec: "input buffer [index] is filled with [size] bytes
                // of data with timestamp [presentationTimeUs] — please decode it"
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,                 // offset: start from beginning of buffer
                    nalUnit.size,      // size: how many bytes to decode
                    presentationTimeUs,
                    0                  // flags: 0 = normal frame (not end-of-stream)
                )
                submitted = true
                StreamStats.videoSubmitted.incrementAndGet()
                StreamStats.videoStage = "submitted"
            } else {
                // The bounded wait expired. The caller will recreate the codec and, if this
                // is the rotation's IDR, retry the SAME frame instead of losing its resync point.
                Logger.v("VideoDecoder: no input buffer available, dropping NAL unit")
                StreamStats.videoInputMiss.incrementAndGet()
                StreamStats.videoStage = "no input buffer"
                isHealthy = false
            }

            // Release any output buffers that MediaCodec has finished decoding.
            // render=true means the frame goes to the Surface immediately.
            releaseOutputBuffers(codec)

        } catch (e: IllegalStateException) {
            StreamStats.videoError("codec failed", e)
            // MediaCodec is now in the error state and cannot recover — flag for recreation.
            Logger.e("VideoDecoder entered error state — will recreate", e)
            isHealthy = false
        } catch (e: Exception) {
            StreamStats.videoError("decode error", e)
            Logger.e("Error decoding NAL unit", e)
        }
        return submitted
    }

    /** Drain asynchronous codec output on the same decoder thread even when no input arrives. */
    fun drainPendingOutput() {
        val codec = mediaCodec ?: return
        if (!isHealthy) return
        try {
            releaseOutputBuffers(codec)
        } catch (e: Exception) {
            StreamStats.videoError("output drain failed", e)
            isHealthy = false
        }
    }

    /**
     * Releases any decoded output buffers back to MediaCodec and renders them to the Surface.
     *
     * MediaCodec works asynchronously: we put encoded data in input buffers,
     * and decoded frames appear in output buffers. We must release each output
     * buffer back to MediaCodec after rendering, or we'll run out of buffers.
     *
     * render=true: the frame is rendered to the Surface (displayed on TV).
     * render=false: the frame is discarded (used to flush without displaying).
     *
     * @param codec The active MediaCodec instance.
     */
    private fun releaseOutputBuffers(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)

        while (outputBufferIndex >= 0 || outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // The decoder parsed the real size from the SPS — authoritative for aspect-fit.
                publishOutputSize(codec.outputFormat)
            } else {
                // Render immediately. We deliberately do NOT schedule a future render time for A/V sync:
                // this Surface's BufferQueue holds only ~3 frames, so any hold quickly back-pressures the
                // decoder → the upstream frame queue saturates → big latency + dropped (corrupt) frames.
                // A/V alignment is handled by keeping the AUDIO path low-latency instead (AudioStreamServer).
                codec.releaseOutputBuffer(outputBufferIndex, true)
                StreamStats.videoOutput.incrementAndGet()
                StreamStats.videoStage = "output released"
            }
            outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    /** Reads the decoder's true display size (honouring the crop rectangle) for StreamingScreen. */
    private fun publishOutputSize(format: MediaFormat) {
        var w = format.getInteger(MediaFormat.KEY_WIDTH)
        var h = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
            w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        }
        if (isPlausibleSize(w, h)) {
            StreamStats.videoWidth = w
            StreamStats.videoHeight = h
            Logger.i("Video output size ${w}x$h")
        }
    }

    /**
     * Releases all MediaCodec resources.
     *
     * MUST be called when streaming ends (from AirPlayReceiver.onStreamingStopped()
     * or from onDestroy()). Failing to release MediaCodec causes:
     * - Memory leaks (codec buffers are GPU memory, a scarce resource)
     * - The hardware decoder being unavailable to other apps
     *
     * After release(), call initialize() again before using the decoder.
     */
    fun release() {
        Logger.d("Releasing VideoDecoder")
        val codec = mediaCodec
        mediaCodec = null // ignore delayed callbacks from the old codec
        try {
            codec?.stop()
        } catch (e: Exception) {
            Logger.e("Error releasing MediaCodec (non-fatal)", e)
        } finally {
            runCatching { codec?.release() }.onFailure {
                Logger.e("Error disposing MediaCodec (non-fatal)", it)
            }
            renderCallbackThread?.quitSafely()
            renderCallbackThread = null
            isInitialized = false
        }
    }

    /** True if (w, h) look like a real video resolution — rejects parser garbage (e.g. 32x87392). */
    private fun isPlausibleSize(w: Int, h: Int): Boolean = w in 64..8192 && h in 64..8192

    companion object {
        private const val INPUT_BUFFER_TIMEOUT_US = 10_000L
        private const val INPUT_BUFFER_WAIT_NS = 250_000_000L

    }
}
