package com.phairplay.diagnostics

import android.os.Debug

/**
 * MemoryReader — the source of the resident memory of the app process.
 *
 * WHAT: one method that answers with the resident memory of the own process in
 * bytes, or with null when the value cannot be read.
 *
 * WHY an interface and not the framework call directly: the memory of the process
 * can only be read with the Android framework (`Debug.getMemoryInfo()`), and the
 * framework is not there on a plain JVM. The interface keeps the measuring logic
 * out of Android, so the whole path can be proven by a test that needs no
 * device: the test hands in a reader that answers with a fixed value.
 *
 * WHY null and not 0: "0 bytes" would be a measurement — of an empty process.
 * "Not measurable" is the honest answer, and the report turns it into "not
 * measured" instead of into a green tick for a value nobody measured.
 *
 * Example:
 *   val reader: MemoryReader = AndroidMemoryReader()
 *   val bytes = reader.readMemoryBytes()   // 88200000 or null
 */
interface MemoryReader {

    /**
     * Reads the resident memory of the own process.
     *
     * @return the number of bytes, or null when the value cannot be read.
     */
    fun readMemoryBytes(): Long?
}

/**
 * AndroidMemoryReader — reads the memory of the process with the Android framework.
 *
 * WHAT: asks `Debug.getMemoryInfo()` for the proportional set size of the own
 * process and returns it in bytes.
 *
 * WHY the proportional set size (PSS): PSS divides the memory that the process
 * shares with other processes — the shared libraries of the system — between the
 * processes that use it. It is therefore the number that says how much memory
 * this app really costs the television, and that is the number a memory budget
 * is about.
 *
 * WHY a failure is a null and not a zero: the framework call does not exist
 * outside a device. A test that runs on a plain JVM therefore gets null, and the
 * report says "not measured". That is the correct answer on a JVM and it cannot
 * happen on a device — the framework is always there.
 *
 * Example:
 *   val bytes = AndroidMemoryReader().readMemoryBytes()
 */
class AndroidMemoryReader : MemoryReader {

    /**
     * Reads the resident memory of the own process.
     *
     * @return the number of bytes, or null when the framework does not answer.
     */
    override fun readMemoryBytes(): Long? = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val totalPssKibibytes = info.totalPss
        if (totalPssKibibytes > 0) totalPssKibibytes.toLong() * BYTES_PER_KIBIBYTE else null
    }.getOrNull()

    companion object {
        /** The number of bytes in a kibibyte — the unit of `Debug.MemoryInfo.totalPss`. */
        private const val BYTES_PER_KIBIBYTE = 1024L
    }
}
