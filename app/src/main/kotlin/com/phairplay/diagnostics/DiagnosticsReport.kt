package com.phairplay.diagnostics

/**
 * DiagnosticProtocol — the receiver protocols a diagnostics report can describe.
 *
 * WHY a separate enum: the report must not depend on the protocol packages, otherwise
 * the text of the report could only be built on a device. The mapping from the running
 * protocol state to the text of the report happens in the UI layer, which knows the
 * localized strings.
 *
 * Example:
 *   val states = mapOf(DiagnosticProtocol.AIRPLAY to "Advertising")
 */
enum class DiagnosticProtocol {
    AIRPLAY,
    MIRACAST,
    CAST,
    DLNA
}

/**
 * DiagnosticsPlatform — the product platforms a PhairPlay build can run on.
 *
 * WHY the names are constants: the platform of a build is decided by the product
 * flavour at compile time. A build that asks the device what it is would report
 * "Android TV" on a device that is not a television at all — and the report would be
 * wrong exactly when it is supposed to help.
 *
 * Example:
 *   val platform = if (isFireTv) DiagnosticsPlatform.FIRE_TV else DiagnosticsPlatform.GOOGLE_TV
 */
object DiagnosticsPlatform {
    /** The platform of the `googletv` product flavour. */
    const val GOOGLE_TV = "Google TV"

    /** The platform of the `firetv` product flavour. */
    const val FIRE_TV = "Fire TV"
}

/**
 * SelfTestResult — the outcome of one check of the local self test.
 *
 * WHY the detail is part of the result: "ok" alone does not help anybody. The owner
 * of the television needs to know *what* was measured — "HTTP 200, 1842 bytes" or
 * "no listener on 127.0.0.1:7000" — to be able to act on it.
 *
 * @param name   The label of the check, already localized.
 * @param ok     True when the check passed.
 * @param detail The measured detail of the check, already localized.
 *
 * Example:
 *   SelfTestResult(name = "Local address", ok = true, detail = "192.168.1.42")
 */
data class SelfTestResult(
    val name: String,
    val ok: Boolean,
    val detail: String
)

/**
 * DiagnosticsPort — one network port of the receiver.
 *
 * @param name The label of the port, already localized ("AirPlay", "SSDP", …).
 * @param port The port number; 0 when the port is not known (an ephemeral port of a
 *   server that is not running cannot be read from anywhere).
 *
 * Example:
 *   DiagnosticsPort(name = "SSDP", port = 1900)
 */
data class DiagnosticsPort(
    val name: String,
    val port: Int
)

/**
 * DiagnosticSession — the streaming session that is running right now.
 *
 * WHY a separate model: the session is the one part of the report that changes
 * every second. Keeping it apart lets the screen show the duration without
 * rebuilding the whole report, and lets the report be built without a service.
 *
 * @param senderName     The name of the sender that streams right now.
 * @param protocol       The protocol the session uses.
 * @param durationMillis How long the session has been running, in milliseconds.
 *
 * Example:
 *   DiagnosticSession("Living Room MacBook", DiagnosticProtocol.AIRPLAY, 252_000)
 */
data class DiagnosticSession(
    val senderName: String,
    val protocol: DiagnosticProtocol,
    val durationMillis: Long
)

/**
 * DiagnosticsReport — the state of the receiver, as data.
 *
 * WHY a data model and not a text: the report is built from values that come from
 * different places (the settings, the running service, the local self test). Keeping
 * the values apart from the text lets the text be built — and tested — without an
 * Android device, and lets the screen refresh one part of the report without
 * rebuilding everything.
 *
 * Every text in this model is already localized: the labels are handed in by the UI
 * layer, so this package stays free of Android.
 *
 * @param deviceName     The name the television shows in the sender pickers.
 * @param localAddress   The IPv4 address the receiver advertises.
 * @param appVersion     The version name and the version code of the build.
 * @param platform       The product platform, see [DiagnosticsPlatform].
 * @param serviceState   The state of the receiver service, as text.
 * @param protocolStates The state of every protocol, as text.
 * @param ports          The ports the receiver listens on.
 * @param selfTestResults The results of the last self test run.
 * @param resourceSample The last measurement of the memory and the CPU load of
 *   the app process, or null when nothing has been measured yet.
 *
 * Example:
 *   val report = DiagnosticsReport(
 *       deviceName = "Living Room TV",
 *       localAddress = "192.168.1.42",
 *       appVersion = "1.0.0-alpha.1-googletv (4)",
 *       platform = DiagnosticsPlatform.GOOGLE_TV,
 *       serviceState = "Running",
 *       protocolStates = mapOf(DiagnosticProtocol.AIRPLAY to "Advertising"),
 *       ports = listOf(DiagnosticsPort("AirPlay", 7000)),
 *       selfTestResults = emptyList()
 *   )
 */
data class DiagnosticsReport(
    val deviceName: String,
    val localAddress: String,
    val appVersion: String,
    val platform: String,
    val serviceState: String,
    val protocolStates: Map<DiagnosticProtocol, String>,
    val ports: List<DiagnosticsPort>,
    val selfTestResults: List<SelfTestResult>,
    /** The session that is streaming right now, or null when nothing streams. */
    val activeSession: DiagnosticSession? = null,
    /** The last measurement of the own resources, or null when nothing was measured yet. */
    val resourceSample: ResourceSample? = null
)

/**
 * SelfTestReport — the result of a self test run.
 *
 * @param results             One result per check, in the order the checks ran.
 * @param dlnaDescriptionPort The port of the DLNA description server, or null when the
 *   server did not answer and the port could not be found out.
 *
 * Example:
 *   val outcome = SelfTest(labels).run()
 *   outcome.dlnaDescriptionPort   // 49152
 */
data class SelfTestReport(
    val results: List<SelfTestResult>,
    val dlnaDescriptionPort: Int?
)
