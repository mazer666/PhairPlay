package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UpnpServiceTest — the service ids/types/paths must match what Windows hard-codes.
 *
 * WHY: Windows "Cast to Device" refuses a renderer whose serviceId strings deviate from
 * `urn:upnp-org:serviceId:AVTransport` etc. The router also depends on the path lookups.
 */
class UpnpServiceTest {

    @Test
    fun `service ids and types use the standard UPnP strings`() {
        assertEquals("urn:upnp-org:serviceId:AVTransport", UpnpService.AV_TRANSPORT.serviceId)
        assertEquals("urn:schemas-upnp-org:service:RenderingControl:1", UpnpService.RENDERING_CONTROL.serviceType)
        assertEquals("urn:schemas-upnp-org:service:ConnectionManager:1", UpnpService.CONNECTION_MANAGER.serviceType)
    }

    @Test
    fun `paths round-trip through the lookups`() {
        assertEquals(UpnpService.AV_TRANSPORT, UpnpService.fromControlPath("/control/AVTransport"))
        assertEquals(UpnpService.RENDERING_CONTROL, UpnpService.fromEventPath("/event/RenderingControl"))
        assertEquals(UpnpService.CONNECTION_MANAGER, UpnpService.fromScpdPath("/scpd/ConnectionManager.xml"))
        assertEquals(UpnpService.AV_TRANSPORT, UpnpService.fromServiceType("urn:schemas-upnp-org:service:AVTransport:1"))
        assertNull(UpnpService.fromControlPath("/control/Nope"))
    }
}
