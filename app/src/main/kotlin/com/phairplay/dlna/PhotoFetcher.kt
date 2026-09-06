package com.phairplay.dlna

import com.phairplay.util.Logger
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * PhotoFetcher — downloads a cast image (or album art) into memory with a hard size cap.
 *
 * WHY: DLNA image items and `albumArtURI` are plain HTTP URLs on the sender; the TV must fetch them itself.
 * The cap and timeouts (RULE 4) protect against hostile or broken servers. The connection opener is
 * injectable so the limits are unit-tested without a network.
 *
 * HOW: `when (val r = fetcher.fetch(uri)) { is Ok -> show(r.bytes, r.mimeType); is Failed -> … }` — blocking,
 * call on an IO dispatcher.
 */
class PhotoFetcher(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val maxBytes: Int = DlnaConstants.MAX_PHOTO_BYTES
) {
    sealed class Result {
        data class Ok(val bytes: ByteArray, val mimeType: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun fetch(uri: String): Result {
        val url = runCatching { URL(uri) }.getOrElse { return Result.Failed("bad url") }
        if (url.protocol != "http" && url.protocol != "https") return Result.Failed("unsupported scheme ${url.protocol}")
        var connection: HttpURLConnection? = null
        return try {
            connection = open(url).apply {
                connectTimeout = DlnaConstants.PHOTO_TIMEOUT_MS
                readTimeout = DlnaConstants.PHOTO_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("transferMode.dlna.org", "Interactive")
            }
            val status = connection.responseCode
            if (status !in 200..299) return Result.Failed("HTTP $status")
            if (connection.contentLengthLong > maxBytes) return Result.Failed("declared size over cap")
            val bytes = readCapped(connection) ?: return Result.Failed("body over cap")
            val declared = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US)
            val mime = declared?.takeIf { it.startsWith("image/") } ?: sniffMime(bytes) ?: FALLBACK_MIME
            Result.Ok(bytes, mime)
        } catch (e: Exception) {
            Logger.w("PhotoFetcher: $uri failed: ${e.message}")
            Result.Failed(e.message ?: "io error")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readCapped(connection: HttpURLConnection): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        connection.inputStream.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > maxBytes) return null
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun sniffMime(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() -> "image/gif"
        else -> null
    }

    companion object {
        private const val READ_CHUNK = 16 * 1024
        private const val FALLBACK_MIME = "image/jpeg"
    }
}
