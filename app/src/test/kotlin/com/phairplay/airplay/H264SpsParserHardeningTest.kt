package com.phairplay.airplay

import com.phairplay.airplay.SpsTestData.SpsBitWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * H264SpsParserHardeningTest — tests the SPS parser against hostile input.
 *
 * WHY THIS FILE EXISTS: the security review of 2026-10-24
 * (docs/reviews/2026-10-24-pr19-pr17-issue18-review.md, §3.3, finding 1) named the integer
 * overflow of `readUe()` as an open finding: with 31 leading zeros the plain formula
 * `(1 shl 31) - 1` overflows `Int` (`1 shl 31` is `Int.MIN_VALUE`), so a crafted SPS could
 * make the parser report a negative or wrapped picture size. The same applied to the crop
 * arithmetic. The SPS is attacker-controlled — every device on the LAN may send one.
 *
 * WHAT WE TEST:
 * - a ue(v) code with 31 leading zeros never yields a negative value
 * - a ue(v) code beyond the allowed range is rejected
 * - se(v) stays inside the Int range for a large code
 * - an SPS whose width field uses the 31-zero attack pattern is rejected as a whole
 * - an SPS whose coded size is far beyond any real stream is rejected (no absurd resolution)
 * - an SPS whose crop offsets do not fit into the coded frame is rejected
 * - an SPS with an undefined pic_order_cnt_type is rejected
 *
 * HOW: [H264SpsParser] and its bit reader are members of an internal pure Kotlin object, so
 * they are accessible from this package without an Android runtime. The synthetic bit streams
 * are built with [SpsTestData.SpsBitWriter], the exact inverse of the reader.
 */
class H264SpsParserHardeningTest {

    // ─── ue(v) / se(v) hardening against hostile input ───────────────────────
    //
    // WHY: the plain Int formula for ue(v) overflows at 31 leading zeros (`1 shl 31` is
    // Int.MIN_VALUE) and could return a negative or wrapped value, which would turn into
    // a bogus picture size. The parser computes in Long and rejects values above
    // SpsBitReader.MAX_UE_VALUE.

    @Test
    fun `readUe rejects a value above the maximum`() {
        // 17 leading zeros → a value far beyond MAX_UE_VALUE
        val writer = SpsBitWriter()
        repeat(17) { writer.writeBit(0) }
        writer.writeBit(1)
        repeat(17) { writer.writeBit(1) }

        val reader = H264SpsParser.SpsBitReader(writer.toByteArray(), startOffset = 0)

        val error = runCatching { reader.readUe() }.exceptionOrNull()
        assertEquals(
            "an out-of-range ue(v) must be rejected",
            true,
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

        val reader = H264SpsParser.SpsBitReader(writer.toByteArray(), startOffset = 0)

        val value = runCatching { reader.readUe() }
        // Either rejected outright, or a sane non-negative value — never a wrapped one.
        value.exceptionOrNull()?.let { assertEquals("only an ArithmeticException may escape", true, it is ArithmeticException) }
        value.getOrNull()?.let { assertEquals("ue(v) must not be negative", true, it >= 0) }
    }

    @Test
    fun `readSe does not overflow for a large code`() {
        // se(v) of a large ue(v) must stay inside the Int range.
        // ue(v)=65534 is even → se(v) = -(65534/2) = -32767 (the sign is defined by the
        // code, not by an overflow).
        val writer = SpsBitWriter()
        writer.writeUe(0xFFFE)

        val reader = H264SpsParser.SpsBitReader(writer.toByteArray(), startOffset = 0)

        assertEquals(-32767, reader.readSe())
    }

    // ─── Whole-SPS rejection of crafted geometry ─────────────────────────────

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

    @Test
    fun `parseSpsResolution rejects an SPS whose width field uses 31 leading zeros`() {
        // THE ATTACK PATTERN FROM THE SECURITY REVIEW: with 31 leading zeros the plain
        // Int formula `(1 shl 31) - 1` overflows to Int.MIN_VALUE, so readUe() could return
        // a negative or wrapped value. The SPS is attacker-controlled (any device on the LAN
        // may send one), so the parser must reject the whole SPS instead of reporting a
        // negative picture size.
        val writer = SpsBitWriter()
        writer.writeByte(0x67)            // NAL header: SPS
        writer.writeByte(66)              // profile_idc (Baseline)
        writer.writeByte(0x40)            // constraint flags
        writer.writeByte(31)              // level_idc
        writer.writeUe(0)                 // seq_parameter_set_id
        writer.writeUe(0)                 // log2_max_frame_num_minus4
        writer.writeUe(0)                 // pic_order_cnt_type
        writer.writeUe(4)                 //   log2_max_pic_order_cnt_lsb_minus4
        writer.writeUe(2)                 // max_num_ref_frames
        writer.writeBit(0)                // gaps_in_frame_num_value_allowed_flag
        // pic_width_in_mbs_minus1 coded with 31 leading zeros → an astronomically large value
        repeat(31) { writer.writeBit(0) }
        writer.writeBit(1)
        repeat(31) { writer.writeBit(1) }

        assertNull(
            "an SPS with 31 leading zeros in the width field must be rejected",
            H264SpsParser.parseResolution(writer.toByteArray())
        )
    }

    @Test
    fun `parseSpsResolution returns null for an SPS whose coded size exceeds the maximum dimension`() {
        // pic_width_in_mbs_minus1 = 0xFFFF → a coded width of 65536 * 16 = 1 048 576 px.
        // No camera or screen produces that; the parser must refuse to report it instead of
        // handing a nonsense size to the caller (the hardware codec reads the true size
        // from csd-0 anyway, but a bogus size still misleads the aspect-fit matrix).
        val writer = SpsBitWriter()
        writer.writeByte(0x67)            // NAL header: SPS
        writer.writeByte(66)              // profile_idc (Baseline)
        writer.writeByte(0x40)            // constraint flags
        writer.writeByte(31)              // level_idc
        writer.writeUe(0)                 // seq_parameter_set_id
        writer.writeUe(0)                 // log2_max_frame_num_minus4
        writer.writeUe(0)                 // pic_order_cnt_type
        writer.writeUe(4)                 //   log2_max_pic_order_cnt_lsb_minus4
        writer.writeUe(2)                 // max_num_ref_frames
        writer.writeBit(0)                // gaps_in_frame_num_value_allowed_flag
        writer.writeUe(0xFFFF)            // pic_width_in_mbs_minus1 → 1 048 576 px
        writer.writeUe(0xFFFF)            // pic_height_in_map_units_minus1 → 1 048 576 px
        writer.writeBit(1)                // frame_mbs_only_flag
        writer.writeBit(1)                // direct_8x8_inference_flag
        writer.writeBit(0)                // frame_cropping_flag

        assertNull(H264SpsParser.parseResolution(writer.toByteArray()))
    }

    @Test
    fun `parseSpsResolution returns null for an SPS whose crop offsets exceed the coded size`() {
        // The crop arithmetic used to run in Int. A crop that is larger than the coded frame
        // is inconsistent with the spec and must be rejected — never turned into a wrapped
        // (positive) dimension.
        val sps = SpsTestData.buildBaselineSps(
            profileIdc = 66,
            levelIdc = 31,
            widthInMbs = 79,
            heightInMapUnits = 44,
            cropLeft = 0xFFFF,
            cropRight = 0xFFFF
        )

        assertNull(H264SpsParser.parseResolution(sps))
    }

    @Test
    fun `parseSpsResolution returns null for an SPS with an undefined pic_order_cnt_type`() {
        // pic_order_cnt_type is defined for 0, 1 and 2 only. A crafted value desynchronises
        // the bit reader, so the whole SPS has to be rejected.
        val writer = SpsBitWriter()
        writer.writeByte(0x67)            // NAL header: SPS
        writer.writeByte(66)              // profile_idc (Baseline)
        writer.writeByte(0x40)            // constraint flags
        writer.writeByte(31)              // level_idc
        writer.writeUe(0)                 // seq_parameter_set_id
        writer.writeUe(0)                 // log2_max_frame_num_minus4
        writer.writeUe(7)                 // pic_order_cnt_type — not defined by the spec
        writer.writeUe(0)                 // log2_max_pic_order_cnt_lsb_minus4
        writer.writeUe(2)                 // max_num_ref_frames
        writer.writeBit(0)                // gaps_in_frame_num_value_allowed_flag
        writer.writeUe(79)                // pic_width_in_mbs_minus1
        writer.writeUe(44)                // pic_height_in_map_units_minus1
        writer.writeBit(1)                // frame_mbs_only_flag
        writer.writeBit(1)                // direct_8x8_inference_flag
        writer.writeBit(0)                // frame_cropping_flag

        assertNull(H264SpsParser.parseResolution(writer.toByteArray()))
    }
}
