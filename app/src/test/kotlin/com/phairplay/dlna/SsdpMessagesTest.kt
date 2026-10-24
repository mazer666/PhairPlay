package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SsdpMessagesTest — proves which SSDP messages are answered and what an answer
 * contains.
 *
 * WHY: SSDP is the first contact between a control point and the renderer. A search
 * that is not recognised leaves the device invisible, and an answer without LOCATION
 * or USN makes the control point drop it again.
 *
 * Example:
 *   SsdpMessages.isRendererSearch(mSearchMessage)
 */
class SsdpMessagesTest {

    @Test
    fun `recognizes MediaRenderer M-SEARCH`() {
        val message = """
            M-SEARCH * HTTP/1.1
            HOST: 239.255.255.250:1900
            MAN: "ssdp:discover"
            MX: 1
            ST: urn:schemas-upnp-org:device:MediaRenderer:1

        """.trimIndent().replace("\n", "\r\n")

        assertTrue(SsdpMessages.isRendererSearch(message))
    }

    @Test
    fun `recognizes ssdp all search`() {
        val message = """
            M-SEARCH * HTTP/1.1
            MAN: "ssdp:discover"
            ST: ssdp:all

        """.trimIndent().replace("\n", "\r\n")

        assertTrue(SsdpMessages.isRendererSearch(message))
    }

    @Test
    fun `recognizes a MAN header without a space after the colon`() {
        // Control points differ in the spacing of their headers — a device that only
        // accepts "MAN: \"ssdp:discover\"" stays invisible.
        val message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN:\"ssdp:discover\"\r\nST: ssdp:all\r\n\r\n"

        assertTrue(SsdpMessages.isRendererSearch(message))
    }

    @Test
    fun `rejects non search messages`() {
        val notify = "NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\n\r\n"

        assertFalse(SsdpMessages.isRendererSearch(notify))
    }

    @Test
    fun `rejects a search for a different device type`() {
        val message = "M-SEARCH * HTTP/1.1\r\nMAN: \"ssdp:discover\"\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"

        assertFalse(SsdpMessages.isRendererSearch(message))
    }

    @Test
    fun `search response includes location and USN`() {
        val response = SsdpMessages.searchResponse(
            location = "http://192.168.1.2:1234/description.xml",
            udn = "uuid:test-device",
            searchTarget = SsdpMessages.MEDIA_RENDERER
        )

        assertTrue(response.contains("HTTP/1.1 200 OK"))
        assertTrue(response.contains("LOCATION: http://192.168.1.2:1234/description.xml"))
        assertTrue(response.contains("USN: uuid:test-device::urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(response.contains("EXT:"))
    }

    @Test
    fun `the UDN answer carries the UDN as its USN`() {
        val response = SsdpMessages.searchResponse(
            location = "http://192.168.1.2:1234/description.xml",
            udn = "uuid:test-device",
            searchTarget = "uuid:test-device"
        )

        assertTrue(response.contains("USN: uuid:test-device\r\n"))
        assertFalse(response.contains("uuid:test-device::uuid:test-device"))
    }

    @Test
    fun `the search target is read from the message`() {
        val message = "M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n"

        assertEquals("ssdp:all", SsdpMessages.searchTargetOf(message))
    }
}
