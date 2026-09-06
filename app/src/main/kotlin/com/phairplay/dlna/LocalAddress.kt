package com.phairplay.dlna

import com.phairplay.util.Logger
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * LocalAddress — the IPv4 address control points can reach this TV on.
 *
 * WHY: The SSDP LOCATION and the description's absolute URLs must name an address on the LAN; Android has
 * no single "my IP" API that works for both Wi-Fi and Ethernet, so we scan the interfaces.
 *
 * HOW: `LocalAddress.ipv4()` → `"192.168.1.185"` or null when no site-local interface is up.
 */
object LocalAddress {
    fun ipv4(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        Logger.w("LocalAddress: could not enumerate interfaces: ${e.message}")
        null
    }
}
