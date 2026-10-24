package com.phairplay.dlna

import com.phairplay.util.Logger
import java.io.OutputStream

/**
 * DlnaHttpResponses — writes the HTTP answers of the description server.
 *
 * WHY a separate class: the answer writer has exactly one job — turn a status code
 * and a body into bytes on the wire — and it is the only place where a header
 * value leaves the process. Keeping it apart makes the header handling easy to
 * see: every header value is stripped of the characters that could end the header
 * line and start a new one, so a value can never smuggle a header of its own into
 * the answer.
 *
 * Example:
 *   DlnaHttpResponses.write(output, 200, "OK", "text/xml; charset=\"utf-8\"", body)
 */
internal object DlnaHttpResponses {

    /**
     * Writes a complete answer with a body.
     *
     * @param extraHeaders headers that are added to the answer — for example the
     *   `Application-Url` header a Samsung control point looks for.
     */
    fun write(
        output: OutputStream,
        statusCode: Int,
        statusMessage: String,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusMessage\r\n")
            append("Connection: close\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            extraHeaders.forEach { (key, value) -> append("$key: ${value.headerSafe()}\r\n") }
            append("\r\n")
        }
        runCatching {
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }.onFailure { Logger.w("DLNA response write failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Writes an answer without a body — the answer to a SUBSCRIBE carries the SID
     * and the timeout instead of a document.
     */
    fun writeHeaders(
        output: OutputStream,
        statusCode: Int,
        statusMessage: String,
        headers: Map<String, String>
    ) {
        val baseHeaders = mutableMapOf(
            "Connection" to "close",
            "Content-Length" to "0"
        )
        baseHeaders.putAll(headers)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusMessage\r\n")
            baseHeaders.forEach { (key, value) -> append("$key: ${value.headerSafe()}\r\n") }
            append("\r\n")
        }
        runCatching {
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.flush()
        }.onFailure { Logger.w("DLNA response write failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Removes the characters that could end the header line and start a new one.
     *
     * WHY: a header value is built from data of the renderer — the host name and
     * the port of this server. Should such a value ever carry a line break, the
     * answer would contain a header the client did not ask for.
     */
    private fun String.headerSafe(): String = replace("\r", "").replace("\n", "")
}
