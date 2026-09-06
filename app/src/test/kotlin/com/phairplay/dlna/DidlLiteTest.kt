package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DidlLiteTest — metadata Windows/BubbleUPnP attach to SetAVTransportURI feeds the now-playing card.
 *
 * WHY: Title/artist/album/art must be read from the DIDL-Lite item; malformed metadata must degrade
 * to "no metadata", never to a fault.
 */
class DidlLiteTest {

    private val didl = """
        <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
            xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
          <item id="1" parentID="0" restricted="1">
            <dc:title>Track One</dc:title>
            <upnp:artist>Some Artist</upnp:artist>
            <dc:creator>Creator Fallback</dc:creator>
            <upnp:album>Album X</upnp:album>
            <upnp:albumArtURI>http://192.168.1.10:2869/art.jpg</upnp:albumArtURI>
            <upnp:class>object.item.audioItem.musicTrack</upnp:class>
            <res protocolInfo="http-get:*:audio/mpeg:DLNA.ORG_PN=MP3" duration="0:03:25.000">http://192.168.1.10:2869/track.mp3</res>
          </item>
        </DIDL-Lite>
    """.trimIndent()

    @Test
    fun `parses title artist album art class protocolInfo and duration`() {
        val item = DidlLite.parse(didl)
        assertEquals("Track One", item.title)
        assertEquals("Some Artist", item.artist)
        assertEquals("Album X", item.album)
        assertEquals("http://192.168.1.10:2869/art.jpg", item.albumArtUri)
        assertEquals("object.item.audioItem.musicTrack", item.upnpClass)
        assertEquals("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3", item.protocolInfo)
        assertEquals(205_000L, item.durationMs)
    }

    @Test
    fun `creator is used when artist is absent`() {
        val item = DidlLite.parse(didl.replace("<upnp:artist>Some Artist</upnp:artist>", ""))
        assertEquals("Creator Fallback", item.artist)
    }

    @Test
    fun `empty null and malformed metadata give an empty item`() {
        assertEquals(DidlItem.EMPTY, DidlLite.parse(""))
        assertEquals(DidlItem.EMPTY, DidlLite.parse(null))
        assertEquals(DidlItem.EMPTY, DidlLite.parse("<DIDL-Lite><item>"))
        assertNull(DidlLite.parse("<DIDL-Lite xmlns=\"x\"><item></item></DIDL-Lite>").title)
    }

    @Test
    fun `a leading thumbnail res is skipped in favor of the video res for duration and protocolInfo`() {
        val xml = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
              <item id="1" parentID="0" restricted="1">
                <upnp:class>object.item.videoItem.movie</upnp:class>
                <res protocolInfo="http-get:*:image/jpeg:*">http://192.168.1.10/thumb.jpg</res>
                <res protocolInfo="http-get:*:video/mp4:*" duration="0:42:10">http://192.168.1.10/movie.mp4</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()
        val item = DidlLite.parse(xml)
        assertEquals("http-get:*:video/mp4:*", item.protocolInfo)
        assertEquals(2_530_000L, item.durationMs)
    }

    @Test
    fun `a container root child parses like an item`() {
        val xml = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                xmlns:dc="http://purl.org/dc/elements/1.1/">
              <container id="1" parentID="0" restricted="1" childCount="3">
                <dc:title>My Folder</dc:title>
              </container>
            </DIDL-Lite>
        """.trimIndent()
        assertEquals("My Folder", DidlLite.parse(xml).title)
    }

    @Test
    fun `albumArtURI with a dlna profileID attribute still yields the text content`() {
        val xml = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/">
              <item id="1" parentID="0" restricted="1">
                <upnp:albumArtURI dlna:profileID="JPEG_TN">http://192.168.1.10/art.jpg</upnp:albumArtURI>
              </item>
            </DIDL-Lite>
        """.trimIndent()
        assertEquals("http://192.168.1.10/art.jpg", DidlLite.parse(xml).albumArtUri)
    }
}
