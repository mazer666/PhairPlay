package com.phairplay.dlna

/**
 * DlnaAvTransportService — the UPnP `AVTransport:1` action set.
 *
 * WHY: the transport service is the heart of the control plane. It owns the state
 * machine ("what is loaded", "is it playing", "where are we in the media") and
 * translates control point commands into calls on the [DlnaPlaybackController].
 * It is kept apart from the dispatcher so the protocol rules can be read without
 * the SOAP plumbing.
 *
 * Example:
 *   val result = service.handle("Play", emptyMap())
 */
internal class DlnaAvTransportService(
    private val state: DlnaTransportState,
    private val controller: DlnaPlaybackController,
    private val onLastChange: (String, String) -> Unit
) {

    /** Handles a single AVTransport action. */
    fun handle(action: String, values: Map<String, String>): SoapResult =
        when (action) {
            "SetAVTransportURI" -> setAvTransportUri(values)
            "Play" -> play()
            "Pause" -> pause()
            "Stop" -> stop()
            "Seek" -> seek(values)
            "GetTransportInfo" -> getTransportInfo()
            "GetPositionInfo" -> getPositionInfo()
            "GetMediaInfo" -> getMediaInfo()
            "GetCurrentTransportActions" -> getCurrentTransportActions()
            else -> SoapResponses.fault(
                SoapResponses.ERROR_INVALID_ACTION,
                "Invalid action: $action"
            )
        }

    private fun setAvTransportUri(values: Map<String, String>): SoapResult {
        // The URI must be accepted in every shape a real control point sends it.
        // Only a URI that cannot be fetched at all (empty, oversized, control
        // characters, unknown scheme) is refused — refusing more would make the
        // renderer useless for the normal case.
        val uri = when (val validated = DlnaMediaUri.validate(values["CurrentURI"])) {
            is DlnaMediaUri.Result.Accepted -> validated.uri
            is DlnaMediaUri.Result.Rejected -> {
                return SoapResponses.fault(SoapResponses.ERROR_INVALID_ARGS, "Invalid CurrentURI")
            }
        }
        state.currentUri = uri
        state.currentUriMetadata = values["CurrentURIMetaData"].orEmpty().take(MAX_METADATA_CHARS)
        state.metadata = DlnaMediaMetadataParser.parse(state.currentUriMetadata)
        state.transportState = DlnaTransportState.TRANSPORT_STOPPED
        state.onStopped()
        controller.onSetTransportUri(uri)
        publishTransportState()
        return SoapResponses.ok(action = "SetAVTransportURI", service = SERVICE, values = emptyMap())
    }

    private fun play(): SoapResult {
        if (state.currentUri.isBlank()) {
            return SoapResponses.fault(
                SoapResponses.ERROR_TRANSITION_NOT_AVAILABLE,
                "No media loaded"
            )
        }
        state.transportState = DlnaTransportState.TRANSPORT_PLAYING
        state.onPlayStarted()
        controller.onPlay(state.currentUri, state.metadata)
        publishTransportState()
        return SoapResponses.ok(action = "Play", service = SERVICE, values = emptyMap())
    }

    private fun pause(): SoapResult {
        if (state.currentUri.isBlank()) {
            return SoapResponses.fault(
                SoapResponses.ERROR_TRANSITION_NOT_AVAILABLE,
                "No media loaded"
            )
        }
        state.transportState = DlnaTransportState.TRANSPORT_PAUSED
        state.onPaused()
        controller.onPause()
        publishTransportState()
        return SoapResponses.ok(action = "Pause", service = SERVICE, values = emptyMap())
    }

    private fun stop(): SoapResult {
        state.transportState = DlnaTransportState.TRANSPORT_STOPPED
        state.onStopped()
        controller.onStop()
        publishTransportState()
        return SoapResponses.ok(action = "Stop", service = SERVICE, values = emptyMap())
    }

    private fun seek(values: Map<String, String>): SoapResult {
        val unit = values["Unit"].orEmpty().uppercase()
        if (unit != "REL_TIME" && unit != "ABS_TIME") {
            return SoapResponses.fault(SoapResponses.ERROR_INVALID_ARGS, "Unsupported seek unit")
        }
        val target = DlnaMediaMetadataParser.parseDuration(values["Target"].orEmpty())
            ?: return SoapResponses.fault(SoapResponses.ERROR_INVALID_ARGS, "Unsupported seek target")
        state.onSeek(target)
        controller.onSeek(target)
        publishTransportState()
        return SoapResponses.ok(action = "Seek", service = SERVICE, values = emptyMap())
    }

    private fun getTransportInfo(): SoapResult =
        SoapResponses.ok(
            action = "GetTransportInfo",
            service = SERVICE,
            values = mapOf(
                "CurrentTransportState" to state.transportState,
                "CurrentTransportStatus" to "OK",
                "CurrentSpeed" to state.playbackSpeed
            )
        )

    private fun getPositionInfo(): SoapResult {
        val duration = resolveDurationMillis()
        val position = resolvePositionMillis()
        return SoapResponses.ok(
            action = "GetPositionInfo",
            service = SERVICE,
            values = mapOf(
                "Track" to state.currentTrack.toString(),
                "TrackDuration" to DlnaTimeFormat.duration(duration),
                "TrackMetaData" to state.currentUriMetadata,
                "TrackURI" to state.currentUri,
                "RelTime" to DlnaTimeFormat.relativeTime(position),
                "AbsTime" to DlnaTimeFormat.relativeTime(position),
                "RelCount" to "0",
                "AbsCount" to "0"
            )
        )
    }

    private fun getMediaInfo(): SoapResult {
        val duration = resolveDurationMillis()
        return SoapResponses.ok(
            action = "GetMediaInfo",
            service = SERVICE,
            values = mapOf(
                "NrTracks" to if (state.currentUri.isBlank()) "0" else "1",
                "MediaDuration" to DlnaTimeFormat.duration(duration),
                "CurrentURI" to state.currentUri,
                "CurrentURIMetaData" to state.currentUriMetadata,
                "PlayMedium" to "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED",
                "WriteStatus" to "NOT_IMPLEMENTED"
            )
        )
    }

    private fun getCurrentTransportActions(): SoapResult =
        SoapResponses.ok(
            action = "GetCurrentTransportActions",
            service = SERVICE,
            values = mapOf("Actions" to currentTransportActions())
        )

    /**
     * Applies a transport command that came from the TV remote.
     *
     * WHY: the remote control of the TV must steer the same state machine the
     * network control point uses, otherwise a control point would keep showing
     * "PLAYING" while the TV is paused.
     */
    fun remotePlayPause() {
        if (state.transportState == DlnaTransportState.TRANSPORT_PLAYING) {
            pause()
        } else {
            play()
        }
    }

    /** Applies a "pause" command that came from the TV remote. */
    fun remotePause() {
        pause()
    }

    /** Applies a "stop" command that came from the TV remote. */
    fun remoteStop() {
        stop()
    }

    /**
     * Returns the actions that make sense in the current state.
     *
     * WHY: a control point uses this list to decide which buttons to enable. A
     * stopped renderer cannot be paused, a playing one cannot be started again.
     */
    private fun currentTransportActions(): String =
        when (state.transportState) {
            DlnaTransportState.TRANSPORT_PLAYING -> "Pause,Stop,Seek"
            DlnaTransportState.TRANSPORT_PAUSED -> "Play,Stop,Seek"
            else -> "Play,Stop,Seek"
        }

    /**
     * Returns the duration of the loaded media in milliseconds.
     *
     * WHY: the media player knows the real duration, but it only knows it after it
     * prepared the stream. Before that the duration the control point announced in
     * its DIDL-Lite metadata is the best available answer — and a control point that
     * asks for the duration before `Play` still gets a usable value.
     */
    private fun resolveDurationMillis(): Long {
        val fromPlayer = controller.positionInfo().durationMillis
        if (fromPlayer > 0L) return fromPlayer
        return state.metadata?.durationMillis ?: 0L
    }

    /** Returns the current position in milliseconds. */
    private fun resolvePositionMillis(): Long {
        val fromPlayer = controller.positionInfo()
        if (fromPlayer.durationMillis > 0L) return fromPlayer.positionMillis
        return state.trackedPositionMillis()
    }

    /** Publishes the transport state to the GENA subscribers. */
    private fun publishTransportState() {
        onLastChange(
            EVENT_PATH,
            GenaLastChange.avTransport(
                "TransportState" to state.transportState,
                "CurrentTransportActions" to currentTransportActions(),
                "CurrentSpeed" to state.playbackSpeed
            )
        )
    }

    companion object {
        private const val SERVICE = "AVTransport"
        private const val EVENT_PATH = "/upnp/event/AVTransport"

        /** Upper bound for the echoed DIDL-Lite document (64 kB). */
        private const val MAX_METADATA_CHARS = 64 * 1024
    }
}
