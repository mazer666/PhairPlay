package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProtocolInfoListTest — what we advertise as playable, and how an incoming item is classified.
 *
 * WHY: Windows transcodes anything not in the sink list, and the class decides whether the item goes to
 * MediaPlayer (video/audio) or the photo overlay (image).
 */
class ProtocolInfoListTest {

    @Test
    fun `sink list contains the MediaPlayer-playable formats and DLNA profiles`() {
        val sink = ProtocolInfoList.sinkString()
        assertTrue(sink.contains("http-get:*:video/mp4:*"))
        assertTrue(sink.contains("http-get:*:audio/mpeg:*"))
        assertTrue(sink.contains("http-get:*:image/jpeg:*"))
        assertTrue(sink.contains("http-get:*:audio/mpeg:DLNA.ORG_PN=MP3"))
        assertTrue(sink.contains("http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG"))
        assertFalse(sink.contains("audio/L16"))
        assertFalse(sink.contains("x-ms-wma"))
    }

    @Test
    fun `protocolInfo MIME wins over class and extension`() {
        assertEquals(MediaClass.AUDIO, ProtocolInfoList.classify("http-get:*:audio/mpeg:*", "object.item.videoItem", "http://x/a.mp4"))
    }

    @Test
    fun `upnp class is used when protocolInfo has a wildcard MIME`() {
        assertEquals(MediaClass.IMAGE, ProtocolInfoList.classify("http-get:*:*:*", "object.item.imageItem.photo", "http://x/blob"))
    }

    @Test
    fun `extension is the last resort`() {
        assertEquals(MediaClass.VIDEO, ProtocolInfoList.classify(null, null, "http://x/movie.MKV?token=1"))
        assertEquals(MediaClass.AUDIO, ProtocolInfoList.classify(null, null, "http://x/song.flac"))
        assertEquals(MediaClass.IMAGE, ProtocolInfoList.classify(null, null, "http://x/pic.png"))
    }

    @Test
    fun `unknown item is null`() {
        assertNull(ProtocolInfoList.classify(null, null, "http://x/blob"))
        assertNull(ProtocolInfoList.classify("http-get:*:application/octet-stream:*", null, "http://x/blob"))
    }

    @Test
    fun `extension survives a fragment or a trailing slash`() {
        assertEquals(MediaClass.VIDEO, ProtocolInfoList.classify(null, null, "http://x/movie.mp4#t=30"))
        assertEquals(MediaClass.AUDIO, ProtocolInfoList.classify(null, null, "http://x/song.mp3/"))
    }

    @Test
    fun `mimeFromProtocolInfo reads the third colon-separated field`() {
        assertEquals("video/mp4", ProtocolInfoList.mimeFromProtocolInfo("HTTP-GET:*:VIDEO/MP4:*"))
        assertNull(ProtocolInfoList.mimeFromProtocolInfo("http-get"))
        assertNull(ProtocolInfoList.mimeFromProtocolInfo(null))
        assertNull(ProtocolInfoList.mimeFromProtocolInfo("http-get:*:*:*"))
    }

    @Test
    fun `classifyMime maps the mime prefix to a MediaClass`() {
        assertEquals(MediaClass.VIDEO, ProtocolInfoList.classifyMime("video/mp4"))
        assertEquals(MediaClass.AUDIO, ProtocolInfoList.classifyMime("audio/mpeg"))
        assertEquals(MediaClass.IMAGE, ProtocolInfoList.classifyMime("image/jpeg"))
        assertNull(ProtocolInfoList.classifyMime("application/octet-stream"))
    }
}
