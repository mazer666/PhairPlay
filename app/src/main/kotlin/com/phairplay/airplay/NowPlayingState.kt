package com.phairplay.airplay

/**
 * NowPlayingState — Holds the "now playing" state of a running AirPlay session.
 *
 * WHAT: Keeps the metadata of the track that is currently playing (title, artist, album,
 * artwork), the name of the sender and the two flags that say whether audio and/or video
 * is playing — and turns that state into a [NowPlayingInfo] for the UI.
 *
 * WHY: The now-playing card must only appear while audio plays WITHOUT video (Apple Music,
 * a podcast). While a video is mirroring, the video screen takes over. The flags that decide
 * this are touched from several places (the RTSP handshake, the AirPlay 2 mirror servers, the
 * buffered audio server), so they live in one small shared object instead of being spread over
 * the orchestrator and its helpers.
 *
 * HOW: The owner creates it with the callback that receives the result and hands the instance
 * to the parts that need it:
 *   val nowPlaying = NowPlayingState { info -> ui.showNowPlaying(info) }
 *   nowPlaying.audioPlaying = true
 *   nowPlaying.emitNowPlaying()          // → ui.showNowPlaying(NowPlayingInfo(...))
 *
 * Example:
 *   nowPlaying.npTitle = "Hey Jude"
 *   nowPlaying.emitNowPlaying()
 */
internal class NowPlayingState(
    /**
     * Receives the new state — a [NowPlayingInfo] while audio plays without video, null otherwise.
     */
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit
) {

    // ─── Now-playing (audio-only) state ──────────────────────────────────────
    // The now-playing card shows only when audio plays WITHOUT video. We track both stream kinds
    // plus the latest DMAP metadata/artwork and recompute on every change (see [emitNowPlaying]).
    @Volatile internal var audioPlaying = false
    @Volatile internal var videoPlaying = false
    @Volatile internal var npSenderName = "AirPlay"
    @Volatile internal var npTitle: String? = null
    @Volatile internal var npArtist: String? = null
    @Volatile internal var npAlbum: String? = null
    @Volatile internal var npArtwork: ByteArray? = null

    /** Pushes the current now-playing state out: a [NowPlayingInfo] when audio plays without video, else null. */
    internal fun emitNowPlaying() {
        val show = audioPlaying && !videoPlaying
        onNowPlayingChanged(
            if (show) NowPlayingInfo(npSenderName, npTitle, npArtist, npAlbum, npArtwork) else null
        )
    }

    /** Drops stale track metadata/artwork when an audio stream ends (so it can't bleed into the next). */
    internal fun clearNowPlayingMetadata() {
        npTitle = null; npArtist = null; npAlbum = null; npArtwork = null
    }

    /**
     * Resets the whole state — used when a session ends completely.
     *
     * The UI must leave the now-playing card, so the reset also pushes the new (empty) state out.
     */
    internal fun reset() {
        audioPlaying = false
        videoPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
    }
}
