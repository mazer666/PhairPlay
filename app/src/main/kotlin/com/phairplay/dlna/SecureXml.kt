package com.phairplay.dlna

import com.phairplay.util.Logger
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SecureXml — DOM parsing hardened against external entities, plus the small helpers every XML
 * consumer in the DLNA stack needs (namespace-agnostic child lookup, escaping).
 *
 * WHY: SOAP bodies and DIDL metadata come straight from the network. Disabling DOCTYPE/entity
 * processing closes the XXE class of bugs (RULE 4). On Android the platform parser ignores unknown
 * features, so each is set best-effort; the platform parser never resolves external entities anyway.
 *
 * HOW: `SecureXml.parse(xml)?.documentElement`; `SecureXml.escape(text)` for anything placed in XML.
 */
object SecureXml {

    /** Elements nested deeper than this are rejected (RULE 4: cap on attacker-controlled recursion). */
    private const val MAX_NESTING_DEPTH = 32

    /** XML 1.0 `Char` production, minus the surrogate-pair range which is handled per-UTF-16-char. */
    private const val XML_CHAR_LOW_START = 0x20
    private const val XML_CHAR_LOW_END = 0xD7FF
    private const val XML_CHAR_HIGH_START = 0xE000
    private const val XML_CHAR_HIGH_END = 0xFFFD

    private val HARDENING_FEATURES = listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false
    )

    /** Parses [xml] or returns null (logged) when it is blank, malformed, contains a DOCTYPE, or nests
     * deeper than [MAX_NESTING_DEPTH]. */
    fun parse(xml: String): Document? {
        if (xml.isBlank()) return null
        // Rejecting on a raw substring also catches a DOCTYPE hidden inside a CDATA section — the
        // parser would still see and act on it, so we deliberately check before any parsing happens.
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) {
            Logger.w("SecureXml: DOCTYPE rejected")
            return null
        }
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                for ((feature, value) in HARDENING_FEATURES) {
                    runCatching { setFeature(feature, value) }   // unsupported on Android's parser
                }
            }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            if (exceedsMaxNestingDepth(document)) {
                Logger.w("SecureXml: nesting depth exceeds $MAX_NESTING_DEPTH")
                null
            } else {
                document
            }
        } catch (e: Exception) {
            Logger.w("SecureXml: parse failed: ${e.message}")
            null
        }
    }

    fun childElements(parent: Node): List<Element> {
        val children = parent.childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }
    }

    /** Text of the first child element whose local name matches, ignoring namespace prefixes. */
    fun firstText(parent: Element, localName: String): String? =
        childElements(parent).firstOrNull { localNameOf(it) == localName }?.textContent

    fun localNameOf(element: Element): String = element.localName ?: element.nodeName.substringAfter(':')

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            '\n' -> append("&#10;")
            '\r' -> append("&#13;")
            '\t' -> append("&#9;")
            // Any other char is only safe to emit as-is if XML 1.0 allows it unescaped; anything
            // else (NUL, other C0 controls, unpaired surrogates) is silently dropped rather than
            // emitted, because writing it would make the whole enclosing document unparseable for
            // the control point (a single bad char kills the entire LastChange/SOAP exchange).
            else -> if (isXmlChar(c)) append(c)
        }
    }

    /** Iterative (no recursion) so a maliciously deep document can't blow the call stack here too. */
    private fun exceedsMaxNestingDepth(document: Document): Boolean {
        val root = document.documentElement ?: return false
        val stack = ArrayDeque<Pair<Element, Int>>()
        stack.addLast(root to 1)
        while (stack.isNotEmpty()) {
            val (element, depth) = stack.removeLast()
            if (depth > MAX_NESTING_DEPTH) return true
            for (child in childElements(element)) stack.addLast(child to depth + 1)
        }
        return false
    }

    private fun isXmlChar(c: Char): Boolean {
        val code = c.code
        return code in XML_CHAR_LOW_START..XML_CHAR_LOW_END || code in XML_CHAR_HIGH_START..XML_CHAR_HIGH_END
    }
}
