package com.phairplay.diagnostics

/**
 * CpuReading — the CPU time of the process at one point in time.
 *
 * WHAT: the two counters of `/proc/self/stat` (`utime` and `stime`) in clock
 * ticks, together with the wall-clock time of the reading.
 *
 * WHY the wall clock belongs to the reading: the CPU load is not a counter but a
 * *rate* — ticks per second. To turn two counters into a rate, the distance
 * between the two readings in real time is needed, and it has to be taken from
 * the same reading instead of being estimated.
 *
 * Example:
 *   val reading = CpuReading(utimeTicks = 120, stimeTicks = 30, wallMillis = 1_000L)
 */
data class CpuReading(
    /** The user-mode time of the process in clock ticks. */
    val utimeTicks: Long,
    /** The kernel-mode time of the process in clock ticks. */
    val stimeTicks: Long,
    /** The wall-clock time of the reading, in milliseconds since the epoch. */
    val wallMillis: Long
) {
    /** The sum of the user-mode and the kernel-mode time — the CPU time the process burned. */
    val totalTicks: Long get() = utimeTicks + stimeTicks
}

/**
 * CpuUsage — turns the counters of `/proc/self/stat` into a CPU load in percent.
 *
 * WHAT: two pure functions — one parses a line of `/proc/self/stat`, the other
 * calculates the load between two readings.
 *
 * WHY the calculation is pure: the CPU load is the one number of the diagnostics
 * report that is easy to get wrong. A wrong divisor or a forgotten normalisation
 * produces a plausible number that is simply false, and a false number on the
 * screen is worse than no number. A pure function can be proven by a test that
 * states the expected value — without a device, without a running stream and
 * without a sleep.
 *
 * WHY the parser looks for the last bracket: the second field of
 * `/proc/self/stat` is the command name of the process, written in brackets, and
 * it may contain spaces and brackets itself (`(My App)`, `((a) b)`). A parser
 * that splits the whole line at the whitespace counts the words of the command
 * name as fields of the process and reads the wrong numbers — silently. The
 * parser therefore takes the text behind the *last* bracket and counts the
 * fields from there: the first field behind it is the state of the process, and
 * `utime` and `stime` are the twelfth and the thirteenth behind it.
 *
 * Example:
 *   val reading = CpuUsage.parseProcStat(line, wallMillis = 1_000L)
 *   CpuUsage.cpuPercentBetween(before, after, ticksPerSecond = 100L)   // 50.0
 */
object CpuUsage {

    /**
     * The index of `utime` in the field list behind the command name.
     *
     * WHY the index and not the field number: the field numbers of
     * `/proc/self/stat` count the command name as one field. Behind the closing
     * bracket the list starts with the state, which is the third field of the
     * line — so `utime` (the fourteenth field of the line) is the twelfth behind
     * the bracket.
     */
    private const val UTIME_INDEX = 11

    /** The index of `stime` in the field list behind the command name — the field behind `utime`. */
    private const val STIME_INDEX = 12

    /** The milliseconds in a second — the unit of the wall clock against the unit of the ticks. */
    private const val MILLIS_PER_SECOND = 1000.0

    /** The factor between a share and a percentage. */
    private const val PERCENT = 100.0

    /** The whitespace between two fields of the process statistics. */
    private val WHITESPACE = Regex("\\s+")

    /**
     * Parses one line of `/proc/self/stat`.
     *
     * @param line       the line as the kernel writes it.
     * @param wallMillis the wall-clock time of the reading, in milliseconds since the epoch.
     * @return the reading, or null when the line does not hold the two counters.
     */
    fun parseProcStat(line: String, wallMillis: Long): CpuReading? {
        val closeBracket = line.lastIndexOf(')')
        if (closeBracket < 0) return null
        val fields = line.substring(closeBracket + 1).trim().split(WHITESPACE)
        if (fields.size <= STIME_INDEX) return null
        val utime = fields[UTIME_INDEX].toLongOrNull() ?: return null
        val stime = fields[STIME_INDEX].toLongOrNull() ?: return null
        if (utime < 0L || stime < 0L) return null
        return CpuReading(utimeTicks = utime, stimeTicks = stime, wallMillis = wallMillis)
    }

    /**
     * The CPU load between two readings, in percent.
     *
     * The formula is the share of the clock ticks that were burned in the window
     * between the two readings:
     *
     *     cpuPercent = deltaTicks / (deltaWallMillis / 1000 * ticksPerSecond) * 100
     *
     * WHY null and not a number: the data can be unusable — a counter that goes
     * backwards (the reading was not taken from this process), a window of zero
     * length (the two readings have the same timestamp), a tick rate that is not
     * known, or a reading that is missing. Every one of these cases has no
     * answer, and a load that is made up would be a lie on the screen.
     *
     * @param before         the first reading, or null when it could not be taken.
     * @param after          the second reading, or null when it could not be taken.
     * @param ticksPerSecond how many clock ticks a second has on this system.
     * @return the load in percent, or null when the data does not allow a load.
     */
    fun cpuPercentBetween(before: CpuReading?, after: CpuReading?, ticksPerSecond: Long): Double? {
        if (before == null || after == null) return null
        if (ticksPerSecond <= 0L) return null
        val tickDelta = after.totalTicks - before.totalTicks
        if (tickDelta < 0L) return null
        val wallDeltaMillis = after.wallMillis - before.wallMillis
        if (wallDeltaMillis <= 0L) return null
        val elapsedSeconds = wallDeltaMillis / MILLIS_PER_SECOND
        val percent = tickDelta / (elapsedSeconds * ticksPerSecond) * PERCENT
        return if (percent.isFinite()) percent else null
    }
}
