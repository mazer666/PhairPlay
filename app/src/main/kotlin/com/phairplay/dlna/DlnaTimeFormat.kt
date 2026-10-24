package com.phairplay.dlna

import java.util.Locale

/**
 * DlnaTimeFormat — renders durations and positions in the literal formats UPnP
 * AVTransport control points parse.
 *
 * WHY: The UPnP AVTransport specification defines `TrackDuration` as `H+:MM:SS[.F+]`
 * and `RelTime` as a relative time with an optional fractional part. Control points
 * such as VLC parse these strings literally: a zero-padded two-digit hour ("00:01:30")
 * or a bare number is mis-parsed, and the control point then believes the renderer
 * reports nonsense. The hour field therefore stays unpadded ("0:01:30") exactly as the
 * specification examples show.
 *
 * Example:
 *   DlnaTimeFormat.duration(90_000)     // "0:01:30"
 *   DlnaTimeFormat.relativeTime(5_250)  // "0:00:05.250"
 */
object DlnaTimeFormat {

    /** Formats a track duration as `H:MM:SS` (no fraction). */
    fun duration(millis: Long): String = format(millis, withMillis = false)

    /** Formats a relative position as `H:MM:SS.mmm`. */
    fun relativeTime(millis: Long): String = format(millis, withMillis = true)

    private fun format(millis: Long, withMillis: Boolean): String {
        val safe = millis.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe / 60_000L) % 60L
        val seconds = (safe / 1_000L) % 60L
        val fraction = safe % 1_000L
        val base = String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        return if (withMillis) {
            String.format(Locale.US, "%s.%03d", base, fraction)
        } else {
            base
        }
    }
}
