package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaSoapDispatcherTest {

    @Test
    fun `ConnectionManager GetProtocolInfo returns sink protocols`() {
        val result = DlnaSoapDispatcher().dispatch(
            controlPath = "/upnp/control/ConnectionManager",
            soapAction = "\"urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo\"",
            body = envelope("ConnectionManager", "GetProtocolInfo")
        )

        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("<Sink>"))
        assertTrue(result.body.contains("http-get:*:video/mp4:*"))
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
    fun `AVTransport actions notify playback controller`() {
        val events = mutableListOf<String>()
        val dispatcher = DlnaSoapDispatcher(object : DlnaPlaybackController {
            override fun onSetTransportUri(uri: String) {
                events += "set:$uri"
            }

            override fun onPlay(uri: String) {
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
    fun `DOCTYPE is rejected`() {
        val result = DlnaSoapDispatcher().dispatch(
            controlPath = "/upnp/control/ConnectionManager",
            soapAction = "\"urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo\"",
            body = """<!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]><foo>&xxe;</foo>"""
        )

        assertEquals(400, result.statusCode)
    }

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
