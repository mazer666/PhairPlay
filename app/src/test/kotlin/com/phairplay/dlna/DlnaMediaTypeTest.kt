package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DlnaMediaTypeTest — proves how a DLNA URL is classified into media kind and MIME
 * type.
 *
 * WHY: the renderer advertises what it can play and answers a play request with the
 * matching MIME type. A URL that is classified wrongly is offered to the decoder and
 * fails there, with a much harder to read error message.
 *
 * Example:
 *   DlnaMediaType.kindFromUrl("http://host/movie.mp4")
 */
class DlnaMediaTypeTest {

    @Test
    fun `detects JPEG and PNG image URLs`() {
        assertEquals("image/jpeg", DlnaMediaType.imageMimeFromUrl("http://host/photo.jpg"))
        assertEquals("image/jpeg", DlnaMediaType.imageMimeFromUrl("http://host/photo.JPEG?x=1"))
        assertEquals("image/png", DlnaMediaType.imageMimeFromUrl("http://host/photo.png"))
    }

    @Test
    fun `non image URLs return null`() {
        assertNull(DlnaMediaType.imageMimeFromUrl("http://host/movie.mp4"))
        assertNull(DlnaMediaType.imageMimeFromUrl("http://host/stream"))
    }

    @Test
    fun `detects the media kind from the file extension`() {
        assertEquals(DlnaMediaKind.VIDEO, DlnaMediaType.kindFromUrl("http://host/movie.mp4"))
        assertEquals(DlnaMediaKind.VIDEO, DlnaMediaType.kindFromUrl("http://host/movie.mkv?token=1"))
        assertEquals(DlnaMediaKind.AUDIO, DlnaMediaType.kindFromUrl("http://host/song.mp3"))
        assertEquals(DlnaMediaKind.AUDIO, DlnaMediaType.kindFromUrl("http://host/song.m4a"))
        assertEquals(DlnaMediaKind.IMAGE, DlnaMediaType.kindFromUrl("http://host/photo.jpg"))
    }

    @Test
    fun `an unknown extension yields the unknown kind`() {
        assertEquals(DlnaMediaKind.UNKNOWN, DlnaMediaType.kindFromUrl("http://host/stream"))
        assertEquals(DlnaMediaKind.UNKNOWN, DlnaMediaType.kindFromUrl("http://host/"))
    }
}
