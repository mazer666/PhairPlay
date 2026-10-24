package com.phairplay.airplay

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import com.phairplay.util.Logger

/** Snapshot of URL-video playback for `GET /playback-info`. */
data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Double,        // 0.0 = paused, 1.0 = playing
    val readyToPlay: Boolean,
)

/**
 * AirPlayVideoPlayer — plays an AirPlay "video URL" stream (the non-mirroring mode: a sender app
 * like Safari or a TV app says "AirPlay this video" and POSTs a URL via `/play`). We hand the URL to
 * Android's [MediaPlayer] and render to the same streaming [Surface] the mirror decoder uses.
 *
 * Distinct from screen mirroring (H.264 over a data stream): here the TV fetches and plays the media
 * itself, and the sender only drives transport (`/rate`, `/scrub`, `/stop`) + polls `/playback-info`.
 *
 * Methods are `@Synchronized` because RTSP control verbs arrive on the RTSP thread while MediaPlayer
 * callbacks fire on its own thread.
 */
class AirPlayVideoPlayer(
    private val surfaceProvider: () -> Surface?,
    private val onEnded: () -> Unit = {},
) {
    private var mp: MediaPlayer? = null
    @Volatile private var prepared = false
    @Volatile private var startFraction = 0.0
    @Volatile private var audioOnly = false

    /** Starts playing [url], seeking to [startPositionFraction] (0..1 of duration) once prepared. */
    @Synchronized
    fun play(url: String, startPositionFraction: Double) {
        start(url, startPositionFraction, audioOnly = false)
    }

    /**
     * Starts playing an audio-only stream [url].
     *
     * WHY: an audio stream must not be rendered onto the video surface — the decoder
     * would show a black rectangle over the whole screen. The DLNA renderer uses this
     * for control points that announce an audio item.
     */
    @Synchronized
    fun playAudio(url: String) {
        start(url, startPositionFraction = 0.0, audioOnly = true)
    }

    @Synchronized
    private fun start(url: String, startPositionFraction: Double, audioOnly: Boolean) {
        release()
        this.audioOnly = audioOnly
        startFraction = startPositionFraction.coerceIn(0.0, 1.0)
        Logger.i("AirPlay video: play url=$url start=$startFraction audioOnly=$audioOnly")
        val player = MediaPlayer()
        mp = player
        prepared = false
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(
                        if (audioOnly) AudioAttributes.CONTENT_TYPE_MUSIC
                        else AudioAttributes.CONTENT_TYPE_MOVIE
                    )
                    .build()
            )
            if (!audioOnly) surfaceProvider()?.let { player.setSurface(it) }
            player.setOnPreparedListener { onPrepared(it) }
            player.setOnCompletionListener { Logger.i("AirPlay video: completed"); onEnded() }
            player.setOnErrorListener { _, what, extra ->
                Logger.e("AirPlay video error what=$what extra=$extra")
                true   // handled — don't also fire onCompletion
            }
            player.setDataSource(url)
            player.prepareAsync()
        }.onFailure { Logger.e("AirPlay video: setup failed", it); release() }
    }

    @Synchronized
    private fun onPrepared(player: MediaPlayer) {
        // onPrepared fires on MediaPlayer's own thread and can race release()/a new play(): if the
        // player was released (or replaced) while preparing, mp no longer points at it — bail before
        // touching a dead MediaPlayer (which would throw IllegalStateException).
        if (mp !== player) return
        // The Surface usually doesn't exist at setDataSource() time (the Activity creates it only
        // after CONNECTED) — attach it now that prepare has completed.
        if (!audioOnly) surfaceProvider()?.let { runCatching { player.setSurface(it) } }
        if (startFraction > 0.0) runCatching { player.seekTo((startFraction * player.duration).toInt()) }
        prepared = true
        runCatching { player.start() }
        Logger.i("AirPlay video: prepared dur=${runCatching { player.duration }.getOrDefault(0)}ms → playing")
    }

    /** rate ≤ 0 pauses, > 0 resumes. */
    @Synchronized
    fun setRate(rate: Float) {
        val player = mp ?: return
        runCatching {
            if (rate <= 0f) { if (player.isPlaying) player.pause() }
            else { if (!player.isPlaying) player.start() }
        }
    }

    /** Seeks to [positionSec] seconds. */
    @Synchronized
    fun scrub(positionSec: Double) {
        runCatching { mp?.seekTo((positionSec * 1000).toInt()) }
    }

    /** Current playback snapshot for `/playback-info`, or null if nothing is loaded. */
    @Synchronized
    fun info(): PlaybackInfo? {
        val player = mp ?: return null
        if (!prepared) return PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
        val dur = runCatching { player.duration }.getOrDefault(0)
        val pos = runCatching { player.currentPosition }.getOrDefault(0)
        val playing = runCatching { player.isPlaying }.getOrDefault(false)
        return PlaybackInfo(dur / 1000.0, pos / 1000.0, if (playing) 1.0 else 0.0, readyToPlay = true)
    }

    /** Re-attach the streaming surface (after the Activity recreates it on foreground). */
    @Synchronized
    fun attachSurface() {
        surfaceProvider()?.let { runCatching { mp?.setSurface(it) } }
    }

    @Synchronized
    fun release() {
        mp?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        mp = null
        prepared = false
    }
}
