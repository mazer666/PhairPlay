package com.phairplay.dlna

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * DlnaTestNetwork — the private LAN address the DLNA tests use.
 *
 * WHY: the GENA callback guard only accepts private IPv4 addresses of the local
 * subnet, and the loopback address is rejected on purpose. The tests that exercise
 * a real callback therefore need a real private address of the machine they run on.
 */
internal object DlnaTestNetwork {

    /** The first private IPv4 address of the machine, or an error when there is none. */
    fun privateAddress(): String =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
            ?: error("the test machine has no private IPv4 address")
}
