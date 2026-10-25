package com.phairplay.diagnostics

/**
 * DiagnosticsReportBuilder — turns a [DiagnosticsReport] into the text on the screen.
 *
 * WHY the text is built here and not in the UI: the report is copied into the
 * clipboard and pasted into bug reports. It therefore has to be one block of text
 * with a stable order, and it has to be reproducible — the same values must always
 * produce the same text, so that two reports can be compared.
 *
 * HOW: every value is passed through [DiagnosticsRedactor] before it is written, so
 * a MAC address or a UUID can never reach the clipboard, no matter which value it
 * came in with. A value that is missing is written as the "unknown" label instead of
 * an empty line — an empty line looks like a bug, "unknown" is an answer.
 *
 * Example:
 *   val text = DiagnosticsReportBuilder.build(report, labels)
 */
object DiagnosticsReportBuilder {

    /** The separator between the name and the value of a line. */
    private const val SEPARATOR = ": "

    /** The separator between the outcome and the detail of a self test line. */
    private const val DETAIL_SEPARATOR = " — "

    /**
     * Builds the report text.
     *
     * @param report the values of the report.
     * @param labels the localized labels of the report.
     * @return the report as text, one "Name: Value" line per entry, the sections
     *   separated by an empty line.
     */
    fun build(report: DiagnosticsReport, labels: DiagnosticsLabels): String {
        val sections = listOf(
            labels.title,
            deviceSection(report, labels),
            protocolSection(report, labels),
            portSection(report, labels),
            selfTestSection(report, labels)
        )
        return sections.joinToString("\n\n")
    }

    /** The section with the name, the address, the version, the platform and the service state. */
    private fun deviceSection(report: DiagnosticsReport, labels: DiagnosticsLabels): String =
        listOf(
            line(labels.device, report.deviceName, labels),
            line(labels.address, report.localAddress, labels),
            line(labels.version, report.appVersion, labels),
            line(labels.platform, report.platform, labels),
            line(labels.service, report.serviceState, labels)
        ).joinToString("\n")

    /** The section with the state of every protocol. */
    private fun protocolSection(report: DiagnosticsReport, labels: DiagnosticsLabels): String {
        val lines = mutableListOf(labels.sectionProtocols)
        // The protocols are always written in the order of the enum, never in the order
        // of the map — that is what makes two reports comparable.
        DiagnosticProtocol.entries.forEach { protocol ->
            val state = report.protocolStates[protocol].orEmpty()
            lines += line(labels.protocolLabel(protocol), state, labels)
        }
        return lines.joinToString("\n")
    }

    /** The section with the ports the receiver listens on. */
    private fun portSection(report: DiagnosticsReport, labels: DiagnosticsLabels): String {
        val lines = mutableListOf(labels.sectionPorts)
        report.ports.forEach { port ->
            val value = if (port.port > 0) port.port.toString() else ""
            lines += line(port.name, value, labels)
        }
        return lines.joinToString("\n")
    }

    /** The section with the results of the last self test run. */
    private fun selfTestSection(report: DiagnosticsReport, labels: DiagnosticsLabels): String {
        val lines = mutableListOf(labels.sectionSelfTest)
        if (report.selfTestResults.isEmpty()) {
            lines += line(labels.sectionSelfTest, labels.notRunYet, labels)
            return lines.joinToString("\n")
        }
        report.selfTestResults.forEach { result ->
            val status = if (result.ok) labels.statusOk else labels.statusFailed
            val detail = DiagnosticsRedactor.redact(result.detail).trim()
            val value = if (detail.isEmpty()) status else status + DETAIL_SEPARATOR + detail
            lines += line(result.name, value, labels)
        }
        return lines.joinToString("\n")
    }

    /** Builds one "Name: Value" line. */
    private fun line(name: String, value: String, labels: DiagnosticsLabels): String {
        val safeName = DiagnosticsRedactor.redact(name).trim().ifEmpty { labels.unknown }
        val safeValue = DiagnosticsRedactor.redact(value).trim().ifEmpty { labels.unknown }
        return safeName + SEPARATOR + safeValue
    }

    /** The localized name of a protocol — the labels carry the four protocol names. */
    private fun DiagnosticsLabels.protocolLabel(protocol: DiagnosticProtocol): String =
        when (protocol) {
            DiagnosticProtocol.AIRPLAY -> protocolAirPlay
            DiagnosticProtocol.MIRACAST -> protocolMiracast
            DiagnosticProtocol.CAST -> protocolCast
            DiagnosticProtocol.DLNA -> protocolDlna
        }
}
