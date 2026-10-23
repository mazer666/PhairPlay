package com.phairplay.airplay

import com.phairplay.airplay.H264SpsParser.SpsBitReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H264SpsParserTest — Tests for S6-3: H.264 SPS resolution parsing.
 *
 * WHY: [VideoDecoder] must configure MediaCodec with the correct video resolution.
 * AirPlay SDP does not include explicit width/height fields — the actual resolution
 * is encoded in the H.264 SPS NAL unit via Exp-Golomb–coded fields.
 * Passing wrong dimensions to MediaCodec produces corrupt or rejected decode output.
 *
 * WHAT WE TEST:
 * - [H264SpsParser.parseResolution] for Baseline 1280×720
 * - [H264SpsParser.parseResolution] for Main 1920×1088 (1080 rounds up to next MB)
 * - Malformed SPS (too short / all-zeros) → null (graceful fallback)
 * - [SpsBitReader] ue(v): 0, 1, 2 patterns
 * - [SpsBitReader] se(v): 0, +1, −1 patterns
 * - [SpsBitReader] bit/byte boundary crossing
 *
 * HOW: [H264SpsParser.parseResolution] and [H264SpsParser.SpsBitReader] are members of an internal pure Kotlin object, so they are accessible from this package without
 * Android runtime dependencies. We build synthetic SPS byte arrays using a helper
 * [SpsBitWriter] that is the inverse of [SpsBitReader].
 */
class H264SpsParserTest {

    // ─── SpsBitReader unit tests ──────────────────────────────────────────────

    @Test
    fun `SpsBitReader readBit reads MSB first`() {
        // 0x80 = 1000_0000: first bit = 1, second bit = 0
        val reader = SpsBitReader(byteArrayOf(0x80.toByte()), startOffset = 0)
        assertEquals(1, reader.readBit())
        assertEquals(0, reader.readBit())
    }

    @Test
    fun `SpsBitReader readBits reads 8 bits as unsigned byte`() {
        // 0x42 = 0100_0010 = 66
        val reader = SpsBitReader(byteArrayOf(0x42), startOffset = 0)
        assertEquals(0x42, reader.readBits(8))
    }

    @Test
    fun `SpsBitReader readBits crosses byte boundary correctly`() {
        // 0xFF 0x00 — reading 12 bits: 1111_1111_0000 = 0xFF0 = 4080
        val reader = SpsBitReader(byteArrayOf(0xFF.toByte(), 0x00), startOffset = 0)
        assertEquals(0xFF0, reader.readBits(12))
    }

    @Test
    fun `SpsBitReader readUe returns 0 for leading-one bit pattern`() {
        // ue(v) = 0 → encoded as single "1" bit
        val reader = SpsBitReader(byteArrayOf(0x80.toByte()), startOffset = 0)
        assertEquals(0, reader.readUe())
    }

    @Test
    fun `SpsBitReader readUe returns 1 for 010 bit pattern`() {
        // ue(v) = 1 → "010" → 0x40 = 0100_0000 (MSB)
        val reader = SpsBitReader(byteArrayOf(0x40), startOffset = 0)
        assertEquals(1, reader.readUe())
    }

    @Test
    fun `SpsBitReader readUe returns 2 for 011 bit pattern`() {
        // ue(v) = 2 → "011" → 0x60 = 0110_0000
        val reader = SpsBitReader(byteArrayOf(0x60), startOffset = 0)
        assertEquals(2, reader.readUe())
    }

    @Test
    fun `SpsBitReader readSe returns 0 for ue=0`() {
        val reader = SpsBitReader(byteArrayOf(0x80.toByte()), startOffset = 0)
        assertEquals(0, reader.readSe())
    }

    @Test
    fun `SpsBitReader readSe returns positive 1 for ue=1`() {
        // se(v): k=1 (odd) → +(1+1)/2 = +1
        val reader = SpsBitReader(byteArrayOf(0x40), startOffset = 0)
        assertEquals(1, reader.readSe())
    }

    @Test
    fun `SpsBitReader readSe returns negative 1 for ue=2`() {
        // se(v): k=2 (even) → -(2/2) = -1
        val reader = SpsBitReader(byteArrayOf(0x60), startOffset = 0)
        assertEquals(-1, reader.readSe())
    }

    // ─── parseSpsResolution boundary conditions ───────────────────────────────

    @Test
    fun `parseSpsResolution returns null for empty SPS`() {
        assertNull(H264SpsParser.parseResolution(ByteArray(0)))
    }

    @Test
    fun `parseSpsResolution returns null for single-byte SPS`() {
        assertNull(H264SpsParser.parseResolution(byteArrayOf(0x67.toByte())))
    }

    @Test
    fun `parseSpsResolution returns null for three-byte SPS (too short for profile fields)`() {
        assertNull(H264SpsParser.parseResolution(byteArrayOf(0x67.toByte(), 0x42, 0x00)))
    }

    @Test
    fun `parseSpsResolution returns null for all-zeros SPS (infinite leading zeros in ue(v))`() {
        // All-zero bit stream → ue(v) leadingZeros counter overflows → ArithmeticException → null
        assertNull(H264SpsParser.parseResolution(ByteArray(32)))
    }

    // ─── ue(v) hardening against hostile input ────────────────────────────────
    //
    // WHY: the SPS is attacker-controlled input — any device on the LAN can send one.
    // The plain Int formula for ue(v) overflows at 31 leading zeros (`1 shl 31` is
    // Int.MIN_VALUE) and could return a negative or wrapped value, which would turn into
    // a bogus picture size. The parser now computes in Long and rejects values above
    // SpsBitReader.MAX_UE_VALUE.

    @Test
    fun `readUe rejects a value above the maximum`() {
        // 17 leading zeros → a value far beyond MAX_UE_VALUE
        val writer = SpsBitWriter()
        repeat(17) { writer.writeBit(0) }
        writer.writeBit(1)
        repeat(17) { writer.writeBit(1) }

        val reader = SpsBitReader(writer.toByteArray(), startOffset = 0)

        val error = runCatching { reader.readUe() }.exceptionOrNull()
        assertTrue(
            "an out-of-range ue(v) must be rejected",
            error is ArithmeticException
        )
    }

    @Test
    fun `readUe never returns a negative value for a long code`() {
        // 31 leading zeros — the case where the plain Int formula overflows
        val writer = SpsBitWriter()
        repeat(31) { writer.writeBit(0) }
        writer.writeBit(1)
        repeat(31) { writer.writeBit(1) }

        val reader = SpsBitReader(writer.toByteArray(), startOffset = 0)

        val value = runCatching { reader.readUe() }
        // Either rejected outright, or a sane non-negative value — never a wrapped one.
        value.exceptionOrNull()?.let { assertTrue(it is ArithmeticException) }
        value.getOrNull()?.let { assertTrue("ue(v) must not be negative, was $it", it >= 0) }
    }

    @Test
    fun `readSe does not overflow for a large code`() {
        // se(v) of a large ue(v) must stay inside the Int range.
        // ue(v)=65534 is even → se(v) = -(65534/2) = -32767 (the sign is defined by the
        // code, not by an overflow).
        val writer = SpsBitWriter()
        writer.writeUe(0xFFFE)

        val reader = SpsBitReader(writer.toByteArray(), startOffset = 0)

        assertEquals(-32767, reader.readSe())
    }

    @Test
    fun `parseSpsResolution returns null for an SPS with an oversized dimension`() {
        // A crafted SPS whose width field is far beyond any real stream must not yield
        // a resolution — the parser rejects it and the caller falls back to its hint.
        val writer = SpsBitWriter()
        writer.writeByte(0x67)            // NAL header: SPS
        writer.writeByte(66)              // profile_idc (Baseline)
        writer.writeByte(0x1F)            // constraint flags + reserved
        writer.writeByte(31)              // level_idc
        writer.writeUe(0)                 // seq_parameter_set_id
        writer.writeUe(0xFFFF)            // log2_max_frame_num_minus4 (out of range)

        assertNull(H264SpsParser.parseResolution(writer.toByteArray()))
    }

    // ─── Known-good SPS byte arrays ───────────────────────────────────────────

    @Test
    fun `parseSpsResolution parses Baseline 1280x720 SPS correctly`() {
        // Baseline (profile_idc=66), level=31
        // pic_width_in_mbs_minus1=79  → (79+1)*16 = 1280
        // pic_height_in_map_units_minus1=44 → (44+1)*16 = 720
        val sps = buildBaselineSps(profileIdc = 66, levelIdc = 31,
                                   widthInMbs = 79, heightInMapUnits = 44)
        assertEquals(Pair(1280, 720), H264SpsParser.parseResolution(sps))
    }

    @Test
    fun `parseSpsResolution parses Main 1920x1088 SPS correctly`() {
        // Main (profile_idc=77), level=40
        // pic_width_in_mbs_minus1=119  → (119+1)*16 = 1920
        // pic_height_in_map_units_minus1=67 → (67+1)*16 = 1088
        val sps = buildBaselineSps(profileIdc = 77, levelIdc = 40,
                                   widthInMbs = 119, heightInMapUnits = 67)
        assertEquals(Pair(1920, 1088), H264SpsParser.parseResolution(sps))
    }

    @Test
    fun `parseSpsResolution applies frame cropping offsets`() {
        // 1920x1088 coded with bottom crop of 4 luma lines -> 1920x1080
        val sps = buildBaselineSps(
            profileIdc = 77,
            levelIdc = 40,
            widthInMbs = 119,
            heightInMapUnits = 67,
            frameMbsOnlyFlag = 1,
            cropBottom = 4
        )
        assertEquals(Pair(1920, 1080), H264SpsParser.parseResolution(sps))
    }

    @Test
    fun `parseSpsResolution handles interlaced frame dimensions`() {
        // frame_mbs_only_flag=0 doubles coded height: (44+1)*16*2 = 1440
        val sps = buildBaselineSps(
            profileIdc = 66,
            levelIdc = 31,
            widthInMbs = 79,
            heightInMapUnits = 44,
            frameMbsOnlyFlag = 0
        )
        assertEquals(Pair(1280, 1440), H264SpsParser.parseResolution(sps))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    @Test
    fun `parseSpsResolution accepts the four byte start code used by mirroring`() {
        val sps = buildBaselineSps(profileIdc = 66, levelIdc = 31,
            widthInMbs = 79, heightInMapUnits = 44)
        assertEquals(Pair(1280, 720), H264SpsParser.parseResolution(byteArrayOf(0, 0, 0, 1) + sps))
    }

    @Test
    fun `parseSpsResolution accepts a three byte Annex B start code`() {
        val sps = buildBaselineSps(profileIdc = 77, levelIdc = 40,
            widthInMbs = 119, heightInMapUnits = 67)
        assertEquals(Pair(1920, 1088), H264SpsParser.parseResolution(byteArrayOf(0, 0, 1) + sps))
    }

    /**
     * Builds a minimal but valid H.264 SPS byte array for Baseline or Main profile.
     * Uses [SpsBitWriter] to produce the exact Exp-Golomb bit patterns that
     * [H264SpsParser.SpsBitReader] expects.
     *
     * pic_order_cnt_type = 0 (most common) is hard-coded.
     */
    private fun buildBaselineSps(
        profileIdc: Int,
        levelIdc: Int,
        widthInMbs: Int,
        heightInMapUnits: Int,
        frameMbsOnlyFlag: Int = 1,
        cropLeft: Int = 0,
        cropRight: Int = 0,
        cropTop: Int = 0,
        cropBottom: Int = 0
    ): ByteArray {
        val w = SpsBitWriter()
        w.writeByte(0x67)       // NAL type = SPS
        w.writeByte(profileIdc) // profile_idc
        w.writeByte(0x40)       // constraint flags
        w.writeByte(levelIdc)   // level_idc

        w.writeUe(0)   // seq_parameter_set_id
        // No high-profile extras (Baseline/Main profiles don't have them)
        w.writeUe(0)   // log2_max_frame_num_minus4
        w.writeUe(0)   // pic_order_cnt_type = 0
        w.writeUe(4)   //   log2_max_pic_order_cnt_lsb_minus4 (value = 4 = e.g. typical)
        w.writeUe(2)   // max_num_ref_frames
        w.writeBit(0)  // gaps_in_frame_num_value_allowed_flag

        w.writeUe(widthInMbs)       // pic_width_in_mbs_minus1
        w.writeUe(heightInMapUnits) // pic_height_in_map_units_minus1
        w.writeBit(frameMbsOnlyFlag) // frame_mbs_only_flag
        if (frameMbsOnlyFlag == 0) {
            w.writeBit(0)            // mb_adaptive_frame_field_flag
        }
        w.writeBit(1)               // direct_8x8_inference_flag
        val hasCropping = cropLeft != 0 || cropRight != 0 || cropTop != 0 || cropBottom != 0
        w.writeBit(if (hasCropping) 1 else 0) // frame_cropping_flag
        if (hasCropping) {
            w.writeUe(cropLeft)
            w.writeUe(cropRight)
            w.writeUe(cropTop)
            w.writeUe(cropBottom)
        }

        return w.toByteArray()
    }

    /**
     * Inverse of [H264SpsParser.SpsBitReader]: writes bits MSB-first.
     * Used only to build synthetic SPS test vectors.
     */
    private class SpsBitWriter {
        private val bits = mutableListOf<Int>()

        fun writeBit(bit: Int) { bits.add(bit and 1) }
        fun writeByte(value: Int) = repeat(8) { i -> writeBit((value ushr (7 - i)) and 1) }

        fun writeUe(value: Int) {
            if (value == 0) { writeBit(1); return }
            val leadingZeros = Integer.numberOfTrailingZeros(Integer.highestOneBit(value + 1))
            repeat(leadingZeros) { writeBit(0) }
            writeBit(1)
            val suffix = value - (1 shl leadingZeros) + 1
            repeat(leadingZeros) { i -> writeBit(suffix ushr (leadingZeros - 1 - i) and 1) }
        }

        fun toByteArray(): ByteArray {
            val padded = bits.toMutableList().also { while (it.size % 8 != 0) it.add(0) }
            return ByteArray(padded.size / 8) { byteIdx ->
                var byte = 0
                repeat(8) { bitIdx -> byte = (byte shl 1) or padded[byteIdx * 8 + bitIdx] }
                byte.toByte()
            }
        }
    }
}
