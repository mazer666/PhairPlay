package com.phairplay.dlna

import com.phairplay.util.Logger
import org.w3c.dom.Element

/**
 * DidlItem — the fields we use from a DIDL-Lite item: what to show and how to classify it.
 */
data class DidlItem(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtUri: String? = null,
    val upnpClass: String? = null,
    val protocolInfo: String? = null,
    val durationMs: Long? = null
) {
    companion object {
        val EMPTY = DidlItem()
    }
}

/**
 * DidlLite — parses the `CurrentURIMetaData` DIDL-Lite document a control point sends with a URI.
 *
 * WHY: Windows and BubbleUPnP describe the item (title, artist, album art, class, MIME, duration) here;
 * the now-playing card and the media classification depend on it. Anything malformed must degrade to
 * [DidlItem.EMPTY] rather than fail the action.
 *
 * HOW: `DidlLite.parse(metadataXml)` — never throws.
 */
object DidlLite {

    fun parse(xml: String?): DidlItem {
        if (xml.isNullOrBlank()) return DidlItem.EMPTY
        val document = SecureXml.parse(xml) ?: return DidlItem.EMPTY
        val root = document.documentElement ?: return DidlItem.EMPTY
        val item = SecureXml.childElements(root).firstOrNull {
            val name = SecureXml.localNameOf(it)
            name == "item" || name == "container"
        }
        if (item == null) {
            // Malformed or empty-catalog documents parse fine but carry nothing to classify; logging
            // helps diagnose a media server that never sends a usable item/container.
            Logger.d("DidlLite: parsed document had no item or container element")
            return DidlItem.EMPTY
        }
        val upnpClass = text(item, "class")
        // Some media servers list a thumbnail <res> before the media <res> inside a videoItem — first
        // res is not necessarily the wanted one, so pick by matching the DIDL class to the res MIME type.
        val resList = SecureXml.childElements(item).filter { SecureXml.localNameOf(it) == "res" }
        val wantedClass = wantedMediaClass(upnpClass)
        val chosenRes = (
            wantedClass?.let { wanted ->
                resList.firstOrNull { res ->
                    val mime = ProtocolInfoList.mimeFromProtocolInfo(res.getAttribute("protocolInfo"))
                    mime != null && ProtocolInfoList.classifyMime(mime) == wanted
                }
            }
        ) ?: resList.firstOrNull()
        val durationMs = resList.firstNotNullOfOrNull { res -> UpnpTime.parse(res.getAttribute("duration")) }
        return DidlItem(
            title = text(item, "title"),
            artist = text(item, "artist") ?: text(item, "creator"),
            album = text(item, "album"),
            albumArtUri = text(item, "albumArtURI"),
            upnpClass = upnpClass,
            protocolInfo = chosenRes?.getAttribute("protocolInfo")?.trim()?.ifEmpty { null },
            durationMs = durationMs
        )
    }

    /** DIDL `upnp:class` mapped to the [MediaClass] its `<res>` should carry; null when unknown/container. */
    private fun wantedMediaClass(upnpClass: String?): MediaClass? = when {
        upnpClass == null -> null
        upnpClass.startsWith("object.item.videoItem") -> MediaClass.VIDEO
        upnpClass.startsWith("object.item.audioItem") -> MediaClass.AUDIO
        upnpClass.startsWith("object.item.imageItem") -> MediaClass.IMAGE
        else -> null
    }

    private fun text(parent: Element, localName: String): String? =
        SecureXml.firstText(parent, localName)?.trim()?.ifEmpty { null }
}
