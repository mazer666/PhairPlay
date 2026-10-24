package com.phairplay.dlna

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * DlnaInterfaceCandidate — one IPv4 address of one network interface.
 */
internal data class DlnaInterfaceCandidate(
    val name: String,
    val address: String,
    val isVirtual: Boolean
)

/**
 * DlnaNetworkAddress — picks the LAN address the renderer advertises.
 *
 * WHY: the address in the SSDP `LOCATION` header and in the device description has
 * to be reachable by the control point. Taking the *first* non-loopback IPv4 address
 * is wrong on a machine that has several interfaces: a VPN, a virtual switch or a
 * container bridge is often enumerated first, and the control point then tries to
 * reach an address that does not exist on its side — the device stays invisible.
 *
 * The address of the interface that carries the **default route** is the right one:
 * that is the address the TV itself uses to reach the network the control point is
 * on. The route is looked up with a connected UDP socket, which performs a routing
 * table lookup *without sending a single packet* — no traffic leaves the device.
 *
 * Example:
 *   DlnaNetworkAddress.localIpv4Address()   // "192.168.1.42"
 */
internal object DlnaNetworkAddress {

    /** Used when no usable interface exists at all. */
    const val FALLBACK = "127.0.0.1"

    /** Interface name prefixes that belong to VPNs, container bridges or tunnels. */
    private val VIRTUAL_PREFIXES = listOf(
        "tun", "tap", "utun", "wg", "ppp", "ipsec", "docker", "veth", "br-",
        "virbr", "vmnet", "vboxnet", "ham", "zt", "tailscale", "lo"
    )

    /**
     * Chooses the address to advertise.
     *
     * @param candidates the usable IPv4 addresses of the device.
     * @param defaultRouteAddress the address of the interface that carries the default
     *   route, or null when it could not be determined.
     * @return the chosen address, or null when there is no candidate.
     */
    fun select(
        candidates: List<DlnaInterfaceCandidate>,
        defaultRouteAddress: String?
    ): String? {
        if (candidates.isEmpty()) return null
        defaultRouteAddress?.let { preferred ->
            candidates.firstOrNull { it.address == preferred }?.let { return it.address }
        }
        return candidates.firstOrNull { !it.isVirtual }?.address
            ?: candidates.first().address
    }

    /** Returns every usable IPv4 address of the device. */
    fun candidates(): List<DlnaInterfaceCandidate> =
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface ->
                    iface.inetAddresses.toList().asSequence()
                        .filterIsInstance<Inet4Address>()
                        .map { address ->
                            DlnaInterfaceCandidate(
                                name = iface.name,
                                address = address.hostAddress.orEmpty(),
                                isVirtual = isVirtual(iface)
                            )
                        }
                }
                .filter { it.address.isNotBlank() }
                .toList()
        }.getOrDefault(emptyList())

    /**
     * Returns the IPv4 address of the interface that carries the default route.
     *
     * WHY a documentation address: `192.0.2.1` is reserved for documentation
     * (RFC 5737) and is never routed to the internet. Connecting a *datagram*
     * socket does not send anything — it only asks the routing table which local
     * address would be used for that destination.
     *
     * @return the local address, or null when the lookup failed.
     */
    fun defaultRouteAddress(): String? =
        runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName(ROUTE_PROBE_ADDRESS), ROUTE_PROBE_PORT)
                val local = socket.localAddress
                if (local is Inet4Address) local.hostAddress else null
            }
        }.getOrNull()

    /** Returns the LAN address the renderer should advertise. */
    fun localIpv4Address(): String =
        select(candidates(), defaultRouteAddress()) ?: FALLBACK

    /** Returns true when the interface looks like a VPN, tunnel or virtual bridge. */
    private fun isVirtual(iface: NetworkInterface): Boolean =
        iface.isPointToPoint ||
            VIRTUAL_PREFIXES.any { prefix -> iface.name.startsWith(prefix, ignoreCase = true) }

    private const val ROUTE_PROBE_ADDRESS = "192.0.2.1"
    private const val ROUTE_PROBE_PORT = 9
}
