package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DlnaMediaUriAttackTest — the end-to-end proof that a control point cannot make the
 * television fetch a URI of its choosing.
 *
 * WHY: the DLNA renderer opens the media URI **itself**. A control point that names
 * `file:///data/data/com.phairplay.googletv/shared_prefs/phairplay_prefs.xml` would
 * otherwise turn the television into a reader for the app's own sandbox, and a control
 * point that names `http://169.254.169.254/latest/meta-data/` would use the television
 * as a request proxy towards the cloud metadata service of the router, whose answer
 * carries credentials.
 *
 * WHAT WE TEST:
 * - `SetAVTransportURI` with a `file://` URI is answered with the SOAP fault
 *   `402 Invalid Args`, and the playback controller is never told about the URI,
 * - the same holds for the cloud metadata address and for a `smb://` URI,
 * - a following `Play` finds no media loaded — the refused URI was never stored,
 * - a legitimate `http://` URI still reaches the controller (the control that proves
 *   the rule does not break the feature).
 */
class DlnaMediaUriAttackTest {

    @Test
    fun `a file URI is refused and never reaches the playback controller`() {
        val controller = RecordingController()

        val result = dispatch(controller, "file:///data/data/com.phairplay.googletv/shared_prefs/phairplay_prefs.xml")

        assertEquals(500, result.statusCode)
        assertTrue(
            "the answer must be the Invalid Args fault",
            result.body.contains("<errorCode>402</errorCode>")
        )
        assertTrue("the playback controller must not see the URI", controller.setUris.isEmpty())
        assertTrue("nothing may be played", controller.playedUris.isEmpty())
    }

    @Test
    fun `the cloud metadata address is refused and never reaches the playback controller`() {
        val controller = RecordingController()

        val result = dispatch(controller, "http://169.254.169.254/latest/meta-data/iam/security-credentials/")

        assertEquals(500, result.statusCode)
        assertTrue(result.body.contains("<errorCode>402</errorCode>"))
        assertTrue(controller.setUris.isEmpty())
    }

    @Test
    fun `a local scheme that a control point may emit is refused`() {
        val controller = RecordingController()

        val result = dispatch(controller, "smb://192.168.1.5/share/movie.mkv")

        assertEquals(500, result.statusCode)
        assertTrue(controller.setUris.isEmpty())
    }

    @Test
    fun `a refused URI is not stored and can therefore not be played`() {
        val controller = RecordingController()
        // ONE dispatcher for both calls — the transport state has to be the state the
        // refused URI would have been stored in.
        val dispatcher = DlnaSoapDispatcher(playbackController = controller)

        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>file:///etc/passwd</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
            )
        )
        val play = dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            body = envelope("AVTransport", "Play")
        )

        assertEquals(500, play.statusCode)
        assertTrue(
            "the refused URI must not be playable",
            play.body.contains("<errorCode>701</errorCode>")
        )
        assertTrue(controller.playedUris.isEmpty())
    }

    @Test
    fun `a legitimate http URI still reaches the playback controller`() {
        val controller = RecordingController()

        val result = dispatch(controller, "http://192.168.1.5:8080/movie.mp4")

        assertEquals(200, result.statusCode)
        assertEquals(listOf("http://192.168.1.5:8080/movie.mp4"), controller.setUris)
    }

    @Test
    fun `the refused URI is not echoed back by GetMediaInfo`() {
        val controller = RecordingController()
        val dispatcher = DlnaSoapDispatcher(playbackController = controller)

        dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>file:///etc/passwd</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
            )
        )
        val info = dispatcher.dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#GetMediaInfo\"",
            body = envelope("AVTransport", "GetMediaInfo")
        )

        assertFalse("the refused URI must not travel back out", info.body.contains("file:///etc/passwd"))
    }

    private fun dispatch(controller: DlnaPlaybackController, uri: String): SoapResult =
        DlnaSoapDispatcher(playbackController = controller).dispatch(
            controlPath = "/upnp/control/AVTransport",
            soapAction = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            body = envelope(
                "AVTransport",
                "SetAVTransportURI",
                "<CurrentURI>$uri</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
            )
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

    /** A controller that records what the dispatcher told it to do. */
    private class RecordingController : DlnaPlaybackController {
        val setUris = mutableListOf<String>()
        val playedUris = mutableListOf<String>()

        override fun onSetTransportUri(uri: String) {
            setUris += uri
        }

        override fun onPlay(uri: String, metadata: DlnaMediaMetadata?) {
            playedUris += uri
        }

        override fun onPause() = Unit

        override fun onStop() = Unit
    }
}
