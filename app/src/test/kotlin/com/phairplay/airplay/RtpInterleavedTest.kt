package com.phairplay.airplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * RtpInterleavedTest — Unit tests for [RtpInterleaved].
 *
 * WHY: [RtpInterleaved] reads binary data from the network. It is critical that:
 * - Valid `$`-framed RTP frames are parsed correctly
 * - Non-`$` bytes are skipped without crashing (keep-alive RTSP text)
 * - Oversized frames are rejected (DoS prevention)
 * - EOF is handled cleanly
 * - RTP header is parsed correctly and presentation timestamp is computed
 * - A fragmented NAL unit is bounded, so a peer that never finishes it cannot
 *   exhaust the heap (security audit 2026-10-24, SEC-03)
 *
 * HOW: We use [ByteArrayInputStream] to simulate the TCP InputStream,
 * avoiding real network sockets. The bound of the FU-A accumulator is not visible
 * from the outside — an over-long unit is simply never delivered — so the tests
 * also plant a Timber tree and watch for the warning that reports the refusal.
 */
class RtpInterleavedTest {

    /**
     * A Timber tree that keeps the formatted messages instead of writing them to logcat.
     * The app itself never installs such a tree — this one exists for the test only.
     */
    private class CapturingTree : Timber.Tree() {
        val messages = mutableListOf<String>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages.add(message)
        }
    }

    private val tree = CapturingTree()

    @Before
    fun setUp() {
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    // ─── Frame parsing ────────────────────────────────────────────────────────

    @Test
    fun `valid video RTP frame triggers onVideoNalUnit callback`() {
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 90000L)
        val stream = buildInterleavedStream(channel = 0, payload = rtp)

        var nalUnitReceived: ByteArray? = null
        var ptsReceived: Long = -1

        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { nal, pts ->
                nalUnitReceived = nal
                ptsReceived = pts
            },
            onStreamEnded = {}
        )

        assertNotNull("NAL unit should be received", nalUnitReceived)
        // 90000 RTP ticks @ 90kHz = 1 second = 1_000_000 µs
        assertEquals(1_000_000L, ptsReceived)
    }

    @Test
    fun `non-video channel (channel 1 = RTCP) does not trigger callback`() {
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 0L)
        val stream = buildInterleavedStream(channel = 1, payload = rtp)  // RTCP

        var callbackInvoked = false
        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { _, _ -> callbackInvoked = true },
            onStreamEnded = {}
        )

        assertTrue("RTCP channel should not trigger video callback", !callbackInvoked)
    }

    @Test
    fun `empty stream calls onStreamEnded without crash`() {
        val stream = ByteArrayInputStream(byteArrayOf())
        var streamEnded = false

        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { _, _ -> },
            onStreamEnded = { streamEnded = true }
        )

        assertTrue("onStreamEnded should be called on empty stream", streamEnded)
    }

    @Test
    fun `non-dollar bytes are skipped`() {
        // Prepend some non-'$' garbage bytes before a valid frame
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 90000L)
        val frame = buildInterleavedFrame(channel = 0, payload = rtp)
        val garbage = byteArrayOf(0x41, 0x42, 0x43)  // "ABC" — not '$'
        val data = garbage + frame

        var nalReceived = false
        RtpInterleaved.readLoop(
            inputStream = ByteArrayInputStream(data),
            onVideoNalUnit = { _, _ -> nalReceived = true },
            onStreamEnded = {}
        )

        assertTrue("NAL unit should be received after garbage bytes", nalReceived)
    }

    @Test
    fun `RTP timestamp zero produces zero presentation time`() {
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 0L)
        val stream = buildInterleavedStream(channel = 0, payload = rtp)

        var ptsReceived = -1L
        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { _, pts -> ptsReceived = pts },
            onStreamEnded = {}
        )

        assertEquals(0L, ptsReceived)
    }

    // ─── FU-A reassembly (security audit 2026-10-24, SEC-03) ───────────────

    @Test
    fun `a FU-A stream that never ends does not grow without bound`() {
        val delivered = readAll(overflowFrames())

        assertTrue(
            "an unfinished FU-A unit must never reach the decoder",
            delivered.isEmpty()
        )
        // The buffer only stays bounded because the over-long unit is refused. The
        // refusal is reported with the two sizes — and without the content.
        val refusal = tree.messages.singleOrNull { it.isLimitWarning() }
        assertNotNull("the refusal of the over-long FU-A unit must be reported", refusal)
        assertTrue("the warning must name the limit of the accumulator", refusal!!.contains("8388608"))
        assertTrue("the warning must report the size that was assembled", refusal.contains("assembled"))
    }

    @Test
    fun `the incomplete FU-A unit is dropped when the limit is exceeded`() {
        // An end fragment on its own. If the incomplete unit had been kept, this
        // fragment would complete it and the truncated NAL unit would reach the decoder.
        val endOnly = buildFuAInterleavedFrame(start = false, end = true, payload = ByteArray(16))

        val delivered = readAll(overflowFrames() + endOnly)

        assertTrue(
            "the truncated FU-A unit must be discarded, not completed",
            delivered.isEmpty()
        )
        assertTrue(
            "the refusal of the over-long FU-A unit must be reported",
            tree.messages.any { it.isLimitWarning() }
        )
    }

    @Test
    fun `a new FU-A start fragment works again after the limit was exceeded`() {
        // A fresh, complete FU-A unit after the overflow: start + one continuation + end.
        val payloadA = ByteArray(32) { 0x11 }
        val payloadB = ByteArray(32) { 0x22 }
        val recovery = listOf(
            buildFuAInterleavedFrame(start = true, end = false, payload = payloadA),
            buildFuAInterleavedFrame(start = false, end = false, payload = payloadB),
            buildFuAInterleavedFrame(start = false, end = true, payload = ByteArray(0))
        )

        val delivered = readAll(overflowFrames() + recovery)

        // The overflow must really have happened — otherwise the recovery below would
        // say nothing about the state of the accumulator.
        assertTrue(
            "the refusal of the over-long FU-A unit must be reported",
            tree.messages.any { it.isLimitWarning() }
        )
        assertEquals("exactly one NAL unit must be delivered", 1, delivered.size)
        // The reconstituted NAL header (NRI=3, type=5 → IDR) followed by the fragments.
        val expected = byteArrayOf(0x65.toByte()) + payloadA + payloadB
        assertTrue(
            "the recovered FU-A unit must be reassembled completely",
            expected.contentEquals(delivered[0])
        )
    }

    @Test
    fun `a normal FU-A stream is reassembled completely`() {
        val payloadA = ByteArray(1000) { 0x41 }
        val payloadB = ByteArray(2000) { 0x42 }
        val payloadC = ByteArray(3000) { 0x43 }
        val frames = listOf(
            buildFuAInterleavedFrame(start = true, end = false, payload = payloadA),
            buildFuAInterleavedFrame(start = false, end = false, payload = payloadB),
            buildFuAInterleavedFrame(start = false, end = true, payload = payloadC)
        )

        val delivered = readAll(frames)

        assertEquals("exactly one NAL unit must be delivered", 1, delivered.size)
        // The reconstituted NAL header (NRI=3, type=5 → IDR) followed by the fragments.
        val expected = byteArrayOf(0x65.toByte()) + payloadA + payloadB + payloadC
        assertEquals(
            "the reassembled NAL unit must have the expected length",
            expected.size,
            delivered[0].size
        )
        assertTrue(
            "the reassembled NAL unit must contain every fragment byte",
            expected.contentEquals(delivered[0])
        )
    }

    @Test
    fun `a large but legitimate FU-A unit below the limit is still reassembled`() {
        // 60 fragments of the maximum size ≈ 3.9 MB — well below the 8 MB limit, so the
        // unit is complete and must be delivered untouched.
        val frames = (0 until 60).map { index ->
            buildFuAInterleavedFrame(start = index == 0, end = index == 59, payload = maxFragment())
        }

        val delivered = readAll(frames)

        assertEquals("exactly one NAL unit must be delivered", 1, delivered.size)
        assertEquals(
            "the reassembled NAL unit must have the expected length",
            1 + 60 * MAX_FRAGMENT_PAYLOAD,
            delivered[0].size
        )
        assertEquals(
            "the reconstituted NAL header must be the first byte",
            0x65.toByte(),
            delivered[0][0]
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds a minimal valid RTP frame (12-byte header + 4-byte dummy NAL payload).
     *
     * @param timestampRtp90k RTP timestamp in 90 kHz clock ticks.
     */
    private fun buildMinimalVideoRtpFrame(timestampRtp90k: Long): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()  // V=2, P=0, X=0, CC=0
        header[1] = 0x60.toByte()  // M=0, PT=96
        header[2] = 0x00           // Seq high
        header[3] = 0x01           // Seq low = 1
        // Timestamp (big-endian 32-bit)
        header[4] = ((timestampRtp90k shr 24) and 0xFF).toByte()
        header[5] = ((timestampRtp90k shr 16) and 0xFF).toByte()
        header[6] = ((timestampRtp90k shr  8) and 0xFF).toByte()
        header[7] = ( timestampRtp90k         and 0xFF).toByte()
        // SSRC = 0 (bytes 8–11 already zero)
        val payload = byteArrayOf(0x65, 0x00, 0x00, 0x00)  // fake IDR NAL unit
        return header + payload
    }

    /**
     * Builds an interleaved frame that carries one H.264 FU-A fragment (RFC 6184 §5.8).
     *
     * The RTP payload is `FU indicator (0x7C) | FU header (S/E/R + type 5) | fragment data`.
     *
     * @param start   sets the start bit (S) of the FU header.
     * @param end     sets the end bit (E) of the FU header.
     * @param payload the fragment data (without the FU indicator and the FU header).
     */
    private fun buildFuAInterleavedFrame(start: Boolean, end: Boolean, payload: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()  // V=2, P=0, X=0, CC=0
        header[1] = 0x60.toByte()  // M=0, PT=96
        // Timestamp (big-endian 32-bit); a fixed value keeps the test deterministic.
        header[4] = 0x00
        header[5] = 0x01
        header[6] = 0x86.toByte()  // 90 000 ticks = 1 s
        header[7] = 0xA0.toByte()
        // SSRC = 0 (bytes 8–11 already zero)

        val fuIndicator = 0x7C  // F=0, NRI=3, type=28 (FU-A)
        var fuHeader = 0x05     // R=0, original NAL type = 5 (IDR)
        if (start) fuHeader = fuHeader or 0x80
        if (end) fuHeader = fuHeader or 0x40

        val rtpFrame = header + byteArrayOf(fuIndicator.toByte(), fuHeader.toByte()) + payload
        return buildInterleavedFrame(channel = 0, payload = rtpFrame)
    }

    /**
     * Feeds the given interleaved frames into the read loop.
     *
     * @return the NAL units that reached the decoder.
     */
    private fun readAll(frames: List<ByteArray>): List<ByteArray> {
        val delivered = mutableListOf<ByteArray>()
        RtpInterleaved.readLoop(
            inputStream = ByteArrayInputStream(concat(frames)),
            onVideoNalUnit = { nal, _ -> delivered.add(nal) },
            onStreamEnded = {}
        )
        return delivered
    }

    /**
     * The fragments of a FU-A unit that never ends: a start fragment followed by
     * continuations — far more than the 8 MB budget of the accumulator.
     */
    private fun overflowFrames(): List<ByteArray> =
        (0 until 160).map { index ->
            buildFuAInterleavedFrame(start = index == 0, end = false, payload = maxFragment())
        }

    /** True when the message is the warning that reports the refusal of an over-long unit. */
    private fun String.isLimitWarning(): Boolean = contains("FU-A") && contains("exceeds the limit")

    /**
     * The largest fragment that fits into one interleaved frame.
     *
     * WHY: the interleaved frame header carries a 16-bit length field (RFC 2326 §10.12),
     * so one RTP packet on the TCP channel is at most 65 535 bytes. The RTP header
     * (12 bytes) and the FU indicator plus FU header (2 bytes) are not fragment data.
     */
    private fun maxFragment(): ByteArray = ByteArray(MAX_FRAGMENT_PAYLOAD) { 0x5A }

    /** Concatenates the given chunks into one buffer. */
    private fun concat(chunks: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (chunk in chunks) out.write(chunk, 0, chunk.size)
        return out.toByteArray()
    }

    /** Wraps [payload] in an interleaved `$` frame with the given [channel]. */
    private fun buildInterleavedFrame(channel: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        return byteArrayOf(
            0x24,                    // '$'
            channel.toByte(),
            (len shr 8).toByte(),    // length high byte
            (len and 0xFF).toByte()  // length low byte
        ) + payload
    }

    /** Creates a [ByteArrayInputStream] containing a single interleaved frame. */
    private fun buildInterleavedStream(channel: Int, payload: ByteArray): ByteArrayInputStream =
        ByteArrayInputStream(buildInterleavedFrame(channel, payload))

    private companion object {
        /** The biggest fragment data that fits into one interleaved frame. */
        const val MAX_FRAGMENT_PAYLOAD = 65535 - 12 - 2
    }
}
