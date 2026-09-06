package com.phairplay.dlna

import com.phairplay.dlna.scpd.Scpd
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * ScpdTest — every service description must parse and declare the actions/variables we implement.
 *
 * WHY: Control points read the SCPD to learn which actions exist; a typo here means Windows never calls
 * the action, and an un-evented `LastChange` means it never subscribes.
 */
class ScpdTest {

    @Test
    fun `all three SCPDs are well-formed`() {
        for (service in UpnpService.entries) {
            assertNotNull("SCPD for $service", SecureXml.parse(Scpd.forService(service)))
        }
    }

    @Test
    fun `AVTransport declares the transport actions and an evented LastChange`() {
        val xml = Scpd.forService(UpnpService.AV_TRANSPORT)
        for (action in listOf("SetAVTransportURI", "SetNextAVTransportURI", "Play", "Pause", "Stop", "Seek",
            "Next", "Previous", "GetMediaInfo", "GetTransportInfo", "GetPositionInfo",
            "GetDeviceCapabilities", "GetTransportSettings", "GetCurrentTransportActions")) {
            assertTrue("missing $action", xml.contains("<name>$action</name>"))
        }
        assertTrue(xml.contains("<stateVariable sendEvents=\"yes\"><name>LastChange</name>"))
        assertTrue(xml.contains("<allowedValue>PAUSED_PLAYBACK</allowedValue>"))
    }

    @Test
    fun `RenderingControl declares volume and mute with a 0-100 range`() {
        val xml = Scpd.forService(UpnpService.RENDERING_CONTROL)
        for (action in listOf("ListPresets", "SelectPreset", "GetVolume", "SetVolume", "GetMute", "SetMute")) {
            assertTrue("missing $action", xml.contains("<name>$action</name>"))
        }
        assertTrue(xml.contains("<minimum>0</minimum><maximum>100</maximum>"))
    }

    @Test
    fun `ConnectionManager declares protocol info and connection actions`() {
        val xml = Scpd.forService(UpnpService.CONNECTION_MANAGER)
        for (action in listOf("GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo")) {
            assertTrue("missing $action", xml.contains("<name>$action</name>"))
        }
        assertTrue(xml.contains("<stateVariable sendEvents=\"yes\"><name>SinkProtocolInfo</name>"))
    }

    @Test
    fun `every relatedStateVariable is a declared stateVariable`() {
        for (service in UpnpService.entries) {
            val document = SecureXml.parse(Scpd.forService(service))!!
            val root = document.documentElement
            val declaredVariables = descendantsNamed(root, "stateVariable")
                .mapNotNull { SecureXml.firstText(it, "name") }
                .toSet()
            val relatedVariables = descendantsNamed(root, "relatedStateVariable")
                .mapNotNull { it.textContent }
                .toSet()
            val undeclared = relatedVariables - declaredVariables
            assertTrue("$service references undeclared state variables: $undeclared", undeclared.isEmpty())
        }
    }

    /** All elements anywhere under [root] whose local name is [localName] (namespace-agnostic, recursive). */
    private fun descendantsNamed(root: Element, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val stack = ArrayDeque<Element>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val element = stack.removeLast()
            if (SecureXml.localNameOf(element) == localName) result += element
            for (child in SecureXml.childElements(element)) stack.addLast(child)
        }
        return result
    }
}
