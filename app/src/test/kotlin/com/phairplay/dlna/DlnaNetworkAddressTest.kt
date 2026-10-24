package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DlnaNetworkAddressTest — proves the choice of the advertised LAN address.
 *
 * WHY: the address in the SSDP `LOCATION` header must be reachable by the control
 * point. On a machine with several interfaces (VPN, container bridge, virtual
 * switch) the *first* non-loopback address is regularly the wrong one, and the
 * renderer then stays invisible. The address of the interface that carries the
 * default route is the right choice.
 */
class DlnaNetworkAddressTest {

    @Test
    fun `the address of the default route wins`() {
        val candidates = listOf(
            DlnaInterfaceCandidate("docker0", "172.17.0.1", isVirtual = true),
            DlnaInterfaceCandidate("wlan0", "192.168.1.42", isVirtual = false),
            DlnaInterfaceCandidate("tun0", "10.8.0.2", isVirtual = true)
        )

        val selected = DlnaNetworkAddress.select(candidates, defaultRouteAddress = "192.168.1.42")

        assertEquals("192.168.1.42", selected)
    }

    @Test
    fun `a virtual interface is only used when there is no other candidate`() {
        val candidates = listOf(
            DlnaInterfaceCandidate("docker0", "172.17.0.1", isVirtual = true),
            DlnaInterfaceCandidate("wlan0", "192.168.1.42", isVirtual = false)
        )

        // The default route is unknown — the real interface must still win.
        assertEquals("192.168.1.42", DlnaNetworkAddress.select(candidates, null))
    }

    @Test
    fun `the default route address wins even when it looks virtual`() {
        // A VPN carries the default route — but then the VPN address is the one the
        // control point can reach, so the route decides, not the interface name.
        val candidates = listOf(
            DlnaInterfaceCandidate("wlan0", "192.168.1.42", isVirtual = false),
            DlnaInterfaceCandidate("tun0", "10.8.0.2", isVirtual = true)
        )

        assertEquals(
            "10.8.0.2",
            DlnaNetworkAddress.select(candidates, defaultRouteAddress = "10.8.0.2")
        )
    }

    @Test
    fun `without any candidate nothing is selected`() {
        assertNull(DlnaNetworkAddress.select(emptyList(), "192.168.1.42"))
    }

    @Test
    fun `an unknown default route address falls back to a real interface`() {
        val candidates = listOf(
            DlnaInterfaceCandidate("docker0", "172.17.0.1", isVirtual = true),
            DlnaInterfaceCandidate("wlan0", "192.168.1.42", isVirtual = false)
        )

        assertEquals(
            "192.168.1.42",
            DlnaNetworkAddress.select(candidates, defaultRouteAddress = "203.0.113.7")
        )
    }

    @Test
    fun `the machine offers at least one candidate or the fallback is used`() {
        val selected = DlnaNetworkAddress.localIpv4Address()

        assertEquals(true, selected.isNotBlank())
    }
}
