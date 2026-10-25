package com.phairplay.diagnostics

import java.util.Locale

/**
 * DiagnosticsLabels — every text the diagnostics report is built from.
 *
 * WHY the labels are data and not resources: the report is built by code that must
 * run without Android. The UI layer reads the labels from `R.string` and hands them
 * in, so the same builder produces an English report on an English television and a
 * German report on a German one — and the builder itself stays testable on a plain
 * JVM.
 *
 * HOW the placeholders work: a label that carries a value uses the positional
 * `String.format` placeholders (`%1$s`, `%2$d`). The values are filled in with
 * `Locale.US`, so a report reads the same on every device.
 *
 * Example:
 *   val labels = DiagnosticsLabels(
 *       title = "PhairPlay diagnostics",
 *       unknown = "unknown",
 *       // … every other label …
 *   )
 *   val text = DiagnosticsReportBuilder.build(report, labels)
 */
data class DiagnosticsLabels(
    // ── The report itself ───────────────────────────────────────────────────

    /** The title line of the report. */
    val title: String,

    /** The label of the device name. */
    val device: String,

    /** The label of the local IPv4 address. */
    val address: String,

    /** The label of the app version. */
    val version: String,

    /** The label of the product platform. */
    val platform: String,

    /** The label of the service state. */
    val service: String,

    /** The heading of the protocol section. */
    val sectionProtocols: String,

    /** The heading of the port section. */
    val sectionPorts: String,

    /** The heading of the self test section. */
    val sectionSelfTest: String,

    /** The heading of the session section. */
    val sectionSession: String,

    /** The text that replaces a session that is not running. */
    val sessionNone: String,

    /** The heading of the resource section. */
    val sectionResources: String,

    /**
     * Formats a duration in milliseconds for the language of the reader.
     *
     * WHY a function and not a string: the duration is a number that has to be
     * written with the digits and the separators of the language — "00:04:12" in
     * German and in English, "٠٠:٠٤:١٢" in Arabic. The formatter is built by the
     * UI layer, which knows the language of the app, and handed in here, so this
     * package stays free of Android.
     *
     * @param millis the duration in milliseconds.
     * @return the formatted duration.
     */
    val formatDuration: (millis: Long) -> String,

    /**
     * Formats a byte count for the language of the reader.
     *
     * WHY a function and not a string: a byte count is a quantity and a quantity
     * has to be written with the digits and the separator of the language — "84,2
     * MB" in German and "84.2 MB" in English. The formatter is built by the UI
     * layer, which knows the language of the app, and handed in here, so this
     * package stays free of Android.
     *
     * @param bytes the number of bytes.
     * @return the formatted size.
     */
    val formatBytes: (bytes: Long) -> String,

    /**
     * Formats a percentage for the language of the reader.
     *
     * WHY a function and not a string: the same reason as for the byte count —
     * the decimal separator of the language decides how the number is written.
     *
     * @param percent the value in percent.
     * @return the formatted number.
     */
    val formatPercent: (percent: Double) -> String,

    /** The text that replaces a value that is not known. */
    val unknown: String,

    // ── The names of the protocols ──────────────────────────────────────────

    /** The name of the AirPlay protocol. */
    val protocolAirPlay: String,

    /** The name of the Miracast protocol. */
    val protocolMiracast: String,

    /** The name of the Cast protocol. */
    val protocolCast: String,

    /** The name of the DLNA protocol. */
    val protocolDlna: String,

    // ── The ports ───────────────────────────────────────────────────────────

    /** The label of the AirPlay port. */
    val portAirPlay: String,

    /** The label of the port of the DLNA description server. */
    val portDlnaDescription: String,

    /** The label of the SSDP port. */
    val portSsdp: String,

    // ── The checks of the self test ────────────────────────────────────────

    /** The name of the address check. */
    val selfTestAddress: String,

    /** The name of the description server check. */
    val selfTestDescriptionServer: String,

    /** The name of the SSDP check. */
    val selfTestSsdp: String,

    /** The name of the AirPlay port check. */
    val selfTestAirPlayPort: String,

    /** The name of the process check. */
    val selfTestProcess: String,

    // ── The outcome of a check ──────────────────────────────────────────────

    /** The word that marks a check that passed. */
    val statusOk: String,

    /** The word that marks a check that failed. */
    val statusFailed: String,

    /** The word that marks a measured value that is above its budget. */
    val statusOverBudget: String,

    /** The detail of a check that was not run yet. */
    val notRunYet: String,

    // ── The details of a check ──────────────────────────────────────────────

    /** The detail when no usable IPv4 address was found. `%1$s` is the address. */
    val reasonNoAddress: String,

    /** The detail when nothing listens on the address. `%1$s` is the endpoint. */
    val reasonNoListener: String,

    /** The detail when the port of the description server is not known. */
    val reasonPortUnknown: String,

    /** The detail when the SSDP search was not answered. `%1$s` is the group, `%2$d` the timeout. */
    val reasonNoSsdpAnswer: String,

    /** The detail when the multicast socket could not be opened. `%1$s` is the reason. */
    val reasonSocketFailed: String,

    /** The detail when a value could not be measured. */
    val reasonNotMeasured: String,

    /**
     * The detail of a successful HTTP request.
     *
     * WHY a function and not a string: the byte count is a quantity, and a quantity
     * needs a plural form — "1 byte" and "1842 bytes" are different words. The
     * plural resource can only be resolved with the count, so the caller passes it
     * in.
     *
     * @param statusCode the HTTP status the server answered with.
     * @param bodyBytes the length of the answer in bytes.
     * @return the filled detail.
     */
    val detailHttpOk: (statusCode: Int, bodyBytes: Int) -> String,

    /** The detail of an HTTP request that was answered with an error. `%1$d` is the status. */
    val detailHttpFailed: String,

    /** The detail of the measured memory of the app process. `%1$d` is the size in kB. */
    val detailResidentMemory: String,

    // ── The resources ───────────────────────────────────────────────────────

    /** The name of the resident memory in the report. */
    val resourceMemory: String,

    /** The name of the CPU load in the report. */
    val resourceCpu: String,

    /**
     * The value of the memory line: the measured size, the budget and the outcome.
     *
     * `%1$s` is the measured size, `%2$s` the budget and `%3$s` the outcome.
     */
    val resourceMemoryValue: String,

    /**
     * The value of the CPU line: the measured load, the budget and the outcome.
     *
     * `%1$s` is the measured load, `%2$s` the budget and `%3$s` the outcome.
     */
    val resourceCpuValue: String
) {

    /**
     * Fills the placeholders of a label.
     *
     * WHY `Locale.US`: the report is a technical document that is compared between
     * devices. A number that is written with a comma in one language and with a dot
     * in another would make two reports look different when they are not.
     *
     * @param template the label with its placeholders.
     * @param arguments the values of the placeholders.
     * @return the filled label.
     */
    fun format(template: String, vararg arguments: Any): String =
        String.format(Locale.US, template, *arguments)
}
