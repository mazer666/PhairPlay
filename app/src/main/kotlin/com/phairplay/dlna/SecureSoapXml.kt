package com.phairplay.dlna

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SecureSoapXml — the only XML parser the DLNA control plane is allowed to use.
 *
 * WHY: Every SOAP body and every DIDL-Lite metadata document arrives from the local
 * network and is therefore hostile input. A permissive parser turns that input into
 * file disclosure (XXE), denial of service (billion laughs) or a crash (deeply nested
 * XML). The parser is configured so that:
 *
 * - external entities and external DTDs are never resolved,
 * - a DOCTYPE declaration is rejected outright (before the parser sees it),
 * - entity expansion is switched off and an [org.xml.sax.EntityResolver] is installed
 *   that returns an empty document for everything,
 * - the payload size and the nesting depth are bounded,
 * - a parser that cannot be hardened fails the request instead of parsing anyway.
 *
 * Example:
 *   val doc = SecureSoapXml.parse(body)   // throws on DOCTYPE, XXE or billion laughs
 */
internal object SecureSoapXml {

    /** Largest XML payload accepted (characters, not bytes — the ASCII limit is lower). */
    const val MAX_XML_CHARS = 64 * 1024

    /** Deepest element nesting accepted. */
    const val MAX_XML_DEPTH = 32

    /**
     * Parses [body] into a DOM document.
     *
     * @throws IllegalArgumentException when the payload is too large, too deep, carries a
     *   DOCTYPE or is not well-formed XML.
     */
    fun parse(body: String): Document {
        require(body.length <= MAX_XML_CHARS) { "XML payload too large" }
        require(!containsDoctype(body)) { "DOCTYPE declarations are not allowed" }
        val factory = hardenedFactory()
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ ->
            // Defence in depth: even if a parser ignored the feature flags above, every
            // external entity would resolve to this empty document instead of a file.
            org.xml.sax.InputSource(ByteArrayInputStream(ByteArray(0)))
        }
        val document = builder.parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
        require(depth(document.documentElement) <= MAX_XML_DEPTH) { "XML nesting too deep" }
        return document
    }

    /**
     * Returns the name of the first element inside the SOAP `Body`.
     *
     * @return the local name, or null when the body is not a SOAP envelope.
     */
    fun actionName(document: Document): String? =
        soapBodyElement(document)
            ?.let { body ->
                (0 until body.childNodes.length)
                    .asSequence()
                    .map { body.childNodes.item(it) }
                    .firstOrNull { it.nodeType == Node.ELEMENT_NODE }
                    ?.localName
            }

    /**
     * Returns the element children of the action element as a name → text map.
     *
     * WHY: the map is built from the *direct* children of the action element only.
     * A control point may stuff arbitrary XML into a child element; reading it as a
     * flat "text" value would let it shadow a real argument.
     */
    fun directChildText(document: Document, action: String): Map<String, String> {
        val actionNode = actionElement(document, action) ?: return emptyMap()
        return (0 until actionNode.childNodes.length)
            .asSequence()
            .map { actionNode.childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE }
            .associate { element ->
                (element as Element).localName to (element.textContent ?: "")
            }
    }

    /**
     * Returns the action element of a SOAP envelope.
     *
     * @param action the expected local name, e.g. `Play`.
     * @return the element, or null when the envelope does not contain it.
     */
    fun actionElement(document: Document, action: String): Element? {
        val body = soapBodyElement(document) ?: return null
        return (0 until body.childNodes.length)
            .asSequence()
            .map { body.childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE }
            .map { it as Element }
            .firstOrNull { it.localName == action }
    }

    /**
     * Returns the SOAP `Body` element.
     *
     * WHY: both the SOAP 1.1 envelope namespace and the (unused but legal) SOAP 1.2
     * namespace are accepted, because some control points send 1.2 envelopes.
     */
    private fun soapBodyElement(document: Document): Element? {
        val bodies = document.getElementsByTagNameNS("*", "Body")
        for (index in 0 until bodies.length) {
            val body = bodies.item(index)
            if (body is Element) return body
        }
        return null
    }

    /**
     * Builds a parser factory that is locked down against XXE and entity expansion.
     *
     * WHY fail closed: if the platform parser does not support one of the hardening
     * features, silently continuing would leave the renderer unprotected. Every
     * mandatory feature is therefore applied without swallowing the exception.
     */
    private fun hardenedFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            // accessExternalDTD / accessExternalSchema are mandatory on the JDK parser but
            // optional on some Android parsers; a missing one is tolerable because the
            // DOCTYPE ban and the entity resolver already block every external access.
            // The property names are spelled out because Android's XMLConstants does
            // not carry the constants.
            runCatching {
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            }
            runCatching {
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }
        }

    /**
     * Detects a DOCTYPE or ENTITY declaration in the raw payload.
     *
     * WHY scan the raw text: the parser feature `disallow-doctype-decl` is not
     * implemented by every platform parser, and the rejection has to happen before
     * the parser starts resolving anything.
     */
    private fun containsDoctype(body: String): Boolean {
        val upper = body.uppercase()
        return upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")
    }

    /** Returns the depth of the element tree below [node] (a leaf has depth 1). */
    private fun depth(node: Node?): Int {
        if (node == null) return 0
        val children = node.childNodes
        var maxChildDepth = 0
        for (index in 0 until children.length) {
            maxChildDepth = maxOf(maxChildDepth, depth(children.item(index)))
        }
        return 1 + maxChildDepth
    }
}
