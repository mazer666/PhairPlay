package com.phairplay.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * SafeBitmapDecoder — decodes a picture that came from the network without letting
 * the television run out of memory.
 *
 * WHY: every picture that reaches the UI is untrusted input. An AirPlay `/photo`
 * payload may be 25 MB, the album artwork of a Now-Playing message is bounded by the
 * RTSP message budget — and the byte size says nothing about the decoded size. A
 * JPEG whose header announces 20000 × 20000 pixels is far below the payload limit
 * but decodes into 20000 × 20000 × 4 bytes = 1.6 GB. `BitmapFactory.decodeByteArray`
 * allocates the full bitmap before the caller can look at it, so any device in the
 * LAN can kill the receiver with a single picture (audit finding SEC-02).
 * The decoder therefore reads the image header first (`inJustDecodeBounds`), checks it
 * against a budget and only then decodes — with an `inSampleSize` and a 16-bit
 * colour format that halves the memory of the result.
 *
 * HOW: call [decode] instead of `BitmapFactory.decodeByteArray`. The two rules that
 * decide what is allowed ([isWithinLimits] and [calculateInSampleSize]) are pure
 * functions and can be tested without an Android runtime.
 *
 * Example:
 *   val bitmap = SafeBitmapDecoder.decode(bytes)          // null when the picture is refused
 *   val smaller = SafeBitmapDecoder.decode(bytes, maxEdge = 1920)
 */
object SafeBitmapDecoder {

    /**
     * The longest edge (in pixels) a decoded picture may have.
     *
     * WHY: a television screen is 4K at most (3840 × 2160). A picture that is larger
     * than that cannot be seen in full anyway, so the extra pixels are pure risk.
     */
    const val MAX_EDGE_PX = 4096

    /**
     * The total number of pixels a decoded picture may have (4096 × 4096).
     *
     * WHY: the edge limit alone would still allow a 4096 × 4096 picture, which is
     * 64 MB as ARGB_8888. The pixel budget is the second, independent line of defence
     * and becomes the binding limit as soon as a caller asks for a smaller edge.
     */
    const val MAX_PIXELS = MAX_EDGE_PX * MAX_EDGE_PX

    /**
     * Returns true when a picture of [width] × [height] pixels may be decoded.
     *
     * WHY: this is the rule that stops a decompression bomb. It is a pure function
     * so that the decision can be tested without an Android runtime.
     *
     * @param width the pixel width the image header announces.
     * @param height the pixel height the image header announces.
     * @param maxEdge the longest edge that may be kept.
     * @param maxPixels the total number of pixels that may be kept.
     * @return true when the picture is inside the budget.
     */
    fun isWithinLimits(
        width: Int,
        height: Int,
        maxEdge: Int = MAX_EDGE_PX,
        maxPixels: Int = MAX_PIXELS
    ): Boolean {
        // A header that could not be parsed reports -1; a zero-sized picture is useless.
        if (width <= 0 || height <= 0) return false
        if (maxEdge <= 0 || maxPixels <= 0) return false
        if (width > maxEdge || height > maxEdge) return false
        // Long arithmetic: 100000 × 100000 overflows an Int and would pass the check.
        if (width.toLong() * height.toLong() > maxPixels.toLong()) return false
        return true
    }

    /**
     * Computes the `inSampleSize` for a picture of [width] × [height] pixels.
     *
     * WHY: `inSampleSize` is a power of two and the decoder applies it *before* the
     * pixels are allocated — a value of 4 means "keep every fourth pixel" and divides
     * the memory need by sixteen. The picture is halved until the next halving would
     * fall below [maxEdge], so the result stays within a factor of two of the limit.
     *
     * @param width the pixel width of the picture.
     * @param height the pixel height of the picture.
     * @param maxEdge the longest edge that may be kept.
     * @return the sample size (1 = no downsampling).
     */
    fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sampleSize = 1
        var longestEdge = maxOf(width, height)
        while (longestEdge / 2 >= maxEdge) {
            longestEdge /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Decodes [bytes] into a bitmap that stays inside the decode budget.
     *
     * WHY: the picture header is read first (`inJustDecodeBounds`), so the announced
     * size is known before a single pixel is allocated. A picture outside the budget
     * is refused instead of being decoded — a payload that is hostile must not cost
     * the receiver its memory, and the size of the *file* says nothing about the
     * size of the *bitmap*.
     *
     * @param bytes the encoded picture.
     * @param maxEdge the longest edge that may be kept.
     * @return the bitmap, or null when the payload is not a picture or is too large.
     */
    fun decode(bytes: ByteArray, maxEdge: Int = MAX_EDGE_PX): Bitmap? {
        if (bytes.isEmpty() || maxEdge <= 0) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // The header read must not throw either — the payload is untrusted input.
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        if (!isWithinLimits(bounds.outWidth, bounds.outHeight, maxEdge)) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            // 16 bits per pixel instead of 32: the picture is shown on a screen, the
            // alpha channel is not worth half of the memory.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        // runCatching also catches an OutOfMemoryError: a single hostile picture must
        // not take the whole receiver down with it.
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }
}
