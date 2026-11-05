package com.phairplay.airplay

import java.util.Locale

/**
 * PictureSize — a picture size in pixels.
 *
 * WHY: The decoder, the SPS parser and the crop rectangle each report a size in their
 * own terms. Wrapping them in one type makes it impossible to mix up the arguments at a
 * call site, which is exactly the mistake that causes a stretched picture.
 *
 * @param width  Width in pixels.
 * @param height Height in pixels.
 */
internal data class PictureSize(val width: Int, val height: Int) {

    /** The width-to-height ratio; 0 for a degenerate size. */
    val aspect: Double
        get() = if (width <= 0 || height <= 0) 0.0 else width.toDouble() / height.toDouble()
}

/**
 * OutputSize — the size the on-screen aspect-fit should use, and why.
 *
 * WHY: The aspect-fit needs the aspect of the *content*, not the aspect of the buffer.
 * When a decoder scales the picture to the panel, the buffer's aspect no longer matches
 * the content, and using the buffer's aspect stretches the picture. The reason string is
 * logged so a misbehaving device can be identified from the log alone.
 *
 * @param width        Width to use for the aspect-fit.
 * @param height       Height to use for the aspect-fit.
 * @param cropAccepted True when the decoder's crop rectangle was used.
 * @param reason       Short, log-friendly explanation of the decision.
 */
internal data class OutputSize(
    val width: Int,
    val height: Int,
    val cropAccepted: Boolean,
    val reason: String,
)

/**
 * OutputSizeResolver — decides which picture size the on-screen aspect-fit uses.
 *
 * WHAT: It compares the size the hardware decoder reports (the coded picture and its
 * crop rectangle) with the size parsed from the stream's SPS, and returns the size whose
 * aspect ratio describes the content of the frame buffer.
 *
 * WHY: Some hardware decoders do not only decode — they also scale the picture to the
 * panel and then report that scaled size as if it were a crop. The Allwinner H726
 * (Magcubic/Gojodoq HY300, Android 14) does exactly that: it decodes 498x1080, scales
 * it non-uniformly to 484x712 and reports `crop-left=0, crop-top=0, crop-right=483,
 * crop-bottom=711`. A crop is a sub-rectangle of the picture, so its aspect may only
 * differ from the coded aspect by the amount the encoder trimmed off — at most a few
 * percent. A 47% deviation means the decoder scaled, and trusting that number stretches
 * the picture horizontally (GitHub issue #21).
 *
 * EXAMPLE: `resolve(coded = 498x1080, crop = 484x712, reference = 498x1080)` returns
 * 498x1080 with `cropAccepted = false`. The aspect-fit then maps the scaled buffer onto
 * the true aspect, which undoes the decoder's distortion.
 *
 * This object holds no Android dependencies so it can be unit-tested on the JVM.
 */
internal object OutputSizeResolver {

    /**
     * Maximum relative difference between the crop's aspect and the coded aspect that
     * is still accepted as a real crop.
     *
     * WHY 5%: a legitimate crop only trims the padding the H.264 encoder added — usually
     * 8 to 16 pixels on one edge, which is under 2% of the height. A larger deviation is
     * a picture the decoder scaled, not trimmed.
     */
    const val MAX_ASPECT_DEVIATION = 0.05

    /** Smallest edge length a picture size may have to be usable. */
    const val MIN_EDGE = 64

    /** Largest edge length a picture size may have to be usable. */
    const val MAX_EDGE = 8192

    /**
     * Largest shrink a real crop may have in one dimension.
     *
     * WHY 10%: a crop trims the padding the encoder added — usually 8 to 16 pixels on
     * one edge, which is under 2% of the height. A shrink of more than a tenth in one
     * dimension is a picture the decoder scaled down, not a rectangle it cut out.
     */
    const val MAX_CROP_SHRINK = 0.10

    /**
     * Decides the size to use for the aspect-fit.
     *
     * @param coded    The size the decoder reports for the picture (MediaFormat width/height).
     * @param crop     The decoder's crop rectangle, or null when it reports none.
     * @param reference The size parsed from the stream's SPS — the authoritative size of
     *   the stream. Null when the SPS could not be parsed.
     * @return The size to use, whether the crop was accepted, and why.
     */
    fun resolve(coded: PictureSize, crop: PictureSize?, reference: PictureSize?): OutputSize {
        val codedUsable = isPlausible(coded)

        if (!codedUsable && (crop == null || !isPlausible(crop))) {
            // Nothing usable at all: fall back to the SPS size, the caller drops it if it is
            // implausible too. Better a wrong number than a crash in the render path.
            val fallback = reference?.takeIf { isPlausible(it) } ?: coded
            return OutputSize(fallback.width, fallback.height, false, "no plausible size, using the SPS size")
        }

        if (crop == null || !isPlausible(crop)) {
            val fallback = if (codedUsable) coded else reference!!
            return OutputSize(fallback.width, fallback.height, false, "no usable crop, using the coded size")
        }

        val trueSize = pickReference(coded, reference)

        // The picture the crop is measured against: the size the decoder decoded.
        // When that size is not plausible, the size of the stream is the only
        // remaining reference.
        val base = if (codedUsable) coded else trueSize

        // A crop is a sub-rectangle of the picture the decoder decoded: it cannot be
        // bigger than it in either dimension.
        //
        // WHY this is checked against the decoded picture and not against the stream
        // size: the crop rectangle is a property of the decoder's picture. A decoder
        // that scaled the picture up reports a rectangle that is bigger than the
        // picture it decoded — that is not a crop, it is a scaled picture.
        val cropFits = crop.width <= base.width && crop.height <= base.height
        if (!cropFits) {
            // A crop cannot be bigger than the picture it crops. A decoder that
            // reports one has scaled the picture up — the coded size is the
            // honest one.
            return OutputSize(
                trueSize.width,
                trueSize.height,
                false,
                "the crop is bigger than the picture, the decoder scaled it up — using the coded aspect",
            )
        }

        // A crop that is much smaller than the picture is a scaled-down picture.
        //
        // WHY the aspect comparison cannot see this: a uniform scaling does not
        // change the aspect, so a decoder that scaled 1280x720 down to 854x480 and
        // reported the scaled size as its crop keeps the aspect exactly. Only the
        // size shows the difference. A real crop trims the padding the encoder
        // added — a few pixels on one edge, under 2% of the height — so a shrink of
        // more than [MAX_CROP_SHRINK] in one dimension is a scaling and not a crop.
        val shrink = minOf(
            crop.width.toDouble() / base.width,
            crop.height.toDouble() / base.height,
        )
        if (shrink < 1.0 - MAX_CROP_SHRINK) {
            val shrinkPercent = String.format(Locale.US, "%.1f", (1.0 - shrink) * 100.0)
            return OutputSize(
                trueSize.width,
                trueSize.height,
                false,
                "the crop is $shrinkPercent% smaller than the picture, " +
                    "the decoder scaled it down — using the coded aspect",
            )
        }

        val deviation = aspectDeviation(crop!!, trueSize)
        val percent = String.format(Locale.US, "%.1f", deviation * 100.0)

        return if (deviation <= MAX_ASPECT_DEVIATION) {
            OutputSize(crop.width, crop.height, true, "crop keeps the coded aspect ($percent% off)")
        } else {
            OutputSize(
                trueSize.width,
                trueSize.height,
                false,
                "crop aspect is $percent% off, the decoder scaled the picture — using the coded aspect",
            )
        }
    }

    /**
     * Picks the size that describes the content of the picture.
     *
     * WHY the rotation dance: a decoder that rotates the picture reports the transposed
     * size, so the SPS size has to be compared in both orientations. When neither
     * orientation matches, the decoder scaled the picture — and the SPS is the only size
     * that still describes the stream.
     */
    private fun pickReference(coded: PictureSize, reference: PictureSize?): PictureSize {
        if (reference == null || !isPlausible(reference)) return coded
        if (aspectDeviation(coded, reference) <= MAX_ASPECT_DEVIATION) return reference

        val rotated = PictureSize(reference.height, reference.width)
        if (aspectDeviation(coded, rotated) <= MAX_ASPECT_DEVIATION) return rotated

        return reference
    }

    /**
     * Returns how far the aspect of [a] is from the aspect of [b], relative to [b].
     *
     * The ratio form is used on purpose: a picture that is 10% too wide is 10% too wide
     * whether it is 100 or 4000 pixels wide.
     */
    private fun aspectDeviation(a: PictureSize, b: PictureSize): Double {
        val referenceAspect = b.aspect
        if (referenceAspect <= 0.0 || a.aspect <= 0.0) return Double.MAX_VALUE
        return kotlin.math.abs(a.aspect / referenceAspect - 1.0)
    }

    /** True when the size is inside the range a real picture can have. */
    private fun isPlausible(size: PictureSize): Boolean =
        size.width in MIN_EDGE..MAX_EDGE && size.height in MIN_EDGE..MAX_EDGE
}
