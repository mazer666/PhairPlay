package com.phairplay.dlna

/**
 * DeviceDescription — the UPnP root device description XML served at `/description.xml`.
 *
 * WHY: This is the document a control point fetches from the SSDP LOCATION to decide whether we are a
 * usable MediaRenderer. Windows additionally requires the DLNA `X_DLNADOC` DMR marker and an icon.
 *
 * HOW: `DeviceDescription(friendlyName, udn, versionName).xml("http://ip:port")` — URLs are absolute so
 * every control point resolves them identically.
 */
class DeviceDescription(
    private val friendlyName: String,
    private val udn: String,
    private val modelNumber: String
) {
    /**
     * Builds the description XML with every URL made absolute against [baseUrl].
     *
     * [baseUrl] must be constructed locally from this device's own known address and listening port
     * (e.g. `"http://" + localAddress + ":" + port`) — never derived from a request header such as
     * `Host` — otherwise a hostile client could redirect a control point's SCPD/control/eventSub/icon
     * fetches to a host of its own choosing.
     */
    fun xml(baseUrl: String): String {
        val services = UpnpService.entries.joinToString("") { service ->
            "<service>" +
                "<serviceType>${service.serviceType}</serviceType>" +
                "<serviceId>${service.serviceId}</serviceId>" +
                "<SCPDURL>$baseUrl${service.scpdPath}</SCPDURL>" +
                "<controlURL>$baseUrl${service.controlPath}</controlURL>" +
                "<eventSubURL>$baseUrl${service.eventPath}</eventSubURL>" +
                "</service>"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">" +
            "<specVersion><major>1</major><minor>0</minor></specVersion>" +
            "<device>" +
            "<deviceType>${DlnaConstants.DEVICE_TYPE}</deviceType>" +
            "<friendlyName>${SecureXml.escape(friendlyName)}</friendlyName>" +
            "<manufacturer>$MANUFACTURER</manufacturer>" +
            "<manufacturerURL>$PROJECT_URL</manufacturerURL>" +
            "<modelDescription>$MODEL_DESCRIPTION</modelDescription>" +
            "<modelName>$MODEL_NAME</modelName>" +
            "<modelNumber>${SecureXml.escape(modelNumber)}</modelNumber>" +
            "<modelURL>$PROJECT_URL</modelURL>" +
            "<UDN>${SecureXml.escape(udn)}</UDN>" +
            "<dlna:X_DLNADOC>$DLNA_DOC</dlna:X_DLNADOC>" +
            "<iconList><icon><mimetype>image/png</mimetype>" +
            "<width>${DlnaConstants.ICON_SIZE_PX}</width><height>${DlnaConstants.ICON_SIZE_PX}</height>" +
            "<depth>24</depth><url>$baseUrl${DlnaConstants.ICON_PATH}</url></icon></iconList>" +
            "<serviceList>$services</serviceList>" +
            "</device></root>"
    }

    companion object {
        const val MANUFACTURER = "PhairPlay"
        const val MODEL_NAME = "PhairPlay"
        const val MODEL_DESCRIPTION = "PhairPlay AirPlay/DLNA receiver for Android TV"
        const val PROJECT_URL = "https://github.com/mazer666/PhairPlay"
        const val DLNA_DOC = "DMR-1.50"
    }
}
