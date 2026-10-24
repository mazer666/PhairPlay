package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DlnaSoapDispatcherSecurityTest — proves that the control plane survives hostile
 * SOAP bodies.
 *
 * WHY: the control plane is reachable from the local network. A control point that
 * sends a DOCTYPE, an external entity or broken XML must not be able to read a file
 * from the TV, exhaust its memory or learn anything about its internals from the
 * error answer.
 *
 * WHAT WE TEST:
 * - an XXE payload is refused and the file content never appears in the answer,
 * - a billion laughs payload is refused,
 * - broken XML is refused without leaking parser details,
 * - an unknown control URL answers with 404, an unknown action with a SOAP fault.
 */
class DlnaSoapDispatcherSecurityTest {

    @Test
    fun `DOCTYPE is rejected`() {
        val result = DlnaSoapDispatcher().dispatch(
            controlPath = "/upnp/control/ConnectionManager",
            soapAction = "\"urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo\"",
            body = """<!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]><foo>&xxe;</foo>"""
        )

        assertEquals(400, result.statusCode)
    }

    @Test
    fun `an XXE payload inside a SOAP envelope is rejected`() {
        val body = """
            <?xml version="1.0"?>
            <!DOCTYPE s:Envelope [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body><u:GetProtocolInfo xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">&xxe;</u:GetProtocolInfo></s:Body>
            </s:Envelope>
        """.trimIndent()

        val result = DlnaSoapDispatcher().dispatch(
            controlPath = "/upnp/control/ConnectionManager",
            soapAction = "\"urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo\"",
            body = body
        )

        assertEquals(400, result.statusCode)
        assertFalse("the file content must not leak", result.body.contains("root:"))
    }

    @Test
    fun `a billion laughs payload is rejected`() {
        val body = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
            ]>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body><u:GetProtocolInfo xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">&lol3;</u:GetProtocolInfo></s:Body>
            </s:Envelope>
        """.trimIndent()

        val result = DlnaSoapDispatcher().dispatch(
            controlPath = "/upnp/control/ConnectionManager",
            soapAction = "\"urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo\"",
            body = body
        )

        assertEquals(400, result.statusCode)
    }

    @Test
    fun `broken XML is rejected without leaking parser details`() {
        val result = DlnaSoapDispatcher().dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            body = "<s:Envelope><s:Body><u:Play"
        )

        assertEquals(400, result.statusCode)
        assertFalse(result.body.contains("SAXParseException"))
        assertFalse(result.body.contains("com.sun.org"))
    }

    @Test
    fun `an unknown control URL answers with 404`() {
        val result = dispatch(
            "/upnp/control/Unknown",
            "urn:schemas-upnp-org:service:AVTransport:1#Play",
            envelope("AVTransport", "Play")
        )

        assertEquals(404, result.statusCode)
    }

    @Test
    fun `an unknown action answers with a SOAP fault`() {
        val result = dispatch(
            "/upnp/control/AVTransport",
            "urn:schemas-upnp-org:service:AVTransport:1#Explode",
            envelope("AVTransport", "Explode")
        )

        assertEquals(500, result.statusCode)
        assertTrue(result.body.contains("<errorCode>401</errorCode>"))
    }

    private fun dispatch(path: String, action: String, body: String): SoapResult =
        DlnaSoapDispatcher().dispatch(
            controlPath = path,
            soapAction = "\"$action\"",
            body = body
        )

    private fun envelope(service: String, action: String, children: String = ""): String =
        """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
          <s:Body>
            <u:$action xmlns:u="urn:schemas-upnp-org:service:$service:1">
              $children
            </u:$action>
          </s:Body>
        </s:Envelope>
        """.trimIndent()
}
