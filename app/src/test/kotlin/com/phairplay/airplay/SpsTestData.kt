package com.phairplay.airplay

/**
 * SpsTestData — shared test data builders for the H.264 SPS tests.
 *
 * WHY: the SPS is a bit stream, so the tests cannot simply write bytes — they have to write
 * individual bits and Exp-Golomb codes. [SpsBitWriter] is the exact inverse of
 * [H264SpsParser.SpsBitReader], and [buildBaselineSps] assembles a syntactically valid
 * Baseline/Main SPS. Both are used by [H264SpsParserTest] and [H264SpsParserHardeningTest],
 * so they live in one place instead of being copied around.
 */
internal object SpsTestData {

    /**
     * Builds a minimal but valid H.264 SPS byte array for Baseline or Main profile.
     *
     * pic_order_cnt_type = 0 (most common) is hard-coded.
     */
    fun buildBaselineSps(
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
    class SpsBitWriter {
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
