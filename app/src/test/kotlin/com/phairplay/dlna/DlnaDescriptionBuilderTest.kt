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
    fun `service description includes requested actions`() {
        val xml = DlnaDescriptionBuilder.serviceDescription(
            serviceName = "AVTransport",
            actions = listOf("Play", "Stop")
        )

        assertTrue(xml.contains("<name>Play</name>"))
        assertTrue(xml.contains("<name>Stop</name>"))
    }
}
