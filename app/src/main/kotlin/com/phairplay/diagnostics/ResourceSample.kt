package com.phairplay.diagnostics

/**
 * ResourceSample — one measurement of the own memory and CPU usage.
 *
 * WHAT: the data model of a measurement of the app process: the resident memory in
 * bytes, the CPU load in percent and the point in time the measurement was taken.
 *
 * WHY both values are nullable: the rule of the project is that "not measured"
 * stays "not measured". A value that could not be read is `null` — never 0 and
 * never an estimate. A report that shows "0 % CPU" for a value it could not
 * measure claims that the app is idle, and a report that shows "0 B" claims that
 * the app uses no memory at all. Both are wrong, and the owner who reads the
 * report while the 30-minute test runs would draw the wrong conclusion from them.
 *
 * The two values are independent: the memory can be read while the CPU load
 * cannot be calculated, and the other way round. A measurement that carries only
 * one of the two is a normal measurement, not a failed one.
 *
 * Example:
 *   val sample = ResourceSample(
 *       memoryBytes = 88_200_000L,
 *       cpuPercent = 3.4,
 *       measuredAtMillis = 1_762_000_000_000L
 *   )
 */
data class ResourceSample(
    /** The resident memory of the app process in bytes, or null when it could not be read. */
    val memoryBytes: Long?,
    /** The CPU load of the app process in percent, or null when it could not be measured. */
    val cpuPercent: Double?,
    /** The point in time of the measurement, in milliseconds since the epoch. */
    val measuredAtMillis: Long
)
