package com.phairplay.airplay

import com.phairplay.util.Logger
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * RtpInterleaved — Reads binary RTP/RTCP frames from the RTSP TCP connection.
 *
 * WHY: After the RTSP RECORD response, the AirPlay sender switches the same
 * TCP connection from text-based RTSP to binary RTP interleaved framing
 * (RFC 2326 §10.12). Each frame looks like:
 *
 * ```
 * ┌──────┬────────────┬───────────────┬──────────────────────────────┐
 * │ '$'  │ channel(1) │ length(2, BE)  │ RTP/RTCP payload (N bytes)  │
 * │  1B  │     1B     │      2B        │            N bytes            │
 * └──────┴────────────┴───────────────┴──────────────────────────────┘
 * ```
 *
 * Channel assignments (negotiated in SETUP responses):
 * - 0 = video RTP
 * - 1 = video RTCP (control, we ignore)
 * - 2 = audio RTP  (if audio is also interleaved over TCP — rarely the case in AirPlay)
 *
 * In AirPlay screen mirroring, video RTP is always interleaved on the RTSP TCP
 * connection (channel 0). Audio typically goes over a separate UDP socket.
 *
 * HOW: Call [readLoop] from a background coroutine after RECORD is acknowledged.
 * It reads frames indefinitely until the stream ends or an error occurs.
 *
 * Example:
 *   // After RECORD response sent:
 *   RtpInterleaved.readLoop(
 *       inputStream = socket.getInputStream(),
 *       session = currentSession,
 *       onVideoNalUnit = { nalUnit, pts -> videoDecoder.decodeNalUnit(nalUnit, pts) },
 *       onStreamEnded = { /* handle disconnect */ }
 *   )
 */
object RtpInterleaved {

    /** The `$` byte that marks the start of an interleaved RTP frame. */
    private const val INTERLEAVED_MARKER = 0x24  // '$'

    /** RTP channel 0 = video RTP data. */
    private const val CHANNEL_VIDEO_RTP = 0

    /** Fixed RTP header size (no CSRC, no extension). */
    private const val RTP_FIXED_HEADER_BYTES = 12

    /**
     * H.264 NAL unit type 28 = FU-A (Fragmentation Unit type A).
     * Used to split a large NAL unit across multiple RTP packets (RFC 6184 §5.8).
     */
    private const val NAL_TYPE_FU_A = 28

    /**
     * H.264 video uses a 90 kHz RTP clock (standard for video, per RFC 6184).
     * Used to convert RTP timestamps to microsecond presentation timestamps.
     */
    private const val RTP_VIDEO_CLOCK_HZ = 90_000L

    /**
     * Maximum acceptable RTP frame size.
     * Real AirPlay video frames are typically 1–200 KB.
     * Cap at 2 MB to reject clearly malformed/malicious frames.
     */
    private const val MAX_RTP_FRAME_BYTES = 2 * 1024 * 1024  // 2 MB

    /**
     * Maximum size of a reassembled FU-A NAL unit.
     *
     * WHY 8 MB: the cap on a single frame ([MAX_RTP_FRAME_BYTES]) only bounds one RTP
     * packet, not the NAL unit that is assembled from many of them. A peer that opens a
     * fragmented NAL unit and never sends the end bit would therefore grow the
     * accumulator until the process dies with an `OutOfMemoryError` (security audit
     * 2026-10-24, SEC-03). A real IDR slice of a 4K frame is a few hundred kilobytes,
     * so 8 MB leaves a wide margin and never touches a legitimate stream.
     */
    private const val MAX_FUA_BYTES = 8 * 1024 * 1024  // 8 MB

    /**
     * Reads RTP interleaved frames from [inputStream] until the connection ends.
     *
     * SECURITY: Frame length is capped at [MAX_RTP_FRAME_BYTES]. Oversized frames
     * are rejected to prevent heap exhaustion via malicious AirPlay senders.
     * A reassembled FU-A NAL unit is capped at [MAX_FUA_BYTES] for the same reason.
     *
     * @param inputStream   The raw TCP InputStream from the RTSP socket, positioned
     *                      immediately after the 200 OK response to RECORD was sent.
     * @param onVideoNalUnit Called with each H.264 NAL unit extracted from a video RTP frame.
     *                      @param nalUnit  Raw NAL unit bytes (RTP header stripped).
     *                      @param ptsUs    Presentation timestamp in microseconds.
     * @param onStreamEnded Called when the stream ends cleanly (EOF) or with an error.
     */
    fun readLoop(
        inputStream: InputStream,
        onVideoNalUnit: (nalUnit: ByteArray, ptsUs: Long) -> Unit,
        onStreamEnded: () -> Unit
    ) {
        // FU-A (Fragmentation Unit A) reassembly state — local so concurrent streams
        // don't share state. Non-null means we are accumulating fragments for one NAL unit.
        var fuaAccumulator: ByteArrayOutputStream? = null

        try {
            while (true) {
                // Read the 4-byte interleaved frame header: $ channel(1) length(2)
                val marker = inputStream.read()
                if (marker == -1) break  // clean EOF — sender disconnected

                // RTSP keeps-alive may send OPTIONS between RTP frames.
                // If the byte is not '$', skip until we find one.
                if (marker != INTERLEAVED_MARKER) {
                    Logger.v("RtpInterleaved: skipping non-$ byte 0x${marker.toString(16)}")
                    continue
                }

                val channel = inputStream.read()
                if (channel == -1) break

                val lenHigh = inputStream.read()
                val lenLow = inputStream.read()
                if (lenHigh == -1 || lenLow == -1) break

                val frameLength = (lenHigh shl 8) or lenLow

                // SECURITY: Reject unreasonably large frames
                if (frameLength <= 0 || frameLength > MAX_RTP_FRAME_BYTES) {
                    Logger.w("RtpInterleaved: invalid frame length $frameLength — stopping")
                    break
                }

                val frameData = ByteArray(frameLength)
                var bytesRead = 0
                while (bytesRead < frameLength) {
                    val n = inputStream.read(frameData, bytesRead, frameLength - bytesRead)
                    if (n == -1) {
                        Logger.w("RtpInterleaved: EOF mid-frame")
                        onStreamEnded()
                        return
                    }
                    bytesRead += n
                }

                // Only process video RTP frames (channel 0); ignore RTCP (channel 1)
                if (channel == CHANNEL_VIDEO_RTP) {
                    fuaAccumulator = processVideoRtpFrame(frameData, fuaAccumulator, onVideoNalUnit)
                }
                // Note: audio over interleaved TCP is rare in AirPlay; UDP is used instead.
            }
        } catch (e: Exception) {
            Logger.e("RtpInterleaved: read error — stream ended", e)
        }

        onStreamEnded()
    }

    /**
     * Parses a video RTP frame and delivers H.264 NAL unit(s) via [callback].
     *
     * Handles two H.264 RTP packetization modes (RFC 6184):
     * - **Single NAL unit** (type ≤ 23): the payload IS the NAL unit.
     * - **FU-A** (type = 28): a large NAL unit fragmented across multiple RTP packets.
     *   Fragments are accumulated in [fuaAccumulator] until the E (end) bit is set,
     *   then the reconstructed NAL unit is delivered and the accumulator is cleared.
     *
     * @param rtpFrame       Full RTP frame bytes (header + payload).
     * @param fuaAccumulator Ongoing FU-A reassembly buffer from the previous call, or null.
     * @param callback       Called with (nalUnit, presentationTimeUs) for each complete NAL unit.
     * @return Updated FU-A accumulator (null = no FU-A in progress after this call).
     */
    private fun processVideoRtpFrame(
        rtpFrame: ByteArray,
        fuaAccumulator: ByteArrayOutputStream?,
        callback: (ByteArray, Long) -> Unit
    ): ByteArrayOutputStream? {
        if (rtpFrame.size < RTP_FIXED_HEADER_BYTES) {
            Logger.w("RtpInterleaved: RTP frame too small (${rtpFrame.size} bytes)")
            return null
        }

        // CSRC count (CC) is in the lower 4 bits of byte 0
        val csrcCount = rtpFrame[0].toInt() and 0x0F
        val headerSize = RTP_FIXED_HEADER_BYTES + csrcCount * 4

        // Check for RTP header extension (X bit = bit 4 of byte 0)
        val hasExtension = (rtpFrame[0].toInt() and 0x10) != 0
        var payloadOffset = headerSize
        if (hasExtension && rtpFrame.size >= payloadOffset + 4) {
            val extLengthWords = ((rtpFrame[payloadOffset + 2].toInt() and 0xFF) shl 8) or
                                  (rtpFrame[payloadOffset + 3].toInt() and 0xFF)
            payloadOffset += 4 + extLengthWords * 4
        }

        if (payloadOffset >= rtpFrame.size) {
            Logger.w("RtpInterleaved: no payload after RTP header")
            return null
        }

        // Extract the 32-bit RTP timestamp from bytes 4–7 (big-endian) and convert to µs
        val rtpTimestamp = ((rtpFrame[4].toLong() and 0xFF) shl 24) or
                           ((rtpFrame[5].toLong() and 0xFF) shl 16) or
                           ((rtpFrame[6].toLong() and 0xFF) shl 8) or
                            (rtpFrame[7].toLong() and 0xFF)
        val ptsUs = rtpTimestamp * 1_000_000L / RTP_VIDEO_CLOCK_HZ

        // Dispatch based on NAL unit type (low 5 bits of first payload byte)
        val nalType = rtpFrame[payloadOffset].toInt() and 0x1F
        return if (nalType == NAL_TYPE_FU_A) {
            handleFuaFragment(rtpFrame, payloadOffset, ptsUs, fuaAccumulator, callback)
        } else {
            // Single NAL unit mode — deliver directly
            if (fuaAccumulator != null) {
                Logger.w("RtpInterleaved: dropping incomplete FU-A (${fuaAccumulator.size()} B)")
            }
            callback(rtpFrame.copyOfRange(payloadOffset, rtpFrame.size), ptsUs)
            null
        }
    }

    /**
     * Accumulates one H.264 FU-A fragment and delivers the reassembled NAL unit
     * when the end fragment arrives.
     *
     * FU-A packet layout (RFC 6184 §5.8):
     * ```
     * ┌─ FU indicator (1B) ──┐  ┌─ FU header (1B) ─────────────────┐
     * │ F(1) NRI(2) type=28  │  │ S(1) E(1) R(1) original NAL type │
     * └──────────────────────┘  └──────────────────────────────────┘
     * ```
     * The original NAL unit header is reconstructed from NRI + original NAL type.
     *
     * SECURITY: the assembled unit is capped at [MAX_FUA_BYTES]. A fragment that would
     * cross the cap is discarded together with the incomplete unit, so a peer that never
     * sends the end bit cannot grow the buffer without limit (audit SEC-03).
     *
     * @return Updated accumulator, or null when the NAL unit is complete or on error.
     */
    private fun handleFuaFragment(
        rtpFrame: ByteArray,
        payloadOffset: Int,
        ptsUs: Long,
        fuaAccumulator: ByteArrayOutputStream?,
        onVideoNalUnit: (ByteArray, Long) -> Unit
    ): ByteArrayOutputStream? {
        if (payloadOffset + 1 >= rtpFrame.size) {
            Logger.w("RtpInterleaved: FU-A packet has no FU header — discarding")
            return null
        }

        val fuIndicator = rtpFrame[payloadOffset    ].toInt() and 0xFF
        val fuHeader    = rtpFrame[payloadOffset + 1].toInt() and 0xFF
        val isStart = (fuHeader and 0x80) != 0
        val isEnd   = (fuHeader and 0x40) != 0

        val accumulator: ByteArrayOutputStream
        if (isStart) {
            if (fuaAccumulator != null) {
                Logger.w("RtpInterleaved: new FU-A start discards incomplete previous fragment")
            }
            // Reconstruct the original NAL unit header: NRI from FU indicator + type from FU header
            val reconstitutedHeader = ((fuIndicator and 0x60) or (fuHeader and 0x1F)).toByte()
            accumulator = ByteArrayOutputStream()
            accumulator.write(reconstitutedHeader.toInt())
        } else {
            if (fuaAccumulator == null) {
                Logger.w("RtpInterleaved: FU-A fragment without start bit — discarding")
                return null
            }
            accumulator = fuaAccumulator
        }

        // Append the fragment data (skip FU indicator byte + FU header byte)
        val fragmentStart = payloadOffset + 2
        val fragmentBytes = if (fragmentStart < rtpFrame.size) rtpFrame.size - fragmentStart else 0

        // SECURITY (security audit 2026-10-24, SEC-03): the reassembled NAL unit is
        // bounded. The frame cap above only bounds a single RTP packet — a peer that
        // opens an FU-A unit and never sends the end bit would otherwise grow the
        // accumulator without limit and kill the process with an OutOfMemoryError.
        // The check happens BEFORE the append, so the buffer never exceeds the limit.
        // Only the size is logged, never the content.
        if (accumulator.size().toLong() + fragmentBytes > MAX_FUA_BYTES) {
            Logger.w(
                "RtpInterleaved: FU-A unit exceeds the limit of $MAX_FUA_BYTES B " +
                    "(${accumulator.size()} B assembled + $fragmentBytes B fragment) — discarding"
            )
            // Drop the fragment AND the incomplete unit. The next FU-A start fragment
            // therefore begins a clean unit instead of continuing a truncated one.
            return null
        }

        if (fragmentBytes > 0) {
            accumulator.write(rtpFrame, fragmentStart, fragmentBytes)
        }

        return if (isEnd) {
            onVideoNalUnit(accumulator.toByteArray(), ptsUs)
            null  // reassembly complete — clear accumulator
        } else {
            accumulator  // still accumulating
        }
    }

}
