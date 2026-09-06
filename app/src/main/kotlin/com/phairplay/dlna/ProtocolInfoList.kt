package com.phairplay.dlna

import java.util.Locale

/**
 * ProtocolInfoList — the formats this renderer says it can play, and how an incoming item is classified.
 *
 * WHY: ConnectionManager.GetProtocolInfo returns the sink list; Windows only sends formats found there and
 * transcodes the rest. We list exactly what Android `MediaPlayer` decodes (no raw LPCM, no WMA/WMV). The
 * classification decides video/audio (MediaPlayer) vs image (photo overlay) for SetAVTransportURI.
 *
 * HOW: `ProtocolInfoList.sinkString()`; `ProtocolInfoList.classify(protocolInfo, upnpClass, uri)`.
 */
object ProtocolInfoList {

    private val VIDEO_MIME = listOf(
        "video/mp4", "video/x-matroska", "video/webm", "video/3gpp", "video/mpeg", "video/vnd.dlna.mpeg-tts"
    )
    private val AUDIO_MIME = listOf(
        "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/flac", "audio/x-wav", "audio/wav", "audio/ogg"
    )
    private val IMAGE_MIME = listOf("image/jpeg", "image/png", "image/gif", "image/webp")

    /** DLNA profile names so Windows recognises the common containers as native. */
    private val DLNA_PROFILES = listOf(
        "video/mp4" to "AVC_MP4_BL_CIF15_AAC_520",
        "video/mp4" to "AVC_MP4_MP_SD_AAC_MULT5",
        "video/mp4" to "AVC_MP4_HP_HD_AAC",
        "audio/mpeg" to "MP3",
        "audio/mp4" to "AAC_ISO",
        "audio/mp4" to "AAC_ISO_320",
        "image/jpeg" to "JPEG_LRG",
        "image/jpeg" to "JPEG_MED",
        "image/jpeg" to "JPEG_SM",
        "image/png" to "PNG_LRG"
    )

    // "mov" maps to VIDEO even though "video/quicktime" is never advertised in the sink list above:
    // this map is the last-resort classifier for senders that skip GetProtocolInfo and send a bare
    // URI with no upnp:class either, so it must cover formats we can still hand to MediaPlayer even
    // when we would not proactively offer them.
    private val EXTENSIONS: Map<String, MediaClass> = mapOf(
        "mp4" to MediaClass.VIDEO, "m4v" to MediaClass.VIDEO, "mkv" to MediaClass.VIDEO, "webm" to MediaClass.VIDEO,
        "3gp" to MediaClass.VIDEO, "mpg" to MediaClass.VIDEO, "mpeg" to MediaClass.VIDEO, "ts" to MediaClass.VIDEO,
        "mov" to MediaClass.VIDEO,
        "mp3" to MediaClass.AUDIO, "m4a" to MediaClass.AUDIO, "aac" to MediaClass.AUDIO, "flac" to MediaClass.AUDIO,
        "wav" to MediaClass.AUDIO, "ogg" to MediaClass.AUDIO, "oga" to MediaClass.AUDIO,
        "jpg" to MediaClass.IMAGE, "jpeg" to MediaClass.IMAGE, "png" to MediaClass.IMAGE, "gif" to MediaClass.IMAGE,
        "webp" to MediaClass.IMAGE
    )

    private val sink: List<String> =
        (VIDEO_MIME + AUDIO_MIME + IMAGE_MIME).map { "http-get:*:$it:*" } +
            DLNA_PROFILES.map { (mime, profile) -> "http-get:*:$mime:DLNA.ORG_PN=$profile" }

    fun sinkString(): String = sink.joinToString(",")

    /** protocolInfo MIME first, then the DIDL `upnp:class`, then the URI extension; null if unknown. */
    fun classify(protocolInfo: String?, upnpClass: String?, uri: String): MediaClass? {
        mimeFromProtocolInfo(protocolInfo)?.let { mime -> classifyMime(mime)?.let { return it } }
        upnpClass?.let { cls ->
            when {
                cls.startsWith("object.item.videoItem") -> return MediaClass.VIDEO
                cls.startsWith("object.item.audioItem") -> return MediaClass.AUDIO
                cls.startsWith("object.item.imageItem") -> return MediaClass.IMAGE
                // Not one of the known DIDL classes: fall through to the extension-based check below.
                else -> Unit
            }
        }
        // Strip the query, then any fragment, then a trailing slash before taking the extension — a
        // sender may send "…/movie.mp4#t=30" or a URI with a trailing slash from URL normalization.
        val cleanUri = uri.substringBefore('?').substringBefore('#').trimEnd('/')
        val extension = cleanUri.substringAfterLast('.', "").lowercase(Locale.US)
        return EXTENSIONS[extension]
    }

    fun classifyMime(mime: String): MediaClass? = when {
        mime.startsWith("video/") -> MediaClass.VIDEO
        mime.startsWith("audio/") -> MediaClass.AUDIO
        mime.startsWith("image/") -> MediaClass.IMAGE
        else -> null
    }

    /** Third field of `protocol:network:contentFormat:additionalInfo`, or null for `*`/missing. */
    fun mimeFromProtocolInfo(protocolInfo: String?): String? {
        val parts = protocolInfo?.split(":") ?: return null
        if (parts.size < 3) return null
        val mime = parts[2].trim().lowercase(Locale.US)
        return if (mime.isEmpty() || mime == "*") null else mime
    }
}
