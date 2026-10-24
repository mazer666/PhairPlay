package com.phairplay.dlna

/**
 * DlnaProtocolInfo — the protocol lists a UPnP ConnectionManager advertises.
 *
 * WHY: `GetProtocolInfo` returns the `Source` and `Sink` protocol lists. A renderer
 * whose `Source` list is empty looks like a device that cannot fetch anything, and
 * control points then hide it from their renderer picker (this is the single most
 * common reason a DLNA renderer never shows up in VLC). The lists below describe what
 * PhairPlay can actually pull: HTTP GET with the container/codec types the Android
 * media stack and the photo pipeline support.
 *
 * The format is `protocol:network:contentFormat:additionalInfo`, comma separated.
 */
object DlnaProtocolInfo {

    /**
     * What the renderer can fetch: progressive HTTP media plus still images.
     *
     * The `video` and `audio` wildcards cover the containers Android's MediaPlayer
     * negotiates (MP4/H.264, WebM/VP8, MP3, AAC, …); the explicit `video/mp4` entry
     * is listed first because several control points only look at the first entry.
     */
    const val SOURCE: String =
        "http-get:*:video/mp4:*,http-get:*:video/*:*,http-get:*:audio/*:*," +
            "http-get:*:image/jpeg:*,http-get:*:image/png:*"

    /** What the renderer can render locally (kept aligned with [SOURCE]). */
    const val SINK: String =
        "http-get:*:video/mp4:*,http-get:*:audio/mpeg:*,http-get:*:image/jpeg:*,http-get:*:image/png:*"
}
