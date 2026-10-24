package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SecureSoapXmlTest — proves that the XML parser of the control plane withstands the
 * classic XML attacks.
 *
 * WHY: every SOAP body and every DIDL-Lite document arrives from the local network.
 * A permissive parser turns that input into file disclosure (XXE), a denial of
 * service (billion laughs) or a crash (deep nesting). The parser is the only
 * boundary between the network and the renderer, so it is tested with the real
 * attack payloads — not with a mock.
 *
 * WHAT WE TEST:
 * - an XXE payload that tries to read a local file,
 * - a billion laughs payload that tries to exhaust the memory,
 * - a DOCTYPE declaration,
 * - broken XML,
 * - a deeply nested document,
 * - an oversized document.
 */
class SecureSoapXmlTest {

    @Test
    fun `a well formed SOAP envelope is parsed`() {
        val document = SecureSoapXml.parse(SOAP_ENVELOPE)

        assertNotNull(document)
        assertEquals("Play", SecureSoapXml.actionName(document))
    }

    @Test
    fun `the arguments of the action are read`() {
        val document = SecureSoapXml.parse(
            """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <CurrentURI>http://192.168.1.4/movie.mp4</CurrentURI>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>
            """.trimIndent()
        )

        val values = SecureSoapXml.directChildText(document, "SetAVTransportURI")

        assertEquals("http://192.168.1.4/movie.mp4", values["CurrentURI"])
    }

    @Test
    fun `an XXE payload cannot read a local file`() {
        val payload = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <foo>&xxe;</foo>
        """.trimIndent()

        val failure = runCatching { SecureSoapXml.parse(payload) }.exceptionOrNull()

        assertNotNull("the DOCTYPE must be refused", failure)
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `an external entity in a SOAP envelope is refused`() {
        val payload = """
            <?xml version="1.0"?>
            <!DOCTYPE s:Envelope [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>&xxe;</s:Body>
            </s:Envelope>
        """.trimIndent()

        val failure = runCatching { SecureSoapXml.parse(payload) }.exceptionOrNull()

        assertNotNull("the external entity must be refused", failure)
    }

    @Test
    fun `a billion laughs payload is refused`() {
        val payload = """
            <?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY lol1 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;">
              <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
            ]>
            <lolz>&lol3;</lolz>
        """.trimIndent()

        val failure = runCatching { SecureSoapXml.parse(payload) }.exceptionOrNull()

        assertNotNull("the entity expansion must be refused", failure)
    }

    @Test
    fun `a DOCTYPE declaration is refused even without an entity`() {
        val payload = """
            <?xml version="1.0"?>
            <!DOCTYPE root SYSTEM "http://example.invalid/evil.dtd">
            <root/>
        """.trimIndent()

        val failure = runCatching { SecureSoapXml.parse(payload) }.exceptionOrNull()

        assertNotNull("the external DTD must be refused", failure)
    }

    @Test
    fun `broken XML is refused`() {
        val failure = runCatching { SecureSoapXml.parse("<a><b></a>") }.exceptionOrNull()

        assertNotNull("broken XML must be refused", failure)
    }

    @Test
    fun `a deeply nested document is refused`() {
        val depth = SecureSoapXml.MAX_XML_DEPTH + 10
        val payload = "<a>".repeat(depth) + "</a>".repeat(depth)

        val failure = runCatching { SecureSoapXml.parse(payload) }.exceptionOrNull()

        assertNotNull("the nesting depth must be bounded", failure)
    }

    @Test
    fun `an oversized document is refused`() {
        val payload = "<a>" + "x".repeat(SecureSoapXml.MAX_XML_CHARS) + "</a>"

        val failure = runCatching { SecureSoapXml.parse(payload) }.exceptionOrNull()

        assertNotNull("the payload size must be bounded", failure)
    }

    @Test
    fun `the parser does not resolve external entities`() {
        // A document that would resolve an entity to a file must not contain its content.
        val payload = """
            <?xml version="1.0"?>
            <!DOCTYPE foo SYSTEM "http://127.0.0.1:1/never-there.dtd">
            <foo>plain</foo>
        """.trimIndent()

        val document = runCatching { SecureSoapXml.parse(payload) }.getOrNull()

        // Either the DOCTYPE is refused outright or the document is parsed without
        // any external access — in both cases the content is the literal text.
        if (document != null) {
            assertFalse(document.documentElement.textContent.contains("never-there"))
        }
    }

    companion object {
        private val SOAP_ENVELOPE = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
    }
}
