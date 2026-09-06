package com.phairplay.dlna

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * HttpDate — formats a timestamp as the RFC 1123 date used in HTTP/SSDP `DATE` headers.
 *
 * WHY: [SsdpMessages] search responses and [HttpResponse] control-server responses both need the exact
 * same "Thu, 01 Jan 1970 00:00:00 GMT" wire format; one formatter stops the two from drifting apart.
 *
 * HOW: `HttpDate.format()` for now, `HttpDate.format(date)` for a fixed instant (tests pass `Date(0)`).
 */
object HttpDate {
    private const val PATTERN = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"

    fun format(date: Date = Date()): String =
        // A new SimpleDateFormat per call — the class is not thread-safe and this is called rarely
        // enough (per response/search-answer) that caching one instance isn't worth the complexity.
        SimpleDateFormat(PATTERN, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(date)
}
