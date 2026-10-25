package com.phairplay.diagnostics

/**
 * FixedMemoryReader — a [MemoryReader] that always answers with the same value.
 *
 * WHY: the memory of the process can only be read with the Android framework,
 * and the framework is not there on a plain JVM. The double lets the test prove
 * the whole path of the RAM measurement without a device — including the path
 * that leads to "not measured", which is answered with null.
 *
 * Example:
 *   val reader = FixedMemoryReader(84_200_000L)
 */
class FixedMemoryReader(
    /** The value the reader answers with — null for "not measurable". */
    private val bytes: Long?
) : MemoryReader {

    /**
     * Answers with the fixed value.
     *
     * @return the fixed number of bytes, or null.
     */
    override fun readMemoryBytes(): Long? = bytes
}
