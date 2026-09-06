package com.phairplay.dlna

/**
 * SoapXml — parses a UPnP SOAP request envelope and builds response / fault envelopes.
 *
 * WHY: UPnP control is SOAP 1.1 with a fixed shape; hand-building it (no library) keeps the wire
 * format exact and testable, and lets us reject anything outside that shape.
 *
 * HOW: `SoapXml.parseAction(body)` → [Action]; `SoapXml.response(type, "Play", args)`; `SoapXml.fault(701, …)`.
 */
object SoapXml {

    /** One parsed SOAP action: its unqualified name and its argument elements as name/text pairs. */
    data class Action(val name: String, val args: Map<String, String>)

    private const val ENVELOPE_NS = "http://schemas.xmlsoap.org/soap/envelope/"
    private const val ENCODING_STYLE = "http://schemas.xmlsoap.org/soap/encoding/"
    private const val CONTROL_NS = "urn:schemas-upnp-org:control-1-0"

    /** First element inside `s:Body` is the action; its children are the arguments. Null if unreadable. */
    fun parseAction(body: String): Action? {
        val document = SecureXml.parse(body) ?: return null
        val envelope = document.documentElement ?: return null
        // Anything that isn't actually a SOAP envelope is not our concern to interpret as one.
        if (SecureXml.localNameOf(envelope) != "Envelope" || envelope.namespaceURI != ENVELOPE_NS) return null
        val soapBody = SecureXml.childElements(envelope).firstOrNull { SecureXml.localNameOf(it) == "Body" }
            ?: return null
        val actionElement = SecureXml.childElements(soapBody).firstOrNull() ?: return null
        val args = LinkedHashMap<String, String>()
        for (arg in SecureXml.childElements(actionElement)) {
            args[SecureXml.localNameOf(arg)] = arg.textContent ?: ""
        }
        return Action(SecureXml.localNameOf(actionElement), args)
    }

    /** `"urn:…:service:AVTransport:1#Play"` → (serviceType, "Play"); null when the header is unusable. */
    fun actionFromHeader(soapAction: String?): Pair<String, String>? {
        val raw = soapAction?.trim()?.trim('"') ?: return null
        val hash = raw.indexOf('#')
        if (hash <= 0 || hash == raw.length - 1) return null
        return raw.substring(0, hash) to raw.substring(hash + 1)
    }

    fun response(serviceType: String, action: String, outArgs: Map<String, String>): String {
        val args = outArgs.entries.joinToString("") { (name, value) -> "<$name>${SecureXml.escape(value)}</$name>" }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"$ENVELOPE_NS\" s:encodingStyle=\"$ENCODING_STYLE\"><s:Body>" +
            "<u:${action}Response xmlns:u=\"$serviceType\">$args</u:${action}Response>" +
            "</s:Body></s:Envelope>"
    }

    fun fault(code: Int, description: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"$ENVELOPE_NS\" s:encodingStyle=\"$ENCODING_STYLE\"><s:Body>" +
            "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"$CONTROL_NS\"><errorCode>$code</errorCode>" +
            "<errorDescription>${SecureXml.escape(description)}</errorDescription></UPnPError>" +
            "</detail></s:Fault></s:Body></s:Envelope>"
}
