package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DlnaMediaMetadataTest — proves the reading of the DIDL-Lite document.
 *
 * WHY: the metadata tells the renderer what the stream is, how long it is and which
 * container it uses. A control point that sends broken metadata must still be able
 * to start playback — the parser returns null instead of failing the action.
 */
class DlnaMediaMetadataTest {

    @Test
    fun `reads the title, the kind and the duration`() {
        val metadata = DlnaMediaMetadataParser.parse(DIDL_VIDEO)

        assertEquals("Sample Movie", metadata?.title)
        assertEquals(DlnaMediaKind.VIDEO, metadata?.kind)
        assertEquals(90_000L, metadata?.durationMillis)
    }

    @Test
    fun `reads the protocol info and the size`() {
        val metadata = DlnaMediaMetadataParser.parse(DIDL_VIDEO)

        assertEquals("http-get:*:video/mp4:*", metadata?.protocolInfo)
        assertEquals("video/mp4", metadata?.mimeType)
        assertEquals(1024L, metadata?.sizeBytes)
    }

    @Test
    fun `detects an audio item`() {
        val metadata = DlnaMediaMetadataParser.parse(
            DIDL_VIDEO.replace("object.item.videoItem", "object.item.audioItem")
                .replace("video/mp4", "audio/mpeg")
        )

        assertEquals(DlnaMediaKind.AUDIO, metadata?.kind)
    }

    @Test
    fun `detects an image item`() {
        val metadata = DlnaMediaMetadataParser.parse(
            DIDL_VIDEO.replace("object.item.videoItem", "object.item.imageItem")
                .replace("video/mp4", "image/jpeg")
        )

        assertEquals(DlnaMediaKind.IMAGE, metadata?.kind)
    }

    @Test
    fun `broken metadata yields null instead of an exception`() {
        assertNull(DlnaMediaMetadataParser.parse("<not-xml"))
        assertNull(DlnaMediaMetadataParser.parse(""))
        assertNull(DlnaMediaMetadataParser.parse(null))
    }

    @Test
    fun `a DOCTYPE in the metadata is refused`() {
        val payload = """
            <?xml version="1.0"?>
            <!DOCTYPE DIDL-Lite [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">&xxe;</DIDL-Lite>
        """.trimIndent()

        assertNull(DlnaMediaMetadataParser.parse(payload))
    }

    @Test
    fun `parses the UPnP duration formats`() {
        assertEquals(90_000L, DlnaMediaMetadataParser.parseDuration("0:01:30"))
        assertEquals(3_723_500L, DlnaMediaMetadataParser.parseDuration("1:02:03.500"))
        assertEquals(30_000L, DlnaMediaMetadataParser.parseDuration("0:30"))
        assertNull(DlnaMediaMetadataParser.parseDuration("nonsense"))
        assertNull(DlnaMediaMetadataParser.parseDuration(""))
    }

    companion object {
        private val DIDL_VIDEO = """
            <?xml version="1.0"?>
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
              <item id="1" parentID="0" restricted="1">
                <dc:title>Sample Movie</dc:title>
                <upnp:class>object.item.videoItem</upnp:class>
                <res protocolInfo="http-get:*:video/mp4:*" duration="0:01:30" size="1024">http://192.168.1.4/movie.mp4</res>
              </item>
            </DIDL-Lite>
        """.trimIndent()
    }
}
