package com.phairplay.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SelfTestTest — proves that the local self test always answers with a measurement.
 *
 * WHY: a self test that stays silent is worse than no self test at all. Every check
 * has to come back with a name, an outcome and a detail — also when the device has
 * no network, which is exactly the situation the owner wants to see.
 *
 * The test needs neither a television nor a network: it proves that every check
 * answers, not that the network works. The values themselves depend on the machine
 * the test runs on and are therefore not asserted.
 */
class SelfTestTest {

    @Test
    fun `the self test answers with one result per check`() {
        val outcome = SelfTest(englishDiagnosticsLabels(), timeoutMillis = TEST_TIMEOUT).run()

        assertEquals("the number of checks is wrong", 5, outcome.results.size)
    }

    @Test
    fun `every check names itself and tells what it measured`() {
        val outcome = SelfTest(englishDiagnosticsLabels(), timeoutMillis = TEST_TIMEOUT).run()

        outcome.results.forEach { result ->
            assertTrue("a check has no name", result.name.isNotBlank())
            assertTrue("the check '${result.name}' has no detail", result.detail.isNotBlank())
        }
    }

    @Test
    fun `the self test runs the checks in the same order every time`() {
        val first = SelfTest(englishDiagnosticsLabels(), timeoutMillis = TEST_TIMEOUT).run()
        val second = SelfTest(englishDiagnosticsLabels(), timeoutMillis = TEST_TIMEOUT).run()

        assertEquals(
            "the order of the checks differs",
            first.results.map { it.name },
            second.results.map { it.name }
        )
    }

    @Test
    fun `the port of the description server is only reported when it was found`() {
        val outcome = SelfTest(englishDiagnosticsLabels(), timeoutMillis = TEST_TIMEOUT).run()

        val port = outcome.dlnaDescriptionPort
        assertTrue(
            "the description server port is not a valid port number",
            port == null || port in 1..65535
        )
    }

    companion object {
        /** A short timeout — the test measures the behaviour, not the network. */
        private const val TEST_TIMEOUT = 500
    }
}
