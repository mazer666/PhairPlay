package com.phairplay.dlna

/**
 * DlnaPositionInfo — where the renderer currently is inside the loaded media.
 *
 * WHY: `GetPositionInfo` has to answer with a duration and a position. The values
 * come from the Android media player when it is running; when it is not (photo mode,
 * or the player has not reported yet) the dispatcher falls back to the duration the
 * control point announced and to the time the dispatcher itself has counted.
 */
data class DlnaPositionInfo(
    val durationMillis: Long,
    val positionMillis: Long
) {
    companion object {
        /** The "nothing loaded" answer. */
        val EMPTY = DlnaPositionInfo(durationMillis = 0L, positionMillis = 0L)
    }
}

/**
 * DlnaPlaybackController — the bridge between the UPnP transport state machine and
 * the Android media stack.
 *
 * WHY: the dispatcher must not know anything about Android. It owns the protocol
 * state; the controller owns the actual playback. The renderer implements the
 * interface, the unit tests implement it with a recorder.
 *
 * Example:
 *   class Renderer : DlnaPlaybackController {
 *       override fun onPlay(uri: String) { player.play(uri, 0.0) }
 *   }
 */
interface DlnaPlaybackController {

    /** Called when a control point announces a new media URI. */
    fun onSetTransportUri(uri: String)

    /** Called when playback should start for [uri]. */
    fun onPlay(uri: String, metadata: DlnaMediaMetadata? = null)

    /** Called when playback should pause. */
    fun onPause()

    /** Called when playback should stop and the media should be released. */
    fun onStop()

    /** Called when a control point seeks to [positionMillis] inside the current media. */
    fun onSeek(positionMillis: Long) = Unit

    /** Returns the current duration and position, or [DlnaPositionInfo.EMPTY]. */
    fun positionInfo(): DlnaPositionInfo = DlnaPositionInfo.EMPTY

    companion object {
        /** A controller that does nothing — used by tests and by the description server. */
        val NOOP = object : DlnaPlaybackController {
            override fun onSetTransportUri(uri: String) = Unit
            override fun onPlay(uri: String, metadata: DlnaMediaMetadata?) = Unit
            override fun onPause() = Unit
            override fun onStop() = Unit
        }
    }
}
