package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SamsungSsdpResponderTest — proves the SSDP answer for the Samsung Smart View search.
 *
 * WHY: a Samsung TV and the Samsung Smart View app do not look for a UPnP
 * MediaRenderer. They send an `M-SEARCH` with the Samsung search target
 * `urn:samsung.com:service:MultiScreenService:1` and wait for a 200 OK that names
 * the location of the device description. A renderer that stays silent here does
 * not appear in the Smart View list at all.
 *
 * WHAT WE TEST:
 * - the Samsung search is answered with exactly one datagram,
 * - the datagram carries ST, USN, LOCATION and EXT,
 * - the LOCATION points at the device description of this server,
 * - the USN names the device and uses the `uuid::…` form,
 * - the filter of the advertiser accepts the Samsung search.
 */
class SamsungSsdpResponderTest {

    private val location = "http://192.168.1.5:49152/description.xml"
    private val udn = "uuid:phairplay-device"
    private val responder = SsdpResponder(location = location, udn = udn)

    @Test
    fun `the Samsung search is answered with exactly one response`() {
        val responses = responder.responsesFor(mSearch(SsdpMessages.SAMSUNG_MULTI_SCREEN))

        assertEquals(1, responses.size)
    }

    @Test
    fun `the Samsung answer carries the search target, the USN and the location`() {
        val response = responder.responsesFor(mSearch(SsdpMessages.SAMSUNG_MULTI_SCREEN)).single()

        assertTrue("expected a 200 OK, got: $response", response.startsWith("HTTP/1.1 200 OK"))
        assertEquals(SsdpMessages.SAMSUNG_MULTI_SCREEN, headerOf(response, "ST"))
        assertEquals("$udn::${SsdpMessages.SAMSUNG_MULTI_SCREEN}", headerOf(response, "USN"))
        assertEquals(location, headerOf(response, "LOCATION"))
        assertTrue("the EXT header is missing", response.contains("\r\nEXT:"))
    }

    @Test
    fun `the location of the Samsung answer is the device description of this server`() {
        val response = responder.responsesFor(mSearch(SsdpMessages.SAMSUNG_MULTI_SCREEN)).single()

        // The control point follows the LOCATION to fetch the description — it has
        // to be the description of this renderer, with its own host and its own port.
        assertEquals(location, headerOf(response, "LOCATION"))
        assertTrue(
            "the location has to name the description document",
            headerOf(response, "LOCATION").endsWith("/description.xml")
        )
    }

    @Test
    fun `the USN of the Samsung answer names the device`() {
        val response = responder.responsesFor(mSearch(SsdpMessages.SAMSUNG_MULTI_SCREEN)).single()
        val usn = headerOf(response, "USN")

        assertTrue("the USN has to start with the UDN of the device", usn.startsWith("$udn::"))
        // "uuid:x::uuid:x" is not a valid USN and control points reject it.
        assertEquals(1, Regex("::").findAll(usn).count())
    }

    @Test
    fun `the Samsung search is not answered twice`() {
        val responses = responder.responsesFor(mSearch(SsdpMessages.SAMSUNG_MULTI_SCREEN))

        assertEquals(
            listOf(SsdpMessages.SAMSUNG_MULTI_SCREEN),
            responses.map { headerOf(it, "ST") }
        )
    }

    @Test
    fun `the advertiser accepts the Samsung search`() {
        // The advertiser only answers a message that the protocol layer accepts —
        // without this the answer would never leave the process.
        assertTrue(SsdpMessages.isRendererSearch(mSearch(SsdpMessages.SAMSUNG_MULTI_SCREEN), udn))
    }

    private fun headerOf(response: String, name: String): String =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()

    private fun mSearch(searchTarget: String): String = """
        M-SEARCH * HTTP/1.1
        HOST: 239.255.255.250:1900
        MAN: "ssdp:discover"
        MX: 1
        ST: $searchTarget

    """.trimIndent().replace("\n", "\r\n")
}
