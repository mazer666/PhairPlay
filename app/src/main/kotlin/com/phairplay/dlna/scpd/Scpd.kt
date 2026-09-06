package com.phairplay.dlna.scpd

import com.phairplay.dlna.UpnpService

/**
 * Scpd — lookup from a [UpnpService] to its description document.
 *
 * WHY: The HTTP router serves `/scpd/{Service}.xml`; this keeps the mapping in one place.
 *
 * HOW: `Scpd.forService(UpnpService.AV_TRANSPORT)` → the pre-built SCPD XML for that service.
 */
object Scpd {
    fun forService(service: UpnpService): String = when (service) {
        UpnpService.AV_TRANSPORT -> AvTransportScpd.XML
        UpnpService.RENDERING_CONTROL -> RenderingControlScpd.XML
        UpnpService.CONNECTION_MANAGER -> ConnectionManagerScpd.XML
    }
}
