package com.phairplay.diagnostics

import com.phairplay.dlna.DlnaNetworkAddress
import com.phairplay.dlna.SsdpMessages
import com.phairplay.util.HostAddressPolicy
import java.io.File

/**
 * DiagnosticsPorts — the fixed ports of the receiver.
 *
 * WHY the AirPlay port is repeated here: the port of the RTSP server is a private
 * constant of [com.phairplay.airplay.RtspServer]. The diagnostics screen must not
 * change the production code, so the value is repeated here with a reference to its
 * origin. A finding for the owning track: the port of the AirPlay receiver should be
 * published by the AirPlay package instead of being copied.
 *
 * Example:
 *   DiagnosticsPorts.AIRPLAY   // 7000
 */
object DiagnosticsPorts {
    /** The TCP port the AirPlay (RTSP) receiver listens on. */
    const val AIRPLAY = 7000
}

/**
 * SelfTest — checks the receiver on the device itself, without a sender.
 *
 * WHY: the owner of the television is not an Android developer and has no logcat.
 * The only thing that helps is a screen that answers the question "what is wrong
 * here?" with measurements instead of guesses. Every check of this class runs
 * locally: the address of the device, the local HTTP server, the multicast group,
 * the AirPlay port and the memory of the app process.
 *
 * HOW: the checks run in this order, because they build on each other:
 *  1. the address — is there a usable IPv4 address at all,
 *  2. the SSDP search — can the device join the multicast group and does the
 *     renderer answer? The answer names the port of the description server,
 *  3. the description server — does the local HTTP server answer with 200,
 *  4. the AirPlay port — does something listen on port 7000,
 *  5. the app process — is the process alive and how much memory does it use.
 *
 * Every check returns a [SelfTestResult] with a measured detail. A check that
 * cannot measure anything says so ("not measured") instead of guessing a number.
 *
 * Example:
 *   val outcome = SelfTest(labels).run()
 *   outcome.results.forEach { println("${it.name}: ${it.detail}") }
 */
class SelfTest(
    /** The localized labels of the report. */
    private val labels: DiagnosticsLabels,
    /** How long a single check may take. */
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS
) {

    /**
     * Runs every check of the self test.
     *
     * @return the results of the checks and the port of the description server.
     */
    fun run(): SelfTestReport {
        val ssdp = SsdpProbe.probe(timeoutMillis)
        val results = listOf(
            checkAddress(),
            checkDescriptionServer(ssdp),
            checkSsdp(ssdp),
            checkAirPlayPort(),
            checkProcess()
        )
        return SelfTestReport(
            results = results,
            dlnaDescriptionPort = ssdp.descriptionPort
        )
    }

    /**
     * Checks whether the device has an IPv4 address a control point can reach.
     *
     * WHY the policy of the receiver is used: [HostAddressPolicy] already knows which
     * addresses are useless for a peer (loopback, link-local, multicast). An address
     * that the receiver itself would refuse to advertise is not a usable address.
     */
    private fun checkAddress(): SelfTestResult {
        val address = DlnaNetworkAddress.localIpv4Address()
        val refusal = HostAddressPolicy.checkHost(address)
        return if (refusal == null) {
            SelfTestResult(labels.selfTestAddress, ok = true, detail = address)
        } else {
            SelfTestResult(
                labels.selfTestAddress,
                ok = false,
                detail = labels.format(labels.reasonNoAddress, address)
            )
        }
    }

    /**
     * Checks the local description server.
     *
     * WHY the port comes from the SSDP answer: the description server binds an
     * ephemeral port, and that port is not published anywhere else. The `LOCATION`
     * header of the SSDP answer is the only place the device itself names it.
     */
    private fun checkDescriptionServer(ssdp: SsdpProbeResult): SelfTestResult {
        val port = ssdp.descriptionPort
        if (port == null) {
            return SelfTestResult(
                labels.selfTestDescriptionServer,
                ok = false,
                detail = labels.reasonPortUnknown
            )
        }
        val answer = HttpProbe.fetchDescription(port, timeoutMillis)
        return when {
            !answer.connected -> SelfTestResult(
                labels.selfTestDescriptionServer,
                ok = false,
                detail = labels.format(
                    labels.reasonNoListener,
                    "${DiagnosticsLoopback.HOST}:$port"
                )
            )
            answer.statusCode == HTTP_OK -> SelfTestResult(
                labels.selfTestDescriptionServer,
                ok = true,
                detail = labels.format(labels.detailHttpOk, answer.statusCode, answer.bodyBytes)
            )
            else -> SelfTestResult(
                labels.selfTestDescriptionServer,
                ok = false,
                detail = labels.format(labels.detailHttpFailed, answer.statusCode)
            )
        }
    }

    /** Checks the multicast group of the SSDP discovery. */
    private fun checkSsdp(ssdp: SsdpProbeResult): SelfTestResult {
        val group = "${SsdpMessages.MULTICAST_ADDRESS}:${SsdpMessages.PORT}"
        return when {
            !ssdp.joined -> SelfTestResult(
                labels.selfTestSsdp,
                ok = false,
                detail = labels.format(labels.reasonSocketFailed, ssdp.failure ?: labels.unknown)
            )
            ssdp.answered -> SelfTestResult(
                labels.selfTestSsdp,
                ok = true,
                detail = group
            )
            else -> SelfTestResult(
                labels.selfTestSsdp,
                ok = false,
                detail = labels.format(labels.reasonNoSsdpAnswer, group, timeoutMillis)
            )
        }
    }

    /** Checks whether the AirPlay receiver listens on its port. */
    private fun checkAirPlayPort(): SelfTestResult {
        val endpoint = "${DiagnosticsLoopback.HOST}:${DiagnosticsPorts.AIRPLAY}"
        val listening = TcpProbe.isListening(
            host = DiagnosticsLoopback.HOST,
            port = DiagnosticsPorts.AIRPLAY,
            timeoutMillis = timeoutMillis
        )
        return if (listening) {
            SelfTestResult(labels.selfTestAirPlayPort, ok = true, detail = endpoint)
        } else {
            SelfTestResult(
                labels.selfTestAirPlayPort,
                ok = false,
                detail = labels.format(labels.reasonNoListener, endpoint)
            )
        }
    }

    /**
     * Reads the memory of the app process.
     *
     * WHY it is measured and not estimated: the number is read from the process
     * itself. If the file cannot be read, the result says "not measured" — a
     * made-up number would be worse than no number at all.
     */
    private fun checkProcess(): SelfTestResult {
        val residentKilobytes = residentMemoryKilobytes()
        return if (residentKilobytes != null) {
            SelfTestResult(
                labels.selfTestProcess,
                ok = true,
                detail = labels.format(labels.detailResidentMemory, residentKilobytes)
            )
        } else {
            SelfTestResult(
                labels.selfTestProcess,
                ok = false,
                detail = labels.reasonNotMeasured
            )
        }
    }

    /** Reads the resident memory of this process in kilobytes, or null when it cannot be read. */
    private fun residentMemoryKilobytes(): Long? = runCatching {
        val status = File("/proc/self/status").takeIf { it.canRead() } ?: return null
        status.bufferedReader().use { reader ->
            reader.lineSequence()
                .firstOrNull { it.startsWith(MEMORY_LINE_PREFIX) }
                ?.split(WHITESPACE)
                ?.getOrNull(1)
                ?.toLongOrNull()
        }
    }.getOrNull()

    companion object {
        /** How long a single check may take when the caller does not say otherwise. */
        const val DEFAULT_TIMEOUT_MILLIS = 2_000

        /** The HTTP status a healthy description server answers with. */
        private const val HTTP_OK = 200

        /** The prefix of the memory line in the status file of the process. */
        private const val MEMORY_LINE_PREFIX = "VmRSS:"

        /** Splits a line of the status file at its whitespace. */
        private val WHITESPACE = Regex("\\s+")
    }
}
