package com.phairplay.diagnostics

/**
 * ResourceStatus — the three states a measured value can be in against its budget.
 *
 * WHY three and not two: a value that could not be measured is not a value that
 * is within the budget. It is a value nobody knows, and the report has to say
 * so — a green tick for a measurement that did not happen would be the worst
 * possible answer, because it looks like a proof.
 *
 * Example:
 *   val status = ResourceStatus.WITHIN_BUDGET
 */
enum class ResourceStatus {
    /** The value was measured and it stays within the budget. */
    WITHIN_BUDGET,

    /** The value was measured and it is above the budget. */
    OVER_BUDGET,

    /** No value could be measured — the budget was neither met nor missed. */
    NOT_MEASURED
}

/**
 * ResourceAssessment — the comparison of one measured value with its budget.
 *
 * WHAT: for one metric — the resident memory or the CPU load — the measured
 * value, the budget it was compared with, whether it stays within that budget,
 * and the outcome as text.
 *
 * WHY the text is part of the result and not built later: the outcome is a
 * decision ("OK", "over budget", "not measured"), and a decision that every
 * caller builds on its own is a decision that two screens can build differently.
 * The text is handed in already localized, so the model stays free of Android.
 *
 * WHY a value that could not be measured is never "within the budget": the
 * constructor refuses an assessment that claims both. A measurement that did not
 * happen cannot be a proof that the budget is met.
 *
 * Example:
 *   val assessment = ResourceAssessment.memory(84_200_000L, labels)
 *   assessment.withinBudget   // true
 *   assessment.text           // "OK"
 */
data class ResourceAssessment(
    /** The measured value in the unit of the metric — bytes for the memory, percent for the CPU. */
    val measuredValue: Double?,
    /** The budget the value was compared with, in the same unit. */
    val budget: Double,
    /** True when a value could be measured at all. */
    val measurable: Boolean,
    /** True when the measured value stays within the budget. */
    val withinBudget: Boolean,
    /** The outcome as text, already localized. */
    val text: String
) {

    init {
        require(measurable == (measuredValue != null)) {
            "an assessment without a value cannot be measurable"
        }
        require(!measurable || withinBudget == (measuredValue!! <= budget)) {
            "the outcome of an assessment does not match its value"
        }
    }

    /** The state of the traffic light: within the budget, over the budget or not measured. */
    val status: ResourceStatus
        get() = when {
            !measurable -> ResourceStatus.NOT_MEASURED
            withinBudget -> ResourceStatus.WITHIN_BUDGET
            else -> ResourceStatus.OVER_BUDGET
        }

    companion object {

        /**
         * Assesses the resident memory against the budget of NFR-03.
         *
         * @param memoryBytes the measured memory of the process, or null when it could not be read.
         * @param labels       the localized labels of the report.
         * @return the assessment of the memory.
         */
        fun memory(memoryBytes: Long?, labels: DiagnosticsLabels): ResourceAssessment =
            assess(
                value = memoryBytes?.toDouble(),
                budget = PerformanceBudgets.MAX_RESIDENT_BYTES.toDouble(),
                labels = labels
            )

        /**
         * Assesses the CPU load against the budget of NFR-04.
         *
         * @param cpuPercent the measured load of the process, or null when it could not be measured.
         * @param labels     the localized labels of the report.
         * @return the assessment of the CPU load.
         */
        fun cpu(cpuPercent: Double?, labels: DiagnosticsLabels): ResourceAssessment =
            assess(
                value = cpuPercent,
                budget = PerformanceBudgets.MAX_CPU_PERCENT,
                labels = labels
            )

        /**
         * Compares one value with its budget.
         *
         * @param value  the measured value, or null when nothing could be measured.
         * @param budget the budget to compare it with.
         * @param labels the localized labels of the report.
         * @return the assessment.
         */
        private fun assess(
            value: Double?,
            budget: Double,
            labels: DiagnosticsLabels
        ): ResourceAssessment = if (value == null) {
            ResourceAssessment(
                measuredValue = null,
                budget = budget,
                measurable = false,
                withinBudget = false,
                text = labels.reasonNotMeasured
            )
        } else {
            val withinBudget = value <= budget
            ResourceAssessment(
                measuredValue = value,
                budget = budget,
                measurable = true,
                withinBudget = withinBudget,
                text = if (withinBudget) labels.statusOk else labels.statusOverBudget
            )
        }
    }
}
