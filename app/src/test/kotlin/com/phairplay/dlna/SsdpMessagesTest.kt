package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * SsdpMessagesTest — discovery datagrams must carry exactly the headers control points key on.
 *
 * WHY: Windows lists a renderer from NOTIFY *or* from its M-SEARCH reply; a missing EXT/LOCATION/USN or
 * a wrong ST match means the TV never appears in "Cast to Device".
 */
class SsdpMessagesTest {

    private val udn = "uuid:12345678-1234-1234-1234-123456789abc"
    private val location = "http://192.168.1.185:49494/description.xml"

    private val search = "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 5\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    @Test
    fun `parse reads method and case-insensitive headers`() {
        val req = SsdpMessages.parse(search)!!
        assertEquals("M-SEARCH", req.method)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", req.header("st"))
        assertTrue(SsdpMessages.isSearch(req))
    }

    @Test
    fun `parse rejects non-SSDP text`() {
        assertNull(SsdpMessages.parse("GET / HTTP/1.1\r\n\r\n"))
        assertNull(SsdpMessages.parse(""))
    }

    @Test
    fun `isSearch requires the ssdp discover MAN header`() {
        val req = SsdpMessages.parse("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n")!!
        assertFalse(SsdpMessages.isSearch(req))
    }

    @Test
    fun `targets cover root, UDN, device type and the three services`() {
        val targets = SsdpMessages.targets(udn)
        assertEquals(6, targets.size)
        assertEquals("upnp:rootdevice" to "$udn::upnp:rootdevice", targets[0])
        assertEquals(udn to udn, targets[1])
        assertTrue(targets.any { it.first == "urn:schemas-upnp-org:service:AVTransport:1" })
    }

    @Test
    fun `matchingTargets answers ssdp all with everything and a device type with one entry`() {
        assertEquals(6, SsdpMessages.matchingTargets("ssdp:all", udn).size)
        val one = SsdpMessages.matchingTargets(DlnaConstants.DEVICE_TYPE, udn)
        assertEquals(listOf(DlnaConstants.DEVICE_TYPE to "$udn::${DlnaConstants.DEVICE_TYPE}"), one)
        assertTrue(SsdpMessages.matchingTargets("urn:schemas-upnp-org:device:MediaServer:1", udn).isEmpty())
        assertTrue(SsdpMessages.matchingTargets(null, udn).isEmpty())
    }

    @Test
    fun `mxSeconds clamps to the configured maximum and defaults to one`() {
        assertEquals(3, SsdpMessages.mxSeconds(SsdpMessages.parse(search)!!))
        val noMx = SsdpMessages.parse("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n")!!
        assertEquals(1, SsdpMessages.mxSeconds(noMx))
    }

    @Test
    fun `notifyAlive carries the mandatory headers`() {
        val text = SsdpMessages.notifyAlive("upnp:rootdevice", "$udn::upnp:rootdevice", location)
        assertTrue(text.startsWith("NOTIFY * HTTP/1.1\r\n"))
        assertTrue(text.contains("NTS: ssdp:alive\r\n"))
        assertTrue(text.contains("LOCATION: $location\r\n"))
        assertTrue(text.contains("CACHE-CONTROL: max-age=1800\r\n"))
        assertTrue(text.contains("USN: $udn::upnp:rootdevice\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun `notifyByeBye has no LOCATION and says byebye`() {
        val text = SsdpMessages.notifyByeBye(udn, udn)
        assertTrue(text.contains("NTS: ssdp:byebye\r\n"))
        assertFalse(text.contains("LOCATION"))
    }

    @Test
    fun `searchResponse is a 200 with EXT, ST, USN and an exact RFC 1123 DATE`() {
        val text = SsdpMessages.searchResponse(
            DlnaConstants.DEVICE_TYPE, "$udn::${DlnaConstants.DEVICE_TYPE}", location, now = Date(0)
        )
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("EXT:\r\n"))
        assertTrue(text.contains("ST: ${DlnaConstants.DEVICE_TYPE}\r\n"))
        assertTrue(text.contains("USN: $udn::${DlnaConstants.DEVICE_TYPE}\r\n"))
        assertTrue(text.contains("DATE: Thu, 01 Jan 1970 00:00:00 GMT\r\n"))
    }

    @Test
    fun `device type matches the standard MediaRenderer URN`() {
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", DlnaConstants.DEVICE_TYPE)
    }
}
