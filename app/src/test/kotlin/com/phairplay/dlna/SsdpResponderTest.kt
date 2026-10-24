package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SsdpResponderTest — proves the answer set a UPnP device owes to a search.
 *
 * WHY: the search target decides how many answers the control point gets. The
 * UPnP specification asks for **three** answers to `ST: ssdp:all` (rootdevice,
 * device type and UDN); a device that answers only once is missing from the
 * renderer picker of some control points — VLC among them.
 *
 * Example:
 *   SsdpResponder(location, udn).responsesFor(mSearchMessage)
 */
class SsdpResponderTest {

    private val responder = SsdpResponder(
        location = "http://192.168.1.5:49152/description.xml",
        udn = "uuid:phairplay-device"
    )

    @Test
    fun `ssdp all is answered with three responses`() {
        val responses = responder.responsesFor(mSearch(SsdpResponder.SSDP_ALL))

        assertEquals(3, responses.size)
    }

    @Test
    fun `the three answers carry the rootdevice, the device type and the UDN`() {
        val responses = responder.responsesFor(mSearch(SsdpResponder.SSDP_ALL))
        val searchTargets = responses.map { headerOf(it, "ST") }
        val uniqueNames = headerOf(responses[0], "USN").substringBefore("::")

        assertEquals(
            listOf(
                SsdpMessages.ROOT_DEVICE,
                SsdpMessages.MEDIA_RENDERER,
                "uuid:phairplay-device"
            ),
            searchTargets
        )
        // The USN of all three answers is the UDN of the device.
        assertTrue(responses.all { headerOf(it, "USN").startsWith(uniqueNames) })
    }

    @Test
    fun `the UDN answer carries the UDN as its USN`() {
        val responses = responder.responsesFor(mSearch(SsdpResponder.SSDP_ALL))
        val udnAnswer = responses.last()

        assertEquals("uuid:phairplay-device", headerOf(udnAnswer, "ST"))
        // "uuid:x::uuid:x" is not a valid USN and control points reject it.
        assertEquals("uuid:phairplay-device", headerOf(udnAnswer, "USN"))
    }

    @Test
    fun `a MediaRenderer search is answered with exactly one response`() {
        val responses = responder.responsesFor(mSearch(SsdpMessages.MEDIA_RENDERER))

        assertEquals(1, responses.size)
        assertEquals(SsdpMessages.MEDIA_RENDERER, headerOf(responses[0], "ST"))
        assertEquals(
            "uuid:phairplay-device::${SsdpMessages.MEDIA_RENDERER}",
            headerOf(responses[0], "USN")
        )
    }

    @Test
    fun `a rootdevice search is answered with exactly one response`() {
        val responses = responder.responsesFor(mSearch(SsdpMessages.ROOT_DEVICE))

        assertEquals(1, responses.size)
        assertEquals(SsdpMessages.ROOT_DEVICE, headerOf(responses[0], "ST"))
    }

    @Test
    fun `every answer carries the headers a control point needs`() {
        val responses = responder.responsesFor(mSearch(SsdpResponder.SSDP_ALL))

        responses.forEach { response ->
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue("EXT header missing", response.contains("\r\nEXT:"))
            assertTrue(
                "LOCATION header missing",
                response.contains("LOCATION: http://192.168.1.5:49152/description.xml")
            )
            assertTrue("ST header missing", response.contains("\r\nST: "))
            assertTrue("USN header missing", response.contains("\r\nUSN: "))
        }
    }

    @Test
    fun `a search for an unknown target is not answered`() {
        val responses = responder.responsesFor(mSearch("urn:schemas-upnp-org:device:MediaServer:1"))

        assertTrue(responses.isEmpty())
    }

    @Test
    fun `a message without a search target is not answered`() {
        val responses = responder.responsesFor("M-SEARCH * HTTP/1.1\r\nMAN: \"ssdp:discover\"\r\n\r\n")

        assertTrue(responses.isEmpty())
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
