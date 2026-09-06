package com.phairplay.dlna

/**
 * RendererPlayer — the playback engine [MediaRenderer] drives; implemented by [DlnaPlayer] on Android and
 * by a fake in tests.
 *
 * WHY: MediaPlayer cannot run on the JVM. Hiding it behind this interface keeps the session state machine
 * fully unit-testable.
 *
 * Contract: [load] prepares asynchronously and reports through the listener; [play] after [stop] must
 * re-prepare the last URI and fire [Listener.onPrepared] again; [stop] is idempotent.
 */
interface RendererPlayer {
    interface Listener {
        fun onPrepared(durationMs: Long)
        fun onCompleted()
        fun onError(message: String)
    }

    fun load(uri: String, audioOnly: Boolean, listener: Listener)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun positionMs(): Long
    fun durationMs(): Long
    /** 0..100, applied to this stream only. */
    fun setVolume(percent: Int)
    fun setMute(mute: Boolean)
    fun release()
}
