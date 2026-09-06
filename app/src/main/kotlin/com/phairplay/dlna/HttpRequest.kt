package com.phairplay.dlna

import com.phairplay.util.Logger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * HttpRequest — one parsed HTTP/1.1 request from a control point.
 *
 * WHY: SOAP control and GENA eventing both arrive as HTTP; the router and services need method, path,
 * case-insensitive headers and the body without touching the socket.
 *
 * HOW: Produced by [HttpRequestReader]; `request.header("soapaction")` (any case). Header keys are
 * normalised to lowercase in [init] (not just in [header]'s lookup) so a hand-built fixture — e.g. a test
 * request constructed directly rather than via [HttpRequestReader] — behaves the same regardless of the
 * casing used when building its headers map.
 */
class HttpRequest(
    val method: String,
    val path: String,
    headers: Map<String, String>,
    val body: ByteArray
) {
    private val headers: Map<String, String>

    init {
        this.headers = headers.mapKeys { it.key.lowercase(Locale.US) }
    }

    fun header(name: String): String? = headers[name.lowercase(Locale.US)]
    val bodyText: String get() = String(body, Charsets.UTF_8)
}

/** Outcome of reading one request — distinct so the server can answer 413/400 or close on EOF. */
sealed class HttpParse {
    data class Ok(val request: HttpRequest) : HttpParse()
    object Eof : HttpParse()
    object Malformed : HttpParse()
    object TooLarge : HttpParse()
}

/**
 * HttpRequestReader — reads exactly one HTTP request (request line, headers, Content-Length body).
 *
 * WHY: It is the validation gate for every byte a DLNA control point sends (RULE 4): line, header and
 * body sizes are capped before anything is parsed. Separate from the AirPlay `RtspRequestReader` because
 * the verbs (SUBSCRIBE/NOTIFY), limits and body semantics differ.
 *
 * HOW: `when (reader.read(socket.getInputStream())) { is HttpParse.Ok -> …; HttpParse.TooLarge -> 413 … }`
 */
class HttpRequestReader(private val maxBodyBytes: Int = DlnaConstants.MAX_HTTP_BODY_BYTES) {

    fun read(input: InputStream): HttpParse {
        val requestLine = when (val result = readLine(input)) {
            is LineResult.Ok -> result.text
            is LineResult.Eof -> {
                // A clean disconnect before any byte arrived is routine (idle keep-alive probes,
                // clients closing early); a partial request line means the client sent something
                // and then vanished, which is a malformed request, not a quiet EOF.
                return if (result.hadPartialLine) {
                    Logger.w("Rejected request: connection closed mid request-line")
                    HttpParse.Malformed
                } else {
                    HttpParse.Eof
                }
            }
            LineResult.TooLong -> {
                Logger.w("Rejected request: request-line exceeds $MAX_LINE_BYTES bytes")
                return HttpParse.TooLarge
            }
        }
        val parts = requestLine.trim().split(" ")
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) {
            Logger.w("Rejected request: malformed request-line '$requestLine'")
            return HttpParse.Malformed
        }

        val headers = HashMap<String, String>()
        var headerBytes = requestLine.length
        while (true) {
            val line = when (val result = readLine(input)) {
                is LineResult.Ok -> result.text
                is LineResult.Eof -> {
                    Logger.w("Rejected request: connection closed mid headers")
                    return HttpParse.Malformed
                }
                LineResult.TooLong -> {
                    Logger.w("Rejected request: header line exceeds $MAX_LINE_BYTES bytes")
                    return HttpParse.TooLarge
                }
            }
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) {
                Logger.w("Rejected request: headers exceed $MAX_HEADER_BYTES bytes total")
                return HttpParse.TooLarge
            }
            val colon = line.indexOf(':')
            if (colon <= 0) {
                Logger.w("Rejected request: malformed header line '$line'")
                return HttpParse.Malformed
            }
            headers[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }

        // We never accept a chunked body — everything we parse is a bounded SOAP/GENA payload with an
        // explicit Content-Length, so reject rather than silently mis-parse a chunked stream.
        if (headers.containsKey("transfer-encoding")) {
            Logger.w("Rejected request: Transfer-Encoding is not supported")
            return HttpParse.Malformed
        }

        val length = when (val outcome = contentLength(headers["content-length"])) {
            is ContentLength.Malformed -> {
                Logger.w("Rejected request: unparseable Content-Length '${headers["content-length"]}'")
                return HttpParse.Malformed
            }
            is ContentLength.Value -> outcome.bytes
        }
        if (length > maxBodyBytes) {
            Logger.w("Rejected request: Content-Length $length exceeds cap of $maxBodyBytes bytes")
            return HttpParse.TooLarge
        }

        val body = ByteArray(length.toInt())
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length.toInt() - read)
            if (n < 0) {
                Logger.w("Rejected request: connection closed with truncated body ($read of $length bytes)")
                return HttpParse.Malformed
            }
            read += n
        }
        return HttpParse.Ok(HttpRequest(parts[0], parts[1].substringBefore('?'), headers, body))
    }

    /** Parses the raw `Content-Length` header text; absent means zero, never negative or non-numeric. */
    private fun contentLength(raw: String?): ContentLength {
        if (raw == null) return ContentLength.Value(0L)
        val parsed = raw.toLongOrNull() ?: return ContentLength.Malformed
        if (parsed < 0) return ContentLength.Malformed
        return ContentLength.Value(parsed)
    }

    private sealed class ContentLength {
        data class Value(val bytes: Long) : ContentLength()
        object Malformed : ContentLength()
    }

    /** Outcome of reading one line — distinguishes a clean EOF from an over-long unterminated line. */
    private sealed class LineResult {
        data class Ok(val text: String) : LineResult()

        /** [hadPartialLine] is true when some bytes were read before the stream ended without CRLF. */
        data class Eof(val hadPartialLine: Boolean) : LineResult()
        object TooLong : LineResult()
    }

    /** Reads up to CRLF/LF (stripped), capped at [MAX_LINE_BYTES]. */
    private fun readLine(input: InputStream): LineResult {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return LineResult.Eof(hadPartialLine = buffer.size() > 0)
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
            if (buffer.size() > MAX_LINE_BYTES) return LineResult.TooLong
        }
        return LineResult.Ok(String(buffer.toByteArray(), Charsets.ISO_8859_1))
    }

    companion object {
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_HEADER_BYTES = 16 * 1024
    }
}
