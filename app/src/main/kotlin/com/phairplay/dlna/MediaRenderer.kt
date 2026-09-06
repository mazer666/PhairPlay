package com.phairplay.dlna

import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Everything the renderer tells the service/UI. All callbacks may be invoked from IO threads. */
class RendererCallbacks(
    val onProtocolState: (ProtocolState) -> Unit,
    val onSnapshot: (RendererSnapshot) -> Unit,
    val onNowPlaying: (NowPlayingInfo?) -> Unit,
    val onPhoto: (bytes: ByteArray, mimeType: String) -> Unit,
    val onPhotoCleared: () -> Unit,
    val onSenderName: (String) -> Unit
)

/** TV-remote media keys routed to the renderer while DLNA is connected. */
enum class RemoteCommand { PLAY_PAUSE, STOP, NEXT, SEEK_FORWARD, SEEK_BACK }

/**
 * MediaRenderer — the one stateful DLNA object: turns SetAVTransportURI into playback or a photo, tracks
 * the transport state, fires LastChange snapshots and drives the service overlays.
 *
 * WHY: The UPnP services are pure protocol; the player and the photo fetch are asynchronous. This class
 * owns the single lock that reconciles SOAP calls (IO threads), player callbacks (MediaPlayer thread) and
 * fetch completions (coroutines), and it alone knows which overlay each media class uses.
 *
 * HOW: Constructed by [DlnaReceiver]; registered as the [RendererControl] for the services. State changes
 * reach the outside only through [RendererCallbacks].
 */
class MediaRenderer(
    private val player: RendererPlayer,
    private val photos: PhotoFetcher,
    private val isAirPlayConnected: () -> Boolean,
    private val callbacks: RendererCallbacks,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : RendererControl {

    private val lock = Any()
    private var snapshot = RendererSnapshot()
    private var mediaClass: MediaClass? = null
    private var currentItem = DidlItem.EMPTY
    private var senderName = DEFAULT_SENDER
    private var lastAgent: String? = null
    private var playRequested = false
    private var lastProtocolState: ProtocolState? = null
    /** Bumped on every load; async completions for an older generation are ignored. */
    private var generation = 0

    // ─── RendererControl ─────────────────────────────────────────────────────

    override fun load(uri: String, metadata: String, senderAgent: String?) =
        loadInternal(uri, metadata, senderAgent, autoPlay = false)

    override fun setNext(uri: String, metadata: String) {
        synchronized(lock) { snapshot = snapshot.copy(nextUri = uri, nextMetadata = metadata) }
        publish()
    }

    override fun play() {
        var startPlayer = false
        var refetchPhoto: String? = null
        synchronized(lock) {
            when (snapshot.state) {
                TransportState.TRANSITIONING -> playRequested = true
                TransportState.PAUSED_PLAYBACK -> {
                    snapshot = snapshot.copy(state = TransportState.PLAYING, status = RendererSnapshot.STATUS_OK)
                    startPlayer = mediaClass != MediaClass.IMAGE
                }
                TransportState.STOPPED -> {
                    playRequested = true
                    if (mediaClass == MediaClass.IMAGE) {
                        refetchPhoto = snapshot.currentUri
                    } else {
                        startPlayer = true                         // player re-prepares → onPrepared → PLAYING
                    }
                    snapshot = snapshot.copy(state = TransportState.TRANSITIONING, status = RendererSnapshot.STATUS_OK)
                }
                TransportState.PLAYING, TransportState.NO_MEDIA_PRESENT -> {}
            }
        }
        if (startPlayer) player.play()
        refetchPhoto?.let { fetchPhoto(it, currentGeneration()) }
        publish()
    }

    override fun pause() {
        var pausePlayer = false
        synchronized(lock) {
            when (snapshot.state) {
                TransportState.PLAYING -> {
                    snapshot = snapshot.copy(state = TransportState.PAUSED_PLAYBACK)
                    pausePlayer = mediaClass != MediaClass.IMAGE
                }
                TransportState.TRANSITIONING -> playRequested = false
                else -> {}
            }
        }
        if (pausePlayer) player.pause()
        publish()
    }

    override fun stop() {
        synchronized(lock) {
            if (!snapshot.hasMedia) return
            playRequested = false
            snapshot = snapshot.copy(state = TransportState.STOPPED, positionMs = 0L, status = RendererSnapshot.STATUS_OK)
        }
        player.stop()
        clearOverlays()
        publish()
    }

    override fun seekTo(positionMs: Long) {
        val target: Long
        synchronized(lock) {
            if (!snapshot.hasMedia || mediaClass == MediaClass.IMAGE) return
            target = positionMs.coerceIn(0L, if (snapshot.durationMs > 0) snapshot.durationMs else Long.MAX_VALUE)
            snapshot = snapshot.copy(positionMs = target)
        }
        player.seekTo(target)
        publish()
    }

    override fun next() {
        val (uri, metadata) = synchronized(lock) { snapshot.nextUri to snapshot.nextMetadata }
        if (uri.isEmpty()) return
        val agent = synchronized(lock) { lastAgent }
        try {
            loadInternal(uri, metadata, agent, autoPlay = true)
        } catch (e: UpnpError) {
            Logger.w("DLNA next item rejected: ${e.description}")
            stop()
        }
    }

    override fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(0, RendererSnapshot.MAX_VOLUME)
        synchronized(lock) { snapshot = snapshot.copy(volume = clamped) }
        player.setVolume(clamped)
        publish()
    }

    override fun setMute(mute: Boolean) {
        synchronized(lock) { snapshot = snapshot.copy(mute = mute) }
        player.setMute(mute)
        publish()
    }

    override fun snapshot(): RendererSnapshot = synchronized(lock) {
        val live = snapshot.state == TransportState.PLAYING || snapshot.state == TransportState.PAUSED_PLAYBACK
        if (live && mediaClass != MediaClass.IMAGE) {
            snapshot.copy(positionMs = player.positionMs(), durationMs = maxOf(player.durationMs(), snapshot.durationMs))
        } else snapshot
    }

    // ─── Remote keys / lifecycle ─────────────────────────────────────────────

    fun remote(command: RemoteCommand) {
        when (command) {
            RemoteCommand.PLAY_PAUSE -> if (snapshot().state == TransportState.PLAYING) pause() else play()
            RemoteCommand.STOP -> stop()
            RemoteCommand.NEXT -> next()
            RemoteCommand.SEEK_FORWARD -> seekTo(snapshot().positionMs + DlnaConstants.SEEK_STEP_MS)
            RemoteCommand.SEEK_BACK -> seekTo(snapshot().positionMs - DlnaConstants.SEEK_STEP_MS)
        }
    }

    fun release() {
        stop()
        player.release()
    }

    // ─── Internals ───────────────────────────────────────────────────────────

    private fun loadInternal(uri: String, metadata: String, senderAgent: String?, autoPlay: Boolean) {
        val item = DidlLite.parse(metadata)
        val mediaClass = ProtocolInfoList.classify(item.protocolInfo, item.upnpClass, uri)
            ?: throw UpnpError.illegalMimeType(uri)
        if (mediaClass == MediaClass.VIDEO && isAirPlayConnected()) {
            throw UpnpError.actionFailed("receiver busy with an AirPlay session")
        }
        val myGeneration: Int
        val name: String
        synchronized(lock) {
            generation++
            myGeneration = generation
            this.mediaClass = mediaClass
            currentItem = item
            lastAgent = senderAgent
            senderName = senderNameFrom(senderAgent)
            name = senderName
            playRequested = autoPlay
            snapshot = snapshot.copy(
                state = TransportState.TRANSITIONING, status = RendererSnapshot.STATUS_OK,
                currentUri = uri, currentMetadata = metadata,
                nextUri = if (autoPlay) "" else snapshot.nextUri,
                nextMetadata = if (autoPlay) "" else snapshot.nextMetadata,
                durationMs = item.durationMs ?: 0L, positionMs = 0L
            )
        }
        player.stop()
        callbacks.onSenderName(name)
        Logger.i("DLNA load $mediaClass from '$name': $uri")
        when (mediaClass) {
            MediaClass.VIDEO -> {
                callbacks.onPhotoCleared(); callbacks.onNowPlaying(null)
                player.load(uri, audioOnly = false, listener = playerListener(myGeneration))
            }
            MediaClass.AUDIO -> {
                callbacks.onPhotoCleared(); callbacks.onNowPlaying(nowPlayingInfo(null))
                player.load(uri, audioOnly = true, listener = playerListener(myGeneration))
                item.albumArtUri?.let { fetchArtwork(it, myGeneration) }
            }
            MediaClass.IMAGE -> {
                callbacks.onNowPlaying(null)
                fetchPhoto(uri, myGeneration)
            }
        }
        publish()
    }

    private fun playerListener(myGeneration: Int) = object : RendererPlayer.Listener {
        override fun onPrepared(durationMs: Long) {
            val shouldPlay: Boolean
            synchronized(lock) {
                if (myGeneration != generation) return
                shouldPlay = playRequested
                snapshot = snapshot.copy(
                    state = if (shouldPlay) TransportState.PLAYING else TransportState.STOPPED,
                    durationMs = if (durationMs > 0) durationMs else snapshot.durationMs
                )
            }
            if (shouldPlay) player.play()
            publish()
        }

        override fun onCompleted() {
            synchronized(lock) { if (myGeneration != generation) return }
            val hasNext = synchronized(lock) { snapshot.nextUri.isNotEmpty() }
            if (hasNext) next() else finish(RendererSnapshot.STATUS_OK)
        }

        override fun onError(message: String) {
            synchronized(lock) { if (myGeneration != generation) return }
            Logger.w("DLNA player error: $message")
            finish(RendererSnapshot.STATUS_ERROR)
        }
    }

    /** Playback (or a photo) ended, normally or with an error: back to STOPPED and hide overlays. */
    private fun finish(status: String) {
        synchronized(lock) {
            playRequested = false
            snapshot = snapshot.copy(state = TransportState.STOPPED, status = status, positionMs = 0L)
        }
        clearOverlays()
        publish()
    }

    private fun fetchPhoto(uri: String, myGeneration: Int) {
        scope.launch(ioDispatcher) {
            when (val result = photos.fetch(uri)) {
                is PhotoFetcher.Result.Ok -> {
                    synchronized(lock) {
                        if (myGeneration != generation) return@launch
                        snapshot = snapshot.copy(state = TransportState.PLAYING, status = RendererSnapshot.STATUS_OK)
                    }
                    callbacks.onPhoto(result.bytes, result.mimeType)
                    publish()
                }
                is PhotoFetcher.Result.Failed -> {
                    synchronized(lock) { if (myGeneration != generation) return@launch }
                    Logger.w("DLNA photo fetch failed: ${result.reason}")
                    finish(RendererSnapshot.STATUS_ERROR)
                }
            }
        }
    }

    private fun fetchArtwork(uri: String, myGeneration: Int) {
        scope.launch(ioDispatcher) {
            val result = photos.fetch(uri) as? PhotoFetcher.Result.Ok ?: return@launch
            val info = synchronized(lock) {
                if (myGeneration != generation) return@launch
                nowPlayingInfo(result.bytes)
            }
            callbacks.onNowPlaying(info)
        }
    }

    private fun nowPlayingInfo(artwork: ByteArray?) =
        NowPlayingInfo(senderName, currentItem.title, currentItem.artist, currentItem.album, artwork)

    private fun clearOverlays() {
        callbacks.onPhotoCleared()
        callbacks.onNowPlaying(null)
    }

    private fun currentGeneration(): Int = synchronized(lock) { generation }

    /** Emits the snapshot (for LastChange) and the protocol state only when the latter changed. */
    private fun publish() {
        val current = snapshot()
        callbacks.onSnapshot(current)
        val protocolState = if (current.state.isActive) ProtocolState.CONNECTED else ProtocolState.ADVERTISING
        val changed = synchronized(lock) {
            (protocolState != lastProtocolState).also { if (it) lastProtocolState = protocolState }
        }
        if (changed) callbacks.onProtocolState(protocolState)
    }

    companion object {
        const val DEFAULT_SENDER = "DLNA Sender"

        /** Friendly sender label from the control point's User-Agent. */
        fun senderNameFrom(userAgent: String?): String = when {
            userAgent == null -> DEFAULT_SENDER
            userAgent.contains("Windows", ignoreCase = true) -> "Windows PC"
            userAgent.contains("BubbleUPnP", ignoreCase = true) -> "BubbleUPnP"
            userAgent.contains("VLC", ignoreCase = true) -> "VLC"
            else -> DEFAULT_SENDER
        }
    }
}
