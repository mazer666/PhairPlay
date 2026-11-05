package com.phairplay.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DiagnosticsExportTest — proves what the **exported** diagnostics report may contain.
 *
 * WHY: the report on the screen names the television and its address, because the
 * owner has to find the device. The report the owner *copies out of the app* is a
 * different thing: it goes into a bug tracker, a chat message or a forum thread, where
 * it becomes public and permanent. Everything that identifies the household — the
 * address, the MAC address, the UUID, the name of the television and the name of the
 * sender — has to be gone by then.
 *
 * WHAT WE TEST:
 * - the export carries no IPv4 address: neither the address of the device nor the ones
 *   in the details of the self test,
 * - the export carries no MAC address and no UUID,
 * - the export carries neither the name of the television nor the name of the sender,
 * - the export still carries the values that make a bug report useful (the version,
 *   the ports, the state of every protocol, the outcome of the self test),
 * - the report **on the screen** still names the device — the two reports are not the
 *   same thing, and the screen must keep what the owner needs.
 */
class DiagnosticsExportTest {

    private val labels = englishDiagnosticsLabels()

    @Test
    fun `the export carries no IPv4 address`() {
        val text = DiagnosticsReportBuilder.buildForExport(fullReport(), labels)

        assertFalse("the address of the device is in the export", text.contains("192.168.1.42"))
        assertFalse(
            "the address in the detail of the self test is in the export",
            text.contains("127.0.0.1")
        )
        assertFalse(
            "the multicast group in the detail of the self test is in the export",
            text.contains("239.255.255.250")
        )
    }

    @Test
    fun `the export carries no MAC address and no UUID`() {
        val text = DiagnosticsReportBuilder.buildForExport(
            fullReport(
                deviceName = "AA:BB:CC:DD:EE:FF",
                localAddress = "123e4567-e89b-12d3-a456-426614174000"
            ),
            labels
        )

        assertFalse("the MAC address is in the export", text.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(
            "the UUID is in the export",
            text.contains("123e4567-e89b-12d3-a456-426614174000")
        )
    }

    @Test
    fun `the export carries neither the name of the television nor the name of the sender`() {
        val text = DiagnosticsReportBuilder.buildForExport(
            fullReport(
                activeSession = DiagnosticSession(
                    senderName = "Living Room MacBook",
                    protocol = DiagnosticProtocol.AIRPLAY,
                    durationMillis = 252_000
                )
            ),
            labels
        )

        assertFalse("the name of the television is in the export", text.contains("Living Room TV"))
        assertFalse("the name of the sender is in the export", text.contains("Living Room MacBook"))
    }

    @Test
    fun `the export keeps the values that make a bug report useful`() {
        val text = DiagnosticsReportBuilder.buildForExport(fullReport(), labels)

        assertTrue("the version is missing", text.contains("1.0.0-alpha.1-googletv (4)"))
        assertTrue("the platform is missing", text.contains("Google TV"))
        assertTrue("the state of AirPlay is missing", text.contains("AirPlay: Advertising"))
        assertTrue("the state of DLNA is missing", text.contains("DLNA: Advertising"))
        assertTrue("the AirPlay port is missing", text.contains("AirPlay: 7000"))
        assertTrue("the SSDP port is missing", text.contains("SSDP: 1900"))
        assertTrue("the outcome of the self test is missing", text.contains("Local address: ok"))
        assertTrue("the failed check is missing", text.contains("Description server: failed"))
        assertTrue("the duration of the session is missing", text.contains("T+252s"))
    }

    @Test
    fun `the report on the screen still names the device`() {
        // The control that proves the two reports are not the same thing: the screen
        // is for the owner, the export is for the world.
        val text = DiagnosticsReportBuilder.build(fullReport(), labels)

        assertTrue("the address of the device is missing", text.contains("192.168.1.42"))
        assertTrue("the name of the device is missing", text.contains("Living Room TV"))
    }

    @Test
    fun `the export redactor removes an IPv6 address`() {
        val redacted = DiagnosticsExportRedactor.redact("the host is fe80::1 on the link")

        assertFalse("the IPv6 address is still there", redacted.contains("fe80::1"))
        assertTrue("the sentence is damaged", redacted.contains("the host is"))
    }

    @Test
    fun `the export redactor keeps a duration`() {
        // A duration has the shape of an IPv6 address without being one. Removing it
        // would make the report useless.
        val redacted = DiagnosticsExportRedactor.redact("the session runs for 0:01:23")

        assertTrue("the duration was removed", redacted.contains("0:01:23"))
    }

    @Test
    fun `the export redactor keeps a version number`() {
        val redacted = DiagnosticsExportRedactor.redact("version 1.2.3.456 of the library")

        assertTrue("the version was removed", redacted.contains("1.2.3.456"))
    }

    @Test
    fun `the export redactor removes a complete address and not only the host octet`() {
        val redacted = DiagnosticsExportRedactor.redact("the device is at 192.168.1.42")

        assertFalse("the prefix of the address is still there", redacted.contains("192.168"))
        assertTrue("the placeholder is missing", redacted.contains(DiagnosticsExportRedactor.PLACEHOLDER))
    }

    @Test
    fun `two exports from the same values are the same text`() {
        val first = DiagnosticsReportBuilder.buildForExport(fullReport(), labels)
        val second = DiagnosticsReportBuilder.buildForExport(fullReport(), labels)

        assertEquals("two exports from the same values differ", first, second)
    }

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
        activeSession: DiagnosticSession? = DiagnosticSession(
            senderName = "Living Room MacBook",
            protocol = DiagnosticProtocol.AIRPLAY,
            durationMillis = 252_000
        )
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
