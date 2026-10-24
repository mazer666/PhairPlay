package com.phairplay.dlna

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaDescriptionBuilderTest {

    @Test
    fun `device description advertises MediaRenderer and core services`() {
        val xml = DlnaDescriptionBuilder.deviceDescription(
            friendlyName = "Living Room TV",
            udn = "uuid:test-device",
            presentationUrl = "http://192.168.1.2:1234/description.xml"
        )

        assertTrue(xml.contains("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(xml.contains("<friendlyName>Living Room TV</friendlyName>"))
        assertTrue(xml.contains("ConnectionManager"))
        assertTrue(xml.contains("AVTransport"))
        assertTrue(xml.contains("RenderingControl"))
    }

    @Test
    fun `device description escapes XML special characters`() {
        val xml = DlnaDescriptionBuilder.deviceDescription(
            friendlyName = "TV & <Room>",
            udn = "uuid:test&device",
            presentationUrl = "http://127.0.0.1:1/description.xml?a=1&b=2"
        )

        assertTrue(xml.contains("TV &amp; &lt;Room&gt;"))
        assertTrue(xml.contains("uuid:test&amp;device"))
        assertFalse(xml.contains("TV & <Room>"))
    }

    @Test
    fun `the device description points at the three control URLs`() {
        val xml = DlnaDescriptionBuilder.deviceDescription(
            friendlyName = "Living Room TV",
            udn = "uuid:test-device",
            presentationUrl = "http://192.168.1.2:1234/description.xml"
        )

        assertTrue(xml.contains("<controlURL>/upnp/control/ConnectionManager</controlURL>"))
        assertTrue(xml.contains("<controlURL>/upnp/control/AVTransport</controlURL>"))
        assertTrue(xml.contains("<controlURL>/upnp/control/RenderingControl</controlURL>"))
        assertTrue(xml.contains("<eventSubURL>/upnp/event/AVTransport</eventSubURL>"))
    }

    @Test
    fun `the AVTransport service description lists the actions with their arguments`() {
        val xml = DlnaDescriptionBuilder.serviceDescription("AVTransport")

        assertTrue(xml.contains("<name>SetAVTransportURI</name>"))
        assertTrue(xml.contains("<name>Play</name>"))
        assertTrue(xml.contains("<name>GetPositionInfo</name>"))
        assertTrue(xml.contains("<name>CurrentURI</name>"))
        assertTrue(xml.contains("<direction>in</direction>"))
        assertTrue(xml.contains("<direction>out</direction>"))
    }

    @Test
    fun `the ConnectionManager service description lists GetProtocolInfo`() {
        val xml = DlnaDescriptionBuilder.serviceDescription("ConnectionManager")

        assertTrue(xml.contains("<name>GetProtocolInfo</name>"))
        assertTrue(xml.contains("<name>Source</name>"))
        assertTrue(xml.contains("<name>Sink</name>"))
    }

    @Test
    fun `the RenderingControl service description lists the volume actions`() {
        val xml = DlnaDescriptionBuilder.serviceDescription("RenderingControl")

        assertTrue(xml.contains("<name>GetVolume</name>"))
        assertTrue(xml.contains("<name>SetVolume</name>"))
        assertTrue(xml.contains("<name>GetMute</name>"))
    }
}
