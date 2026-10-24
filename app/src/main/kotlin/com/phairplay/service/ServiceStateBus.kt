package com.phairplay.service

import com.phairplay.airplay.NowPlayingInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ServiceStateBus — Owns every piece of state the running service publishes.
 *
 * WHAT: One place that holds the observable state of [PhairPlayService]: the service
 * lifecycle state, the state of each of the four protocols, the active connection, the
 * latest received photo, the now-playing info and the pairing PIN.
 *
 * WHY: The service used to declare all of these flows itself. That mixed two different
 * concerns — "which state exists" and "what the Android service does" — and pushed the
 * service class past the 400-line limit of CONTRIBUTING.md. A small holder keeps the
 * state readable on its own and lets the components that publish changes do so without
 * reaching into the service.
 *
 * HOW: The service creates one bus, publishes the read-only views as its public API and
 * hands the bus to the components that need to write into it. Every flow comes in pairs:
 * the `…Flow` property is the mutable source, the plain property is the read-only view.
 *
 * Example:
 *   val bus = ServiceStateBus()
 *   bus.airPlayStateFlow.value = ProtocolState.ADVERTISING   // publish
 *   bus.airPlayState.collect { state -> render(state) }     // observe
 */
internal class ServiceStateBus {

    // ─── Service lifecycle ───────────────────────────────────────────────────

    /** Mutable source of the service state (Running / Stopped / Restarting / Error). */
    val serviceStateFlow = MutableStateFlow<ServiceState>(ServiceState.Stopped)

    /** Read-only view of [serviceStateFlow] — this is what the bound UI observes. */
    val serviceState: StateFlow<ServiceState> = serviceStateFlow.asStateFlow()

    // ─── Protocol states ─────────────────────────────────────────────────────

    /** Mutable source of the AirPlay protocol state. */
    val airPlayStateFlow = MutableStateFlow(ProtocolState.DISABLED)

    /** Read-only view of [airPlayStateFlow]. */
    val airPlayState: StateFlow<ProtocolState> = airPlayStateFlow.asStateFlow()

    /** Mutable source of the Miracast protocol state. */
    val miracastStateFlow = MutableStateFlow(ProtocolState.DISABLED)

    /** Read-only view of [miracastStateFlow]. */
    val miracastState: StateFlow<ProtocolState> = miracastStateFlow.asStateFlow()

    /** Mutable source of the Cast protocol state. */
    val castStateFlow = MutableStateFlow(ProtocolState.DISABLED)

    /** Read-only view of [castStateFlow]. */
    val castState: StateFlow<ProtocolState> = castStateFlow.asStateFlow()

    /** Mutable source of the DLNA protocol state. */
    val dlnaStateFlow = MutableStateFlow(ProtocolState.DISABLED)

    /** Read-only view of [dlnaStateFlow]. */
    val dlnaState: StateFlow<ProtocolState> = dlnaStateFlow.asStateFlow()

    // ─── Session state ───────────────────────────────────────────────────────

    /** Mutable source of the currently streaming connection (null when nothing streams). */
    val activeConnectionFlow = MutableStateFlow<ActiveConnection?>(null)

    /** Read-only view of [activeConnectionFlow]. */
    val activeConnection: StateFlow<ActiveConnection?> = activeConnectionFlow.asStateFlow()

    /** Mutable source of the latest still image received via AirPlay `/photo`. */
    val photoFrameFlow = MutableStateFlow<PhotoFrame?>(null)

    /** Read-only view of [photoFrameFlow]. */
    val photoFrame: StateFlow<PhotoFrame?> = photoFrameFlow.asStateFlow()

    /** Mutable source of the audio-only "now playing" information. */
    val nowPlayingFlow = MutableStateFlow<NowPlayingInfo?>(null)

    /** Read-only view of [nowPlayingFlow]. */
    val nowPlaying: StateFlow<NowPlayingInfo?> = nowPlayingFlow.asStateFlow()

    /** Mutable source of the SRP pairing PIN that has to be shown on the TV. */
    val pairingPinFlow = MutableStateFlow<String?>(null)

    /** Read-only view of [pairingPinFlow]. */
    val pairingPin: StateFlow<String?> = pairingPinFlow.asStateFlow()
}
