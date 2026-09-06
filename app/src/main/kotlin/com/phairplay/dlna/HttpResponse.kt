package com.phairplay.dlna

/**
 * HttpResponse — an HTTP/1.1 response the DLNA server writes back, always `Connection: close`.
 *
 * WHY: One place to get the status line, Content-Length and server headers right; [afterSend] lets GENA
 * fire the initial NOTIFY only after the SUBSCRIBE reply has been written (the spec-mandated order).
 *
 * HOW: `HttpResponse.xml(200, body)`, `HttpResponse.empty(404)`, then `socket.write(response.toBytes())`.
 */
class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    /** Invoked by the server once the response bytes are on the wire (used for the GENA initial event). */
    val afterSend: (() -> Unit)? = null
) {
    fun toBytes(): ByteArray {
        val head = StringBuilder()
        head.append("HTTP/1.1 $status ${reason(status)}\r\n")
        head.append("SERVER: ${DlnaConstants.SERVER_HEADER}\r\n")
        head.append("DATE: ${HttpDate.format()}\r\n")
        head.append("CONNECTION: close\r\n")
        head.append("CONTENT-LENGTH: ${body.size}\r\n")
        headers.forEach { (name, value) ->
            // Sanitise before comparing: a name like "CONTENT-LENGTH\r\n" must not slip past this
            // guard by comparing unequal to "CONTENT-LENGTH" and then be emitted as a duplicate
            // header once appendHeader strips the CR/LF itself.
            val safeName = sanitizeHeaderPart(name)
            // The built-ins above already own these two headers; never let a caller-supplied map
            // duplicate or override them.
            if (!safeName.equals("CONTENT-LENGTH", ignoreCase = true) && !safeName.equals("CONNECTION", ignoreCase = true)) {
                appendHeader(head, safeName, value)
            }
        }
        head.append("\r\n")
        return head.toString().toByteArray(Charsets.ISO_8859_1) + body
    }

    /**
     * Appends one caller-supplied header, stripping any CR/LF from its name and value first.
     *
     * WHY (RULE 4, HTTP response splitting): a header value that itself contains "\r\n" — e.g. a GENA
     * SID we didn't generate — could inject extra header lines or split this response into two, forging
     * data the control point wasn't sent. Every caller-supplied value must be sanitized before it reaches
     * the wire.
     */
    private fun appendHeader(head: StringBuilder, name: String, value: String) {
        head.append("${sanitizeHeaderPart(name)}: ${sanitizeHeaderPart(value)}\r\n")
    }

    private fun sanitizeHeaderPart(part: String): String = part.replace("\r", "").replace("\n", "")

    companion object {
        private const val XML_CONTENT_TYPE = "text/xml; charset=\"utf-8\""

        fun empty(status: Int, headers: Map<String, String> = emptyMap()) = HttpResponse(status, headers)

        fun bytes(status: Int, contentType: String, body: ByteArray, extraHeaders: Map<String, String> = emptyMap()) =
            HttpResponse(status, mapOf("CONTENT-TYPE" to contentType) + extraHeaders, body)

        fun xml(status: Int, xml: String, extraHeaders: Map<String, String> = emptyMap()) =
            bytes(status, XML_CONTENT_TYPE, xml.toByteArray(Charsets.UTF_8), extraHeaders)

        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            412 -> "Precondition Failed"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }
    }
}
