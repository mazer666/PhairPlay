package com.phairplay.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ResourceAssessmentTest — proves that the app tells the truth about its own resources.
 *
 * WHY these tests exist: the owner of the television reads the resources section
 * of the diagnostics report while the 30-minute test runs, and he draws his
 * conclusion from it. A report that says "OK" for a value nobody measured would
 * send him away with a false answer, and a report that stays silent about a
 * value that is over the budget would hide the reason for a stall.
 *
 * WHAT WE TEST:
 * - a value below the budget is "OK",
 * - a value above the budget says so, and the border is exact: the budget itself
 *   is still allowed, one byte more is not,
 * - a value that could not be measured says "not measured" and never "OK",
 * - the report says the same when both values are missing.
 *
 * The test runs on a plain JVM: the assessment needs neither a television nor a
 * measurement, and every number in it is a number the test itself wrote down.
 */
class ResourceAssessmentTest {

    private val labels = englishDiagnosticsLabels()

    // ── The memory against its budget ───────────────────────────────────────

    @Test
    fun `the memory below the budget is ok`() {
        val assessment = ResourceAssessment.memory(84_200_000L, labels)

        assertTrue("the memory should be within the budget", assessment.withinBudget)
        assertTrue("the memory should be measurable", assessment.measurable)
        assertEquals("the outcome is wrong", labels.statusOk, assessment.text)
        assertEquals("the state is wrong", ResourceStatus.WITHIN_BUDGET, assessment.status)
    }

    @Test
    fun `the memory exactly at the budget is ok`() {
        val assessment = ResourceAssessment.memory(PerformanceBudgets.MAX_RESIDENT_BYTES, labels)

        assertTrue("the budget itself has to be allowed", assessment.withinBudget)
        assertEquals("the outcome is wrong", labels.statusOk, assessment.text)
    }

    @Test
    fun `the memory one byte above the budget is over the budget`() {
        val assessment = ResourceAssessment.memory(PerformanceBudgets.MAX_RESIDENT_BYTES + 1L, labels)

        assertFalse("one byte above the budget has to be over the budget", assessment.withinBudget)
        assertEquals("the outcome is wrong", labels.statusOverBudget, assessment.text)
        assertEquals("the state is wrong", ResourceStatus.OVER_BUDGET, assessment.status)
    }

    @Test
    fun `the budget of the memory is the budget of the requirement`() {
        assertEquals(
            "the memory budget is not the one of NFR-03",
            150_000_000L,
            PerformanceBudgets.MAX_RESIDENT_BYTES
        )
    }

    // ── The CPU load against its budget ─────────────────────────────────────

    @Test
    fun `the cpu load below the budget is ok`() {
        val assessment = ResourceAssessment.cpu(3.0, labels)

        assertTrue("the load should be within the budget", assessment.withinBudget)
        assertEquals("the outcome is wrong", labels.statusOk, assessment.text)
    }

    @Test
    fun `the cpu load exactly at the budget is ok`() {
        val assessment = ResourceAssessment.cpu(PerformanceBudgets.MAX_CPU_PERCENT, labels)

        assertTrue("the budget itself has to be allowed", assessment.withinBudget)
        assertEquals("the outcome is wrong", labels.statusOk, assessment.text)
    }

    @Test
    fun `the cpu load above the budget is over the budget`() {
        val assessment = ResourceAssessment.cpu(PerformanceBudgets.MAX_CPU_PERCENT + 0.1, labels)

        assertFalse("the load has to be over the budget", assessment.withinBudget)
        assertEquals("the outcome is wrong", labels.statusOverBudget, assessment.text)
        assertEquals("the state is wrong", ResourceStatus.OVER_BUDGET, assessment.status)
    }

    @Test
    fun `the budget of the cpu load is the budget of the requirement`() {
        assertEquals(
            "the cpu budget is not the one of NFR-04",
            30.0,
            PerformanceBudgets.MAX_CPU_PERCENT,
            0.0
        )
    }

    // ── A value that could not be measured is not a good value ─────────────

    @Test
    fun `a memory that could not be measured is not measured`() {
        val assessment = ResourceAssessment.memory(null, labels)

        assertFalse("a value that was not measured must not be measurable", assessment.measurable)
        assertFalse(
            "a value that was not measured must not be within the budget",
            assessment.withinBudget
        )
        assertEquals("the outcome is wrong", labels.reasonNotMeasured, assessment.text)
        assertEquals("the state is wrong", ResourceStatus.NOT_MEASURED, assessment.status)
        assertFalse(
            "the outcome must not claim that everything is fine",
            assessment.text == labels.statusOk
        )
    }

    @Test
    fun `a cpu load that could not be measured is not measured`() {
        val assessment = ResourceAssessment.cpu(null, labels)

        assertFalse("a value that was not measured must not be measurable", assessment.measurable)
        assertFalse(
            "a value that was not measured must not be within the budget",
            assessment.withinBudget
        )
        assertEquals("the outcome is wrong", labels.reasonNotMeasured, assessment.text)
        assertEquals("the state is wrong", ResourceStatus.NOT_MEASURED, assessment.status)
        assertFalse(
            "the outcome must not claim that everything is fine",
            assessment.text == labels.statusOk
        )
    }

    // ── The report shows the values against their budget ────────────────────

    @Test
    fun `the report shows the memory and the cpu load against their budget`() {
        val text = report(ResourceSample(memoryBytes = 84_200_000L, cpuPercent = 3.0, measuredAtMillis = 0L))

        assertTrue("the memory line is missing", text.contains("RAM: 84.2 MB / 150 MB (${labels.statusOk})"))
        assertTrue("the cpu line is missing", text.contains("CPU: 3 % / 30 % (${labels.statusOk})"))
        assertTrue("the heading of the section is missing", text.contains(labels.sectionResources))
    }

    @Test
    fun `the report says that a value is over the budget`() {
        val text = report(
            ResourceSample(
                memoryBytes = 180_000_000L,
                cpuPercent = 42.0,
                measuredAtMillis = 0L
            )
        )

        assertTrue(
            "the memory line does not say that the memory is over the budget",
            text.contains("RAM: 180 MB / 150 MB (${labels.statusOverBudget})")
        )
        assertTrue(
            "the cpu line does not say that the load is over the budget",
            text.contains("CPU: 42 % / 30 % (${labels.statusOverBudget})")
        )
    }

    @Test
    fun `the report says so when nothing could be measured`() {
        val text = report(ResourceSample(memoryBytes = null, cpuPercent = null, measuredAtMillis = 0L))

        assertTrue("the memory line does not say that it was not measured", text.contains("RAM: not measured"))
        assertTrue("the cpu line does not say that it was not measured", text.contains("CPU: not measured"))
        assertFalse(
            "the report claims that the resources are fine although nothing was measured",
            text.contains("(${labels.statusOk})")
        )
    }

    @Test
    fun `the report says that the resources were not measured yet`() {
        val text = report(null)

        assertTrue("the memory line does not say that it was not measured", text.contains("RAM: not measured"))
        assertTrue("the cpu line does not say that it was not measured", text.contains("CPU: not measured"))
    }

    // ── The helper ──────────────────────────────────────────────────────────

    /** The report with a measurement, without the parts the test does not care about. */
    private fun report(resourceSample: ResourceSample?): String = DiagnosticsReportBuilder.build(
        DiagnosticsReport(
            deviceName = "Living Room TV",
            localAddress = "192.168.1.42",
            appVersion = "1.0.0-alpha.3-googletv (6)",
            platform = DiagnosticsPlatform.GOOGLE_TV,
            serviceState = "Running",
            protocolStates = emptyMap(),
            ports = emptyList(),
            selfTestResults = emptyList(),
            resourceSample = resourceSample
        ),
        labels
    )
}
