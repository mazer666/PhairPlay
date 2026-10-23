package com.phairplay.dlna

import android.content.Context
import android.view.Surface
import com.phairplay.airplay.AirPlayVideoPlayer
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/**
 * DLNA MediaRenderer discovery receiver.
 *
 * D1 exposes PhairPlay to UPnP control points via SSDP and static device descriptors. SOAP
 * control and media playback are intentionally implemented in later phases.
 */
class DlnaReceiver(
    private val context: Context,
    private val displayName: String,
    private val surfaceProvider: () -> Surface?,
    private val onPhotoReceived: (ByteArray, String) -> Unit,
    private val onPhotoCleared: () -> Unit,
    private val onStateChanged: (ProtocolState) -> Unit
) : DlnaPlaybackController {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var descriptionServer: DlnaDescriptionServer? = null
    private var ssdpAdvertiser: SsdpAdvertiser? = null
    private val mediaPlayer = AirPlayVideoPlayer(
        surfaceProvider = surfaceProvider,
        onEnded = {
            Logger.i("DLNA media playback ended")
            onStateChanged(ProtocolState.ADVERTISING)
        }
    )
    @Volatile private var currentUri: String = ""

    fun start() {
        try {
            val localHost = localIpv4Address()
            val friendlyName = displayName.ifBlank { NetworkUtils.getDeviceName(context) }
            val udn = "uuid:${NetworkUtils.getPersistentUuid(context)}"
            val server = DlnaDescriptionServer(friendlyName, udn, localHost, this).also {
                it.start(scope)
            }
            val advertiser = SsdpAdvertiser(
                location = server.descriptionUrl,
                udn = udn
            ).also {
                it.start(scope)
            }
            descriptionServer = server
            ssdpAdvertiser = advertiser
            Logger.i("DLNA MediaRenderer advertised as '$friendlyName' at ${server.descriptionUrl}")
            onStateChanged(ProtocolState.ADVERTISING)
        } catch (e: Exception) {
            Logger.e("Failed to start DLNA receiver", e)
            stop()
            onStateChanged(ProtocolState.ERROR)
        }
    }

    fun stop() {
        runCatching { ssdpAdvertiser?.stop() }
            .onFailure { Logger.w("DLNA SSDP stop failed: ${it.message}") }
        runCatching { descriptionServer?.stop() }
            .onFailure { Logger.w("DLNA description server stop failed: ${it.message}") }
        runCatching { mediaPlayer.release() }
            .onFailure { Logger.w("DLNA media player release failed: ${it.message}") }
        ssdpAdvertiser = null
        descriptionServer = null
        job.cancel()
        onStateChanged(ProtocolState.DISABLED)
    }

    private fun localIpv4Address(): String {
        return NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
            ?: "127.0.0.1"
    }

    override fun onSetTransportUri(uri: String) {
        currentUri = uri
        Logger.i("DLNA AVTransport URI set: $uri")
    }

    override fun onPlay(uri: String) {
        val target = uri.ifBlank { currentUri }
        if (target.isBlank()) {
            Logger.w("DLNA Play ignored: no CurrentURI")
            return
        }
        currentUri = target
        val imageMime = DlnaMediaType.imageMimeFromUrl(target)
        if (imageMime != null) {
            playImage(target, imageMime)
            return
        }
        onPhotoCleared()
        mediaPlayer.play(target, startPositionFraction = 0.0)
        onStateChanged(ProtocolState.CONNECTED)
    }

    override fun onPause() {
        mediaPlayer.setRate(0f)
    }

    override fun onStop() {
        mediaPlayer.release()
        onPhotoCleared()
        onStateChanged(ProtocolState.ADVERTISING)
    }

    fun sendRemoteCommand(command: String) {
        when (command) {
            COMMAND_PLAY_PAUSE -> mediaPlayer.setRate(1f)
            COMMAND_PAUSE -> mediaPlayer.setRate(0f)
            COMMAND_STOP -> onStop()
        }
    }

    private fun playImage(uri: String, mimeType: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = fetchBounded(uri)
                onPhotoReceived(bytes, mimeType)
                onStateChanged(ProtocolState.CONNECTED)
            } catch (e: Exception) {
                Logger.e("DLNA photo fetch failed", e)
                onStateChanged(ProtocolState.ERROR)
            }
        }
    }

    private fun fetchBounded(uri: String): ByteArray {
        val connection = (URL(uri).openConnection() as HttpURLConnection).apply {
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            instanceFollowRedirects = false
        }
        connection.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_IMAGE_BYTES) { "DLNA image exceeds 20 MB limit" }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    companion object {
        const val COMMAND_PLAY_PAUSE = "play-pause"
        const val COMMAND_PAUSE = "pause"
        const val COMMAND_STOP = "stop"
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        private const val HTTP_TIMEOUT_MS = 10_000
    }
}
