package com.phairplay.dlna

/**
 * DlnaTransportState — the mutable state of the AVTransport instance.
 *
 * WHY: the AVTransport service is a small state machine. Keeping its state in one
 * place (instead of scattering it over the dispatcher) makes the transitions
 * reviewable and lets the position be tracked with a monotonic clock — a wall clock
 * would jump backwards when the TV adjusts its time and would make `RelTime` go
 * negative.
 *
 * All fields are read by the HTTP worker threads and written by them as well, so
 * they are volatile; the position bookkeeping is guarded by the object's monitor.
 */
internal class DlnaTransportState {

    /** The URI of the media the control point announced. */
    @Volatile var currentUri: String = ""

    /** The DIDL-Lite document the control point announced next to the URI. */
    @Volatile var currentUriMetadata: String = ""

    /** The parsed form of [currentUriMetadata]. */
    @Volatile var metadata: DlnaMediaMetadata? = null

    /** One of the `TransportState` values of the UPnP AVTransport specification. */
    @Volatile var transportState: String = TRANSPORT_STOPPED

    /** Render volume in percent (0..100). */
    @Volatile var volume: Int = DEFAULT_VOLUME

    /** Mute state of the renderer. */
    @Volatile var muted: Boolean = false

    /** Playback speed as the control point set it ("1" is normal speed). */
    @Volatile var playbackSpeed: String = "1"

    /** Index of the current track (PhairPlay renders one track at a time). */
    @Volatile var currentTrack: Int = 1

    private var basePositionMillis: Long = 0L
    private var startedAtNanos: Long = 0L

    /** Called when playback starts: the clock starts running from the current position. */
    @Synchronized
    fun onPlayStarted() {
        basePositionMillis = positionLocked()
        startedAtNanos = System.nanoTime()
    }

    /** Called when playback pauses: the running time is folded into the base position. */
    @Synchronized
    fun onPaused() {
        basePositionMillis = positionLocked()
        startedAtNanos = 0L
    }

    /** Called when playback stops: the position is reset. */
    @Synchronized
    fun onStopped() {
        basePositionMillis = 0L
        startedAtNanos = 0L
    }

    /** Called when the control point seeks: the base position moves to [positionMillis]. */
    @Synchronized
    fun onSeek(positionMillis: Long) {
        basePositionMillis = positionMillis.coerceAtLeast(0L)
        if (startedAtNanos != 0L) startedAtNanos = System.nanoTime()
    }

    /** Returns the position the renderer has counted itself, in milliseconds. */
    @Synchronized
    fun trackedPositionMillis(): Long = positionLocked()

    private fun positionLocked(): Long {
        if (startedAtNanos == 0L) return basePositionMillis
        val elapsed = (System.nanoTime() - startedAtNanos) / 1_000_000L
        return basePositionMillis + elapsed
    }

    companion object {
        const val TRANSPORT_STOPPED = "STOPPED"
        const val TRANSPORT_PLAYING = "PLAYING"
        const val TRANSPORT_PAUSED = "PAUSED_PLAYBACK"
        const val TRANSPORT_NO_MEDIA = "NO_MEDIA_PRESENT"

        /** Render volume used until a control point sets one. */
        const val DEFAULT_VOLUME = 50
    }
}
