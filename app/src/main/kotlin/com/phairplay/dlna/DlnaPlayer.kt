package com.phairplay.dlna

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import com.phairplay.util.Logger

/**
 * DlnaPlayer — [RendererPlayer] on Android's [MediaPlayer]: fetches the URL itself, renders video on the
 * shared streaming [Surface] or plays audio-only.
 *
 * WHY: Same engine and the same patterns as `AirPlayVideoPlayer` (synchronized methods, lazy surface, guard
 * against `onPrepared` racing a release), plus what DLNA needs: stream-level volume/mute, absolute seek,
 * transport callbacks and re-preparing on Play-after-Stop.
 *
 * Volume is applied with `MediaPlayer.setVolume` so the TV's system volume is untouched.
 */
class DlnaPlayer(
    private val context: Context,
    private val surfaceProvider: () -> Surface?
) : RendererPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var lastUri: String? = null
    private var lastAudioOnly = false
    private var listener: RendererPlayer.Listener? = null
    private var playWhenPrepared = false
    private var volumePercent = RendererSnapshot.MAX_VOLUME
    private var muted = false

    @Synchronized
    override fun load(uri: String, audioOnly: Boolean, listener: RendererPlayer.Listener) {
        releaseLocked()
        lastUri = uri
        lastAudioOnly = audioOnly
        this.listener = listener
        playWhenPrepared = false
        prepareLocked(uri, audioOnly)
    }

    private fun prepareLocked(uri: String, audioOnly: Boolean) {
        val player = MediaPlayer()
        mediaPlayer = player
        val currentListener = listener
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (audioOnly) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            if (!audioOnly) surfaceProvider()?.let { player.setSurface(it) }
            player.setOnPreparedListener { prepared -> onPrepared(prepared, audioOnly) }
            player.setOnCompletionListener { done -> if (isCurrent(done)) currentListener?.onCompleted() }
            player.setOnErrorListener { failed, what, extra ->
                if (isCurrent(failed)) currentListener?.onError("MediaPlayer error what=$what extra=$extra")
                true   // handled — do not also fire onCompletion
            }
            player.setDataSource(context, Uri.parse(uri), DLNA_HEADERS)
            player.prepareAsync()
            Logger.i("DlnaPlayer: preparing ${if (audioOnly) "audio" else "video"} $uri")
        }.onFailure { e ->
            Logger.e("DlnaPlayer: load failed", e)
            releaseLocked()
            currentListener?.onError(e.message ?: "load failed")
        }
    }

    private fun onPrepared(prepared: MediaPlayer, audioOnly: Boolean) {
        val shouldStart: Boolean
        val duration: Long
        synchronized(this) {
            if (mediaPlayer !== prepared) return     // released or replaced while preparing
            // The Surface usually appears only after the overlay is shown — attach it now as well.
            if (!audioOnly) surfaceProvider()?.let { runCatching { prepared.setSurface(it) } }
            applyVolumeLocked(prepared)
            shouldStart = playWhenPrepared
            playWhenPrepared = false
            duration = runCatching { prepared.duration.toLong() }.getOrDefault(0L)
            if (shouldStart) runCatching { prepared.start() }
        }
        Logger.i("DlnaPlayer: prepared dur=${duration}ms autoStart=$shouldStart")
        listener?.onPrepared(duration)
    }

    @Synchronized
    override fun play() {
        val player = mediaPlayer
        if (player == null) {
            // Play after Stop: MediaPlayer was released, so prepare the last URI again and start on prepared.
            val uri = lastUri ?: return
            playWhenPrepared = true
            prepareLocked(uri, lastAudioOnly)
            return
        }
        runCatching { if (!player.isPlaying) player.start() }.onFailure { Logger.w("DlnaPlayer: start failed: ${it.message}") }
    }

    @Synchronized
    override fun pause() {
        runCatching { mediaPlayer?.let { if (it.isPlaying) it.pause() } }.onFailure { Logger.w("DlnaPlayer: pause failed: ${it.message}") }
    }

    /** Idempotent. Releases the MediaPlayer; [play] re-prepares [lastUri]. */
    @Synchronized
    override fun stop() {
        releaseLocked()
    }

    @Synchronized
    override fun seekTo(positionMs: Long) {
        runCatching { mediaPlayer?.seekTo(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) }
            .onFailure { Logger.w("DlnaPlayer: seek failed: ${it.message}") }
    }

    @Synchronized
    override fun positionMs(): Long = runCatching { mediaPlayer?.currentPosition?.toLong() }.getOrNull() ?: 0L

    @Synchronized
    override fun durationMs(): Long = runCatching { mediaPlayer?.duration?.toLong() }.getOrNull() ?: 0L

    @Synchronized
    override fun setVolume(percent: Int) {
        volumePercent = percent.coerceIn(0, RendererSnapshot.MAX_VOLUME)
        mediaPlayer?.let { applyVolumeLocked(it) }
    }

    @Synchronized
    override fun setMute(mute: Boolean) {
        muted = mute
        mediaPlayer?.let { applyVolumeLocked(it) }
    }

    @Synchronized
    override fun release() {
        releaseLocked()
        lastUri = null
        listener = null
    }

    private fun isCurrent(player: MediaPlayer): Boolean = synchronized(this) { mediaPlayer === player }

    /** Perceptual curve: 50 % on the slider is noticeably quieter than half amplitude would be. */
    private fun applyVolumeLocked(player: MediaPlayer) {
        val gain = if (muted) 0f else {
            val fraction = volumePercent / RendererSnapshot.MAX_VOLUME.toFloat()
            fraction * fraction
        }
        runCatching { player.setVolume(gain, gain) }
    }

    private fun releaseLocked() {
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        mediaPlayer = null
        playWhenPrepared = false
    }

    companion object {
        /** DLNA transport hints some servers (Windows included) expect on media requests. */
        private val DLNA_HEADERS = mapOf(
            "transferMode.dlna.org" to "Streaming",
            "getcontentFeatures.dlna.org" to "1"
        )
    }
}
