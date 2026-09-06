package com.phairplay.airplay

import com.phairplay.util.Logger
import java.io.InputStream

/**
 * RtspRequestReader parses RTSP/HTTP-style requests from the AirPlay control socket.
 *
 * The reader consumes bytes directly from the socket so the caller can safely switch
 * to binary RTP interleaved frames after RTSP RECORD without losing buffered data.
 */
internal class RtspRequestReader(
    private val maxMessageBytes: Int,
    private val maxPhotoBytes: Int,
    /** Album artwork arrives as `SET_PARAMETER` with an image-typed body of several hundred KB. */
    private val maxArtworkBytes: Int = maxPhotoBytes
) {
    /**
     * Reads one complete RTSP or AirPlay photo request from [inputStream].
     *
     * Returns null on clean EOF, malformed input, or payloads over the configured limits.
     */
    fun read(inputStream: InputStream): RtspRequest? {
        val requestLine = readLine(inputStream) ?: return null
        if (requestLine.isBlank()) return read(inputStream)

        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            Logger.w("Malformed RTSP request line: '$requestLine'")
            return null
        }

        val headers = readHeaders(inputStream, requestLine.length) ?: return null
        val method = parts[0]
        val uri = parts[1]
        val protocol = parts[2]
        val bodyBytes = readBody(inputStream, method, uri, headers) ?: return null

        return RtspRequest(
            method = method,
            uri = uri,
            headers = headers,
            body = String(bodyBytes, Charsets.UTF_8),
            bodyBytes = bodyBytes,
            protocol = protocol
        )
    }

    private fun readHeaders(inputStream: InputStream, requestLineBytes: Int): Map<String, String>? {
        val headers = mutableMapOf<String, String>()
        var totalBytes = requestLineBytes

        while (true) {
            val line = readLine(inputStream) ?: return null
            if (line.isEmpty()) return headers

            totalBytes += line.length
            if (totalBytes > maxMessageBytes) {
                Logger.w("RTSP message too large — rejecting")
                return null
            }

            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                headers[line.substring(0, colonIndex).trim()] =
                    line.substring(colonIndex + 1).trim()
            }
        }
    }

    private fun readBody(
        inputStream: InputStream,
        method: String,
        uri: String,
        headers: Map<String, String>
    ): ByteArray? {
        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        val bodyLimit = when {
            method == "PUT" && uri.substringBefore("?") == PhotoHandler.PHOTO_PATH -> maxPhotoBytes
            method == "SET_PARAMETER" && isImage(headers) -> maxArtworkBytes
            else -> maxMessageBytes
        }

        if (contentLength > bodyLimit) {
            Logger.w("Request body too large ($contentLength bytes) — rejecting")
            return null
        }
        if (contentLength <= 0) return ByteArray(0)

        val buf = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = inputStream.read(buf, read, contentLength - read)
            if (n == -1) return null
            read += n
        }
        return buf
    }

    private fun readLine(inputStream: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inputStream.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) continue
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
            if (sb.length > maxMessageBytes) return null
        }
    }

    private fun isImage(headers: Map<String, String>): Boolean =
        headers["Content-Type"]?.lowercase()?.startsWith("image/") == true
}
