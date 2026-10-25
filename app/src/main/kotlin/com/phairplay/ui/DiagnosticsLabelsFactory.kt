package com.phairplay.ui

import android.content.Context
import com.phairplay.R
import com.phairplay.diagnostics.DiagnosticsLabels
import com.phairplay.service.ProtocolState
import com.phairplay.service.ServiceState

/**
 * DiagnosticsLabelsFactory — reads the labels of the diagnostics report from the resources.
 *
 * WHY the factory exists: the report is built by code that must not depend on
 * Android. The labels are therefore read here — in the UI layer, where `R.string`
 * lives — and handed to the builder. The same builder produces an English report on
 * an English television and a German report on a German one.
 *
 * HOW: the protocol names and the protocol states reuse the strings the Home screen
 * already shows, so a sender never sees two different names for the same protocol.
 *
 * Example:
 *   val labels = DiagnosticsLabelsFactory.create(requireContext())
 */
internal object DiagnosticsLabelsFactory {

    /**
     * Builds the labels of the report.
     *
     * @param context the context whose resources are read.
     * @return the labels of the report.
     */
    fun create(context: Context): DiagnosticsLabels = DiagnosticsLabels(
        title = context.getString(R.string.diagnostics_title),
        device = context.getString(R.string.diagnostics_device),
        address = context.getString(R.string.diagnostics_address),
        version = context.getString(R.string.diagnostics_version),
        platform = context.getString(R.string.diagnostics_platform),
        service = context.getString(R.string.diagnostics_service),
        sectionProtocols = context.getString(R.string.diagnostics_section_protocols),
        sectionPorts = context.getString(R.string.diagnostics_section_ports),
        sectionSelfTest = context.getString(R.string.diagnostics_section_self_test),
        unknown = context.getString(R.string.diagnostics_unknown),
        protocolAirPlay = context.getString(R.string.protocol_airplay),
        protocolMiracast = context.getString(R.string.protocol_miracast),
        protocolCast = context.getString(R.string.protocol_cast),
        protocolDlna = context.getString(R.string.protocol_dlna),
        portAirPlay = context.getString(R.string.protocol_airplay),
        portDlnaDescription = context.getString(R.string.diagnostics_port_dlna_description),
        portSsdp = context.getString(R.string.diagnostics_port_ssdp),
        selfTestAddress = context.getString(R.string.diagnostics_address),
        selfTestDescriptionServer = context.getString(R.string.diagnostics_selftest_description),
        selfTestSsdp = context.getString(R.string.diagnostics_selftest_ssdp),
        selfTestAirPlayPort = context.getString(R.string.diagnostics_selftest_airplay_port),
        selfTestProcess = context.getString(R.string.diagnostics_selftest_process),
        statusOk = context.getString(R.string.diagnostics_status_ok),
        statusFailed = context.getString(R.string.diagnostics_status_failed),
        notRunYet = context.getString(R.string.diagnostics_not_run_yet),
        reasonNoAddress = context.getString(R.string.diagnostics_reason_no_address),
        reasonNoListener = context.getString(R.string.diagnostics_reason_no_listener),
        reasonPortUnknown = context.getString(R.string.diagnostics_reason_port_unknown),
        reasonNoSsdpAnswer = context.getString(R.string.diagnostics_reason_no_ssdp_answer),
        reasonSocketFailed = context.getString(R.string.diagnostics_reason_socket_failed),
        reasonNotMeasured = context.getString(R.string.diagnostics_reason_not_measured),
        detailHttpOk = { statusCode, bodyBytes ->
            context.resources.getQuantityString(
                R.plurals.diagnostics_detail_http_ok,
                bodyBytes,
                statusCode,
                bodyBytes
            )
        },
        detailHttpFailed = context.getString(R.string.diagnostics_detail_http_failed),
        detailResidentMemory = context.getString(R.string.diagnostics_detail_resident_memory)
    )

    /**
     * The text of a protocol state.
     *
     * WHY the same strings as the Home screen: the owner compares the two screens
     * when something does not work. Two different words for the same state would
     * only make that harder.
     *
     * @param context the context whose resources are read.
     * @param state the state of a protocol.
     * @return the localized text of the state.
     */
    fun protocolStateText(context: Context, state: ProtocolState): String = when (state) {
        ProtocolState.DISABLED -> context.getString(R.string.protocol_state_disabled)
        ProtocolState.ADVERTISING -> context.getString(R.string.protocol_state_advertising)
        ProtocolState.CONNECTED -> context.getString(R.string.protocol_state_connected)
        ProtocolState.ERROR -> context.getString(R.string.protocol_state_error)
    }

    /**
     * The text of the state of the service.
     *
     * @param context the context whose resources are read.
     * @param state the state of the service.
     * @return the localized text of the state; an error carries its message.
     */
    fun serviceStateText(context: Context, state: ServiceState): String = when (state) {
        is ServiceState.Running -> context.getString(R.string.service_state_running)
        is ServiceState.Stopped -> context.getString(R.string.service_state_stopped)
        is ServiceState.Restarting -> context.getString(R.string.service_state_restarting)
        is ServiceState.Error ->
            if (state.message.isBlank()) {
                context.getString(R.string.service_state_error)
            } else {
                context.getString(R.string.service_state_error) + ": " + state.message
            }
    }
}
