package com.phairplay.dlna

/**
 * SoapResult — the HTTP status and SOAP body a control action produced.
 *
 * WHY: the HTTP layer and the action logic are separated, so the dispatcher can be
 * tested without a socket and the server can be tested without a control point.
 *
 * Example:
 *   val result = DlnaSoapDispatcher().dispatch("/upnp/control/AVTransport", action, body)
 *   if (result.statusCode != 200) { /* SOAP fault */ }
 */
data class SoapResult(
    val statusCode: Int,
    val statusMessage: String,
    val body: String
)
