package com.phairplay.dlna

/**
 * SsdpMessages — builds and classifies SSDP messages for UPnP/DLNA discovery.
 *
 * WHY: SSDP is a line based protocol. Parsing it with `startsWith`/`contains` on the
 * whole datagram is fragile: control points differ in the spacing of their headers
 * (`MAN:"ssdp:discover"` is as legal as `MAN: "ssdp:discover"`), and a device that
 * misses a search stays invisible. The headers are therefore parsed line by line.
 *
 * Example:
 *   SsdpMessages.isRendererSearch(message, udn)
 */
object SsdpMessages {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val ROOT_DEVICE = "upnp:rootdevice"
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /**
     * The search target a Samsung TV and the Samsung Smart View app use to find a
     * receiver.
     *
     * WHY: they do not search for a UPnP MediaRenderer. They send an `M-SEARCH`
     * with this target and expect a 200 OK that names the location of the device
     * description. The DIAL documents behind that location are built by
     * [DialAppCatalog].
     */
    const val SAMSUNG_MULTI_SCREEN = "urn:samsung.com:service:MultiScreenService:1"

    /**
     * Returns true when [message] is an `M-SEARCH` this device has to answer.
     *
     * @param message the raw datagram payload.
     * @param udn the UDN of this device; a search for it is answered as well.
     */
    fun isRendererSearch(message: String, udn: String? = null): Boolean {
        val firstLine = message.lineSequence().firstOrNull()?.trim().orEmpty()
        if (!firstLine.startsWith("M-SEARCH", ignoreCase = true)) return false
        val man = headerValue(message, "MAN") ?: return false
        if (!man.contains("ssdp:discover", ignoreCase = true)) return false
        val target = searchTargetOf(message) ?: return false
        return target.equals("ssdp:all", ignoreCase = true) ||
            target.equals(ROOT_DEVICE, ignoreCase = true) ||
            target.equals(MEDIA_RENDERER, ignoreCase = true) ||
            target.equals(SAMSUNG_MULTI_SCREEN, ignoreCase = true) ||
            (udn != null && target.equals(udn, ignoreCase = true))
    }

    /**
     * Returns the `ST` (search target) of an SSDP message.
     *
     * @return the search target, or null when the message carries none.
     */
    fun searchTargetOf(message: String): String? =
        headerValue(message, "ST")?.takeIf { it.isNotBlank() }

    /**
     * Returns the value of the header [name] (case insensitive).
     *
     * @return the trimmed value, or null when the header is absent.
     */
    fun headerValue(message: String, name: String): String? =
        message.lineSequence()
            .drop(1)
            .map { it.trim() }
            .firstOrNull { line ->
                val separator = line.indexOf(':')
                separator > 0 && line.substring(0, separator).trim().equals(name, ignoreCase = true)
            }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Builds the 200 OK answer to an `M-SEARCH`.
     *
     * WHY the USN special case: the USN of the UDN answer is the UDN itself —
     * `uuid:x::uuid:x` is not a valid USN and control points reject it.
     */
    fun searchResponse(location: String, udn: String, searchTarget: String): String {
        val usn = if (searchTarget.equals(udn, ignoreCase = true)) udn else "$udn::$searchTarget"
        return listOf(
            "HTTP/1.1 200 OK",
            "CACHE-CONTROL: max-age=1800",
            "EXT:",
            "LOCATION: $location",
            "SERVER: Android/1.0 UPnP/1.0 PhairPlay",
            "ST: $searchTarget",
            "USN: $usn",
            "",
            ""
        ).joinToString("\r\n")
    }

    fun notifyAlive(location: String, udn: String, notificationType: String): String {
        val usn = if (notificationType.equals(udn, ignoreCase = true)) udn else "$udn::$notificationType"
        return listOf(
            "NOTIFY * HTTP/1.1",
            "HOST: $MULTICAST_ADDRESS:$PORT",
            "CACHE-CONTROL: max-age=1800",
            "LOCATION: $location",
            "NT: $notificationType",
            "NTS: ssdp:alive",
            "SERVER: Android/1.0 UPnP/1.0 PhairPlay",
            "USN: $usn",
            "",
            ""
        ).joinToString("\r\n")
    }

    fun notifyByebye(udn: String, notificationType: String): String {
        val usn = if (notificationType.equals(udn, ignoreCase = true)) udn else "$udn::$notificationType"
        return listOf(
            "NOTIFY * HTTP/1.1",
            "HOST: $MULTICAST_ADDRESS:$PORT",
            "NT: $notificationType",
            "NTS: ssdp:byebye",
            "USN: $usn",
            "",
            ""
        ).joinToString("\r\n")
    }
}
