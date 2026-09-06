package com.phairplay.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.view.Surface
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DlnaReceiver — top-level orchestrator for the DLNA MediaRenderer, the DLNA counterpart of `AirPlayReceiver`.
 *
 * WHY: Wires discovery ([SsdpAdvertiser]), the HTTP server + router, the SOAP services, GENA eventing and the
 * [MediaRenderer] into one lifecycle that `PhairPlayService` starts and stops, and holds the multicast lock
 * without which Android Wi-Fi drops SSDP traffic.
 *
 * HOW:
 *   val receiver = DlnaReceiver(context, displayName, versionName, videoSurfaceProvider = { surface },
 *       isAirPlayConnected = { … }, onStateChanged = { … })
 *   receiver.start(); receiver.remote(RemoteCommand.PLAY_PAUSE); receiver.stop()
 */
class DlnaReceiver(
    private val context: Context,
    /** User-configured display name (blank = system device name), shown as the UPnP friendlyName. */
    private val displayName: String,
    private val versionName: String,
    private val videoSurfaceProvider: () -> Surface?,
    private val isAirPlayConnected: () -> Boolean,
    private val onStateChanged: (ProtocolState) -> Unit,
    private val onSenderNameChanged: (String) -> Unit = {},
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit = {},
    private val onPhotoReceived: (bytes: ByteArray, mimeType: String) -> Unit = { _, _ -> },
    private val onPhotoCleared: () -> Unit = {}
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val udn = "uuid:" + NetworkUtils.getPersistentUuid(context)

    private var multicastLock: WifiManager.MulticastLock? = null
    private var httpServer: DlnaHttpServer? = null
    private var advertiser: SsdpAdvertiser? = null
    private var events: EventSubscriptions? = null
    private var renderer: MediaRenderer? = null
    @Volatile private var boundPort = 0

    fun start() {
        Logger.i("DlnaReceiver starting (displayName='$displayName')")
        scope.launch {
            try {
                startInternal()
            } catch (e: Exception) {
                Logger.e("Failed to start DlnaReceiver", e)
                onStateChanged(ProtocolState.ERROR)
            }
        }
    }

    fun stop() {
        Logger.i("DlnaReceiver stopping")
        try {
            advertiser?.stop()
            httpServer?.stop()
            renderer?.release()
        } catch (e: Exception) {
            Logger.e("Error during DlnaReceiver stop", e)
        } finally {
            releaseMulticastLock()
            scope.cancel()
            onStateChanged(ProtocolState.DISABLED)
        }
    }

    /** TV-remote media keys while DLNA is connected. No-op before start. */
    fun remote(command: RemoteCommand) {
        renderer?.remote(command)
    }

    // ─── Startup ─────────────────────────────────────────────────────────────

    private fun startInternal() {
        acquireMulticastLock()
        val friendlyName = displayName.trim().ifEmpty { NetworkUtils.getDeviceName(context) }
        val description = DeviceDescription(friendlyName, udn, versionName)
        val icon = DlnaIcon.png(context)

        val subscriptions = EventSubscriptions(HttpNotifySender(), initialState = { initialEventState(it) })
        events = subscriptions
        val mediaRenderer = MediaRenderer(
            player = DlnaPlayer(context, videoSurfaceProvider),
            photos = PhotoFetcher(),
            isAirPlayConnected = isAirPlayConnected,
            callbacks = RendererCallbacks(
                onProtocolState = onStateChanged,
                onSnapshot = { publishSnapshot(it) },
                onNowPlaying = onNowPlayingChanged,
                onPhoto = onPhotoReceived,
                onPhotoCleared = onPhotoCleared,
                onSenderName = onSenderNameChanged
            ),
            scope = scope
        )
        renderer = mediaRenderer

        val dispatcher = SoapDispatcher(
            mapOf(
                UpnpService.AV_TRANSPORT to AvTransportService(mediaRenderer),
                UpnpService.RENDERING_CONTROL to RenderingControlService(mediaRenderer),
                UpnpService.CONNECTION_MANAGER to ConnectionManagerService()
            )
        )
        val router = DlnaRouter(description, dispatcher, subscriptions, icon = { icon }, baseUrl = { baseUrl() })
        val server = DlnaHttpServer(scope) { router.handle(it) }
        httpServer = server
        boundPort = server.start()

        advertiser = SsdpAdvertiser(scope, udn) { baseUrl() + DlnaConstants.DESCRIPTION_PATH }.also { it.start() }

        scope.launch { while (isActive) { delay(DlnaConstants.EVENT_MODERATION_MS); subscriptions.flushPending() } }
        scope.launch { while (isActive) { delay(DlnaConstants.EXPIRY_SWEEP_MS); subscriptions.sweepExpired() } }

        onStateChanged(ProtocolState.ADVERTISING)
        Logger.i("DLNA renderer '$friendlyName' ready at ${baseUrl()}")
    }

    private fun baseUrl(): String = "http://${LocalAddress.ipv4() ?: LOOPBACK}:$boundPort"

    private fun publishSnapshot(snapshot: RendererSnapshot) {
        val subscriptions = events ?: return
        subscriptions.publish(
            UpnpService.AV_TRANSPORT,
            mapOf(LAST_CHANGE to LastChangeEncoder.avTransport(LastChangeEncoder.avTransportVars(snapshot)))
        )
        subscriptions.publish(
            UpnpService.RENDERING_CONTROL,
            mapOf(LAST_CHANGE to LastChangeEncoder.renderingControl(LastChangeEncoder.renderingControlVars(snapshot)))
        )
    }

    /** Full evented state for the GENA initial event (SEQ 0). */
    private fun initialEventState(service: UpnpService): Map<String, String> {
        val snapshot = renderer?.snapshot() ?: RendererSnapshot()
        return when (service) {
            UpnpService.AV_TRANSPORT ->
                mapOf(LAST_CHANGE to LastChangeEncoder.avTransport(LastChangeEncoder.avTransportVars(snapshot)))
            UpnpService.RENDERING_CONTROL ->
                mapOf(LAST_CHANGE to LastChangeEncoder.renderingControl(LastChangeEncoder.renderingControlVars(snapshot)))
            UpnpService.CONNECTION_MANAGER -> mapOf(
                "SourceProtocolInfo" to "",
                "SinkProtocolInfo" to ProtocolInfoList.sinkString(),
                "CurrentConnectionIDs" to ConnectionManagerService.CONNECTION_ID
            )
        }
    }

    // ─── Multicast lock ──────────────────────────────────────────────────────

    private fun acquireMulticastLock() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
            .onFailure { Logger.w("MulticastLock release failed: ${it.message}") }
        multicastLock = null
    }

    companion object {
        private const val LAST_CHANGE = "LastChange"
        private const val LOOPBACK = "127.0.0.1"
        private const val MULTICAST_LOCK_TAG = "phairplay-dlna-ssdp"
    }
}
