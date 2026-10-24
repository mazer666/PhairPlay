package com.phairplay.dlna

/**
 * SoapResponses — builds the SOAP envelopes the control plane answers with.
 *
 * WHY: every UPnP action answer is either a successful response (HTTP 200) or a
 * SOAP fault (HTTP 500). Both shapes are defined by the UPnP control specification,
 * so they are built in exactly one place — a control point that receives a
 * malformed envelope rejects the whole device.
 *
 * The fault detail never contains an exception message, a file path or a version
 * string: the control plane is reachable from the local network and error details
 * are a classic information leak.
 *
 * Example:
 *   SoapResponses.ok("Play", "AVTransport", emptyMap())
 *   SoapResponses.fault(402, "Invalid CurrentURI")
 */
internal object SoapResponses {

    /** UPnP error code: the action is not implemented by the service. */
    const val ERROR_INVALID_ACTION = 401

    /** UPnP error code: an argument is missing, malformed or out of range. */
    const val ERROR_INVALID_ARGS = 402

    /** UPnP error code: the requested transition is not available in the current state. */
    const val ERROR_TRANSITION_NOT_AVAILABLE = 701

    /** Builds a successful action response. */
    fun ok(action: String, service: String, values: Map<String, String>): SoapResult {
        val body = values.entries.joinToString("") { (key, value) ->
            "<$key>${escape(value)}</$key>"
        }
        return SoapResult(
            statusCode = 200,
            statusMessage = "OK",
            body = envelope(
                """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">$body</u:${action}Response>"""
            )
        )
    }

    /** Builds a SOAP fault answer (HTTP 500, as the UPnP control specification demands). */
    fun fault(errorCode: Int, message: String): SoapResult =
        SoapResult(
            statusCode = 500,
            statusMessage = "Internal Server Error",
            body = envelope(
                """
                <s:Fault>
                  <faultcode>s:Client</faultcode>
                  <faultstring>UPnPError</faultstring>
                  <detail>
                    <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                      <errorCode>$errorCode</errorCode>
                      <errorDescription>${escape(message)}</errorDescription>
                    </UPnPError>
                  </detail>
                </s:Fault>
                """.trimIndent()
            )
        )

    /** Wraps [content] into a SOAP 1.1 envelope. */
    fun envelope(content: String): String =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>$content</s:Body></s:Envelope>"""

    /** Escapes the five XML predefined entities. */
    fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
