package com.phairplay.diagnostics

import java.io.File

/**
 * ProcStatSource — the source of the process statistics of the own process.
 *
 * WHAT: one method that answers with the line of `/proc/self/stat` that holds
 * the CPU counters, or with null when the line cannot be read.
 *
 * WHY an interface: `/proc/self/stat` is a file of the operating system, and a
 * file cannot be handed into a test. The interface lets the test answer with a
 * line it wrote itself — including a line with a broken command name, which is
 * the case a parser has to survive.
 *
 * Example:
 *   val line = LinuxProcStat.readStatLine()
 */
fun interface ProcStatSource {

    /**
     * Reads the line of the process statistics.
     *
     * @return the line, or null when it cannot be read.
     */
    fun readStatLine(): String?
}

/**
 * LinuxProcStat — reads the process statistics of the own process from `/proc`.
 *
 * WHAT: reads the first line of `/proc/self/stat`, the line that holds the CPU
 * counters of the process.
 *
 * WHY the file is read defensively: `/proc` does not exist on every system, and
 * a process may not be allowed to read its own statistics. Both cases are
 * "not measured" — never a number that was made up.
 *
 * Example:
 *   val line = LinuxProcStat.readStatLine()
 */
object LinuxProcStat : ProcStatSource {

    /** The path of the statistics of the own process. */
    private const val PROC_SELF_STAT = "/proc/self/stat"

    /**
     * Reads the line of the process statistics.
     *
     * @return the line, or null when the file cannot be read.
     */
    override fun readStatLine(): String? = runCatching {
        val file = File(PROC_SELF_STAT)
        if (!file.canRead()) null else file.useLines { lines -> lines.firstOrNull() }
    }.getOrNull()
}

/**
 * ClockTicksPerSecond — how many clock ticks a second has on this system.
 *
 * WHAT: the rate of the clock ticks of `/proc/self/stat` — the unit the CPU
 * counters are written in.
 *
 * WHY the system has to be asked: the counters are ticks and not milliseconds,
 * and the number of ticks per second is a property of the kernel and not of
 * PhairPlay. It is 100 on the systems the app runs on, but a hard-coded 100
 * would be a guess dressed up as a fact. The system is therefore asked with
 * `getconf CLK_TCK`, and the value that is used when the system does not answer
 * is the documented fallback.
 *
 * WHY the fallback is 100 and not an error: the counter is only used to turn
 * ticks into a percentage, and a wrong rate would turn a correct measurement
 * into a wrong number. The fallback is the rate of every Linux and Android
 * kernel the app can run on, so it is a fact about the target systems and not
 * an estimate of the measurement.
 *
 * Example:
 *   val ticksPerSecond = ClockTicksPerSecond.detected
 */
object ClockTicksPerSecond {

    /** The rate used when the system does not answer — the rate of the target kernels. */
    const val FALLBACK_TICKS_PER_SECOND = 100L

    /** The rate of this system — the system is asked once per process. */
    val detected: Long by lazy { detect() }

    /**
     * Asks the system for its clock tick rate.
     *
     * @return the number of ticks per second, or the fallback when the system
     *   does not answer.
     */
    fun detect(): Long = runCatching {
        val process = ProcessBuilder("getconf", "CLK_TCK")
            .redirectErrorStream(true)
            .start()
        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            // The output has been read — the command is not needed any more. This
            // also keeps a command that does not end on its own from living on.
            process.destroy()
        }
        output.trim().toLongOrNull()
    }.getOrNull()?.takeIf { it > 0L } ?: FALLBACK_TICKS_PER_SECOND
}

/**
 * ProcessMemoryProbe — measures the memory and the CPU load of the own process.
 *
 * WHAT: one method that measures both values and hands them out as a
 * [ResourceSample]. The memory comes from a [MemoryReader], the CPU load from
 * the counters of `/proc/self/stat`.
 *
 * WHY the CPU load needs two readings: the counters of `/proc/self/stat` only
 * say how much CPU time the process has used *so far*. A load is a rate, and a
 * rate needs a distance. The probe reads the counters, waits a short moment and
 * reads them again; the load is the share of the ticks that were burned in that
 * window. A single reading would be the average since the process started — a
 * number that says nothing about the moment the owner is looking at.
 *
 * WHY the window is short: the owner reads the report while the 30-minute test
 * runs. A measurement that takes a second is still a measurement; one that takes
 * a minute would be a test the owner cannot repeat while the stream runs. The
 * window is therefore a fraction of a second — and the number it produces is an
 * *instantaneous* load, not the average of the whole test. That limitation is
 * part of the measurement and is written down in the report of the milestone.
 *
 * WHY a value that cannot be read is null: the probe reports what it measured.
 * A missing file, a missing framework call or a window of zero length produce
 * null, and the report says "not measured" instead of a number.
 *
 * Example:
 *   val sample = ProcessMemoryProbe().measure()
 *   sample.memoryBytes   // 88200000 or null
 *   sample.cpuPercent    // 3.4 or null
 */
class ProcessMemoryProbe(
    /** Where the memory of the process comes from. */
    private val memoryReader: MemoryReader = AndroidMemoryReader(),
    /** Where the counters of the process come from. */
    private val procStatSource: ProcStatSource = LinuxProcStat,
    /** The clock that says how much real time has passed. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** The wait between the two readings of the counters. */
    private val sleeper: (Long) -> Unit = { millis -> sleepQuietly(millis) },
    /** How many clock ticks a second has on this system. */
    private val ticksPerSecond: Long = ClockTicksPerSecond.detected,
    /** How far apart the two readings of the counters are. */
    private val sampleIntervalMillis: Long = DEFAULT_SAMPLE_INTERVAL_MILLIS
) {

    /**
     * Measures the memory and the CPU load of the own process.
     *
     * @return the measurement; a value that could not be read is null.
     */
    fun measure(): ResourceSample {
        val memoryBytes = memoryReader.readMemoryBytes()
        val before = readReading()
        if (before != null) sleeper(sampleIntervalMillis)
        val after = readReading()
        return ResourceSample(
            memoryBytes = memoryBytes,
            cpuPercent = CpuUsage.cpuPercentBetween(before, after, ticksPerSecond),
            measuredAtMillis = clock()
        )
    }

    /**
     * Reads the counters of the process and puts the wall-clock time on them.
     *
     * @return the reading, or null when the line cannot be read or parsed.
     */
    private fun readReading(): CpuReading? {
        val line = procStatSource.readStatLine() ?: return null
        return CpuUsage.parseProcStat(line, clock())
    }

    companion object {
        /** The distance between the two readings of the counters, in milliseconds. */
        const val DEFAULT_SAMPLE_INTERVAL_MILLIS = 1_000L

        /**
         * Waits without letting an interruption escape.
         *
         * WHY the interruption is kept: a measurement that is cancelled has to
         * stay cancelled — swallowing the flag would let the thread continue to
         * work although it was asked to stop.
         *
         * @param millis the time to wait.
         */
        private fun sleepQuietly(millis: Long) {
            try {
                Thread.sleep(millis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
