package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * PhotoFetcherTest — image download limits and MIME detection, with a fake connection.
 *
 * WHY: A cast photo is fetched into memory; the 20 MB cap (RULE 4) and a sane MIME fallback protect the
 * TV from a hostile or misconfigured server.
 */
class PhotoFetcherTest {

    private fun connection(bytes: ByteArray, status: Int = 200, contentType: String? = "image/png", declaredLength: Long = -1) =
        { url: URL ->
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = status
                override fun getContentType() = contentType
                override fun getContentLengthLong() = declaredLength
                override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
            }
        }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9, 9)

    @Test
    fun `returns bytes and the declared image MIME`() {
        val result = PhotoFetcher(connection(png)).fetch("http://192.168.1.10/pic.png") as PhotoFetcher.Result.Ok
        assertEquals(png.size, result.bytes.size)
        assertEquals("image/png", result.mimeType)
    }

    @Test
    fun `sniffs the MIME when the server does not declare an image type`() {
        val result = PhotoFetcher(connection(jpeg, contentType = "application/octet-stream")).fetch("http://x/a") as PhotoFetcher.Result.Ok
        assertEquals("image/jpeg", result.mimeType)
    }

    @Test
    fun `rejects non-2xx, oversized declared and oversized actual bodies`() {
        assertTrue(PhotoFetcher(connection(png, status = 404)).fetch("http://x/a") is PhotoFetcher.Result.Failed)
        assertTrue(PhotoFetcher(connection(png, declaredLength = 100), maxBytes = 50).fetch("http://x/a") is PhotoFetcher.Result.Failed)
        assertTrue(PhotoFetcher(connection(ByteArray(60)), maxBytes = 50).fetch("http://x/a") is PhotoFetcher.Result.Failed)
    }

    @Test
    fun `rejects non-http schemes and bad URLs`() {
        assertTrue(PhotoFetcher(connection(png)).fetch("ftp://x/a") is PhotoFetcher.Result.Failed)
        assertTrue(PhotoFetcher(connection(png)).fetch("not a url") is PhotoFetcher.Result.Failed)
    }
}
