package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DlnaTimeFormatTest — proves the literal time format of the UPnP AVTransport.
 *
 * WHY: control points such as VLC parse `TrackDuration` and `RelTime` literally.
 * The specification format is `H+:MM:SS[.F+]` — the hour field is *not* zero
 * padded. A value like "00:01:30" is mis-parsed and makes the renderer look broken.
 */
class DlnaTimeFormatTest {

    @Test
    fun `formats a duration as H MM SS`() {
        assertEquals("0:00:00", DlnaTimeFormat.duration(0))
        assertEquals("0:00:01", DlnaTimeFormat.duration(1_000))
        assertEquals("0:01:30", DlnaTimeFormat.duration(90_000))
        assertEquals("1:00:00", DlnaTimeFormat.duration(3_600_000))
        assertEquals("2:03:04", DlnaTimeFormat.duration(2 * 3_600_000 + 3 * 60_000 + 4_000))
    }

    @Test
    fun `formats a relative time with milliseconds`() {
        assertEquals("0:00:00.000", DlnaTimeFormat.relativeTime(0))
        assertEquals("0:00:05.250", DlnaTimeFormat.relativeTime(5_250))
        assertEquals("0:01:30.000", DlnaTimeFormat.relativeTime(90_000))
    }

    @Test
    fun `a negative value is clamped to zero`() {
        assertEquals("0:00:00", DlnaTimeFormat.duration(-5_000))
        assertEquals("0:00:00.000", DlnaTimeFormat.relativeTime(-5_000))
    }

    @Test
    fun `the hour field is not zero padded`() {
        assertEquals("0:00:00", DlnaTimeFormat.duration(0))
        // "0:00:00" is seven characters — a zero padded hour would be eight.
        assertEquals(7, DlnaTimeFormat.duration(0).length)
    }
}
