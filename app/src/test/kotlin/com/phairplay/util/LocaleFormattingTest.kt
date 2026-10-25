package com.phairplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * Verifies the locale-aware formatting of numbers, sizes, durations and timestamps.
 *
 * The Arabic locale is the important case: it is the proof that the output follows the
 * locale and not the ASCII default of the JVM.
 */
class LocaleFormattingTest {

    private val arabicEgypt = Locale("ar", "EG")

    /** 2026-10-24 12:00:00 UTC — a fixed point in time so the test cannot drift. */
    private val october24NoonUtc = 1_792_843_200_000L

    /** 2026-10-24 14:30:00 UTC. */
    private val october24HalfPastTwoUtc = 1_792_852_200_000L

    private val utc = TimeZone.getTimeZone("UTC")

    // ── numbers ──────────────────────────────────────────────────────────────

    @Test
    fun `formats a decimal number with the German decimal comma`() {
        assertEquals("12,5", LocaleFormatting.formatDecimal(12.5, Locale.GERMAN))
    }

    @Test
    fun `formats a decimal number with the US decimal point`() {
        assertEquals("12.5", LocaleFormatting.formatDecimal(12.5, Locale.US))
    }

    @Test
    fun `writes Arabic-Indic digits for an Arabic locale`() {
        val formatted = LocaleFormatting.formatDecimal(12.5, arabicEgypt)

        assertTrue(
            "expected Arabic-Indic digits in '$formatted'",
            formatted.any { it in '٠'..'٩' }
        )
        assertFalse(
            "expected no ASCII digits in '$formatted'",
            formatted.any { it in '0'..'9' }
        )
        assertTrue(
            "expected the Arabic decimal separator in '$formatted'",
            '٫' in formatted
        )
    }

    @Test
    fun `formats an integer with the group separator of the locale`() {
        assertEquals("1.234", LocaleFormatting.formatNumber(1234, Locale.GERMAN))
        assertEquals("1,234", LocaleFormatting.formatNumber(1234, Locale.US))
    }

    @Test
    fun `writes the digits of an integer in the locale of the reader`() {
        val formatted = LocaleFormatting.formatNumber(1234, arabicEgypt)

        assertTrue(
            "expected Arabic-Indic digits in '$formatted'",
            formatted.any { it in '٠'..'٩' }
        )
        assertFalse(
            "expected no ASCII digits in '$formatted'",
            formatted.any { it in '0'..'9' }
        )
    }

    // ── duration ─────────────────────────────────────────────────────────────

    @Test
    fun `formats a duration below an hour as minutes and seconds`() {
        assertEquals("00:00", LocaleFormatting.formatDuration(0, Locale.US))
        assertEquals("01:05", LocaleFormatting.formatDuration(65_000, Locale.US))
    }

    @Test
    fun `formats a duration of an hour or more with the hour field`() {
        assertEquals("01:02:05", LocaleFormatting.formatDuration(3_725_000, Locale.US))
    }

    @Test
    fun `keeps the duration readable in an Arabic locale`() {
        val formatted = LocaleFormatting.formatDuration(65_000, arabicEgypt)

        assertEquals("the separator structure must not change", 5, formatted.length)
        assertEquals("the separator structure must not change", ':', formatted[2])
        assertFalse(
            "expected no ASCII digits in '$formatted'",
            formatted.any { it in '0'..'9' }
        )
        assertTrue(
            "expected Arabic-Indic digits in '$formatted'",
            formatted.any { it in '٠'..'٩' }
        )
    }

    // ── byte size ────────────────────────────────────────────────────────────

    @Test
    fun `formats a byte size with the unit and the locale separator`() {
        assertEquals("12,5 MB", LocaleFormatting.formatBytes(12_500_000, Locale.GERMAN))
        assertEquals("12.5 MB", LocaleFormatting.formatBytes(12_500_000, Locale.US))
    }

    @Test
    fun `keeps a small byte size as a whole number of bytes`() {
        assertEquals("0 B", LocaleFormatting.formatBytes(0, Locale.US))
        assertEquals("999 B", LocaleFormatting.formatBytes(999, Locale.US))
        assertEquals("1 kB", LocaleFormatting.formatBytes(1_000, Locale.US))
    }

    // ── date and time ────────────────────────────────────────────────────────

    @Test
    fun `writes the date in the pattern of the locale`() {
        assertEquals(
            "24.10.2026",
            LocaleFormatting.formatDate(october24NoonUtc, Locale.GERMAN, utc)
        )
        assertEquals(
            "Oct 24, 2026",
            LocaleFormatting.formatDate(october24NoonUtc, Locale.US, utc)
        )
    }

    @Test
    fun `writes the time in the pattern of the locale`() {
        assertEquals(
            "14:30",
            LocaleFormatting.formatTime(october24HalfPastTwoUtc, Locale.GERMAN, utc)
        )
        assertEquals(
            "2:30 PM",
            LocaleFormatting.formatTime(october24HalfPastTwoUtc, Locale.US, utc)
        )
    }

    // ── negative cases ───────────────────────────────────────────────────────

    @Test
    fun `falls back to a fixed locale when the caller passes none`() {
        val fallback = LocaleFormatting.formatDecimal(12.5, null)

        assertEquals("12.5", fallback)
        assertEquals(
            "the fallback must not depend on the machine that runs the test",
            LocaleFormatting.formatDecimal(12.5, LocaleFormatting.FALLBACK_LOCALE),
            fallback
        )
        assertEquals("01:05", LocaleFormatting.formatDuration(65_000, null))
        assertEquals("12.5 MB", LocaleFormatting.formatBytes(12_500_000, null))
        assertEquals(
            "Oct 24, 2026",
            LocaleFormatting.formatDate(october24NoonUtc, null, utc)
        )
    }

    @Test
    fun `answers an empty input with a defined text`() {
        assertEquals("00:00", LocaleFormatting.formatDuration(0, Locale.US))
        assertEquals("0 B", LocaleFormatting.formatBytes(0, Locale.US))
        assertEquals("0", LocaleFormatting.formatNumber(0, Locale.US))
        assertEquals(
            "Jan 1, 1970",
            LocaleFormatting.formatDate(0, Locale.US, utc)
        )
    }

    @Test
    fun `clamps a negative duration to zero instead of writing a negative time`() {
        assertEquals("00:00", LocaleFormatting.formatDuration(-5_000, Locale.US))
    }
}
