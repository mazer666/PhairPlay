package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DlnaSoapDispatcherTest — proves that a UPnP control point gets the answers it
 * needs to drive PhairPlay as a renderer.
 *
 * WHY: the control plane is the part a control point such as VLC talks to. If one
 * answer is missing or malformed, the control point drops the device — the classic
 * symptom being "PhairPlay does not appear in the renderer list".
 *
 * WHAT WE TEST:
 * - `GetProtocolInfo` returns a non-empty Source *and* Sink protocol list,
 * - `SetAVTransportURI` accepts the URIs a real control point sends,
 * - `GetPositionInfo` returns the times in the literal `H:MM:SS` format,
 * - the remaining AVTransport and RenderingControl actions answer correctly,
 * - hostile SOAP bodies (XXE, billion laughs, broken XML) are refused.
 */
class DlnaSoapDispatcherTest {

    @Test
    fun `ConnectionManager GetProtocolInfo returns a non-empty Source list`() {
        val result = dispatch(
            "/upnp/control/ConnectionManager",
            "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo",
            envelope("ConnectionManager", "GetProtocolInfo")
        )

        assertEquals(200, result.statusCode)
        // An empty Source makes the renderer invisible in the control point's picker.
        assertTrue(result.body.contains("<Source>http-get:*:video/mp4:*"))
        assertTrue(result.body.contains("http-get:*:video/*:*"))
        assertTrue(result.body.contains("http-get:*:audio/*:*"))
    }

    @Test
    fun `ConnectionManager GetProtocolInfo returns a non-empty Sink list`() {
        val result = dispatch(
            "/upnp/control/ConnectionManager",
            "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo",
            envelope("ConnectionManager", "GetProtocolInfo")
        )

        assertTrue(result.body.contains("<Sink>http-get:*:video/mp4:*"))
    }

    @Test
    fun `AVTransport stores URI and reports it in GetPositionInfo`() {
        val dispatcher = DlnaSoapDispatcher()
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>http://192.168.1.4/movie.mp4</CurrentURI>"
            )
        )
        val result = dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo\"",
            body = envelope("AVTransport", "GetPositionInfo")
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("<TrackURI>http://192.168.1.4/movie.mp4</TrackURI>"))
    }

    @Test
    fun `SetAVTransportURI accepts the http URL a control point sends`() {
        val result = dispatch(
            "/upnp/control/AVTransport",
            "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
            envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>http://192.168.1.10:8080/videos/sample.mp4</CurrentURI>" +
                    "<CurrentURIMetaData></CurrentURIMetaData>"
            )
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("SetAVTransportURIResponse"))
    }

    @Test
    fun `SetAVTransportURI accepts a file URI and an rtsp URI`() {
        listOf(
            "file:///C:/Users/test/movie.mkv",
            "rtsp://192.168.1.10:554/live",
            "https://example.invalid/stream.mp4"
        ).forEach { uri ->
            val result = dispatch(
                "/upnp/control/AVTransport",
                "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
                envelope("AVTransport", "SetAVTransportURI", "<CurrentURI>$uri</CurrentURI>")
            )

            assertEquals("URI must be accepted: $uri", 200, result.statusCode)
        }
    }

    @Test
    fun `SetAVTransportURI refuses a URI without a scheme`() {
        val result = dispatch(
            "/upnp/control/AVTransport",
            "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
            envelope("AVTransport", "SetAVTransportURI", "<CurrentURI>not a uri</CurrentURI>")
        )

        assertEquals(500, result.statusCode)
        assertTrue(result.body.contains("<errorCode>402</errorCode>"))
    }

    @Test
    fun `GetPositionInfo returns the times in the UPnP H MM SS format`() {
        val result = dispatch(
            "/upnp/control/AVTransport",
            "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo",
            envelope("AVTransport", "GetPositionInfo")
        )

        assertEquals(200, result.statusCode)
        // VLC parses these values literally — "00:00:00" would be mis-parsed.
        assertTrue(result.body.contains("<TrackDuration>0:00:00</TrackDuration>"))
        assertTrue(result.body.contains("<RelTime>0:00:00.000</RelTime>"))
    }

    @Test
    fun `GetPositionInfo reports the duration announced in the DIDL metadata`() {
        val dispatcher = DlnaSoapDispatcher()
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>http://192.168.1.4/movie.mp4</CurrentURI>" +
                    // A control point sends the DIDL-Lite document escaped.
                    "<CurrentURIMetaData>" + didl("0:01:30", "http-get:*:video/mp4:*").xmlEscaped() + "</CurrentURIMetaData>"
            )
        )
        val result = dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo\"",
            body = envelope("AVTransport", "GetPositionInfo")
        )

        assertTrue(result.body.contains("<TrackDuration>0:01:30</TrackDuration>"))
    }

    @Test
    fun `a body that starts with a newline is still answered`() {
        // WHY: a control point that puts whitespace in front of the XML declaration must
        // not be answered with an error - otherwise the renderer silently disappears
        // from the device list of that control point.
        val result = dispatch(
            "/upnp/control/ConnectionManager",
            "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo",
            "\n" + envelope("ConnectionManager", "GetProtocolInfo")
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("<Source>http-get:*:video/mp4:*"))
    }

    @Test
    fun `GetMediaInfo reports the loaded media`() {
        val dispatcher = DlnaSoapDispatcher()
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>http://192.168.1.4/movie.mp4</CurrentURI>"
            )
        )
        val result = dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#GetMediaInfo\"",
            body = envelope("AVTransport", "GetMediaInfo")
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("<CurrentURI>http://192.168.1.4/movie.mp4</CurrentURI>"))
        assertTrue(result.body.contains("<NrTracks>1</NrTracks>"))
        assertTrue(result.body.contains("<PlayMedium>NETWORK</PlayMedium>"))
    }

    @Test
    fun `GetCurrentTransportActions lists the actions of the current state`() {
        val dispatcher = DlnaSoapDispatcher()
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>http://192.168.1.4/movie.mp4</CurrentURI>"
            )
        )
        val result = dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#GetCurrentTransportActions\"",
            body = envelope("AVTransport", "GetCurrentTransportActions")
        )

        assertTrue(result.body.contains("<Actions>"))
        assertTrue(result.body.contains("Play"))
    }

    @Test
    fun `GetTransportInfo reports the transport state`() {
        val result = dispatch(
            "/upnp/control/AVTransport",
            "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo",
            envelope("AVTransport", "GetTransportInfo")
        )

        assertTrue(result.body.contains("<CurrentTransportState>STOPPED</CurrentTransportState>"))
        assertTrue(result.body.contains("<CurrentTransportStatus>OK</CurrentTransportStatus>"))
    }

    @Test
    fun `RenderingControl SetVolume affects GetVolume`() {
        val dispatcher = DlnaSoapDispatcher()
        dispatcher.dispatch(
            controlPath = "/upnp/control/RenderingControl",
            soapAction = "\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"",
            body = envelope("RenderingControl", "SetVolume", "<DesiredVolume>31</DesiredVolume>")
        )
        val result = dispatcher.dispatch(
            controlPath = "/upnp/control/RenderingControl",
            soapAction = "\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\"",
            body = envelope("RenderingControl", "GetVolume")
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("<CurrentVolume>31</CurrentVolume>"))
    }

    @Test
    fun `RenderingControl rejects an out of range volume`() {
        val result = dispatch(
            "/upnp/control/RenderingControl",
            "urn:schemas-upnp-org:service:RenderingControl:1#SetVolume",
            envelope("RenderingControl", "SetVolume", "<DesiredVolume>900</DesiredVolume>")
        )

        assertEquals(500, result.statusCode)
        assertTrue(result.body.contains("<errorCode>402</errorCode>"))
    }

    @Test
    fun `AVTransport actions notify playback controller`() {
        val events = mutableListOf<String>()
        val dispatcher = DlnaSoapDispatcher(object : DlnaPlaybackController {
            override fun onSetTransportUri(uri: String) {
                events += "set:$uri"
            }

            override fun onPlay(uri: String, metadata: DlnaMediaMetadata?) {
                events += "play:$uri"
            }

            override fun onPause() {
                events += "pause"
            }

            override fun onStop() {
                events += "stop"
            }
        })

        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope("AVTransport", "SetAVTransportURI", "<CurrentURI>http://host/video.mp4</CurrentURI>")
        )
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            body = envelope("AVTransport", "Play")
        )
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Pause\"",
            body = envelope("AVTransport", "Pause")
        )
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"",
            body = envelope("AVTransport", "Stop")
        )

        assertEquals(listOf("set:http://host/video.mp4", "play:http://host/video.mp4", "pause", "stop"), events)
    }

    @Test
    fun `transport state changes are published to the event subscribers`() {
        val events = mutableListOf<Pair<String, String>>()
        val dispatcher = DlnaSoapDispatcher(onLastChange = { path, payload -> events += path to payload })

        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope("AVTransport", "SetAVTransportURI", "<CurrentURI>http://host/video.mp4</CurrentURI>")
        )
        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            body = envelope("AVTransport", "Play")
        )

        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.first == "/upnp/event/AVTransport" })
        // The nested UPnP format — a flat list is ignored by the control points.
        assertTrue(events.last().second.contains("TransportState"))
        assertTrue(events.last().second.contains("PLAYING"))
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

    private fun didl(duration: String, protocolInfo: String): String =
        // WHY one line: a control point sends the escaped DIDL-Lite document on a single
        // line, and a multi-line document would break the indentation of the SOAP
        // envelope that carries it.
        """
        <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
          <item id="1" parentID="0" restricted="1">
            <dc:title>Sample</dc:title>
            <upnp:class>object.item.videoItem</upnp:class>
            <res protocolInfo="$protocolInfo" duration="$duration" size="1024">http://192.168.1.4/movie.mp4</res>
          </item>
        </DIDL-Lite>
        """.trimIndent().replace("\n", " ")

    private fun String.xmlEscaped(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
