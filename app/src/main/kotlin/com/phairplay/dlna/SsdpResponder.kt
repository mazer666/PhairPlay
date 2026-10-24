package com.phairplay.dlna

/**
 * SsdpResponder — turns an incoming `M-SEARCH` into the answers a UPnP device owes.
 *
 * WHY: the answer depends on the search target:
 *
 * - `ST: ssdp:all` must be answered with **three** separate 200 OK datagrams —
 *   `upnp:rootdevice`, the device type and the UDN. Answering only with one of
 *   them is the most common reason a device shows up in some pickers but not in
 *   others (VLC uses the rootdevice answer to build its renderer list).
 * - Every other search target is answered with exactly one datagram.
 *
 * The Samsung search target `urn:samsung.com:service:MultiScreenService:1` also
 * gets exactly one datagram. A Samsung TV asks with it, and the answer carries
 * the same location as every other answer — the device description of this
 * server. The DIAL documents behind that location are served by
 * [DialAppCatalog].
 *
 * The class is deliberately free of sockets: the advertiser owns the network, this
 * class owns the protocol. That makes the answer set testable without multicast.
 *
 * Example:
 *   val responses = SsdpResponder(location, udn).responsesFor(mSearchMessage)
 */
class SsdpResponder(
    private val location: String,
    private val udn: String
) {

    /**
     * Returns the search targets this device answers [searchTarget] with.
     *
     * @param searchTarget the `ST` value of the request.
     * @return the targets to answer with; empty when the device does not match.
     */
    fun targetsFor(searchTarget: String): List<String> {
        val requested = searchTarget.trim()
        return when {
            requested.equals(SSDP_ALL, ignoreCase = true) -> listOf(
                SsdpMessages.ROOT_DEVICE,
                SsdpMessages.MEDIA_RENDERER,
                udn
            )
            requested.equals(SsdpMessages.ROOT_DEVICE, ignoreCase = true) ->
                listOf(SsdpMessages.ROOT_DEVICE)
            requested.equals(SsdpMessages.MEDIA_RENDERER, ignoreCase = true) ->
                listOf(SsdpMessages.MEDIA_RENDERER)
            requested.equals(udn, ignoreCase = true) -> listOf(udn)
            requested.equals(SsdpMessages.SAMSUNG_MULTI_SCREEN, ignoreCase = true) ->
                listOf(SsdpMessages.SAMSUNG_MULTI_SCREEN)
            else -> emptyList()
        }
    }

    /**
     * Returns the complete 200 OK datagrams for an incoming `M-SEARCH` message.
     *
     * @param message the raw datagram payload.
     * @return one datagram per matching search target; empty when the message is
     *   not a search this device has to answer.
     */
    fun responsesFor(message: String): List<String> {
        val searchTarget = SsdpMessages.searchTargetOf(message) ?: return emptyList()
        return targetsFor(searchTarget).map { target ->
            SsdpMessages.searchResponse(location = location, udn = udn, searchTarget = target)
        }
    }

    companion object {
        /** The `ST` value that asks for every device of the network. */
        const val SSDP_ALL = "ssdp:all"
    }
}
