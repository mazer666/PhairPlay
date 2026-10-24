package com.phairplay.airplay

/**
 * PhotoHandler — validates AirPlay photo endpoint payloads.
 *
 * AirPlay photo sharing sends an HTTP PUT to `/photo` with a JPEG or PNG body
 * and an HTTP DELETE to clear the displayed photo. This object keeps validation
 * separate from socket handling so malformed network input is easy to test.
 */
object PhotoHandler {
    const val PHOTO_PATH = "/photo"
    const val MAX_PHOTO_BYTES = 25 * 1024 * 1024

    fun validatePhoto(bytes: ByteArray, contentType: String?): PhotoValidation {
        if (bytes.isEmpty()) {
            return PhotoValidation.Invalid("empty photo payload")
        }
        if (bytes.size > MAX_PHOTO_BYTES) {
            return PhotoValidation.Invalid("photo payload too large")
        }

        val detectedType = detectImageType(bytes)
            ?: return PhotoValidation.Invalid("unsupported image format")

        val declaredType = contentType?.substringBefore(";")?.trim()?.lowercase()
        if (declaredType != null &&
            declaredType.isNotEmpty() &&
            declaredType !in setOf(detectedType.mimeType, "application/octet-stream")
        ) {
            return PhotoValidation.Invalid("content type does not match image payload")
        }

        return PhotoValidation.Valid(detectedType)
    }

    private fun detectImageType(bytes: ByteArray): PhotoImageType? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return PhotoImageType.JPEG
        }

        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        ) {
            return PhotoImageType.PNG
        }

        return null
    }
}

/**
 * PhotoImageType — the image formats the AirPlay photo endpoint accepts.
 *
 * WHY: the payload is classified by its magic bytes and not by its file name, so the
 * detected format has to carry the MIME type that belongs to it — the sender is
 * answered with that type and the decoder is picked from it.
 *
 * Example:
 *   PhotoImageType.JPEG.mimeType // "image/jpeg"
 */
enum class PhotoImageType(val mimeType: String) {
    JPEG("image/jpeg"),
    PNG("image/png")
}

/**
 * PhotoValidation — the result of validating an AirPlay photo payload.
 *
 * WHY: the caller has to distinguish "this is a usable image" from "this is not", and
 * it has to be able to say why a payload was rejected. A sealed class names both
 * cases, so the compiler forces every caller to handle them.
 *
 * Example:
 *   when (PhotoHandler.validatePhoto(bytes, "image/jpeg")) {
 *       is PhotoValidation.Valid -> show(bytes)
 *       is PhotoValidation.Invalid -> reject(validation.reason)
 *   }
 */
sealed class PhotoValidation {
    /**
     * Valid — the payload is an image of a supported format.
     *
     * WHY: the caller needs the detected [PhotoImageType] to pick the decoder and to
     * log the format, a plain yes/no answer would not carry it.
     */
    data class Valid(val imageType: PhotoImageType) : PhotoValidation()

    /**
     * Invalid — the payload was rejected, [reason] says why.
     *
     * WHY: the reason is logged to explain the rejection and to tell a broken sender
     * apart from an attack, so it has to travel with the result instead of staying
     * inside the validation.
     */
    data class Invalid(val reason: String) : PhotoValidation()
}
