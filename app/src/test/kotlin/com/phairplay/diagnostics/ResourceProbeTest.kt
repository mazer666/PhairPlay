package com.phairplay.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ResourceProbeTest — proves that the measurement of the own resources is honest.
 *
 * WHY these tests exist: the CPU load is the one number of the report that is
 * easy to get wrong, and a wrong number looks exactly like a right one. A
 * missing divisor, a counter that is read at the wrong place of the line or a
 * rate that is guessed produces a plausible percentage — and the owner who
 * reads it draws a conclusion from a number that was never measured.
 *
 * WHAT WE TEST:
 * - the load is the share of the ticks that were burned in the window,
 * - the kernel time counts towards the load,
 * - data that cannot be used produces no number at all,
 * - the process statistics are parsed behind the command name, even when the
 *   name contains spaces and brackets,
 * - the probe reports the memory of the reader and says "not measured" when the
 *   counters cannot be read.
 *
 * The test runs on a plain JVM: it needs neither a television nor a stream, and
 * every number it checks is a number the test itself wrote down.
 */
class ResourceProbeTest {

    // ── The calculation of the load ─────────────────────────────────────────

    @Test
    fun `the load is the share of the ticks in the measured window`() {
        val before = CpuReading(utimeTicks = 0L, stimeTicks = 0L, wallMillis = 0L)
        val after = CpuReading(utimeTicks = 50L, stimeTicks = 0L, wallMillis = 1_000L)

        val percent = CpuUsage.cpuPercentBetween(before, after, ticksPerSecond = 100L)

        assertNotNull("the load could not be calculated", percent)
        assertEquals("the load is wrong", 50.0, requireNotNull(percent), 0.0001)
    }

    @Test
    fun `the kernel time counts towards the load`() {
        val before = CpuReading(utimeTicks = 0L, stimeTicks = 0L, wallMillis = 0L)
        val after = CpuReading(utimeTicks = 30L, stimeTicks = 20L, wallMillis = 1_000L)

        val percent = CpuUsage.cpuPercentBetween(before, after, ticksPerSecond = 100L)

        assertEquals("the load is wrong", 50.0, requireNotNull(percent), 0.0001)
    }

    @Test
    fun `the load of a half second window is twice as high`() {
        val before = CpuReading(utimeTicks = 0L, stimeTicks = 0L, wallMillis = 0L)
        val after = CpuReading(utimeTicks = 25L, stimeTicks = 0L, wallMillis = 500L)

        val percent = CpuUsage.cpuPercentBetween(before, after, ticksPerSecond = 100L)

        assertEquals("the load is wrong", 50.0, requireNotNull(percent), 0.0001)
    }

    @Test
    fun `no load is calculated when a counter goes backwards`() {
        val before = CpuReading(utimeTicks = 100L, stimeTicks = 0L, wallMillis = 0L)
        val after = CpuReading(utimeTicks = 40L, stimeTicks = 0L, wallMillis = 1_000L)

        assertNull(
            "a counter that goes backwards must not produce a load",
            CpuUsage.cpuPercentBetween(before, after, ticksPerSecond = 100L)
        )
    }

    @Test
    fun `no load is calculated when the window is empty`() {
        val before = CpuReading(utimeTicks = 0L, stimeTicks = 0L, wallMillis = 1_000L)
        val after = CpuReading(utimeTicks = 50L, stimeTicks = 0L, wallMillis = 1_000L)

        assertNull(
            "a window of zero length must not produce a load",
            CpuUsage.cpuPercentBetween(before, after, ticksPerSecond = 100L)
        )
    }

    @Test
    fun `no load is calculated when a reading is missing`() {
        val reading = CpuReading(utimeTicks = 0L, stimeTicks = 0L, wallMillis = 0L)

        assertNull(
            "a missing first reading must not produce a load",
            CpuUsage.cpuPercentBetween(null, reading, ticksPerSecond = 100L)
        )
        assertNull(
            "a missing second reading must not produce a load",
            CpuUsage.cpuPercentBetween(reading, null, ticksPerSecond = 100L)
        )
    }

    @Test
    fun `no load is calculated when the tick rate is not known`() {
        val before = CpuReading(utimeTicks = 0L, stimeTicks = 0L, wallMillis = 0L)
        val after = CpuReading(utimeTicks = 50L, stimeTicks = 0L, wallMillis = 1_000L)

        assertNull(
            "a tick rate of zero must not produce a load",
            CpuUsage.cpuPercentBetween(before, after, ticksPerSecond = 0L)
        )
        assertNull(
            "a negative tick rate must not produce a load",
            CpuUsage.cpuPercentBetween(before, after, ticksPerSecond = -100L)
        )
    }

    // ── The parser of the process statistics ────────────────────────────────

    @Test
    fun `the counters are read behind a command name with a space`() {
        val reading = CpuUsage.parseProcStat(STAT_LINE, wallMillis = 1_000L)

        assertNotNull("the line was not parsed", reading)
        assertEquals("the user time is wrong", 12L, requireNotNull(reading).utimeTicks)
        assertEquals("the kernel time is wrong", 34L, requireNotNull(reading).stimeTicks)
    }

    @Test
    fun `the counters are read behind a command name with a bracket`() {
        val reading = CpuUsage.parseProcStat(STAT_LINE_ODD_NAME, wallMillis = 1_000L)

        assertNotNull("the line was not parsed", reading)
        assertEquals("the user time is wrong", 12L, requireNotNull(reading).utimeTicks)
        assertEquals("the kernel time is wrong", 34L, requireNotNull(reading).stimeTicks)
    }

    @Test
    fun `a line without a command name is not parsed`() {
        assertNull(
            "a line without a bracket must not be parsed",
            CpuUsage.parseProcStat("1234 S 1 2 3", wallMillis = 1_000L)
        )
    }

    @Test
    fun `a line without the counters is not parsed`() {
        assertNull(
            "a line that is too short must not be parsed",
            CpuUsage.parseProcStat("1234 (My App) S 1234 1234 0 0", wallMillis = 1_000L)
        )
    }

    @Test
    fun `a line with a broken counter is not parsed`() {
        assertNull(
            "a counter that is not a number must not be parsed",
            CpuUsage.parseProcStat(STAT_LINE_BROKEN, wallMillis = 1_000L)
        )
    }

    // ── The probe ───────────────────────────────────────────────────────────

    @Test
    fun `the probe reports the memory of the reader and the load of the window`() {
        var now = 10_000L
        val probe = ProcessMemoryProbe(
            memoryReader = FixedMemoryReader(84_200_000L),
            procStatSource = SequenceProcStatSource(listOf(STAT_LINE, STAT_LINE_LATER)),
            clock = { now },
            sleeper = { millis -> now += millis },
            ticksPerSecond = 100L,
            sampleIntervalMillis = 1_000L
        )

        val sample = probe.measure()

        assertEquals("the memory is wrong", 84_200_000L, sample.memoryBytes)
        assertEquals("the load is wrong", 50.0, requireNotNull(sample.cpuPercent), 0.0001)
        assertEquals("the time of the measurement is wrong", 11_000L, sample.measuredAtMillis)
    }

    @Test
    fun `the probe reports the load as not measured when the counters cannot be read`() {
        val probe = ProcessMemoryProbe(
            memoryReader = FixedMemoryReader(84_200_000L),
            procStatSource = SequenceProcStatSource(emptyList()),
            clock = { 10_000L },
            sleeper = { },
            ticksPerSecond = 100L,
            sampleIntervalMillis = 1_000L
        )

        val sample = probe.measure()

        assertEquals("the memory is wrong", 84_200_000L, sample.memoryBytes)
        assertNull("the load must not be measured", sample.cpuPercent)
    }

    @Test
    fun `the probe reports the memory as not measured when it cannot be read`() {
        val probe = ProcessMemoryProbe(
            memoryReader = FixedMemoryReader(null),
            procStatSource = SequenceProcStatSource(listOf(STAT_LINE, STAT_LINE_LATER)),
            clock = { 10_000L },
            sleeper = { },
            ticksPerSecond = 100L,
            sampleIntervalMillis = 1_000L
        )

        val sample = probe.measure()

        assertNull("the memory must not be measured", sample.memoryBytes)
    }

    companion object {
        /** A line of `/proc/self/stat` with a command name that contains a space. */
        private const val STAT_LINE =
            "1234 (My App) S 1234 1234 0 0 -1 4194304 100 0 0 0 12 34 0 0 20 0 1 0 100 12345 " +
                "456 789 4096 18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"

        /** The same line with a command name that contains a bracket. */
        private const val STAT_LINE_ODD_NAME =
            "1234 ((a) b) S 1234 1234 0 0 -1 4194304 100 0 0 0 12 34 0 0 20 0 1 0 100 12345 " +
                "456 789 4096 18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"

        /** The same line 50 ticks later. */
        private const val STAT_LINE_LATER =
            "1234 (My App) S 1234 1234 0 0 -1 4194304 100 0 0 0 62 34 0 0 20 0 1 0 100 12345 " +
                "456 789 4096 18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"

        /** The same line with a counter that is not a number. */
        private const val STAT_LINE_BROKEN =
            "1234 (My App) S 1234 1234 0 0 -1 4194304 100 0 0 0 xx 34 0 0 20 0 1 0 100 12345 " +
                "456 789 4096 18446744073709551615 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
    }
}
