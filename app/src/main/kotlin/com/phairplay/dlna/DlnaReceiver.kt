package com.phairplay.dlna

import android.content.Context
import android.graphics.Bitmap
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
import java.net.URL

/**
 * DlnaReceiver — the DLNA MediaRenderer of PhairPlay.
 *
 * WHY: this class ties the UPnP control plane to the Android media stack. It
 * publishes the renderer (SSDP + description server), forwards the transport
 * commands to the player and shows photos.
 *
 * The three media kinds need different pipelines:
 * - video and audio are played by [AirPlayVideoPlayer]; an audio-only stream is
 *   played without a video surface so that no black rectangle covers the screen,
 * - a photo is fetched with a hard size limit and downsampled before it reaches
 *   the screen (see [DlnaImageDecoder]).
 *
 * Example:
 *   val receiver = DlnaReceiver(context, "Living Room TV", { surface }, { bytes, mime -> }, {}, { })
 *   receiver.start()
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

    /** Starts the renderer: description server first, then the SSDP advertisement. */
    fun start() {
        try {
            val localHost = DlnaNetworkAddress.localIpv4Address()
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

    /** Stops the renderer and releases every resource. */
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

    override fun onSetTransportUri(uri: String) {
        currentUri = uri
        Logger.i("DLNA AVTransport URI set: $uri")
    }

    override fun onPlay(uri: String, metadata: DlnaMediaMetadata?) {
        val target = uri.ifBlank { currentUri }
        if (target.isBlank()) {
            Logger.w("DLNA Play ignored: no CurrentURI")
            return
        }
        currentUri = target
        val kind = metadata?.kind?.takeIf { it != DlnaMediaKind.UNKNOWN }
            ?: DlnaMediaType.kindFromUrl(target)
        when (kind) {
            DlnaMediaKind.IMAGE -> playImage(target, metadata?.mimeType)
            DlnaMediaKind.AUDIO -> {
                onPhotoCleared()
                mediaPlayer.playAudio(target)
                onStateChanged(ProtocolState.CONNECTED)
            }
            else -> {
                onPhotoCleared()
                mediaPlayer.play(target, startPositionFraction = 0.0)
                onStateChanged(ProtocolState.CONNECTED)
            }
        }
    }

    override fun onPause() {
        mediaPlayer.setRate(0f)
    }

    override fun onStop() {
        mediaPlayer.release()
        onPhotoCleared()
        onStateChanged(ProtocolState.ADVERTISING)
    }

    override fun onSeek(positionMillis: Long) {
        mediaPlayer.scrub(positionMillis / 1000.0)
    }

    override fun positionInfo(): DlnaPositionInfo {
        val info = mediaPlayer.info() ?: return DlnaPositionInfo.EMPTY
        return DlnaPositionInfo(
            durationMillis = (info.durationSec * 1000).toLong(),
            positionMillis = (info.positionSec * 1000).toLong()
        )
    }

    /**
     * Handles a transport command from the TV remote.
     *
     * WHY the command is handed to the control plane: the DLNA transport state has a
     * single owner. If the remote changed the player directly, the control point
     * would keep showing a stale state and the GENA event would be wrong.
     */
    fun sendRemoteCommand(command: String) {
        val server = descriptionServer
        if (server == null) {
            Logger.w("DLNA remote command ignored: renderer is not running")
            return
        }
        server.applyRemoteCommand(command)
    }

    /**
     * Fetches a photo, bounds its size and shows it on the screen.
     *
     * WHY the size limit: a control point may offer any file. An unbounded download
     * would fill the memory of the TV, and a 20 MB JPEG decodes into far more pixels
     * than the screen can show.
     */
    private fun playImage(uri: String, announcedMimeType: String?) {
        scope.launch(Dispatchers.IO) {
            try {
                val bytes = fetchBounded(uri)
                val bitmap = DlnaImageDecoder.decode(bytes, MAX_PHOTO_DIMENSION)
                if (bitmap == null) {
                    Logger.w("DLNA photo payload could not be decoded")
                    onStateChanged(ProtocolState.ERROR)
                    return@launch
                }
                val encoded = encodeJpeg(bitmap)
                onPhotoReceived(encoded, "image/jpeg")
                onStateChanged(ProtocolState.CONNECTED)
            } catch (e: Exception) {
                Logger.e("DLNA photo fetch failed", e)
                onStateChanged(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Downloads a photo with a hard size limit.
     *
     * @throws IllegalStateException when the payload exceeds the limit.
     */
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
                require(total <= DlnaImageDecoder.MAX_IMAGE_BYTES) {
                    "DLNA image exceeds the ${DlnaImageDecoder.MAX_IMAGE_BYTES} byte limit"
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /** Re-encodes a decoded photo so that the screen never decodes a huge bitmap. */
    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    companion object {
        const val COMMAND_PLAY_PAUSE = DlnaRemoteCommand.PLAY_PAUSE
        const val COMMAND_PAUSE = DlnaRemoteCommand.PAUSE
        const val COMMAND_STOP = DlnaRemoteCommand.STOP

        /** Edge length a photo is scaled down to before it reaches the screen. */
        private const val MAX_PHOTO_DIMENSION = 1920

        /** Quality of the re-encoded photo. */
        private const val JPEG_QUALITY = 90

        private const val HTTP_TIMEOUT_MS = 10_000
    }
}
