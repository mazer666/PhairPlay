package com.phairplay.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.Surface
import androidx.lifecycle.LifecycleCoroutineScope
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.service.PhotoFrame
import com.phairplay.service.PhairPlayService
import com.phairplay.service.ProtocolState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ServiceConnectionBinder — Binds the Activity to the service and mirrors its state.
 *
 * WHAT: Owns the [ServiceConnection] to [PhairPlayService], keeps the reference to the
 * bound service, hands the service the video [Surface] and collects the five state flows
 * that the streaming overlay reacts to.
 *
 * WHY: [com.phairplay.MainActivity] used to hold all of this itself, which mixed the
 * Android service lifecycle with the Activity's own view handling and pushed the class
 * past the 400-line limit of CONTRIBUTING.md. The binder keeps the service plumbing in
 * one place, so the Activity is left with the UI and nothing else.
 *
 * HOW: The Activity creates one binder with its context, its lifecycle scope and a
 * surface provider, calls [bind] in `onStart()` and [unbind] in `onStop()`. Every
 * collected value is mirrored into the property of the same name and reported through
 * the [onStateChanged] callback, which the Activity uses to pick the overlay.
 *
 * Example:
 *   val binder = ServiceConnectionBinder(context, lifecycleScope, { surface }) { updateOverlay() }
 *   binder.bind()    // connects to the service and starts collecting
 *   binder.unbind()  // releases the Surface and unbinds the service
 */
internal class ServiceConnectionBinder(
    /** Context used to bind the service — the Activity's own context. */
    private val context: Context,
    /** Scope the state collectors live in — cancelled together with the Activity. */
    private val scope: LifecycleCoroutineScope,
    /** Supplies the video Surface to the service (null while no Surface exists). */
    private val surfaceProvider: () -> Surface?,
    /** Called after every mirrored state change. */
    private val onStateChanged: () -> Unit
) {

    // The bound service — null while the Activity is not bound.
    private var service: PhairPlayService? = null

    // Whether the service is currently bound.
    private var isBound = false

    /** Mirrored AirPlay protocol state — the last value of [PhairPlayService.airPlayState]. */
    var airPlayState: ProtocolState = ProtocolState.DISABLED
        private set

    /** Mirrored DLNA protocol state — the last value of [PhairPlayService.dlnaState]. */
    var dlnaState: ProtocolState = ProtocolState.DISABLED
        private set

    /** Mirrored still image — the last value of [PhairPlayService.photoFrame]. */
    var photoFrame: PhotoFrame? = null
        private set

    /** Mirrored now-playing information — the last value of [PhairPlayService.nowPlaying]. */
    var nowPlaying: NowPlayingInfo? = null
        private set

    /** Mirrored pairing PIN — the last value of [PhairPlayService.pairingPin]. */
    var pairingPin: String? = null
        private set

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? PhairPlayService.LocalBinder)?.getService()
            isBound = true
            Timber.d("MainActivity: bound to PhairPlayService")

            // Wire the streaming Surface so the service can pass it to VideoDecoder
            service?.setVideoSurfaceProvider { surfaceProvider() }

            // Show/hide the full-screen overlay for video streams and photos.
            observeState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            Timber.d("MainActivity: unbound from PhairPlayService")
        }
    }

    /**
     * Binds to [PhairPlayService].
     *
     * `BIND_AUTO_CREATE` starts the service when it is not running yet, so the Activity
     * always has a service to talk to.
     */
    fun bind() {
        val intent = Intent(context, PhairPlayService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Releases the video Surface and unbinds from [PhairPlayService].
     *
     * The Surface reference is cleared first: a Surface of a destroyed window must not
     * stay referenced by the service, otherwise the decoder writes into a dead buffer.
     */
    fun unbind() {
        service?.setVideoSurfaceProvider { null }
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
        }
    }

    /**
     * Sends a DACP transport command (TV remote → AirPlay sender), e.g. play/pause or
     * skip what the Mac/iPhone is streaming. No-op while the service is not bound.
     */
    fun sendAirPlayRemoteCommand(command: String) {
        service?.sendAirPlayRemoteCommand(command)
    }

    /**
     * Sends a DAVP transport command to the DLNA renderer (play/pause/skip).
     * No-op while the service is not bound.
     */
    fun sendDlnaRemoteCommand(command: String) {
        service?.sendDlnaRemoteCommand(command)
    }

    /**
     * Collects the five state flows the streaming overlay depends on.
     *
     * Called once after the service is bound. The coroutines are automatically cancelled
     * by the lifecycle scope when the Activity is destroyed.
     */
    private fun observeState() {
        val svc = service ?: return
        scope.launch {
            svc.airPlayState.collectLatest { state ->
                airPlayState = state
                onStateChanged()
            }
        }
        scope.launch {
            svc.dlnaState.collectLatest { state ->
                dlnaState = state
                onStateChanged()
            }
        }
        scope.launch {
            svc.photoFrame.collectLatest { frame ->
                photoFrame = frame
                onStateChanged()
            }
        }
        scope.launch {
            svc.nowPlaying.collectLatest { info ->
                nowPlaying = info
                onStateChanged()
            }
        }
        scope.launch {
            svc.pairingPin.collectLatest { pin ->
                pairingPin = pin
                onStateChanged()
            }
        }
    }
}
