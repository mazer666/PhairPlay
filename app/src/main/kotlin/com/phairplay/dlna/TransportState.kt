package com.phairplay.dlna

/**
 * TransportState — the AVTransport:1 transport states this renderer can report.
 *
 * WHY: Enum names are the exact wire strings (`PLAYING`, `PAUSED_PLAYBACK`, …), so `state.name` is what
 * goes into GetTransportInfo and LastChange.
 */
enum class TransportState {
    NO_MEDIA_PRESENT, STOPPED, TRANSITIONING, PLAYING, PAUSED_PLAYBACK;

    /** True while a control point would consider the renderer "in use" (drives the CONNECTED card state). */
    val isActive: Boolean get() = this == TRANSITIONING || this == PLAYING || this == PAUSED_PLAYBACK
}

/** What kind of item a SetAVTransportURI pointed at; decides player vs. photo path and which overlay. */
enum class MediaClass { VIDEO, AUDIO, IMAGE }
