package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceDescriptionTest — the root description is what Windows validates before listing the renderer.
 *
 * WHY: Missing `X_DLNADOC`, a non-standard serviceId or a relative URL Windows cannot resolve all make
 * the TV silently absent from "Cast to Device".
 */
class DeviceDescriptionTest {

    private val base = "http://192.168.1.185:49494"
    private val xml = DeviceDescription(
        friendlyName = "Living Room <TV>",
        udn = "uuid:12345678-1234-1234-1234-123456789abc",
        modelNumber = "1.0.0"
    ).xml(base)

    @Test
    fun `is well-formed XML with a root device`() {
        val doc = SecureXml.parse(xml)
        assertNotNull(doc)
        assertEquals("root", SecureXml.localNameOf(doc!!.documentElement))
    }

    @Test
    fun `carries identity, DLNA doc marker and escaped friendly name`() {
        assertTrue(xml.contains("<deviceType>${DlnaConstants.DEVICE_TYPE}</deviceType>"))
        assertTrue(xml.contains("<friendlyName>Living Room &lt;TV&gt;</friendlyName>"))
        assertTrue(xml.contains("<UDN>uuid:12345678-1234-1234-1234-123456789abc</UDN>"))
        assertTrue(xml.contains("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>"))
        assertTrue(xml.contains("<modelNumber>1.0.0</modelNumber>"))
    }

    @Test
    fun `lists the three services with standard ids and absolute URLs`() {
        for (service in UpnpService.entries) {
            assertTrue(xml.contains("<serviceId>${service.serviceId}</serviceId>"))
            assertTrue(xml.contains("<serviceType>${service.serviceType}</serviceType>"))
            assertTrue(xml.contains("<SCPDURL>$base${service.scpdPath}</SCPDURL>"))
            assertTrue(xml.contains("<controlURL>$base${service.controlPath}</controlURL>"))
            assertTrue(xml.contains("<eventSubURL>$base${service.eventPath}</eventSubURL>"))
        }
    }

    @Test
    fun `advertises a PNG icon`() {
        assertTrue(xml.contains("<mimetype>image/png</mimetype>"))
        assertTrue(xml.contains("<url>$base${DlnaConstants.ICON_PATH}</url>"))
    }
}
