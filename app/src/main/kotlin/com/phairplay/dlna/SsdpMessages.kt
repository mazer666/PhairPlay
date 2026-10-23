package com.phairplay.dlna

import java.util.Locale

/**
 * Builds and classifies SSDP messages for UPnP/DLNA discovery.
 */
object SsdpMessages {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val ROOT_DEVICE = "upnp:rootdevice"
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    fun isRendererSearch(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        if (!normalized.startsWith("m-search ")) return false
        if (!normalized.contains("man: \"ssdp:discover\"")) return false
        val st = normalized.lineSequence()
            .firstOrNull { it.startsWith("st:") }
            ?.substringAfter(':')
            ?.trim()
            ?: return false
        return st == "ssdp:all" ||
            st == ROOT_DEVICE ||
            st == MEDIA_RENDERER.lowercase(Locale.US)
    }

    fun searchResponse(location: String, udn: String, searchTarget: String): String =
        listOf(
            "HTTP/1.1 200 OK",
            "CACHE-CONTROL: max-age=1800",
            "EXT:",
            "LOCATION: $location",
            "SERVER: Android/1.0 UPnP/1.0 PhairPlay/1.0",
            "ST: $searchTarget",
            "USN: $udn::$searchTarget",
            "",
            ""
        ).joinToString("\r\n")

    fun notifyAlive(location: String, udn: String, notificationType: String): String =
        listOf(
            "NOTIFY * HTTP/1.1",
            "HOST: $MULTICAST_ADDRESS:$PORT",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: $location",
            "NT: $notificationType",
            "NTS: ssdp:alive",
            "SERVER: Android/1.0 UPnP/1.0 PhairPlay/1.0",
            "USN: $udn::$notificationType",
            "",
            ""
        ).joinToString("\r\n")

    fun notifyByebye(udn: String, notificationType: String): String =
        listOf(
            "NOTIFY * HTTP/1.1",
            "HOST: $MULTICAST_ADDRESS:$PORT",
            "NT: $notificationType",
            "NTS: ssdp:byebye",
            "USN: $udn::$notificationType",
            "",
            ""
        ).joinToString("\r\n")
}
