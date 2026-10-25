package com.phairplay.util

import java.text.DateFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * LocaleFormatting — the locale-aware text for the numbers, sizes, durations and timestamps
 * that PhairPlay shows on the screen.
 *
 * WHAT: Pure functions that turn a value into the string a reader expects in a given [Locale]:
 * a decimal number, a byte size, a duration, a date and a time.
 *
 * WHY: FR-41 requires that "date/time formats, number formats and text direction respect the
 * device locale". A German television expects "12,5 MB" and "24.10.2026", an American one
 * expects "12.5 MB" and "Oct 24, 2026", and an Arabic one expects Arabic-Indic digits
 * (U+0660 … U+0669) with the Arabic decimal separator (U+066B). `String.format("%d")` and
 * `Double.toString()` always produce the ASCII form with an English decimal point, so they
 * cannot be used for a screen.
 *
 * HOW: Every function takes the [Locale] as a parameter and never falls back to
 * [Locale.getDefault] on its own — a formatting decision that depends on the machine that
 * runs the code can neither be tested nor reproduced. The caller passes the locale of the
 * app configuration (on Android 13+ the user can pick a per-app language, so the
 * configuration locale is not necessarily the device locale):
 *
 *   val locale = context.resources.configuration.locales[0]   // API 24+
 *
 * The class uses nothing but `java.text`, so the JVM test suite can exercise it without a
 * device; `LocaleFormattingTest` is the proof.
 *
 * Example:
 *   LocaleFormatting.formatDecimal(12.5, Locale.GERMAN)       // "12,5"
 *   LocaleFormatting.formatDecimal(12.5, Locale.US)           // "12.5"
 *   LocaleFormatting.formatBytes(12_500_000, Locale.GERMAN)   // "12,5 MB"
 *   LocaleFormatting.formatDuration(3_725_000, Locale.US)     // "01:02:05"
 *   LocaleFormatting.formatDate(epochMillis, Locale.GERMAN)    // "24.10.2026"
 */
internal object LocaleFormatting {

    /**
     * The locale used when a caller passes none.
     *
     * WHY a fixed fallback and not [Locale.getDefault]: a missing locale is a programming
     * error, and the answer to it must be the same on every machine. English with a 12-hour
     * clock and the MM/dd/yyyy order is the one combination every JDK and every Android
     * release renders identically.
     */
    val FALLBACK_LOCALE: Locale = Locale.US

    /** The SI units of [formatBytes], from the smallest to the largest. */
    private val BYTE_UNITS = listOf("B", "kB", "MB", "GB", "TB")

    /** The step between two byte units — the decimal (SI) scale, not the binary one. */
    private const val BYTE_UNIT_STEP = 1000.0

    /**
     * Formats an integer with the digits and the group separator of [locale].
     *
     * @param value  the number to write.
     * @param locale the locale that decides digits and group separator; null falls back to
     *   [FALLBACK_LOCALE].
     * @return "1.234" in German, "1,234" in US English, "١٬٢٣٤" in Arabic.
     */
    fun formatNumber(value: Int, locale: Locale?): String =
        NumberFormat.getNumberInstance(resolve(locale)).format(value.toLong())

    /**
     * Formats an integer with the digits and the group separator of [locale].
     *
     * @param value  the number to write.
     * @param locale the locale that decides digits and group separator; null falls back to
     *   [FALLBACK_LOCALE].
     * @return "1.234" in German, "1,234" in US English, "١٬٢٣٤" in Arabic.
     */
    fun formatNumber(value: Long, locale: Locale?): String =
        NumberFormat.getNumberInstance(resolve(locale)).format(value)

    /**
     * Formats a decimal number with at most [maxFractionDigits] fraction digits.
     *
     * The decimal separator and the digits come from the locale, so the same value reads
     * "12,5" in German, "12.5" in US English and "١٢٫٥" in Arabic.
     *
     * @param value             the number to write.
     * @param locale            the locale that decides digits and decimal separator; null
     *   falls back to [FALLBACK_LOCALE].
     * @param maxFractionDigits how many fraction digits may be written; the value is
     *   rounded to it (half-even, the rule of [NumberFormat]).
     * @return the formatted number, without a fraction part when the value is a whole number.
     */
    fun formatDecimal(value: Double, locale: Locale?, maxFractionDigits: Int = 1): String {
        val format = NumberFormat.getNumberInstance(resolve(locale))
        format.maximumFractionDigits = maxFractionDigits.coerceAtLeast(0)
        format.minimumFractionDigits = 0
        return format.format(value)
    }

    /**
     * Formats a duration as `mm:ss` or as `hh:mm:ss` from one hour on.
     *
     * The rule:
     *
     * - the input is milliseconds, the seconds are truncated (never rounded up),
     * - below one hour the result is `mm:ss` — "00:00" for zero, "01:05" for 65 seconds,
     * - from one hour on the result is `hh:mm:ss` — "01:02:05" for 3725 seconds,
     * - every field is padded with the zero digit of the locale to two digits, so an Arabic
     *   duration reads "٠١:٠٥" and the colon stays the only ASCII character,
     * - a negative duration is clamped to zero — a duration before the start is not a
     *   value a reader can make sense of.
     *
     * @param durationMillis the length of the stream or of the recording.
     * @param locale         the locale that decides the digits; null falls back to
     *   [FALLBACK_LOCALE].
     * @return the duration as `mm:ss` or `hh:mm:ss`.
     */
    fun formatDuration(durationMillis: Long, locale: Locale?): String {
        val effective = resolve(locale)
        val totalSeconds = if (durationMillis < 0L) 0L else durationMillis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "${pad(hours, 2, effective)}:${pad(minutes, 2, effective)}:${pad(seconds, 2, effective)}"
        } else {
            "${pad(minutes, 2, effective)}:${pad(seconds, 2, effective)}"
        }
    }

    /**
     * Formats a byte count with a decimal (SI) unit: "B", "kB", "MB", "GB", "TB".
     *
     * The rule:
     *
     * - below 1000 bytes the value is written as a whole number with the unit "B",
     * - from 1000 bytes on the value is divided by 1000 per unit and written with at most
     *   one fraction digit: 12 500 000 bytes become "12,5 MB" in German and "12.5 MB" in
     *   US English,
     * - the unit is written in Latin letters in every locale — "kB" and "MB" are read the
     *   same way in every language the app ships in.
     *
     * @param bytes  the number of bytes.
     * @param locale the locale that decides digits and decimal separator; null falls back to
     *   [FALLBACK_LOCALE].
     * @return the size with its unit, for example "12,5 MB".
     */
    fun formatBytes(bytes: Long, locale: Locale?): String {
        val effective = resolve(locale)
        if (bytes < BYTE_UNIT_STEP.toLong()) {
            return "${formatNumber(bytes, effective)} ${BYTE_UNITS[0]}"
        }
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= BYTE_UNIT_STEP && unitIndex < BYTE_UNITS.lastIndex) {
            value /= BYTE_UNIT_STEP
            unitIndex++
        }
        return "${formatDecimal(value, effective, 1)} ${BYTE_UNITS[unitIndex]}"
    }

    /**
     * Formats a point in time as a date in the medium form of the locale.
     *
     * The pattern comes from the locale, not from the code: "24.10.2026" in German,
     * "Oct 24, 2026" in US English. A hard-coded "yyyy-MM-dd" would show an American date
     * to a German reader.
     *
     * @param epochMillis the point in time, in milliseconds since the epoch.
     * @param locale      the locale that decides the pattern and the month names; null falls
     *   back to [FALLBACK_LOCALE].
     * @param timeZone    the zone the date is written in; the default is the zone of the
     *   device, which is the zone the reader is sitting in.
     * @return the date as the locale writes it.
     */
    fun formatDate(
        epochMillis: Long,
        locale: Locale?,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String = dateFormat(DateFormat.MEDIUM, locale, timeZone).format(Date(epochMillis))

    /**
     * Formats a point in time as a time in the short form of the locale.
     *
     * @param epochMillis the point in time, in milliseconds since the epoch.
     * @param locale      the locale that decides the pattern and the 12-/24-hour clock; null
     *   falls back to [FALLBACK_LOCALE].
     * @param timeZone    the zone the time is written in; the default is the zone of the
     *   device, which is the zone the reader is sitting in.
     * @return the time as the locale writes it, for example "14:30" or "2:30 PM".
     */
    fun formatTime(
        epochMillis: Long,
        locale: Locale?,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String = timeFormat(DateFormat.SHORT, locale, timeZone).format(Date(epochMillis))

    /**
     * Formats a point in time as a date and a time in the medium/short form of the locale.
     *
     * @param epochMillis the point in time, in milliseconds since the epoch.
     * @param locale      the locale that decides the pattern and the month names; null falls
     *   back to [FALLBACK_LOCALE].
     * @param timeZone    the zone the timestamp is written in; the default is the zone of the
     *   device, which is the zone the reader is sitting in.
     * @return the timestamp as the locale writes it.
     */
    fun formatDateTime(
        epochMillis: Long,
        locale: Locale?,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String = dateTimeFormat(DateFormat.MEDIUM, DateFormat.SHORT, locale, timeZone)
        .format(Date(epochMillis))

    /** The locale to work with — the given one, or the fixed fallback. */
    private fun resolve(locale: Locale?): Locale = locale ?: FALLBACK_LOCALE

    /**
     * Writes a whole number with the digits of the locale, padded with the locale's zero
     * digit on the left.
     *
     * WHY the locale's zero digit and not '0': in an Arabic locale the padding would be an
     * ASCII zero in front of Arabic-Indic digits, which looks like a typo.
     */
    private fun pad(value: Long, width: Int, locale: Locale): String {
        val format = NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }
        val zeroDigit = DecimalFormatSymbols.getInstance(locale).zeroDigit
        return format.format(value).padStart(width, zeroDigit)
    }

    /** A date formatter for the locale, pinned to the given zone. */
    private fun dateFormat(style: Int, locale: Locale?, timeZone: TimeZone): DateFormat =
        DateFormat.getDateInstance(style, resolve(locale)).apply { this.timeZone = timeZone }

    /** A time formatter for the locale, pinned to the given zone. */
    private fun timeFormat(style: Int, locale: Locale?, timeZone: TimeZone): DateFormat =
        DateFormat.getTimeInstance(style, resolve(locale)).apply { this.timeZone = timeZone }

    /** A date-and-time formatter for the locale, pinned to the given zone. */
    private fun dateTimeFormat(
        dateStyle: Int,
        timeStyle: Int,
        locale: Locale?,
        timeZone: TimeZone
    ): DateFormat = DateFormat.getDateTimeInstance(dateStyle, timeStyle, resolve(locale))
        .apply { this.timeZone = timeZone }
}
