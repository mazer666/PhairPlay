package com.phairplay.airplay

import com.phairplay.util.Logger

/**
 * Parses H.264 SPS dimensions without Android dependencies.
 * Keeps the codec's configured size consistent with raw or Annex-B-prefixed SPS input.
 * Call parseResolution(sps) before configuring MediaCodec; malformed input returns null.
 */
internal object H264SpsParser {
    /**
     * Parses the H.264 SPS NAL unit to extract the actual video resolution.
     *
     * WHY: AirPlay SDP does not include explicit width/height fields.
     * The true resolution is encoded inside the SPS (Sequence Parameter Set) NAL unit
     * as `pic_width_in_mbs_minus1` and `pic_height_in_map_units_minus1`.
     * Each macroblock (MB) is 16×16 pixels.
     *
     * Formula:
     *   width  = (pic_width_in_mbs_minus1  + 1) × 16
     *   height = (pic_height_in_map_units_minus1 + 1) × 16
     *
     * Handles Baseline (66), Main (77), and High profile (100+) SPS formats.
     * Returns null (graceful fallback) if the SPS is too short, malformed, or uses
     * an unsupported bitstream feature.
     *
     * Exposed as `internal` for unit testing without an Android runtime.
     *
     * @param sps SPS NAL bytes, optionally preceded by a three/four-byte Annex-B start code.
     * @return (width, height) in pixels, or null if parsing fails.
     */
    fun parseResolution(sps: ByteArray): Pair<Int, Int>? {
        try {
            val nalOffset = when {
                sps.size >= 4 && sps[0] == 0.toByte() && sps[1] == 0.toByte() &&
                    sps[2] == 0.toByte() && sps[3] == 1.toByte() -> 4
                sps.size >= 3 && sps[0] == 0.toByte() && sps[1] == 0.toByte() &&
                    sps[2] == 1.toByte() -> 3
                else -> 0
            }
            if (sps.size - nalOffset < 4 || (sps[nalOffset].toInt() and 0x1F) != 7) return null
            val reader = SpsBitReader(sps, startOffset = nalOffset + 1) // skip the SPS NAL header

            val profileIdc = reader.readBits(8)
            reader.readBits(8)   // constraint flags + 2 reserved zeros
            reader.readBits(8)   // level_idc
            reader.readUe()      // seq_parameter_set_id

            // Default for Baseline/Main profiles per H.264 spec.
            var chromaFormatIdc = 1
            var separateColorPlaneFlag = 0

            // High-profile and variants have extra fields before the common fields.
            val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
            if (profileIdc in highProfiles) {
                chromaFormatIdc = reader.readUe()
                if (chromaFormatIdc == 3) {
                    separateColorPlaneFlag = reader.readBits(1)  // separate_colour_plane_flag
                }
                reader.readUe()     // bit_depth_luma_minus8
                reader.readUe()     // bit_depth_chroma_minus8
                reader.readBits(1)  // qpprime_y_zero_transform_bypass_flag
                if (reader.readBits(1) == 1) {  // seq_scaling_matrix_present_flag
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    repeat(count) {
                        if (reader.readBits(1) == 1) {  // seq_scaling_list_present_flag[i]
                            reader.skipScalingList(if (it < 6) 16 else 64)
                        }
                    }
                }
            }

            reader.readUe()   // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUe()
            when (picOrderCntType) {
                0 -> reader.readUe()  // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    reader.readBits(1)  // delta_pic_order_always_zero_flag
                    reader.readSe()     // offset_for_non_ref_pic
                    reader.readSe()     // offset_for_top_to_bottom_field
                    repeat(reader.readUe()) { reader.readSe() }
                }
            }

            reader.readUe()     // max_num_ref_frames
            reader.readBits(1)  // gaps_in_frame_num_value_allowed_flag

            val picWidthInMbsMinus1 = reader.readUe()
            val picHeightInMapUnitsMinus1 = reader.readUe()
            val frameMbsOnlyFlag = reader.readBits(1)
            if (frameMbsOnlyFlag == 0) {
                reader.readBits(1)  // mb_adaptive_frame_field_flag
            }
            reader.readBits(1)  // direct_8x8_inference_flag

            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (reader.readBits(1) == 1) {  // frame_cropping_flag
                cropLeft = reader.readUe()
                cropRight = reader.readUe()
                cropTop = reader.readUe()
                cropBottom = reader.readUe()
            }

            val codedWidth = (picWidthInMbsMinus1 + 1) * 16
            val codedHeight = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)

            val chromaArrayType = if (separateColorPlaneFlag == 1) 0 else chromaFormatIdc

            val subWidthC = when (chromaArrayType) {
                0 -> 1
                1, 2 -> 2
                else -> 1
            }
            val subHeightC = when (chromaArrayType) {
                1 -> 2
                else -> 1
            }

            val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
            val cropUnitY = if (chromaArrayType == 0) (2 - frameMbsOnlyFlag) else subHeightC * (2 - frameMbsOnlyFlag)

            val width = codedWidth - (cropLeft + cropRight) * cropUnitX
            val height = codedHeight - (cropTop + cropBottom) * cropUnitY
            if (width <= 0 || height <= 0) return null

            Logger.d("SPS parsed: ${width}x${height} (profile=$profileIdc)")
            return Pair(width, height)

        } catch (e: Exception) {
            Logger.w("SPS resolution parsing failed: ${e.message} — will use hint dimensions")
            return null
        }
    }

    /**
     * Bitstream reader for H.264 NAL units.
     *
     * Reads bits MSB-first and implements unsigned/signed Exp-Golomb coding.
     * Exposed as `internal` for use in tests.
     *
     * @param data        The raw SPS byte array (including NAL type header).
     * @param startOffset Byte offset to start reading from (typically 1 to skip NAL type).
     */
    class SpsBitReader(private val data: ByteArray, startOffset: Int) {
        private var bytePos = startOffset
        private var bitPos  = 7  // MSB first (bit 7 = most significant)

        fun readBit(): Int {
            if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS RBSP underflow")
            val bit = (data[bytePos].toInt() ushr bitPos) and 1
            if (--bitPos < 0) { bitPos = 7; bytePos++ }
            return bit
        }

        fun readBits(n: Int): Int {
            var result = 0
            repeat(n) { result = (result shl 1) or readBit() }
            return result
        }

        /** Reads an unsigned Exp-Golomb code ue(v). */
        fun readUe(): Int {
            var leadingZeros = 0
            while (readBit() == 0) {
                if (++leadingZeros > 31) throw ArithmeticException("ue(v) overflow")
            }
            return if (leadingZeros == 0) 0
                   else (1 shl leadingZeros) - 1 + readBits(leadingZeros)
        }

        /** Reads a signed Exp-Golomb code se(v). */
        fun readSe(): Int {
            val k = readUe()
            return if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
        }

        /** Skips a scaling list of [size] entries (High Profile SPS §7.3.2.1.1.1). */
        fun skipScalingList(size: Int) {
            var lastScale = 8
            var nextScale = 8
            repeat(size) {
                if (nextScale != 0) {
                    val deltaScale = readSe()
                    nextScale = (lastScale + deltaScale + 256) % 256
                }
                lastScale = if (nextScale == 0) lastScale else nextScale
            }
        }
    }
}
