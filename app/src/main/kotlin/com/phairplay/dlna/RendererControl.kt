package com.phairplay.dlna

/**
 * RendererSnapshot — an immutable view of the renderer's transport, media and volume state.
 *
 * WHY: The UPnP services and the LastChange encoder read state through this value object, so they never
 * touch player threads or locks.
 *
 * Invariant: whenever [state] is not `NO_MEDIA_PRESENT`, [currentUri] is non-empty. This class does not
 * enforce it — it is a plain data holder — the renderer (the [RendererControl] implementation building
 * each snapshot) is responsible for keeping the two fields in sync.
 */
data class RendererSnapshot(
    val state: TransportState = TransportState.NO_MEDIA_PRESENT,
    /** `OK` or `ERROR_OCCURRED` (AVTransport TransportStatus). */
    val status: String = STATUS_OK,
    val currentUri: String = "",
    val currentMetadata: String = "",
    val nextUri: String = "",
    val nextMetadata: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val volume: Int = MAX_VOLUME,
    val mute: Boolean = false
) {
    val hasMedia: Boolean get() = currentUri.isNotEmpty()
    val numberOfTracks: Int get() = if (hasMedia) 1 else 0

    /** Comma-separated `CurrentTransportActions` a control point may call in this state. */
    val transportActions: String
        get() {
            val next = if (nextUri.isNotEmpty()) ",Next" else ""
            return when (state) {
                TransportState.NO_MEDIA_PRESENT -> ""
                TransportState.STOPPED -> "Play,Stop,Seek$next"
                TransportState.TRANSITIONING -> "Stop"
                TransportState.PLAYING -> "Pause,Stop,Seek$next"
                TransportState.PAUSED_PLAYBACK -> "Play,Stop,Seek$next"
            }
        }

    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_ERROR = "ERROR_OCCURRED"
        const val MAX_VOLUME = 100
    }
}

/**
 * RendererControl — what the UPnP services may ask the renderer to do.
 *
 * WHY: AvTransportService and RenderingControlService are pure protocol logic tested against a fake;
 * [MediaRenderer] is the real implementation that owns the player, the photo fetch and the UI callbacks.
 *
 * Contract: [load] classifies the item and may throw [UpnpError] (714 unknown type, 501 busy). State
 * changes are asynchronous — [snapshot] is the only source of truth.
 */
interface RendererControl {
    @Throws(UpnpError::class)
    fun load(uri: String, metadata: String, senderAgent: String?)
    fun setNext(uri: String, metadata: String)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    /** Starts the item set by [setNext] immediately. Callers check `snapshot().nextUri` first. */
    fun next()
    fun setVolume(volume: Int)
    fun setMute(mute: Boolean)
    fun snapshot(): RendererSnapshot
}
