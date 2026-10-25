package com.phairplay.diagnostics

/**
 * SequenceProcStatSource — a [ProcStatSource] that hands out one line per call.
 *
 * WHY: the CPU load is calculated from two readings, and the second reading has
 * to show a different counter than the first one. A double that answers with a
 * fixed line cannot show that; one that answers with a list can.
 *
 * Example:
 *   val source = SequenceProcStatSource(listOf(lineBefore, lineAfter))
 */
class SequenceProcStatSource(
    /** The lines the source hands out, one per call. */
    private val lines: List<String>
) : ProcStatSource {

    /** The number of lines that were handed out already. */
    private var index = 0

    /**
     * Answers with the next line of the list.
     *
     * @return the line, or null when the list is empty.
     */
    override fun readStatLine(): String? = lines.getOrNull(index++)
}
