package com.phairplay.dlna

import org.w3c.dom.Element

/** The kind of media a control point asked the renderer to play. */
enum class DlnaMediaKind {
    VIDEO,
    AUDIO,
    IMAGE,
    UNKNOWN
}

/**
 * DlnaMediaMetadata — the facts a control point announces with `SetAVTransportURI`.
 *
 * WHY: `SetAVTransportURI` carries a DIDL-Lite document (`CurrentURIMetaData`) next
 * to the plain URI. It tells the renderer what the stream actually is (video, audio or
 * a still image), how long it is and which container it uses. Without it the renderer
 * has to guess from the file extension, which is wrong for a large share of real
 * control points (VLC announces `protocolInfo` and a duration for every stream).
 *
 * The metadata is untrusted network input, so it is parsed with the hardened XML
 * parser. Broken metadata is never fatal: a control point that sends garbage must
 * still be able to start playback.
 */
data class DlnaMediaMetadata(
    val title: String? = null,
    val protocolInfo: String? = null,
    val mimeType: String? = null,
    val durationMillis: Long? = null,
    val sizeBytes: Long? = null,
    val kind: DlnaMediaKind = DlnaMediaKind.UNKNOWN
)

/**
 * DlnaMediaMetadataParser — reads a DIDL-Lite document into a [DlnaMediaMetadata].
 *
 * Example:
 *   DlnaMediaMetadataParser.parse(didlXml)?.durationMillis  // 90000 for "0:01:30"
 */
object DlnaMediaMetadataParser {

    /**
     * Parses [xml] (a DIDL-Lite document) into metadata.
     *
     * @return the parsed metadata, or null when the document is absent, malformed or
     *   carries no usable information. A null result never blocks playback.
     */
    fun parse(xml: String?): DlnaMediaMetadata? {
        val body = xml?.trim().orEmpty()
        if (body.isEmpty()) return null
        val document = runCatching { SecureSoapXml.parse(body) }.getOrNull() ?: return null

        val res = document.getElementsByTagNameNS("*", "res").item(0) as? Element
        val protocolInfo = res?.getAttribute("protocolInfo")?.takeIf { it.isNotBlank() }
        val duration = res?.getAttribute("duration")?.takeIf { it.isNotBlank() }
        val size = res?.getAttribute("size")?.takeIf { it.isNotBlank() }

        val upnpClass = document.getElementsByTagNameNS("*", "class").item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val title = document.getElementsByTagNameNS("*", "title").item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val mimeType = protocolInfo?.split(':', limit = 4)?.getOrNull(2)
            ?.takeIf { it.isNotBlank() }

        return DlnaMediaMetadata(
            title = title?.take(MAX_TITLE_LENGTH),
            protocolInfo = protocolInfo?.take(MAX_PROTOCOL_INFO_LENGTH),
            mimeType = mimeType?.take(MAX_MIME_LENGTH),
            durationMillis = duration?.let { parseDuration(it) },
            sizeBytes = size?.toLongOrNull()?.takeIf { it >= 0 },
            kind = kindOf(upnpClass, mimeType)
        )
    }

    /**
     * Parses an UPnP duration such as `0:01:30` or `1:02:03.500` into milliseconds.
     *
     * @return the duration in milliseconds, or null when the value is not a duration.
     */
    fun parseDuration(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { part ->
            part.substringBefore('.').toLongOrNull() ?: return null
        }
        if (numbers.any { it < 0 }) return null
        val seconds = numbers[numbers.size - 1]
        val minutes = numbers[numbers.size - 2]
        val hours = if (numbers.size == 3) numbers[0] else 0L
        val fractionText = parts.last().substringAfter('.', "")
        val fraction = if (fractionText.isEmpty()) {
            0L
        } else {
            fractionText.take(3).padEnd(3, '0').toLongOrNull() ?: return null
        }
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + fraction
    }

    private fun kindOf(upnpClass: String?, mimeType: String?): DlnaMediaKind {
        val type = mimeType?.lowercase().orEmpty()
        return when {
            upnpClass?.contains("audioItem", ignoreCase = true) == true -> DlnaMediaKind.AUDIO
            upnpClass?.contains("videoItem", ignoreCase = true) == true -> DlnaMediaKind.VIDEO
            upnpClass?.contains("imageItem", ignoreCase = true) == true -> DlnaMediaKind.IMAGE
            type.startsWith("audio/") -> DlnaMediaKind.AUDIO
            type.startsWith("video/") -> DlnaMediaKind.VIDEO
            type.startsWith("image/") -> DlnaMediaKind.IMAGE
            else -> DlnaMediaKind.UNKNOWN
        }
    }

    private const val MAX_TITLE_LENGTH = 512
    private const val MAX_PROTOCOL_INFO_LENGTH = 256
    private const val MAX_MIME_LENGTH = 128
}
