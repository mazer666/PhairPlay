package com.phairplay.diagnostics

/**
 * PerformanceBudgets — the performance budgets the app measures itself against.
 *
 * WHAT: the two budgets of NFR-03 and NFR-04 as constants: the peak resident
 * memory of one active stream and the average CPU load on a mid-range TV SoC.
 *
 * WHY the values live here and not in the code that shows them: a budget is a
 * fact of the requirement (NFR-03 and NFR-04 in `docs/spec/REQUIREMENTS.md`), and
 * it has to exist exactly once. The diagnostics screen compares the measurement
 * with these constants, and the test proves that they still carry the value of
 * the requirement. A second copy of "150" somewhere else would be a number that
 * can go stale without anybody noticing — and a budget that goes stale silently
 * is worse than no budget at all.
 *
 * HOW the memory budget is counted: 150 MB in the SI sense — 150 000 000 bytes.
 * The report writes the sizes with the same decimal scale (the one of
 * `LocaleFormatting.formatBytes`), so the budget appears on the screen as
 * "150 MB" and not as "146,5 MiB". A budget that the reader cannot compare with
 * the number next to it is not a budget he can judge.
 *
 * Example:
 *   PerformanceBudgets.MAX_RESIDENT_BYTES   // 150000000
 *   PerformanceBudgets.MAX_CPU_PERCENT      // 30.0
 */
object PerformanceBudgets {

    /** The peak resident memory of one active stream — NFR-03, 150 MB. */
    const val MAX_RESIDENT_BYTES: Long = 150L * 1000L * 1000L

    /** The average CPU load on a mid-range TV SoC — NFR-04, 30 %. */
    const val MAX_CPU_PERCENT: Double = 30.0
}
