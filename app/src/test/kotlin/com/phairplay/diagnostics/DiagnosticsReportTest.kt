package com.phairplay.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DiagnosticsReportTest — proves what the diagnostics report shows and what it hides.
 *
 * WHY these tests exist: the report is the only thing the owner of the television
 * can show when something does not work. A report that hides a broken protocol, or
 * that carries the MAC address of the household into a public bug tracker, is worse
 * than no report at all.
 *
 * WHAT WE TEST:
 * - the report shows the address, the ports, every protocol state and every self test,
 * - the report never carries a MAC address,
 * - a protocol in the error state stays visible,
 * - a value that is not known is written as "unknown" and not as an empty value,
 * - two reports from the same values are the same text, and the sections keep
 *   their order.
 *
 * The test runs on a plain JVM: neither the report nor the builder knows Android.
 */
class DiagnosticsReportTest {

    private val labels = englishDiagnosticsLabels()

    // ── The report shows what it promises ───────────────────────────────────

    @Test
    fun `the report shows the address the ports every protocol state and every self test`() {
        val text = DiagnosticsReportBuilder.build(fullReport(), labels)

        // The address of the device — the one piece of information the owner needs.
        assertTrue("the IPv4 address is missing", text.contains("192.168.1.42"))

        // The three ports.
        assertTrue("the AirPlay port is missing", text.contains("AirPlay: 7000"))
        assertTrue("the DLNA description port is missing", text.contains("DLNA description: 49152"))
        assertTrue("the SSDP port is missing", text.contains("SSDP: 1900"))

        // The state of every protocol.
        assertTrue("the state of AirPlay is missing", text.contains("AirPlay: Advertising"))
        assertTrue("the state of Miracast is missing", text.contains("Miracast: Disabled"))
        assertTrue("the state of Cast is missing", text.contains("Cast: Connected"))
        assertTrue("the state of DLNA is missing", text.contains("DLNA: Advertising"))

        // Every check of the self test, with its outcome.
        assertTrue("the address check is missing", text.contains("Local address: ok"))
        assertTrue("the description server check is missing", text.contains("Description server: failed"))
        assertTrue("the SSDP check is missing", text.contains("SSDP multicast: ok"))
        assertTrue("the AirPlay port check is missing", text.contains("AirPlay port: failed"))
        assertTrue("the process check is missing", text.contains("App process: ok"))
    }

    @Test
    fun `the report shows the name the version the platform and the service state`() {
        val text = DiagnosticsReportBuilder.build(fullReport(), labels)

        assertTrue("the device name is missing", text.contains("Device: Living Room TV"))
        assertTrue("the version is missing", text.contains("Version: 1.0.0-alpha.1-googletv (4)"))
        assertTrue("the platform is missing", text.contains("Platform: Google TV"))
        assertTrue("the service state is missing", text.contains("Service: Running"))
    }

    // ── The report hides what it must not show ─────────────────────────────

    @Test
    fun `the report never shows a MAC address`() {
        val text = DiagnosticsReportBuilder.build(
            fullReport(
                deviceName = "AA:BB:CC:DD:EE:FF",
                localAddress = "aa:bb:cc:dd:ee:ff"
            ),
            labels
        )

        assertFalse(
            "the MAC address of the device name is in the report",
            text.contains("AA:BB:CC:DD:EE:FF")
        )
        assertFalse(
            "the MAC address of the address is in the report",
            text.contains("aa:bb:cc:dd:ee:ff")
        )
        assertFalse(
            "the MAC address is in the report in any spelling",
            text.lowercase().contains("aa:bb:cc:dd:ee:ff")
        )
    }

    @Test
    fun `the report never shows a UUID`() {
        val text = DiagnosticsReportBuilder.build(
            fullReport(deviceName = "TV 123e4567-e89b-12d3-a456-426614174000"),
            labels
        )

        assertFalse(
            "the UUID of the device is in the report",
            text.contains("123e4567-e89b-12d3-a456-426614174000")
        )
    }

    // ── A broken protocol stays visible ─────────────────────────────────────

    @Test
    fun `a protocol in the error state stays visible in the report`() {
        val text = DiagnosticsReportBuilder.build(
            fullReport(
                protocolStates = mapOf(
                    DiagnosticProtocol.AIRPLAY to "Error",
                    DiagnosticProtocol.MIRACAST to "Advertising",
                    DiagnosticProtocol.CAST to "Disabled",
                    DiagnosticProtocol.DLNA to "Advertising"
                )
            ),
            labels
        )

        assertTrue("the error state is not visible", text.contains("AirPlay: Error"))
        assertFalse(
            "the error state was replaced by an unknown value",
            text.contains("AirPlay: unknown")
        )
    }

    // ── A missing value is an answer, not an empty line ─────────────────────

    @Test
    fun `a missing value is written as unknown and not as an empty value`() {
        val text = DiagnosticsReportBuilder.build(
            fullReport(
                deviceName = "",
                localAddress = "   ",
                appVersion = "",
                serviceState = ""
            ),
            labels
        )

        assertTrue("the device name is not marked as unknown", text.contains("Device: unknown"))
        assertTrue("the address is not marked as unknown", text.contains("Local address: unknown"))
        assertTrue("the version is not marked as unknown", text.contains("Version: unknown"))
        assertTrue("the service state is not marked as unknown", text.contains("Service: unknown"))
        assertFalse("the report has a line with an empty value", text.contains(": \n"))
    }

    @Test
    fun `a protocol without a state is written as unknown`() {
        val text = DiagnosticsReportBuilder.build(
            fullReport(protocolStates = emptyMap()),
            labels
        )

        assertTrue("the state of AirPlay is not marked as unknown", text.contains("AirPlay: unknown"))
        assertTrue("the state of DLNA is not marked as unknown", text.contains("DLNA: unknown"))
    }

    @Test
    fun `a port that is not known is written as unknown`() {
        val text = DiagnosticsReportBuilder.build(
            fullReport(
                ports = listOf(
                    DiagnosticsPort(labels.portAirPlay, 7000),
                    DiagnosticsPort(labels.portDlnaDescription, 0),
                    DiagnosticsPort(labels.portSsdp, 1900)
                )
            ),
            labels
        )

        assertTrue("the unknown port is not marked as unknown", text.contains("DLNA description: unknown"))
    }

    @Test
    fun `a self test that did not run is written as not run yet`() {
        val text = DiagnosticsReportBuilder.build(fullReport(selfTestResults = emptyList()), labels)

        assertTrue("the missing self test is not marked", text.contains("Self-test: not run yet"))
    }

    // ── The report is reproducible ──────────────────────────────────────────

    @Test
    fun `two reports from the same values are the same text`() {
        val first = DiagnosticsReportBuilder.build(fullReport(), labels)
        val second = DiagnosticsReportBuilder.build(fullReport(), labels)

        assertEquals("two reports from the same values differ", first, second)
    }

    @Test
    fun `the report names the session that is streaming and its duration`() {
        val report = fullReport(
            activeSession = DiagnosticSession(
                senderName = "Living Room MacBook",
                protocol = DiagnosticProtocol.AIRPLAY,
                durationMillis = 252_000
            )
        )

        val text = DiagnosticsReportBuilder.build(report, labels)

        assertTrue(text.contains("Living Room MacBook"))
        assertTrue(text.contains("T+252s"))
    }

    @Test
    fun `the report says that nothing streams when there is no session`() {
        val text = DiagnosticsReportBuilder.build(fullReport(activeSession = null), labels)

        assertTrue(text.contains("nothing is streaming"))
    }

    @Test
    fun `the sections keep their order in the report`() {
        val text = DiagnosticsReportBuilder.build(fullReport(), labels)

        val title = text.indexOf(labels.title)
        val device = text.indexOf("${labels.device}:")
        val protocols = text.indexOf(labels.sectionProtocols)
        val ports = text.indexOf(labels.sectionPorts)
        val selfTest = text.indexOf(labels.sectionSelfTest)

        assertTrue("the title is missing", title >= 0)
        assertTrue("the device section comes after the title", title < device)
        assertTrue("the protocol section comes after the device section", device < protocols)
        assertTrue("the port section comes after the protocol section", protocols < ports)
        assertTrue("the self test section comes after the port section", ports < selfTest)
    }

    @Test
    fun `the protocols are always listed in the same order`() {
        val text = DiagnosticsReportBuilder.build(fullReport(), labels)

        val airPlay = text.indexOf("AirPlay: Advertising")
        val miracast = text.indexOf("Miracast: Disabled")
        val cast = text.indexOf("Cast: Connected")
        val dlna = text.indexOf("DLNA: Advertising")

        assertTrue("the AirPlay state is missing", airPlay >= 0)
        assertTrue("AirPlay does not come first", airPlay < miracast)
        assertTrue("Miracast does not come before Cast", miracast < cast)
        assertTrue("Cast does not come before DLNA", cast < dlna)
    }

    // ── The helpers ─────────────────────────────────────────────────────────

    /** A report with every value filled in. */
    private fun fullReport(
        deviceName: String = "Living Room TV",
        localAddress: String = "192.168.1.42",
        appVersion: String = "1.0.0-alpha.1-googletv (4)",
        serviceState: String = "Running",
        protocolStates: Map<DiagnosticProtocol, String> = mapOf(
            DiagnosticProtocol.AIRPLAY to "Advertising",
            DiagnosticProtocol.MIRACAST to "Disabled",
            DiagnosticProtocol.CAST to "Connected",
            DiagnosticProtocol.DLNA to "Advertising"
        ),
        ports: List<DiagnosticsPort> = listOf(
            DiagnosticsPort(labels.portAirPlay, 7000),
            DiagnosticsPort(labels.portDlnaDescription, 49152),
            DiagnosticsPort(labels.portSsdp, 1900)
        ),
        selfTestResults: List<SelfTestResult> = listOf(
            SelfTestResult(labels.selfTestAddress, ok = true, detail = "192.168.1.42"),
            SelfTestResult(labels.selfTestDescriptionServer, ok = false, detail = "HTTP 404"),
            SelfTestResult(labels.selfTestSsdp, ok = true, detail = "239.255.255.250:1900"),
            SelfTestResult(labels.selfTestAirPlayPort, ok = false, detail = "no listener on 127.0.0.1:7000"),
            SelfTestResult(labels.selfTestProcess, ok = true, detail = "resident memory 48256 kB")
        ),
        activeSession: DiagnosticSession? = null
    ): DiagnosticsReport = DiagnosticsReport(
        deviceName = deviceName,
        localAddress = localAddress,
        appVersion = appVersion,
        platform = DiagnosticsPlatform.GOOGLE_TV,
        serviceState = serviceState,
        protocolStates = protocolStates,
        ports = ports,
        selfTestResults = selfTestResults,
        activeSession = activeSession
    )
}
