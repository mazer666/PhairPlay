package com.phairplay.service

import android.content.Context
import android.view.Surface
import com.phairplay.airplay.AirPlayReceiver
import com.phairplay.cast.CastReceiver
import com.phairplay.dlna.DlnaReceiver
import com.phairplay.miracast.MiracastReceiver
import com.phairplay.settings.AppSettings
import com.phairplay.util.Logger

/**
 * ReceiverSupervisor — Owns the four protocol receivers and their start/stop lifecycle.
 *
 * WHAT: Creates the AirPlay, Miracast, Cast and DLNA receivers, starts them, stops them
 * and translates their callbacks into the published service state (protocol state,
 * active connection, photo, now-playing info, pairing PIN) and the notification text.
 *
 * WHY: [PhairPlayService] used to do all of this itself, which made the service class
 * grow past the 400-line limit of CONTRIBUTING.md and mixed two unrelated concerns: the
 * Android service lifecycle and the lifecycle of four independent protocols. The
 * supervisor keeps the protocol-specific wiring in one place, so the service only has to
 * say "start" and "stop".
 *
 * HOW: The service creates one supervisor in `onCreate()` and calls [startAirPlay],
 * [startMiracast], [startCast] and [startDlna] for the protocols that are enabled in the
 * settings. [stopAllReceiversInternal] releases every receiver again. The supervisor
 * publishes into the shared [ServiceStateBus] and writes the notification through
 * [ServiceNotificationManager].
 *
 * Example:
 *   val supervisor = ReceiverSupervisor(context, stateBus, notifications, surfaceProvider) { bringToFront() }
 *   supervisor.startAirPlay(settings)
 *   supervisor.stopAllReceiversInternal()
 */
internal class ReceiverSupervisor(
    /** Application context — the receivers outlive the Activity that started them. */
    private val context: Context,
    /** Shared state holder the receivers publish into. */
    private val states: ServiceStateBus,
    /** Notification writer for the "now streaming from …" status line. */
    private val notifications: ServiceNotificationManager,
    /** Lazy provider for the video Surface — supplied by the bound Activity. */
    private val surfaceProvider: () -> Surface?,
    /** Brings the streaming screen to the front — the service owns the Activity intent. */
    private val bringToFront: () -> Unit
) {

    // Local aliases for the shared state flows. The receiver code below is unchanged;
    // it only publishes into the bus instead of into the service.
    private val _airPlayState = states.airPlayStateFlow
    private val _miracastState = states.miracastStateFlow
    private val _castState = states.castStateFlow
    private val _dlnaState = states.dlnaStateFlow
    private val _activeConnection = states.activeConnectionFlow
    private val _photoFrame = states.photoFrameFlow
    private val _nowPlaying = states.nowPlayingFlow
    private val _pairingPin = states.pairingPinFlow

    // Receiver instances — null when not running
    private var airPlayReceiver: AirPlayReceiver? = null
    private var miracastReceiver: MiracastReceiver? = null
    private var castReceiver: CastReceiver? = null
    private var dlnaReceiver: DlnaReceiver? = null

    // ─── Individual Protocol Starters ────────────────────────────────────────

    /**
     * Creates and starts the [AirPlayReceiver].
     *
     * The display name comes from settings — blank means use the Android device name,
     * which [com.phairplay.airplay.MdnsService] resolves at runtime.
     *
     * Surface is not available here (it lives in the Activity/Fragment).
     * The surface provider is wired up from [com.phairplay.MainActivity] in Sprint 5.
     * Until then, video frames are silently discarded and only audio plays.
     *
     * @param settings Current app settings; read once per start/restart cycle.
     */
    internal fun startAirPlay(settings: AppSettings) {
        // Mirror the debug-overlay setting into the shared stats bus that StreamingScreen reads.
        com.phairplay.airplay.StreamStats.overlayEnabled = settings.showDebugOverlay

        // Report "Advertising" right away, like Miracast/Cast/DLNA do.
        //
        // WHY: The AirPlay receiver only announces ADVERTISING once BOTH mDNS registrations
        // are confirmed. Until then the state stayed at its initial DISABLED value, and the
        // Home screen rendered that as "Disabled — Enable in Settings". Users then toggled a
        // switch that was already on, because the message told them to (issue #18).
        //
        // DISABLED now means exactly one thing: "the user switched this protocol off".
        // A registration that fails ends in ERROR, which is a different message.
        _airPlayState.value = ProtocolState.ADVERTISING

        // Idempotent: a redundant ACTION_START (e.g. the activity being recreated while the
        // foreground service is still alive) must NOT spin up a second AirPlayReceiver competing
        // for port 7000. The existing receiver keeps running and picks up the new Surface via the
        // surfaceProvider. A genuine restart goes through ACTION_RESTART (stop → delay → start).
        if (airPlayReceiver != null) {
            Logger.i("AirPlay receiver already running — skipping duplicate start")
            return
        }
        // Captures the sender name reported by AirPlayReceiver before CONNECTED fires.
        // onSenderNameChanged is called synchronously before emitState(CONNECTED), so
        // this assignment happens-before the Main-thread read in onStateChanged.
        var pendingSenderName = "AirPlay Sender"

        // Whether the session that just started carries video (see onVideoSessionChanged).
        var pendingHasVideo = false

        airPlayReceiver = AirPlayReceiver(
            context = context,
            displayName = settings.effectiveDisplayName,
            mirrorWidth = settings.mirrorWidth,
            mirrorHeight = settings.mirrorHeight,
            audioEnabled = settings.mirrorAudioEnabled,
            pinAuthEnabled = settings.airPlayPinAuthEnabled,
            // Delegate to the current provider at call time — captures the field, not a fixed value.
            // When MainActivity calls setVideoSurfaceProvider(), future surface requests use it.
            videoSurfaceProvider = surfaceProvider,
            onSenderNameChanged = { name ->
                pendingSenderName = name.ifEmpty { "AirPlay Sender" }
            },
            // Whether the session that just started carries video. Only a video session
            // needs the streaming Surface, so only a video session may pull the app to
            // the front — an audio-only session (Apple Music, a podcast) must not
            // interrupt whatever the user is watching on the TV.
            onVideoSessionChanged = { hasVideo ->
                pendingHasVideo = hasVideo
            },
            onPhotoReceived = { bytes, imageType ->
                _photoFrame.value = PhotoFrame(
                    bytes = bytes.copyOf(),
                    mimeType = imageType.mimeType
                )
                notifications.updateNotification(isRunning = true)
            },
            onPhotoCleared = {
                _photoFrame.value = null
            },
            onNowPlayingChanged = { info ->
                _nowPlaying.value = info
            },
            onPinChanged = { pin ->
                _pairingPin.value = pin
            },
            onStateChanged = { state ->
                _airPlayState.value = state
                when (state) {
                    ProtocolState.CONNECTED   -> {
                        _photoFrame.value = null
                        _activeConnection.value =
                            ActiveConnection(pendingSenderName, Protocol.AIRPLAY)
                        notifications.updateNotification(isRunning = true, streamingSenderName = pendingSenderName)
                        // Only a video session needs the streaming Surface — an audio-only
                        // session must not pull the app to the front.
                        if (pendingHasVideo) bringToFront()
                    }
                    ProtocolState.ADVERTISING,
                    ProtocolState.DISABLED,
                    ProtocolState.ERROR       -> {
                        _activeConnection.value = null
                        pendingHasVideo = false
                        notifications.updateNotification(isRunning = state != ProtocolState.DISABLED &&
                                                       state != ProtocolState.ERROR)
                    }
                }
            }
        ).also { it.start() }
        Logger.d("AirPlay receiver started (displayName='${settings.effectiveDisplayName}')")
    }

    /** Creates and starts the [MiracastReceiver]. */
    internal fun startMiracast() {
        _miracastState.value = ProtocolState.ADVERTISING
        miracastReceiver = MiracastReceiver(
            context = context,
            onStateChanged = { state -> _miracastState.value = state }
        ).also { it.start() }
        Logger.d("Miracast receiver started")
    }

    /** Creates and starts the [CastReceiver]. */
    internal fun startCast() {
        _castState.value = ProtocolState.ADVERTISING
        castReceiver = CastReceiver(
            context = context,
            onStateChanged = { state -> _castState.value = state }
        ).also { it.start() }
        Logger.d("Cast receiver started")
    }

    /** Creates and starts the [DlnaReceiver]. */
    internal fun startDlna(settings: AppSettings) {
        _dlnaState.value = ProtocolState.ADVERTISING
        dlnaReceiver = DlnaReceiver(
            context = context,
            displayName = settings.effectiveDisplayName,
            surfaceProvider = surfaceProvider,
            onPhotoReceived = { bytes, mimeType ->
                _photoFrame.value = PhotoFrame(
                    bytes = bytes.copyOf(),
                    mimeType = mimeType
                )
                notifications.updateNotification(isRunning = true, streamingSenderName = "DLNA Controller")
            },
            onPhotoCleared = {
                _photoFrame.value = null
            },
            onStateChanged = { state ->
                _dlnaState.value = state
                when (state) {
                    ProtocolState.CONNECTED -> {
                        _activeConnection.value = ActiveConnection("DLNA Controller", Protocol.DLNA)
                        notifications.updateNotification(isRunning = true, streamingSenderName = "DLNA Controller")
                    }
                    ProtocolState.ADVERTISING,
                    ProtocolState.DISABLED,
                    ProtocolState.ERROR -> {
                        if (_activeConnection.value?.protocol == Protocol.DLNA) {
                            _activeConnection.value = null
                        }
                        notifications.updateNotification(isRunning = state == ProtocolState.ADVERTISING)
                    }
                }
            }
        ).also { it.start() }
        Logger.d("DLNA receiver started")
    }

    /**
     * Stops every receiver and clears the session state.
     *
     * Every receiver is stopped inside its own try/catch: one failing protocol must not
     * keep the other three running, and it must not stop the remaining resources from
     * being released.
     */
    internal fun stopAllReceiversInternal() {
        try { airPlayReceiver?.stop() } catch (e: Exception) { Logger.e("AirPlay stop error", e) }
        try { miracastReceiver?.stop() } catch (e: Exception) { Logger.e("Miracast stop error", e) }
        try { castReceiver?.stop() } catch (e: Exception) { Logger.e("Cast stop error", e) }
        try { dlnaReceiver?.stop() } catch (e: Exception) { Logger.e("DLNA stop error", e) }
        airPlayReceiver = null
        miracastReceiver = null
        castReceiver = null
        dlnaReceiver = null
        _airPlayState.value = ProtocolState.DISABLED
        _miracastState.value = ProtocolState.DISABLED
        _castState.value = ProtocolState.DISABLED
        _dlnaState.value = ProtocolState.DISABLED
        _photoFrame.value = null
        _nowPlaying.value = null
        _pairingPin.value = null
    }

    // ─── Commands forwarded to a running receiver ────────────────────────────

    /**
     * Sends a DACP transport command (TV remote → AirPlay sender), e.g. play/pause or skip
     * what the Mac/iPhone is streaming. No-op if no AirPlay sender has advertised a DACP
     * identity.
     */
    internal fun sendAirPlayRemoteCommand(command: String) {
        airPlayReceiver?.sendRemoteCommand(command)
    }

    /** Sends a DAVP transport command to the DLNA renderer (play/pause/skip). */
    internal fun sendDlnaRemoteCommand(command: String) {
        dlnaReceiver?.sendRemoteCommand(command)
    }

    /**
     * Repeats the mDNS advertisement of the AirPlay receiver.
     *
     * Called by the [NetworkRecoveryMonitor] after the network came back — an NsdManager
     * registration does not survive a network outage.
     */
    internal fun readvertiseAirPlay() {
        airPlayReceiver?.readvertise()
    }

    /**
     * Repeats the advertisements of every receiver that has one.
     *
     * WHY both protocols: after a network outage the mDNS registration of the AirPlay
     * receiver is gone, and the DLNA renderer has to announce itself again — with a
     * new description URL when the device came back with a different IP address.
     * FR-61 asks every enabled receiver to re-advertise; a receiver that fails must
     * not keep the other one from advertising itself.
     */
    internal fun readvertiseAll() {
        readvertiseAirPlay()
        try {
            dlnaReceiver?.readvertise()
        } catch (e: Exception) {
            Logger.e("DLNA re-advertisement error", e)
        }
    }
}
