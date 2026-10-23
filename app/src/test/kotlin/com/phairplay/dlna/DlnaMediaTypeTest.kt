package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
