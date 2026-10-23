package com.phairplay.dlna

/**
 * Builds the static UPnP device and service descriptors for PhairPlay's DLNA MediaRenderer.
 */
object DlnaDescriptionBuilder {

    fun deviceDescription(
        friendlyName: String,
        udn: String,
        presentationUrl: String
    ): String {
        val name = friendlyName.xmlEscaped()
        val escapedUdn = udn.xmlEscaped()
        val escapedUrl = presentationUrl.xmlEscaped()
        return """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <specVersion>
                <major>1</major>
                <minor>0</minor>
              </specVersion>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>$name</friendlyName>
                <manufacturer>PhairPlay</manufacturer>
                <manufacturerURL>https://github.com/mazer666/PhairPlay</manufacturerURL>
                <modelDescription>PhairPlay DLNA MediaRenderer</modelDescription>
                <modelName>PhairPlay</modelName>
                <modelNumber>1</modelNumber>
                <UDN>$escapedUdn</UDN>
                <presentationURL>$escapedUrl</presentationURL>
                <serviceList>
                  ${service("ConnectionManager", "1", "/upnp/control/ConnectionManager", "/upnp/event/ConnectionManager", "/ConnectionManager.xml")}
                  ${service("AVTransport", "1", "/upnp/control/AVTransport", "/upnp/event/AVTransport", "/AVTransport.xml")}
                  ${service("RenderingControl", "1", "/upnp/control/RenderingControl", "/upnp/event/RenderingControl", "/RenderingControl.xml")}
                </serviceList>
              </device>
            </root>
        """.trimIndent()
    }

    fun serviceDescription(serviceName: String, actions: List<String>): String {
        val actionXml = actions.joinToString(separator = "\n") { action ->
            "      <action><name>${action.xmlEscaped()}</name></action>"
        }
        return """
            <?xml version="1.0"?>
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <specVersion>
                <major>1</major>
                <minor>0</minor>
              </specVersion>
              <actionList>
            $actionXml
              </actionList>
              <serviceStateTable>
                <stateVariable sendEvents="no">
                  <name>PhairPlay${serviceName.xmlEscaped()}State</name>
                  <dataType>string</dataType>
                </stateVariable>
              </serviceStateTable>
            </scpd>
        """.trimIndent()
    }

    private fun service(
        name: String,
        version: String,
        controlUrl: String,
        eventUrl: String,
        scpdUrl: String
    ): String =
        """
        <service>
          <serviceType>urn:schemas-upnp-org:service:$name:$version</serviceType>
          <serviceId>urn:upnp-org:serviceId:$name</serviceId>
          <SCPDURL>$scpdUrl</SCPDURL>
          <controlURL>$controlUrl</controlURL>
          <eventSubURL>$eventUrl</eventSubURL>
        </service>
        """.trimIndent()

    private fun String.xmlEscaped(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
