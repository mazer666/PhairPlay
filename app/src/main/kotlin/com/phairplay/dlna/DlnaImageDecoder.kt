package com.phairplay.dlna

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * DlnaImageDecoder — decodes a photo payload without letting the TV run out of memory.
 *
 * WHY: a DLNA control point can offer any image. A 20 MB JPEG can decode into
 * several hundred megabytes of pixels (1920×1080×4 bytes ≈ 8 MB per bitmap, and a
 * 12000×8000 photo is 384 MB) — enough to kill the app with an `OutOfMemoryError`.
 * The payload is therefore bounded *and* the bitmap is downsampled with
 * `inSampleSize` before it reaches the screen.
 *
 * Example:
 *   val bytes = DlnaImageDecoder.decode(payload, maxDimension = 1920)
 */
object DlnaImageDecoder {

    /** Hard limit for a photo payload (20 MB), as required by ADR-004. */
    const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

    /** Edge length the decoded photo is scaled down to. */
    const val DEFAULT_MAX_DIMENSION = 1920

    /**
     * Computes the `inSampleSize` for a bitmap of [width] × [height] pixels.
     *
     * WHY: `inSampleSize` is a power of two and is applied by the decoder *before*
     * the pixels are allocated. A value of 4 means "decode every fourth pixel",
     * which divides the memory need by sixteen.
     *
     * @param width the pixel width of the image.
     * @param height the pixel height of the image.
     * @param maxDimension the largest edge length that may be kept.
     * @return the sample size (1 = no downsampling).
     */
    fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var sampleSize = 1
        var longestEdge = maxOf(width, height)
        while (longestEdge / 2 >= maxDimension) {
            longestEdge /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    /** Returns true when [size] is inside the payload limit. */
    fun isWithinPayloadLimit(size: Int): Boolean = size in 0..MAX_IMAGE_BYTES

    /**
     * Decodes [bytes] into a bitmap that is scaled down to [maxDimension].
     *
     * @return the bitmap, or null when the payload is not a decodable image.
     */
    fun decode(bytes: ByteArray, maxDimension: Int = DEFAULT_MAX_DIMENSION): Bitmap? {
        if (!isWithinPayloadLimit(bytes.size)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }
}
