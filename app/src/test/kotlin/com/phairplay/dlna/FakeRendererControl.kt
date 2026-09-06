package com.phairplay.dlna

/**
 * FakeRendererControl — records every call the UPnP services make and serves a settable snapshot.
 *
 * WHY: Lets AvTransportService/RenderingControlService be tested as pure protocol logic, with no
 * MediaPlayer and no threads.
 */
class FakeRendererControl : RendererControl {
    val calls = mutableListOf<String>()
    var snapshot = RendererSnapshot()
    var loadError: UpnpError? = null

    override fun load(uri: String, metadata: String, senderAgent: String?) {
        loadError?.let { throw it }
        calls += "load:$uri:${senderAgent ?: "-"}"
        snapshot = snapshot.copy(state = TransportState.TRANSITIONING, currentUri = uri, currentMetadata = metadata)
    }
    override fun setNext(uri: String, metadata: String) { calls += "next:$uri"; snapshot = snapshot.copy(nextUri = uri, nextMetadata = metadata) }
    override fun play() { calls += "play"; snapshot = snapshot.copy(state = TransportState.PLAYING) }
    override fun pause() { calls += "pause"; snapshot = snapshot.copy(state = TransportState.PAUSED_PLAYBACK) }
    override fun stop() { calls += "stop"; snapshot = snapshot.copy(state = TransportState.STOPPED, positionMs = 0) }
    override fun seekTo(positionMs: Long) { calls += "seek:$positionMs"; snapshot = snapshot.copy(positionMs = positionMs) }
    override fun next() { calls += "advance" }
    override fun setVolume(volume: Int) { calls += "volume:$volume"; snapshot = snapshot.copy(volume = volume) }
    override fun setMute(mute: Boolean) { calls += "mute:$mute"; snapshot = snapshot.copy(mute = mute) }
    override fun snapshot(): RendererSnapshot = snapshot
}
