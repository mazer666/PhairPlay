package com.phairplay.airplay

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PhotoHandlerTest — proves which payloads the AirPlay photo endpoint accepts.
 *
 * WHY: the photo endpoint takes bytes straight from the network, so the magic-byte
 * check and the content-type check are the only barrier between an attacker and the
 * image decoder. A payload that slips through is decoded by the TV.
 *
 * Example:
 *   PhotoHandler.validatePhoto(jpegBytes, "image/jpeg")
 */
class PhotoHandlerTest {

    @Test
    fun `valid JPEG payload is accepted`() {
        val result = PhotoHandler.validatePhoto(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            "image/jpeg"
        )

        assertTrue(result is PhotoValidation.Valid)
    }

    @Test
    fun `valid PNG payload is accepted`() {
        val result = PhotoHandler.validatePhoto(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A
            ),
            "image/png"
        )

        assertTrue(result is PhotoValidation.Valid)
    }

    @Test
    fun `unsupported payload is rejected`() {
        val result = PhotoHandler.validatePhoto("not an image".toByteArray(), "image/jpeg")

        assertTrue(result is PhotoValidation.Invalid)
    }

    @Test
    fun `content type mismatch is rejected`() {
        val result = PhotoHandler.validatePhoto(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            "image/png"
        )

        assertTrue(result is PhotoValidation.Invalid)
    }
}
