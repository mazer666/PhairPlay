package com.phairplay.ui

/** Scale the full-view texture into the container without stretching or cropping the video. */
internal object VideoAspectFit {
    fun scale(videoWidth: Int, videoHeight: Int, containerWidth: Int, containerHeight: Int): Pair<Float, Float> {
        if (videoWidth <= 0 || videoHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
            return 1f to 1f
        }
        val videoRatio = videoWidth.toFloat() / videoHeight
        val containerRatio = containerWidth.toFloat() / containerHeight
        return if (videoRatio > containerRatio) 1f to (containerRatio / videoRatio)
            else (videoRatio / containerRatio) to 1f
    }
}
