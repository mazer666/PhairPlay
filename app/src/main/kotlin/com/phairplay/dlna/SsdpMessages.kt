package com.phairplay.dlna

import com.phairplay.util.Logger
import java.util.Date
import java.util.Locale

/**
 * SsdpMessages — builds and parses the SSDP datagrams used for UPnP discovery.
 *
 * WHY: Discovery is plain text over UDP; keeping the wire format here (and out of the socket code in
 * [SsdpAdvertiser]) makes every header Windows keys on unit-testable.
 *
 * HOW: `parse(text)` → [SsdpRequest] or null; `matchingTargets(st, udn)` decides what to answer;
 * `notifyAlive`/`notifyByeBye`/`searchResponse` produce the exact bytes to send.
 */
object SsdpMessages {

    /** A parsed M-SEARCH or NOTIFY. Header names are stored upper-case; use [header]. */
    data class SsdpRequest(val method: String, val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.uppercase(Locale.US)]
    }

    private const val SEARCH_METHOD = "M-SEARCH"
    private const val NOTIFY_METHOD = "NOTIFY"
    private const val SEARCH_MAN = "ssdp:discover"
    private const val SEARCH_ALL = "ssdp:all"
    private const val ROOT_DEVICE = "upnp:rootdevice"
    private const val CRLF = "\r\n"

    /** How much of a rejected datagram to log — enough to identify it without flooding logcat. */
    private const val LOG_PREVIEW_CHARS = 80

    fun parse(text: String): SsdpRequest? {
        val lines = text.split(CRLF, "\n")
        val startLine = lines.firstOrNull()?.trim().orEmpty()
        val parts = startLine.split(" ")
        if (parts.size < 3) return rejected(text)
        val method = parts[0]
        if (method != SEARCH_METHOD && method != NOTIFY_METHOD) return rejected(text)
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().uppercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        return SsdpRequest(method, headers)
    }

    /** Logs and returns null for a datagram that isn't a valid M-SEARCH/NOTIFY (an empty one is routine). */
    private fun rejected(text: String): SsdpRequest? {
        if (text.isNotBlank()) {
            Logger.d("Ignored non-SSDP datagram: '${text.take(LOG_PREVIEW_CHARS)}'")
        }
        return null
    }

    fun isSearch(request: SsdpRequest): Boolean =
        request.method == SEARCH_METHOD && request.header("MAN")?.trim('"') == SEARCH_MAN

    /** Every (NT, USN) pair the renderer announces: root, UDN, device type, then the three services. */
    fun targets(udn: String): List<Pair<String, String>> = buildList {
        add(ROOT_DEVICE to "$udn::$ROOT_DEVICE")
        add(udn to udn)
        add(DlnaConstants.DEVICE_TYPE to "$udn::${DlnaConstants.DEVICE_TYPE}")
        for (service in UpnpService.entries) add(service.serviceType to "$udn::${service.serviceType}")
    }

    /** Targets to answer for a search target `st`; empty when we are not what is being looked for. */
    fun matchingTargets(st: String?, udn: String): List<Pair<String, String>> {
        val wanted = st?.trim().orEmpty()
        if (wanted.isEmpty()) return emptyList()
        val all = targets(udn)
        return if (wanted == SEARCH_ALL) all else all.filter { it.first == wanted }
    }

    /** MX (seconds a responder may wait) clamped to 1..[DlnaConstants.MAX_SEARCH_DELAY_SECONDS]. */
    fun mxSeconds(request: SsdpRequest): Int =
        (request.header("MX")?.toIntOrNull() ?: 1).coerceIn(1, DlnaConstants.MAX_SEARCH_DELAY_SECONDS)

    fun notifyAlive(nt: String, usn: String, location: String): String =
        "NOTIFY * HTTP/1.1$CRLF" +
            "HOST: ${DlnaConstants.SSDP_ADDRESS}:${DlnaConstants.SSDP_PORT}$CRLF" +
            "CACHE-CONTROL: max-age=${DlnaConstants.CACHE_MAX_AGE_SECONDS}$CRLF" +
            "LOCATION: $location$CRLF" +
            "NT: $nt$CRLF" +
            "NTS: ssdp:alive$CRLF" +
            "SERVER: ${DlnaConstants.SERVER_HEADER}$CRLF" +
            "USN: $usn$CRLF$CRLF"

    fun notifyByeBye(nt: String, usn: String): String =
        "NOTIFY * HTTP/1.1$CRLF" +
            "HOST: ${DlnaConstants.SSDP_ADDRESS}:${DlnaConstants.SSDP_PORT}$CRLF" +
            "NT: $nt$CRLF" +
            "NTS: ssdp:byebye$CRLF" +
            "USN: $usn$CRLF$CRLF"

    fun searchResponse(st: String, usn: String, location: String, now: Date = Date()): String =
        "HTTP/1.1 200 OK$CRLF" +
            "CACHE-CONTROL: max-age=${DlnaConstants.CACHE_MAX_AGE_SECONDS}$CRLF" +
            "DATE: ${HttpDate.format(now)}$CRLF" +
            "EXT:$CRLF" +
            "LOCATION: $location$CRLF" +
            "SERVER: ${DlnaConstants.SERVER_HEADER}$CRLF" +
            "ST: $st$CRLF" +
            "USN: $usn$CRLF$CRLF"
}
