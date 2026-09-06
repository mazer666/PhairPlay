package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LastChangeEncoderTest — the LastChange document control points parse to update their UI.
 *
 * WHY: Windows' transport buttons and BubbleUPnP's progress bar follow LastChange; the namespace, the
 * `channel="Master"` attribute on RCS variables and attribute escaping must be exact.
 */
class LastChangeEncoderTest {

    @Test
    fun `avTransport wraps variables in the AVT namespace with instance 0`() {
        val xml = LastChangeEncoder.avTransport(linkedMapOf("TransportState" to "PLAYING", "CurrentTrackURI" to "http://x/a?b=1&c=2"))
        assertTrue(xml.startsWith("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">"))
        assertTrue(xml.contains("<TransportState val=\"PLAYING\"/>"))
        assertTrue(xml.contains("<CurrentTrackURI val=\"http://x/a?b=1&amp;c=2\"/>"))
        assertTrue(xml.endsWith("</InstanceID></Event>"))
    }

    @Test
    fun `renderingControl adds the Master channel to every variable`() {
        val xml = LastChangeEncoder.renderingControl(linkedMapOf("Volume" to "40", "Mute" to "0"))
        assertTrue(xml.contains("xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\""))
        assertTrue(xml.contains("<Volume channel=\"Master\" val=\"40\"/>"))
        assertTrue(xml.contains("<Mute channel=\"Master\" val=\"0\"/>"))
    }

    @Test
    fun `snapshot to variable maps carry the fields control points poll`() {
        val snapshot = RendererSnapshot(
            state = TransportState.PAUSED_PLAYBACK, currentUri = "http://x/a.mp4", currentMetadata = "<m/>",
            nextUri = "http://x/b.mp4", durationMs = 90_000, volume = 55, mute = true
        )
        val avt = LastChangeEncoder.avTransportVars(snapshot)
        assertEquals("PAUSED_PLAYBACK", avt["TransportState"])
        assertEquals("OK", avt["TransportStatus"])
        assertEquals("Play,Stop,Seek,Next", avt["CurrentTransportActions"])
        assertEquals("1", avt["NumberOfTracks"])
        assertEquals("0:01:30", avt["CurrentTrackDuration"])
        assertEquals("http://x/a.mp4", avt["AVTransportURI"])
        assertEquals("<m/>", avt["CurrentTrackMetaData"])
        assertEquals("http://x/b.mp4", avt["NextAVTransportURI"])
        val rcs = LastChangeEncoder.renderingControlVars(snapshot)
        assertEquals("55", rcs["Volume"])
        assertEquals("1", rcs["Mute"])
    }
}
