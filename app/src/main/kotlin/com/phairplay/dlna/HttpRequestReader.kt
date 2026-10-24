package com.phairplay.dlna

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * HttpRequestReader — reads one HTTP request with hard limits on size and time.
 *
 * WHY: the description server is reachable from the whole local network. Without
 * limits a single peer can exhaust the memory of the TV (an endless header block),
 * keep a thread busy forever (a request that trickles one byte per second) or make
 * the server allocate a huge body (a forged `Content-Length`).
 *
 * The reader therefore enforces:
 * - a maximum length for the request line and for every header line,
 * - a maximum total size of the header block,
 * - a maximum body size,
 * - a total deadline for the request — a peer that sends a byte every second runs
 *   into the deadline even though every single read succeeds.
 *
 * Example:
 *   val request = HttpRequestReader(socket, deadlineMillis).read()
 */
internal class HttpRequestReader(
    private val socket: Socket,
    private val deadlineMillis: Long
) {

    /** Reads the request; the result carries either the request or the reason it failed. */
    fun read(): HttpRequest {
        val reader = BufferedReader(
            InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
        )
        val requestLine = try {
            readBoundedLine(reader)
        } catch (e: SocketTimeoutException) {
            return HttpRequest.failed(HttpFailure.TIMEOUT)
        }
        if (requestLine.tooLong) return HttpRequest.failed(HttpFailure.REQUEST_LINE_TOO_LONG)
        val line = requestLine.value ?: return HttpRequest.failed(HttpFailure.MALFORMED)
        val parts = line.split(' ')
        if (parts.size < 2) return HttpRequest.failed(HttpFailure.MALFORMED)
        val method = parts[0].uppercase()
        val path = parts[1]

        val headers = try {
            readHeaders(reader) ?: return HttpRequest.failed(HttpFailure.HEADERS_TOO_LARGE)
        } catch (e: SocketTimeoutException) {
            return HttpRequest.failed(HttpFailure.TIMEOUT)
        }

        val body = try {
            readBody(reader, headers) ?: return HttpRequest.failed(HttpFailure.BODY_TOO_LARGE)
        } catch (e: SocketTimeoutException) {
            return HttpRequest.failed(HttpFailure.TIMEOUT)
        }
        return HttpRequest(method = method, path = path, headers = headers, body = body)
    }

    /**
     * Reads the header block.
     *
     * @return the headers, or null when the block exceeds its budget.
     */
    private fun readHeaders(reader: BufferedReader): Map<String, String>? {
        val headers = mutableMapOf<String, String>()
        var totalBytes = 0
        while (true) {
            val line = readBoundedLine(reader)
            if (line.tooLong) return null
            val value = line.value ?: break
            totalBytes += value.length
            if (totalBytes > MAX_HEADER_BYTES) return null
            if (value.isEmpty()) break
            val key = value.substringBefore(':', missingDelimiterValue = "").trim().lowercase()
            val headerValue = value.substringAfter(':', missingDelimiterValue = "").trim()
            if (key.isNotEmpty()) headers[key] = headerValue
        }
        return headers
    }

    /**
     * Reads the request body.
     *
     * @return the body, or null when the announced length exceeds the limit.
     */
    private fun readBody(reader: BufferedReader, headers: Map<String, String>): String? {
        val announced = headers["content-length"]?.toLongOrNull() ?: return ""
        if (announced < 0) return ""
        if (announced > MAX_BODY_BYTES) return null
        val buffer = CharArray(announced.toInt())
        var read = 0
        while (read < buffer.size) {
            val count = reader.read(buffer, read, buffer.size - read)
            if (count < 0) break
            read += count
        }
        return String(buffer, 0, read)
    }

    /**
     * Reads a single line with a hard length limit.
     *
     * WHY NOT `BufferedReader.readLine()`: that method materialises the whole line in
     * memory before the caller can look at it. A client on the LAN that sends a long
     * line without a newline would make the server allocate all of it — the byte budget
     * checked afterwards would come too late. Reading character by character (still
     * buffered underneath) stops at [MAX_LINE_BYTES] instead.
     *
     * @return the line without its terminator, or null at end of stream; [tooLong] marks
     *   a line that exceeded the limit.
     */
    private fun readBoundedLine(reader: BufferedReader): BoundedLine {
        val builder = StringBuilder(128)
        while (true) {
            if (System.currentTimeMillis() > deadlineMillis) {
                throw SocketTimeoutException("request deadline exceeded")
            }
            val c = reader.read()
            if (c == -1) {
                return if (builder.isEmpty()) BoundedLine(null) else BoundedLine(builder.toString())
            }
            if (c == '\n'.code) {
                val line = builder.toString()
                return BoundedLine(if (line.endsWith("\r")) line.dropLast(1) else line)
            }
            builder.append(c.toChar())
            if (builder.length > MAX_LINE_BYTES) {
                return BoundedLine(null, tooLong = true)
            }
        }
    }

    /** Result of [readBoundedLine]. */
    private data class BoundedLine(val value: String?, val tooLong: Boolean = false)

    companion object {
        /** Maximum length of a single request line or header line. */
        const val MAX_LINE_BYTES = 8 * 1024

        /** Maximum total size of the header block. */
        const val MAX_HEADER_BYTES = 16 * 1024

        /** Maximum size of a request body (the SOAP envelope). */
        const val MAX_BODY_BYTES = 64 * 1024
    }
}

/** The reason a request could not be read. */
internal enum class HttpFailure {
    REQUEST_LINE_TOO_LONG,
    HEADERS_TOO_LARGE,
    BODY_TOO_LARGE,
    TIMEOUT,
    MALFORMED
}

/** A parsed HTTP request, or the reason it failed. */
internal data class HttpRequest(
    val method: String = "",
    val path: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val failure: HttpFailure? = null
) {
    /** Returns true when the request was read successfully. */
    val isValid: Boolean get() = failure == null

    companion object {
        /** Builds a failed request. */
        fun failed(failure: HttpFailure): HttpRequest = HttpRequest(failure = failure)
    }
}
