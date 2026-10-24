package com.phairplay.airplay

import android.view.Surface
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger

/**
 * AirPlayUrlSession — Plays an AirPlay video that the sender announces as a plain URL.
 *
 * WHAT: Wraps the [AirPlayVideoPlayer] — the non-mirroring video mode in which macOS/iOS
 * sends `POST /play` with a media URL instead of streaming H.264 — and exposes the transport
 * controls (rate, scrub, playback info) the RTSP layer needs.
 *
 * WHY: The URL mode is a completely different path from the mirroring pipeline: it plays a
 * media file with MediaPlayer onto the streaming Surface instead of decoding a live RTP stream.
 * Keeping it in its own object means the mirroring pipeline stays free of URL-mode details.
 *
 * HOW: The owner hands over the Surface provider and the callbacks that report the session:
 *   val url = AirPlayUrlSession(videoSurfaceProvider, onSenderNameChanged,
 *                               onVideoSessionChanged, onStateChanged, onStreamingStopped)
 *   url.startUrlVideo("https://example.com/movie.mov", 0.0)
 *
 * Example:
 *   url.setRate(2f)      // sender asked for double speed
 *   url.scrub(30.0)      // sender asked for the 30-second mark
 */
internal class AirPlayUrlSession(
    /** Lazy Surface provider — the player renders onto that Surface. */
    private val videoSurfaceProvider: () -> Surface?,
    /** Reports the sender name to the UI (called before the state change). */
    private val onSenderNameChanged: (String) -> Unit,
    /** Reports whether the session carries video. */
    private val onVideoSessionChanged: (hasVideo: Boolean) -> Unit,
    /** Publishes the protocol state of the session. */
    private val onStateChanged: (ProtocolState) -> Unit,
    /** Called when the URL playback ends — the owner tears the session down. */
    private val onStreamingStopped: () -> Unit
) {

    // The MediaPlayer-based URL player — created on the first /play request.
    @Volatile private var urlVideoPlayer: AirPlayVideoPlayer? = null

    /**
     * AirPlay video URL mode (non-mirroring): show the streaming surface and hand the URL to
     * [AirPlayVideoPlayer], which fetches + plays it via MediaPlayer onto the same Surface.
     */
    internal fun startUrlVideo(url: String, startFraction: Double) {
        onSenderNameChanged("AirPlay")
        onVideoSessionChanged(true)   // the URL player renders onto the Surface
        onStateChanged(ProtocolState.CONNECTED)   // shows StreamingScreen → Surface becomes available
        val player = urlVideoPlayer ?: AirPlayVideoPlayer(
            surfaceProvider = videoSurfaceProvider,
            onEnded = { stopUrlVideo() }
        ).also { urlVideoPlayer = it }
        player.play(url, startFraction)
        Logger.i("AirPlay URL video started: $url (start=$startFraction)")
    }

    /** Stops AirPlay video URL playback (POST /stop or end-of-media) and ends the session. */
    internal fun stopUrlVideo() {
        urlVideoPlayer?.release()
        urlVideoPlayer = null
        onStreamingStopped()
        Logger.i("AirPlay URL video stopped")
    }

    /** Applies the playback rate the sender asked for (POST /rate). */
    internal fun setRate(rate: Float) {
        urlVideoPlayer?.setRate(rate)
    }

    /** Seeks to [positionSec] (POST /scrub). */
    internal fun scrub(positionSec: Double) {
        urlVideoPlayer?.scrub(positionSec)
    }

    /** Returns the current playback snapshot (GET /playback-info and GET /scrub). */
    internal fun info(): PlaybackInfo? = urlVideoPlayer?.info()

    /** Releases the player — the MediaPlayer and its Surface reference are freed. */
    internal fun release() {
        urlVideoPlayer?.release()
        urlVideoPlayer = null
    }
}
