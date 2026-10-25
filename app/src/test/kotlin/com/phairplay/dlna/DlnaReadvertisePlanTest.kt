package com.phairplay.dlna

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DlnaReadvertisePlanTest — proves the decision a DLNA re-advertisement takes.
 *
 * WHY: after a network loss the renderer has to announce itself again. Whether the
 * description server has to be rebuilt with a new URL depends on the address the
 * device has now, and getting that wrong is invisible in the app: the renderer
 * either stays invisible or answers with a description URL that no control point can
 * fetch. The decision is therefore worth a test of its own.
 *
 * The socket part of the recovery cannot be tested here — it needs a device. What can
 * be tested is the decision that decides whether the description server is rebuilt,
 * and that the address which ends up in the description URL is one a control point
 * can actually reach.
 */
class DlnaReadvertisePlanTest {

    private lateinit var scope: CoroutineScope
    private val servers = mutableListOf<DlnaDescriptionServer>()

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        servers.forEach { server -> runCatching { server.stop() } }
        servers.clear()
        scope.cancel()
    }

    @Test
    fun `the same address only repeats the announcements`() {
        val action = DlnaReadvertisePlan.decide(
            previousAddress = "192.168.1.5",
            currentAddress = "192.168.1.5"
        )

        assertEquals(DlnaReadvertisePlan.Action.ANNOUNCE_ONLY, action)
    }

    @Test
    fun `a changed address rebuilds the description server`() {
        val action = DlnaReadvertisePlan.decide(
            previousAddress = "192.168.1.5",
            currentAddress = "192.168.1.6"
        )

        assertEquals(DlnaReadvertisePlan.Action.REBUILD_DESCRIPTION, action)
    }

    @Test
    fun `a rebuilt description server carries the new address in its URL`() {
        val oldServer = startServer("192.168.1.5")
        val newServer = startServer("192.168.1.6")

        // The whole point of the rebuild: the URL of the new server names the address
        // the device has now — the old one pointed at an address that is gone.
        assertTrue(
            "the new description URL does not carry the new address: ${newServer.descriptionUrl}",
            newServer.descriptionUrl.contains("192.168.1.6")
        )
        assertFalse(
            "the new description URL still carries the old address: ${newServer.descriptionUrl}",
            newServer.descriptionUrl.contains("192.168.1.5")
        )
        assertNotEquals(oldServer.descriptionUrl, newServer.descriptionUrl)
        assertNotEquals(oldServer.port, newServer.port)
    }

    @Test
    fun `an empty address needs no rebuild`() {
        assertEquals(
            DlnaReadvertisePlan.Action.ANNOUNCE_ONLY,
            DlnaReadvertisePlan.decide(previousAddress = "192.168.1.5", currentAddress = "")
        )
        assertEquals(
            DlnaReadvertisePlan.Action.ANNOUNCE_ONLY,
            DlnaReadvertisePlan.decide(previousAddress = "192.168.1.5", currentAddress = "   ")
        )
        assertEquals(
            DlnaReadvertisePlan.Action.ANNOUNCE_ONLY,
            DlnaReadvertisePlan.decide(previousAddress = "192.168.1.5", currentAddress = null)
        )
    }

    @Test
    fun `the unspecified address needs no rebuild`() {
        assertEquals(
            DlnaReadvertisePlan.Action.ANNOUNCE_ONLY,
            DlnaReadvertisePlan.decide(previousAddress = "192.168.1.5", currentAddress = "0.0.0.0")
        )
        assertNull(DlnaReadvertisePlan.approveAddress("0.0.0.0"))
    }

    @Test
    fun `a broken address never reaches a description URL`() {
        // A broken address must not be advertised: the control point would find the
        // renderer and then wait for a description that can never arrive.
        listOf(
            "192.168.1.999",      // an octet out of range
            "192.168.1",          // too few octets
            "192.168.1.5.7",      // too many octets
            "not-an-address",     // not a literal at all
            "192.168.1.5/evil",   // a literal with something appended
            "0177.0.0.1",         // the octal notation of an address
            "3232235777",         // the decimal notation of an address
            "127.0.0.1",          // loopback — the network cannot reach it
            "239.255.255.250",    // the multicast group of SSDP
            "255.255.255.255"     // the broadcast address
        ).forEach { broken ->
            assertNull(
                "the address '$broken' must not be advertised",
                DlnaReadvertisePlan.approveAddress(broken)
            )
            assertEquals(
                "the address '$broken' must not trigger a rebuild",
                DlnaReadvertisePlan.Action.ANNOUNCE_ONLY,
                DlnaReadvertisePlan.decide(previousAddress = "192.168.1.5", currentAddress = broken)
            )
        }
    }

    @Test
    fun `an address that was never advertised is built`() {
        // The renderer never advertised an address (or advertised the loopback
        // fallback) — the description server has to be built with the real address.
        assertEquals(
            DlnaReadvertisePlan.Action.REBUILD_DESCRIPTION,
            DlnaReadvertisePlan.decide(previousAddress = null, currentAddress = "192.168.1.6")
        )
        assertEquals(
            DlnaReadvertisePlan.Action.REBUILD_DESCRIPTION,
            DlnaReadvertisePlan.decide(previousAddress = "127.0.0.1", currentAddress = "192.168.1.6")
        )
    }

    /**
     * Starts a description server on the given address and remembers it for the
     * clean-up.
     *
     * @param host the address the server describes.
     * @return the running server.
     */
    private fun startServer(host: String): DlnaDescriptionServer {
        val server = DlnaDescriptionServer(
            friendlyName = "Test TV",
            udn = UDN,
            localHost = host
        )
        server.start(scope)
        servers += server
        return server
    }

    companion object {
        private const val UDN = "uuid:phairplay-readvertise-test"
    }
}
