package com.phairplay.dlna

import java.util.Locale

/**
 * DlnaMediaType — lightweight media classification for DLNA URLs before handing them
 * to Android playback.
 *
 * WHY: the renderer has to know whether a stream is a video, an audio track or a
 * still image — the three need completely different pipelines (MediaPlayer on the
 * streaming surface, MediaPlayer without a surface, or the photo screen). The control
 * point usually announces the kind in its DIDL-Lite metadata; when it does not, the
 * file extension is the fallback.
 *
 * Example:
 *   DlnaMediaType.kindFromUrl("http://host/song.mp3")   // DlnaMediaKind.AUDIO
 */
object DlnaMediaType {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "aiff")
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "avi", "mov", "webm", "ts", "m2ts", "wmv", "flv", "3gp")

    /** Returns the MIME type of an image URL, or null when the URL is not an image. */
    fun imageMimeFromUrl(uri: String): String? {
        val path = uri.substringBefore('?').lowercase(Locale.US)
        return when (extensionOf(path)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            else -> null
        }
    }

    /**
     * Guesses the kind of media a URL points to.
     *
     * @param uri the media URI.
     * @return the detected kind, or [DlnaMediaKind.UNKNOWN].
     */
    fun kindFromUrl(uri: String): DlnaMediaKind {
        val path = uri.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        val extension = extensionOf(path)
        return when (extension) {
            in IMAGE_EXTENSIONS -> DlnaMediaKind.IMAGE
            in AUDIO_EXTENSIONS -> DlnaMediaKind.AUDIO
            in VIDEO_EXTENSIONS -> DlnaMediaKind.VIDEO
            else -> DlnaMediaKind.UNKNOWN
        }
    }

    /** Returns the file extension of [path] without the dot. */
    private fun extensionOf(path: String): String {
        val lastSegment = path.substringAfterLast('/')
        if (!lastSegment.contains('.')) return ""
        return lastSegment.substringAfterLast('.').lowercase(Locale.US)
    }
}
