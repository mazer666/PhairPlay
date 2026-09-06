package com.phairplay.dlna

/**
 * FakeRendererPlayer — records player calls and lets tests fire prepared/completed/error callbacks.
 *
 * WHY: MediaPlayer cannot run on the JVM; the renderer state machine is tested against this instead.
 */
class FakeRendererPlayer : RendererPlayer {
    val calls = mutableListOf<String>()
    var listener: RendererPlayer.Listener? = null
    var position = 0L
    var duration = 0L

    override fun load(uri: String, audioOnly: Boolean, listener: RendererPlayer.Listener) {
        calls += "load:$uri:${if (audioOnly) "audio" else "video"}"
        this.listener = listener
    }
    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun stop() { calls += "stop" }
    override fun seekTo(positionMs: Long) { calls += "seek:$positionMs"; position = positionMs }
    override fun positionMs() = position
    override fun durationMs() = duration
    override fun setVolume(percent: Int) { calls += "volume:$percent" }
    override fun setMute(mute: Boolean) { calls += "mute:$mute" }
    override fun release() { calls += "release" }
}
