package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SecureXmlTest — the parser must reject the XXE/DoS shapes it claims to, and `escape` must never
 * produce a document its own parser (or a control point's) would refuse to read back.
 *
 * WHY: Every SOAP/GENA document in this app goes through these two functions; a gap here is a gap in
 * every caller at once.
 */
class SecureXmlTest {

    /** Builds nested elements so the root is at depth 1 and the innermost leaf is at depth [depth]. */
    private fun nested(depth: Int): String {
        var xml = "<leaf/>"
        repeat(depth - 1) { xml = "<a>$xml</a>" }
        return xml
    }

    @Test
    fun `well-formed xml parses`() {
        assertNotNull(SecureXml.parse("<root><child>text</child></root>"))
    }

    @Test
    fun `blank input is null`() {
        assertNull(SecureXml.parse(""))
        assertNull(SecureXml.parse("   "))
    }

    @Test
    fun `a DOCTYPE is rejected`() {
        assertNull(SecureXml.parse("<!DOCTYPE root><root/>"))
    }

    @Test
    fun `nesting depth 33 is rejected but depth 5 is fine`() {
        assertNull(SecureXml.parse(nested(33)))
        assertNotNull(SecureXml.parse(nested(5)))
    }

    @Test
    fun `escape handles the five predefined entities`() {
        assertEquals("&amp;", SecureXml.escape("&"))
        assertEquals("&lt;", SecureXml.escape("<"))
        assertEquals("&gt;", SecureXml.escape(">"))
        assertEquals("&quot;", SecureXml.escape("\""))
        assertEquals("&apos;", SecureXml.escape("'"))
    }

    @Test
    fun `escape numeric-escapes newline, carriage return and tab so attribute whitespace survives`() {
        assertEquals("&#10;", SecureXml.escape("\n"))
        assertEquals("&#13;", SecureXml.escape("\r"))
        assertEquals("&#9;", SecureXml.escape("\t"))
        assertEquals("a&#10;b", SecureXml.escape("a\nb"))
    }

    @Test
    fun `escape drops characters illegal in XML 1_0`() {
        assertEquals("ab", SecureXml.escape("a\u0000b"))          // NUL — a C0 control
        assertEquals("ab", SecureXml.escape("a\uD800b"))           // unpaired (lone) surrogate
    }

    @Test
    fun `childElements, firstText and localNameOf ignore namespace prefixes`() {
        val document = SecureXml.parse(
            "<root xmlns:x=\"urn:example:x\"><x:Foo>bar</x:Foo></root>"
        )!!
        val root = document.documentElement
        val children = SecureXml.childElements(root)
        assertEquals(1, children.size)
        assertEquals("Foo", SecureXml.localNameOf(children[0]))
        assertEquals("bar", SecureXml.firstText(root, "Foo"))
        assertNull(SecureXml.firstText(root, "Missing"))
    }
}
