package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * UpnpTimeTest — AVTransport positions/durations travel as `H:MM:SS[.fff]` strings.
 *
 * WHY: Windows and VLC compare the strings we return from GetPositionInfo; a wrong pad or a rejected
 * fractional Seek target shows up as a stuck progress bar or a failed scrub.
 */
class UpnpTimeTest {

    @Test
    fun `format pads minutes and seconds but not hours`() {
        assertEquals("0:00:00", UpnpTime.format(0L))
        assertEquals("1:02:03", UpnpTime.format(3_723_000L))
    }

    @Test
    fun `format truncates milliseconds and clamps negatives to zero`() {
        assertEquals("0:00:01", UpnpTime.format(1_999L))
        assertEquals("0:00:00", UpnpTime.format(-5L))
    }

    @Test
    fun `parse accepts whole seconds and fractional seconds`() {
        assertEquals(90_000L, UpnpTime.parse("0:01:30"))
        assertEquals(3_723_500L, UpnpTime.parse("1:02:03.500"))
        assertEquals(3_723_500L, UpnpTime.parse(" 1:02:03.5 "))
    }

    @Test
    fun `parse rejects garbage and out-of-range fields`() {
        assertNull(UpnpTime.parse("abc"))
        assertNull(UpnpTime.parse("0:61:00"))
        assertNull(UpnpTime.parse("1:2"))
        assertNull(UpnpTime.parse("0:00:00.1.2"))
    }

    @Test
    fun `parse rejects a signed or empty fraction and an absurd hour count`() {
        assertNull(UpnpTime.parse("0:00:00.-5"))
        assertNull(UpnpTime.parse("0:00:00.+5"))
        assertNull(UpnpTime.parse("0:00:00."))
        assertNull(UpnpTime.parse("99999999999:00:00"))
    }

    @Test
    fun `format uses ASCII digits regardless of the default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("1:02:03", UpnpTime.format(3_723_000L))
        } finally {
            Locale.setDefault(original)
        }
    }
}
