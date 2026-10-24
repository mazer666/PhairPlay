package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DlnaDeviceDescriptionFieldsTest — proves the device description of the renderer.
 *
 * WHY: a Samsung TV does not read the device description the way a UPnP control
 * point does. It expects a complete set of device fields and skips a description
 * that is incomplete. These tests pin down the fields the TV needs.
 *
 * WHAT WE TEST:
 * - the description carries every field a Samsung TV reads,
 * - the serial number is derived from the UDN and stays stable,
 * - the model URL is part of the description,
 * - the friendly name is the name of the television.
 */
class DlnaDeviceDescriptionFieldsTest {

    private val udn = "uuid:550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `the description carries every field a Samsung TV reads`() {
        val xml = DlnaDescriptionBuilder.deviceDescription(
            friendlyName = "Living Room TV",
            udn = udn,
            presentationUrl = "http://192.168.1.2:1234/description.xml"
        )

        assertTrue("the device type is missing", xml.contains("<deviceType>"))
        assertTrue("the friendly name is missing", xml.contains("<friendlyName>Living Room TV</friendlyName>"))
        assertTrue("the manufacturer is missing", xml.contains("<manufacturer>"))
        assertTrue("the model description is missing", xml.contains("<modelDescription>"))
        assertTrue("the model name is missing", xml.contains("<modelName>"))
        assertTrue("the model number is missing", xml.contains("<modelNumber>"))
        assertTrue("the model URL is missing", xml.contains("<modelURL>"))
        assertTrue("the serial number is missing", xml.contains("<serialNumber>"))
        assertTrue("the UDN is missing", xml.contains("<UDN>$udn</UDN>"))
        assertTrue("the presentation URL is missing", xml.contains("<presentationURL>"))
    }

    @Test
    fun `the serial number is derived from the UDN`() {
        val xml = DlnaDescriptionBuilder.deviceDescription(
            friendlyName = "Living Room TV",
            udn = udn,
            presentationUrl = "http://192.168.1.2:1234/description.xml"
        )

        assertTrue(
            "the serial number has to name the uuid part of the UDN",
            xml.contains("<serialNumber>550e8400-e29b-41d4-a716-446655440000</serialNumber>")
        )
    }

    @Test
    fun `the serial number stays the same for the same device`() {
        val first = DlnaDescriptionBuilder.serialNumberFor(udn)
        val second = DlnaDescriptionBuilder.serialNumberFor(udn)

        assertEquals(first, second)
    }

    @Test
    fun `two devices get two different serial numbers`() {
        val first = DlnaDescriptionBuilder.serialNumberFor("uuid:aaaaaaaa-0000-0000-0000-000000000000")
        val second = DlnaDescriptionBuilder.serialNumberFor("uuid:bbbbbbbb-0000-0000-0000-000000000000")

        assertTrue("the serial numbers have to differ", first != second)
    }

    @Test
    fun `the serial number carries no character that would break the XML`() {
        val serial = DlnaDescriptionBuilder.serialNumberFor("uuid:test<device>&\"'")

        assertTrue("the serial number has to be plain", serial.none { it == '<' || it == '>' || it == '&' })
    }

    @Test
    fun `the model URL is part of the description`() {
        val xml = DlnaDescriptionBuilder.deviceDescription(
            friendlyName = "Living Room TV",
            udn = udn,
            presentationUrl = "http://192.168.1.2:1234/description.xml"
        )

        assertTrue(
            "the model URL is missing",
            xml.contains("<modelURL>${DlnaDescriptionBuilder.MODEL_URL}</modelURL>")
        )
    }

    @Test
    fun `the friendly name is the name of the television`() {
        val xml = DlnaDescriptionBuilder.deviceDescription(
            friendlyName = "Kueche TV",
            udn = udn,
            presentationUrl = "http://192.168.1.2:1234/description.xml"
        )

        assertTrue(
            "the friendly name has to be the name of the television",
            xml.contains("<friendlyName>Kueche TV</friendlyName>")
        )
    }
}
