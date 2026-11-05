package com.phairplay.diagnostics

/**
 * DiagnosticsReportBuilder — turns a [DiagnosticsReport] into the text on the screen
 * and into the text that leaves the device.
 *
 * WHY the text is built here and not in the UI: the report is copied into the
 * clipboard and pasted into bug reports. It therefore has to be one block of text
 * with a stable order, and it has to be reproducible — the same values must always
 * produce the same text, so that two reports can be compared.
 *
 * TWO REPORTS, TWO RULES
 * - [build] writes the report the **owner** reads on the television. It names the
 *   device and its address, because that is the question the screen exists for
 *   ("which address do I look for?"). Only the MAC addresses and the UUIDs are
 *   removed.
 * - [buildForExport] writes the report the owner **copies out of the app**. It goes
 *   into a bug tracker or a chat message, where it becomes public and permanent, so
 *   every identifying value is gone: the address, the name of the television and the
 *   name of the sender. See [DiagnosticsExportRedactor].
 *
 * WHY the names are removed by the builder and not by a pattern: a name is free text.
 * A pattern cannot recognise "Wohnzimmer TV", so the builder — which knows where the
 * names are — removes them where they stand. The pattern pass then cleans every
 * value that the builder does not own (the details of the self test).
 *
 * WHY a value that is missing is written as the "unknown" label instead of an empty
 * line: an empty line looks like a bug, "unknown" is an answer.
 *
 * Example:
 *   val text = DiagnosticsReportBuilder.build(report, labels)          // on the screen
 *   val copy = DiagnosticsReportBuilder.buildForExport(report, labels) // out of the app
 */
object DiagnosticsReportBuilder {

    /** The separator between the name and the value of a line. */
    private const val SEPARATOR = ": "

    /** The separator between the outcome and the detail of a self test line. */
    private const val DETAIL_SEPARATOR = " — "

    /**
     * Builds the report text for the screen.
     *
     * @param report the values of the report.
     * @param labels the localized labels of the report.
     * @return the report as text, one "Name: Value" line per entry, the sections
     *   separated by an empty line.
     */
    fun build(report: DiagnosticsReport, labels: DiagnosticsLabels): String =
        build(report, labels, Policy.screen())

    /**
     * Builds the report text that leaves the app.
     *
     * WHY the personal fields are replaced before the text is built: the name of the
     * television and the name of the sender are free text. A pattern cannot recognise
     * them, and a pattern that tried would also hit the labels of the report. The
     * builder knows the two values, so it replaces them where they stand.
     *
     * @param report the values of the report.
     * @param labels the localized labels of the report.
     * @return the report as text, without any address, MAC address, UUID, name of the
     *   television or name of the sender.
     */
    fun buildForExport(report: DiagnosticsReport, labels: DiagnosticsLabels): String {
        val sanitised = report.copy(
            deviceName = DiagnosticsExportRedactor.PLACEHOLDER,
            localAddress = DiagnosticsExportRedactor.PLACEHOLDER,
            activeSession = report.activeSession?.copy(
                senderName = DiagnosticsExportRedactor.PLACEHOLDER
            )
        )
        return build(sanitised, labels, Policy.export())
    }

    /** Builds the report with the given policy. */
    private fun build(report: DiagnosticsReport, labels: DiagnosticsLabels, policy: Policy): String {
        val sections = listOf(
            labels.title,
            deviceSection(report, labels, policy),
            protocolSection(report, labels, policy),
            portSection(report, labels, policy),
            selfTestSection(report, labels, policy),
            resourceSection(report, labels, policy),
            sessionSection(report, labels, policy)
        )
        return sections.joinToString("\n\n")
    }

    /** The section with the name, the address, the version, the platform and the service state. */
    private fun deviceSection(report: DiagnosticsReport, labels: DiagnosticsLabels, policy: Policy): String =
        listOf(
            line(labels.device, report.deviceName, labels, policy),
            line(labels.address, report.localAddress, labels, policy),
            line(labels.version, report.appVersion, labels, policy),
            line(labels.platform, report.platform, labels, policy),
            line(labels.service, report.serviceState, labels, policy)
        ).joinToString("\n")

    /** The section with the state of every protocol. */
    private fun protocolSection(report: DiagnosticsReport, labels: DiagnosticsLabels, policy: Policy): String {
        val lines = mutableListOf(labels.sectionProtocols)
        // The protocols are always written in the order of the enum, never in the order
        // of the map — that is what makes two reports comparable.
        DiagnosticProtocol.entries.forEach { protocol ->
            val state = report.protocolStates[protocol].orEmpty()
            lines += line(labels.protocolLabel(protocol), state, labels, policy)
        }
        return lines.joinToString("\n")
    }

    /** The section with the ports the receiver listens on. */
    private fun portSection(report: DiagnosticsReport, labels: DiagnosticsLabels, policy: Policy): String {
        val lines = mutableListOf(labels.sectionPorts)
        report.ports.forEach { port ->
            val value = if (port.port > 0) port.port.toString() else ""
            lines += line(port.name, value, labels, policy)
        }
        return lines.joinToString("\n")
    }

    /**
     * The section with the session that is streaming right now.
     *
     * WHY the duration is formatted here and not in the model: the model stays free
     * of the language of the reader, so the report can be built and compared on any
     * device. The labels carry the formatter.
     */
    private fun sessionSection(report: DiagnosticsReport, labels: DiagnosticsLabels, policy: Policy): String {
        val lines = mutableListOf(labels.sectionSession)
        val session = report.activeSession
        if (session == null) {
            lines += line(labels.sectionSession, labels.sessionNone, labels, policy)
            return lines.joinToString("\n")
        }
        lines += line(labels.protocolLabel(session.protocol), session.senderName, labels, policy)
        lines += line(labels.sectionSession, labels.formatDuration(session.durationMillis), labels, policy)
        return lines.joinToString("\n")
    }

    /** The section with the results of the last self test run. */
    private fun selfTestSection(report: DiagnosticsReport, labels: DiagnosticsLabels, policy: Policy): String {
        val lines = mutableListOf(labels.sectionSelfTest)
        if (report.selfTestResults.isEmpty()) {
            lines += line(labels.sectionSelfTest, labels.notRunYet, labels, policy)
            return lines.joinToString("\n")
        }
        report.selfTestResults.forEach { result ->
            val status = if (result.ok) labels.statusOk else labels.statusFailed
            val detail = policy.value(result.detail).trim()
            val value = if (detail.isEmpty()) status else status + DETAIL_SEPARATOR + detail
            lines += line(result.name, value, labels, policy)
        }
        return lines.joinToString("\n")
    }

    /**
     * The section with the memory and the CPU load of the app process, each of
     * them against its budget.
     *
     * WHY the two values are compared here and not in the measurement: the
     * comparison needs the budgets and the localized outcome texts, and both are
     * in the labels. The report therefore carries the measurement, and the
     * builder decides what it means — one place, so that two reports can never
     * judge the same number differently.
     */
    private fun resourceSection(report: DiagnosticsReport, labels: DiagnosticsLabels, policy: Policy): String {
        val lines = mutableListOf(labels.sectionResources)
        val sample = report.resourceSample
        val memory = ResourceAssessment.memory(sample?.memoryBytes, labels)
        val cpu = ResourceAssessment.cpu(sample?.cpuPercent, labels)
        lines += line(labels.resourceMemory, memoryValue(memory, labels), labels, policy)
        lines += line(labels.resourceCpu, cpuValue(cpu, labels), labels, policy)
        return lines.joinToString("\n")
    }

    /**
     * The value of the memory line: the measured size, the budget and the outcome.
     *
     * WHY a value that was not measured is written alone: "84,2 MB / 150 MB
     * (not measured)" would put two numbers next to a word that denies them.
     * The line says "not measured" and nothing else.
     */
    private fun memoryValue(assessment: ResourceAssessment, labels: DiagnosticsLabels): String {
        val measured = assessment.measuredValue ?: return labels.reasonNotMeasured
        return labels.format(
            labels.resourceMemoryValue,
            labels.formatBytes(measured.toLong()),
            labels.formatBytes(assessment.budget.toLong()),
            assessment.text
        )
    }

    /** The value of the CPU line: the measured load, the budget and the outcome. */
    private fun cpuValue(assessment: ResourceAssessment, labels: DiagnosticsLabels): String {
        val measured = assessment.measuredValue ?: return labels.reasonNotMeasured
        return labels.format(
            labels.resourceCpuValue,
            labels.formatPercent(measured),
            labels.formatPercent(assessment.budget),
            assessment.text
        )
    }

    /** Builds one "Name: Value" line. */
    private fun line(name: String, value: String, labels: DiagnosticsLabels, policy: Policy): String {
        val safeName = policy.label(name).trim().ifEmpty { labels.unknown }
        val safeValue = policy.value(value).trim().ifEmpty { labels.unknown }
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

    /**
     * Policy — how the names and the values of the report are treated.
     *
     * WHY two rules and not one: the labels are static, localized texts and carry no
     * personal data. The values carry everything the report knows about the household.
     * The export therefore has to treat the two differently — and the label rule of
     * the export is the pattern rule, so a device name that happens to be a word of a
     * label cannot mangle the label.
     */
    private class Policy(
        val label: (String) -> String,
        val value: (String) -> String
    ) {
        companion object {
            /** The report on the screen: the owner needs the name and the address. */
            fun screen(): Policy = Policy(
                label = DiagnosticsRedactor::redact,
                value = DiagnosticsRedactor::redact
            )

            /** The report that leaves the app: nothing that identifies the household. */
            fun export(): Policy = Policy(
                label = DiagnosticsExportRedactor::redact,
                value = DiagnosticsExportRedactor::redact
            )
        }
    }
}
