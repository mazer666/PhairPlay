package com.phairplay.diagnostics

/**
 * englishDiagnosticsLabels — the English labels of the report, for the tests.
 *
 * WHY the tests do not read the resources: the resources belong to Android, and the
 * logic of the report has to be provable on a plain JVM. The texts here are the
 * same texts the English resource file carries.
 *
 * Example:
 *   val labels = englishDiagnosticsLabels()
 */
internal fun englishDiagnosticsLabels() = DiagnosticsLabels(
    title = "PhairPlay diagnostics",
    device = "Device",
    address = "Local address",
    version = "Version",
    platform = "Platform",
    service = "Service",
    sectionProtocols = "Protocols",
    sectionPorts = "Ports",
    sectionSelfTest = "Self-test",
    unknown = "unknown",
    protocolAirPlay = "AirPlay",
    protocolMiracast = "Miracast",
    protocolCast = "Cast",
    protocolDlna = "DLNA",
    portAirPlay = "AirPlay",
    portDlnaDescription = "DLNA description",
    portSsdp = "SSDP",
    selfTestAddress = "Local address",
    selfTestDescriptionServer = "Description server",
    selfTestSsdp = "SSDP multicast",
    selfTestAirPlayPort = "AirPlay port",
    selfTestProcess = "App process",
    statusOk = "ok",
    statusFailed = "failed",
    notRunYet = "not run yet",
    reasonNoAddress = "no usable IPv4 address (%1\$s)",
    reasonNoListener = "no listener on %1\$s",
    reasonPortUnknown = "port unknown — no SSDP answer",
    reasonNoSsdpAnswer = "no answer from %1\$s within %2\$d ms",
    reasonSocketFailed = "multicast socket failed: %1\$s",
    reasonNotMeasured = "not measured",
    detailHttpOk = { statusCode, bodyBytes -> "HTTP $statusCode, $bodyBytes bytes" },
    detailHttpFailed = "HTTP %1\$d",
    detailResidentMemory = "resident memory %1\$d kB"
)
