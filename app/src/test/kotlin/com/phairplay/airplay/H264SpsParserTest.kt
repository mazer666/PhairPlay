package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * - [H264SpsParser.SpsBitReader] ue(v): 0, 1, 2 patterns
 * - [H264SpsParser.SpsBitReader] se(v): 0, +1, −1 patterns
 * - [H264SpsParser.SpsBitReader] bit/byte boundary crossing
 *
 * The hostile-input cases (the integer overflow of the security review) live in
 * [H264SpsParserHardeningTest].
 *
 * HOW: [H264SpsParser.parseResolution] and [H264SpsParser.SpsBitReader] are members of an internal pure Kotlin object, so they are accessible from this package without
 * Android runtime dependencies. We build synthetic SPS byte arrays using a helper
 * [SpsBitWriter] that is the inverse of [H264SpsParser.SpsBitReader].
 */
class H264SpsParserTest {

    // ─── SpsBitReader unit tests ──────────────────────────────────────────────

    @Test
    fun `SpsBitReader readBit reads MSB first`() {
        // 0x80 = 1000_0000: first bit = 1, second bit = 0
        val reader = H264SpsParser.SpsBitReader(byteArrayOf(0x80.toByte()), startOffset = 0)
        assertEquals(1, reader.readBit())
        assertEquals(0, reader.readBit())
    }

    @Test
    fun `SpsBitReader readBits reads 8 bits as unsigned byte`() {
        // 0x42 = 0100_0010 = 66
        val reader = H264SpsParser.SpsBitReader(byteArrayOf(0x42), startOffset = 0)
        assertEquals(0x42, reader.readBits(8))
    }

    @Test
    fun `SpsBitReader readBits crosses byte boundary correctly`() {
        // 0xFF 0x00 — reading 12 bits: 1111_1111_0000 = 0xFF0 = 4080
        val reader = H264SpsParser.SpsBitReader(byteArrayOf(0xFF.toByte(), 0x00), startOffset = 0)
        assertEquals(0xFF0, reader.readBits(12))
    }

    @Test
    fun `SpsBitReader readUe returns 0 for leading-one bit pattern`() {
        // ue(v) = 0 → encoded as single "1" bit
        val reader = H264SpsParser.SpsBitReader(byteArrayOf(0x80.toByte()), startOffset = 0)
        assertEquals(0, reader.readUe())
    }

    @Test
    fun `SpsBitReader readUe returns 1 for 010 bit pattern`() {
        // ue(v) = 1 → "010" → 0x40 = 0100_0000 (MSB)
        val reader = H264SpsParser.SpsBitReader(byteArrayOf(0x40), startOffset = 0)
        assertEquals(1, reader.readUe())
    }

    @Test
    fun `SpsBitReader readUe returns 2 for 011 bit pattern`() {
        // ue(v) = 2 → "011" → 0x60 = 0110_0000
        val reader = H264SpsParser.SpsBitReader(byteArrayOf(0x60), startOffset = 0)
        assertEquals(2, reader.readUe())
    }

    @Test
    fun `SpsBitReader readSe returns 0 for ue=0`() {
        val reader = H264SpsParser.SpsBitReader(byteArrayOf(0x80.toByte()), startOffset = 0)
        assertEquals(0, reader.readSe())
    }

    @Test
    fun `SpsBitReader readSe returns positive 1 for ue=1`() {
        // se(v): k=1 (odd) → +(1+1)/2 = +1
        val reader = H264SpsParser.SpsBitReader(byteArrayOf(0x40), startOffset = 0)
        assertEquals(1, reader.readSe())
    }

    @Test
    fun `SpsBitReader readSe returns negative 1 for ue=2`() {
        // se(v): k=2 (even) → -(2/2) = -1
        val reader = H264SpsParser.SpsBitReader(byteArrayOf(0x60), startOffset = 0)
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

    // ─── Known-good SPS byte arrays ───────────────────────────────────────────

    @Test
    fun `parseSpsResolution parses Baseline 1280x720 SPS correctly`() {
        // Baseline (profile_idc=66), level=31
        // pic_width_in_mbs_minus1=79  → (79+1)*16 = 1280
        // pic_height_in_map_units_minus1=44 → (44+1)*16 = 720
        val sps = SpsTestData.buildBaselineSps(profileIdc = 66, levelIdc = 31,
                                   widthInMbs = 79, heightInMapUnits = 44)
        assertEquals(Pair(1280, 720), H264SpsParser.parseResolution(sps))
    }

    @Test
    fun `parseSpsResolution parses Main 1920x1088 SPS correctly`() {
        // Main (profile_idc=77), level=40
        // pic_width_in_mbs_minus1=119  → (119+1)*16 = 1920
        // pic_height_in_map_units_minus1=67 → (67+1)*16 = 1088
        val sps = SpsTestData.buildBaselineSps(profileIdc = 77, levelIdc = 40,
                                   widthInMbs = 119, heightInMapUnits = 67)
        assertEquals(Pair(1920, 1088), H264SpsParser.parseResolution(sps))
    }

    @Test
    fun `parseSpsResolution applies frame cropping offsets`() {
        // 1920x1088 coded with bottom crop of 4 luma lines -> 1920x1080
        val sps = SpsTestData.buildBaselineSps(
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
        val sps = SpsTestData.buildBaselineSps(
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
        val sps = SpsTestData.buildBaselineSps(profileIdc = 66, levelIdc = 31,
            widthInMbs = 79, heightInMapUnits = 44)
        assertEquals(Pair(1280, 720), H264SpsParser.parseResolution(byteArrayOf(0, 0, 0, 1) + sps))
    }

    @Test
    fun `parseSpsResolution accepts a three byte Annex B start code`() {
        val sps = SpsTestData.buildBaselineSps(profileIdc = 77, levelIdc = 40,
            widthInMbs = 119, heightInMapUnits = 67)
        assertEquals(Pair(1920, 1088), H264SpsParser.parseResolution(byteArrayOf(0, 0, 1) + sps))
    }
}
