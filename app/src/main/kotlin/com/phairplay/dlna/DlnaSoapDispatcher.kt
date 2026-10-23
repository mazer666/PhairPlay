package com.phairplay.dlna

import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal SOAP dispatcher for DLNA D2 control-plane actions.
 */
class DlnaSoapDispatcher(
    private val playbackController: DlnaPlaybackController = DlnaPlaybackController.NOOP,
    private val onLastChange: (String, String) -> Unit = { _, _ -> }
) {
    private val state = DlnaTransportState()

    fun dispatch(controlPath: String, soapAction: String?, body: String): SoapResult {
        val action = soapAction?.substringAfter('#')?.trim('"')
            ?: SecureSoapXml.actionName(body)
            ?: return SoapResult(400, "Bad Request", fault("Client", "Missing SOAP action"))

        return try {
            val document = SecureSoapXml.parse(body)
            val values = SecureSoapXml.directChildText(document, action)
            val response = when (controlPath) {
                "/upnp/control/ConnectionManager" -> connectionManager(action)
                "/upnp/control/AVTransport" -> avTransport(action, values)
                "/upnp/control/RenderingControl" -> renderingControl(action, values)
                else -> SoapResult(404, "Not Found", fault("Client", "Unknown control URL"))
            }
            response
        } catch (e: Exception) {
            SoapResult(400, "Bad Request", fault("Client", e.message ?: "Malformed SOAP body"))
        }
    }

    private fun connectionManager(action: String): SoapResult =
        when (action) {
            "GetProtocolInfo" -> ok(
                action,
                "ConnectionManager",
                mapOf(
                    "Source" to "",
                    "Sink" to SINK_PROTOCOL_INFO
                )
            )
            "GetCurrentConnectionIDs" -> ok(
                action,
                "ConnectionManager",
                mapOf("ConnectionIDs" to "0")
            )
            else -> invalidAction(action)
        }

    private fun avTransport(action: String, values: Map<String, String>): SoapResult =
        when (action) {
            "SetAVTransportURI" -> {
                state.currentUri = values["CurrentURI"].orEmpty()
                state.transportState = "STOPPED"
                playbackController.onSetTransportUri(state.currentUri)
                publishTransportState()
                ok(action, "AVTransport", emptyMap())
            }
            "Play" -> {
                state.transportState = "PLAYING"
                playbackController.onPlay(state.currentUri)
                publishTransportState()
                ok(action, "AVTransport", emptyMap())
            }
            "Pause" -> {
                state.transportState = "PAUSED_PLAYBACK"
                playbackController.onPause()
                publishTransportState()
                ok(action, "AVTransport", emptyMap())
            }
            "Stop" -> {
                state.transportState = "STOPPED"
                playbackController.onStop()
                publishTransportState()
                ok(action, "AVTransport", emptyMap())
            }
            "GetTransportInfo" -> ok(
                action,
                "AVTransport",
                mapOf(
                    "CurrentTransportState" to state.transportState,
                    "CurrentTransportStatus" to "OK",
                    "CurrentSpeed" to "1"
                )
            )
            "GetPositionInfo" -> ok(
                action,
                "AVTransport",
                mapOf(
                    "Track" to "1",
                    "TrackDuration" to "00:00:00",
                    "TrackMetaData" to "",
                    "TrackURI" to state.currentUri,
                    "RelTime" to "00:00:00",
                    "AbsTime" to "00:00:00",
                    "RelCount" to "0",
                    "AbsCount" to "0"
                )
            )
            else -> invalidAction(action)
        }

    private fun renderingControl(action: String, values: Map<String, String>): SoapResult =
        when (action) {
            "GetVolume" -> ok(
                action,
                "RenderingControl",
                mapOf("CurrentVolume" to state.volume.toString())
            )
            "SetVolume" -> {
                values["DesiredVolume"]?.toIntOrNull()?.coerceIn(0, 100)?.let { state.volume = it }
                onLastChange("/upnp/event/RenderingControl", "Volume=${state.volume}")
                ok(action, "RenderingControl", emptyMap())
            }
            "GetMute" -> ok(
                action,
                "RenderingControl",
                mapOf("CurrentMute" to if (state.muted) "1" else "0")
            )
            "SetMute" -> {
                state.muted = values["DesiredMute"] == "1" || values["DesiredMute"].equals("true", ignoreCase = true)
                onLastChange("/upnp/event/RenderingControl", "Mute=${if (state.muted) 1 else 0}")
                ok(action, "RenderingControl", emptyMap())
            }
            else -> invalidAction(action)
        }

    private fun ok(action: String, service: String, values: Map<String, String>): SoapResult {
        val escapedValues = values.entries.joinToString("") { (key, value) ->
            "<$key>${value.xmlEscaped()}</$key>"
        }
        return SoapResult(
            statusCode = 200,
            statusMessage = "OK",
            body = envelope(
                """<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">$escapedValues</u:${action}Response>"""
            )
        )
    }

    private fun invalidAction(action: String): SoapResult =
        SoapResult(500, "Internal Server Error", fault("Client", "Invalid action: $action"))

    private fun publishTransportState() {
        onLastChange("/upnp/event/AVTransport", "TransportState=${state.transportState}")
    }

    private fun fault(code: String, message: String): String =
        envelope(
            """
            <s:Fault>
              <faultcode>s:$code</faultcode>
              <faultstring>UPnPError</faultstring>
              <detail>
                <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                  <errorCode>401</errorCode>
                  <errorDescription>${message.xmlEscaped()}</errorDescription>
                </UPnPError>
              </detail>
            </s:Fault>
            """.trimIndent()
        )

    private fun envelope(content: String): String =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>$content</s:Body></s:Envelope>"""

    private fun String.xmlEscaped(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    companion object {
        private const val SINK_PROTOCOL_INFO =
            "http-get:*:video/mp4:*,http-get:*:audio/mpeg:*,http-get:*:image/jpeg:*,http-get:*:image/png:*"
    }
}

interface DlnaPlaybackController {
    fun onSetTransportUri(uri: String)
    fun onPlay(uri: String)
    fun onPause()
    fun onStop()

    companion object {
        val NOOP = object : DlnaPlaybackController {
            override fun onSetTransportUri(uri: String) = Unit
            override fun onPlay(uri: String) = Unit
            override fun onPause() = Unit
            override fun onStop() = Unit
        }
    }
}

data class SoapResult(
    val statusCode: Int,
    val statusMessage: String,
    val body: String
)

private class DlnaTransportState {
    var currentUri: String = ""
    var transportState: String = "STOPPED"
    var volume: Int = 50
    var muted: Boolean = false
}

private object SecureSoapXml {
    fun parse(body: String): Document {
        require(body.length <= MAX_SOAP_CHARS) { "SOAP body too large" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
        require(depth(document.documentElement) <= MAX_XML_DEPTH) { "XML nesting too deep" }
        return document
    }

    fun actionName(body: String): String? {
        return parse(body)
            .getElementsByTagNameNS("*", "Body")
            .item(0)
            ?.childNodes
            ?.let { nodes ->
                (0 until nodes.length)
                    .asSequence()
                    .map { nodes.item(it) }
                    .firstOrNull { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
                    ?.localName
            }
    }

    fun directChildText(document: Document, action: String): Map<String, String> {
        val actionNode = document.getElementsByTagNameNS("*", action).item(0) ?: return emptyMap()
        return (0 until actionNode.childNodes.length)
            .asSequence()
            .map { actionNode.childNodes.item(it) }
            .filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
            .associate { it.localName to (it.textContent ?: "") }
    }

    private fun depth(node: org.w3c.dom.Node?): Int {
        if (node == null) return 0
        val children = node.childNodes
        var maxChildDepth = 0
        for (i in 0 until children.length) {
            maxChildDepth = maxOf(maxChildDepth, depth(children.item(i)))
        }
        return 1 + maxChildDepth
    }

    private const val MAX_SOAP_CHARS = 64 * 1024
    private const val MAX_XML_DEPTH = 32
}
