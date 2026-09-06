package com.phairplay.dlna

/**
 * UpnpService — the three UPnP AV services a MediaRenderer exposes, with their standard identifiers.
 *
 * WHY: Control points (Windows especially) match serviceId/serviceType strings exactly; deriving every
 * id, type and URL path from one enum guarantees the description, router and SCPD agree.
 *
 * HOW: `UpnpService.AV_TRANSPORT.controlPath` → `/control/AVTransport`; `fromControlPath(path)` for routing.
 */
enum class UpnpService(val pathName: String) {
    AV_TRANSPORT("AVTransport"),
    RENDERING_CONTROL("RenderingControl"),
    CONNECTION_MANAGER("ConnectionManager");

    val serviceId: String get() = "urn:upnp-org:serviceId:$pathName"
    val serviceType: String get() = "urn:schemas-upnp-org:service:$pathName:1"
    val scpdPath: String get() = "/scpd/$pathName.xml"
    val controlPath: String get() = "/control/$pathName"
    val eventPath: String get() = "/event/$pathName"

    companion object {
        fun fromControlPath(path: String): UpnpService? = entries.firstOrNull { it.controlPath == path }
        fun fromEventPath(path: String): UpnpService? = entries.firstOrNull { it.eventPath == path }
        fun fromScpdPath(path: String): UpnpService? = entries.firstOrNull { it.scpdPath == path }
        fun fromServiceType(type: String): UpnpService? = entries.firstOrNull { it.serviceType == type }
    }
}
