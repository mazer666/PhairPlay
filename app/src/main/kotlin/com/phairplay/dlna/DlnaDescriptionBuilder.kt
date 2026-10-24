package com.phairplay.dlna

/**
 * DlnaDescriptionBuilder — builds the static UPnP device and service descriptors for
 * PhairPlay's DLNA MediaRenderer.
 *
 * WHY: the device description is the first document a control point fetches. It names
 * the device (`friendlyName` — the label in the renderer picker), the UDN and the
 * services. The service description (SCPD) tells the control point which actions a
 * service offers and which arguments they take.
 *
 * Example:
 *   DlnaDescriptionBuilder.deviceDescription("Living Room TV", "uuid:…", "http://…/description.xml")
 */
object DlnaDescriptionBuilder {

    /** The page of the project — the manufacturer of the device. */
    const val MANUFACTURER_URL = "https://github.com/mazer666/PhairPlay"

    /** The page that describes the model — a Samsung TV reads this field. */
    const val MODEL_URL = "https://github.com/mazer666/PhairPlay"

    /** The serial number of a device whose UDN carries no usable characters. */
    private const val DEFAULT_SERIAL_NUMBER = "PhairPlay"

    /**
     * Builds the device description document.
     *
     * WHY the extra fields: a Samsung TV does not read the device description the
     * same way a UPnP control point does. It expects a complete set of device
     * fields — among them `serialNumber` and `modelURL` — and skips a device whose
     * description is incomplete. `friendlyName` is the name the TV shows in its
     * device list, so it has to be the name of the television.
     *
     * @param friendlyName the name shown in the control point's device list.
     * @param udn the unique device identifier, e.g. `uuid:…`.
     * @param presentationUrl the URL of the presentation page.
     */
    fun deviceDescription(
        friendlyName: String,
        udn: String,
        presentationUrl: String
    ): String {
        val name = friendlyName.xmlEscaped()
        val escapedUdn = udn.xmlEscaped()
        val escapedUrl = presentationUrl.xmlEscaped()
        val serial = serialNumberFor(udn).xmlEscaped()
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
                <manufacturerURL>$MANUFACTURER_URL</manufacturerURL>
                <modelDescription>PhairPlay DLNA MediaRenderer</modelDescription>
                <modelName>PhairPlay</modelName>
                <modelNumber>1</modelNumber>
                <modelURL>$MODEL_URL</modelURL>
                <serialNumber>$serial</serialNumber>
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

    /**
     * Derives the serial number of the device from its UDN.
     *
     * WHY: the device description wants a serial number that stays the same over
     * the lifetime of the device. The UDN is already a stable and unique id, so
     * its uuid part is used — with every character that does not belong in a
     * serial number replaced by a dash.
     *
     * @param udn the unique device identifier, e.g. `uuid:…`.
     * @return the serial number, for example `550e8400-e29b-41d4-a716-446655440000`.
     */
    internal fun serialNumberFor(udn: String): String {
        val uuidPart = udn.trim()
            .removePrefix("uuid:")
            .removePrefix("uuid-")
        val serial = buildString {
            uuidPart.forEach { character ->
                if (character in 'a'..'z' ||
                    character in 'A'..'Z' ||
                    character in '0'..'9' ||
                    character == '-'
                ) {
                    append(character)
                } else {
                    append('-')
                }
            }
        }.trim('-')
        return serial.ifBlank { DEFAULT_SERIAL_NUMBER }
    }

    /**
     * Builds the service description (SCPD) of [serviceName].
     *
     * @param serviceName `ConnectionManager`, `AVTransport` or `RenderingControl`.
     */
    fun serviceDescription(serviceName: String): String {
        val actions = DlnaServiceCatalog.actionsFor(serviceName)
        val actionXml = actions.joinToString(separator = "\n") { spec ->
            val arguments = spec.arguments.joinToString(separator = "\n") { argument ->
                """
                    <argument>
                      <name>${argument.name.xmlEscaped()}</name>
                      <direction>${argument.direction}</direction>
                      <relatedStateVariable>${argument.stateVariable.xmlEscaped()}</relatedStateVariable>
                    </argument>
                """.trimIndent()
            }
            buildString {
                append("    <action>\n")
                append("      <name>${spec.name.xmlEscaped()}</name>\n")
                if (spec.arguments.isNotEmpty()) {
                    append("      <argumentList>\n")
                    append(arguments)
                    append("\n      </argumentList>\n")
                }
                append("    </action>")
            }
        }
        val stateVariables = DlnaServiceCatalog.stateVariablesFor(serviceName).joinToString(separator = "\n") { name ->
            """
                <stateVariable sendEvents="no">
                  <name>${name.xmlEscaped()}</name>
                  <dataType>${DlnaServiceCatalog.typeOf(name)}</dataType>
                </stateVariable>
            """.trimIndent()
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
            $stateVariables
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
