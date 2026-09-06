package com.phairplay.dlna

import java.util.Locale

/**
 * UpnpTime — converts between milliseconds and the AVTransport `H:MM:SS[.fff]` time format.
 *
 * WHY: GetPositionInfo/GetMediaInfo report durations as strings and Seek REL_TIME targets arrive as
 * strings; both must be exact or control points show a wrong progress bar or reject the seek.
 *
 * HOW: `UpnpTime.format(90_000)` → `"0:01:30"`; `UpnpTime.parse("0:01:30")` → `90000L` (null if malformed).
 */
object UpnpTime {
    private const val MS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val FRACTION_DIGITS = 3

    /** Rejects absurd but technically-parseable hour counts (e.g. a 12-digit overflow attempt). */
    private const val MAX_HOURS = 24L * 365L

    fun format(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / MS_PER_SECOND
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        // RULE 4 / correctness: the default-locale "%d" formats digits as localized numerals on
        // some devices (e.g. Arabic, Persian, Bengali), which control points cannot parse back.
        return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    }

    fun parse(text: String): Long? {
        val parts = text.trim().split(":")
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val secondParts = parts[2].split(".")
        if (secondParts.size > 2) return null
        val seconds = secondParts[0].toLongOrNull() ?: return null
        if (hours !in 0..MAX_HOURS || minutes !in 0..59 || seconds !in 0..59) return null
        val fraction = secondParts.getOrNull(1)
        val millis = when {
            fraction == null -> 0L
            // A dot with an empty or non-digit fraction (".", ".-5", ".+5") is malformed, not zero.
            fraction.isEmpty() || !fraction.all { it.isDigit() } -> return null
            else -> fraction.take(FRACTION_DIGITS).padEnd(FRACTION_DIGITS, '0').toLongOrNull() ?: return null
        }
        return (hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE + seconds) * MS_PER_SECOND + millis
    }
}
