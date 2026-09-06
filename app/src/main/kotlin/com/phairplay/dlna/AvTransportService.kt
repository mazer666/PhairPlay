package com.phairplay.dlna

/**
 * AvTransportService — AVTransport:1 actions mapped onto [RendererControl].
 *
 * WHY: This is the service Windows "Cast to Device", BubbleUPnP and VLC drive to load and control media.
 * It enforces the state rules (701 faults) and the wire formats; the renderer does the actual work.
 *
 * HOW: Registered with [SoapDispatcher] for [UpnpService.AV_TRANSPORT]. Every action carries
 * `InstanceID`, which must be 0.
 */
class AvTransportService(private val control: RendererControl) : SoapActionHandler {

    override fun handle(action: String, args: Map<String, String>, context: SoapContext): Map<String, String> {
        SoapArgs.requireInstanceZero(args)
        return when (action) {
            "SetAVTransportURI" -> {
                control.load(SoapArgs.required(args, "CurrentURI"), args["CurrentURIMetaData"] ?: "", context.userAgent)
                emptyMap()
            }
            "SetNextAVTransportURI" -> {
                control.setNext(SoapArgs.required(args, "NextURI"), args["NextURIMetaData"] ?: "")
                emptyMap()
            }
            "Play" -> {
                if (!control.snapshot().hasMedia) throw UpnpError.transitionNotAvailable("no media loaded")
                control.play(); emptyMap()
            }
            "Pause" -> {
                val state = control.snapshot().state
                if (state != TransportState.PLAYING && state != TransportState.TRANSITIONING) {
                    throw UpnpError.transitionNotAvailable("not playing")
                }
                control.pause(); emptyMap()
            }
            "Stop" -> { control.stop(); emptyMap() }
            "Seek" -> seek(args)
            "Next" -> {
                if (control.snapshot().nextUri.isEmpty()) throw UpnpError.transitionNotAvailable("no next item")
                control.next(); emptyMap()
            }
            "Previous" -> {
                if (!control.snapshot().hasMedia) throw UpnpError.transitionNotAvailable("no media loaded")
                control.seekTo(0L); emptyMap()
            }
            "GetMediaInfo" -> mediaInfo()
            "GetTransportInfo" -> transportInfo()
            "GetPositionInfo" -> positionInfo()
            "GetDeviceCapabilities" -> mapOf(
                "PlayMedia" to "NETWORK,NONE", "RecMedia" to NOT_IMPLEMENTED, "RecQualityModes" to NOT_IMPLEMENTED
            )
            "GetTransportSettings" -> mapOf("PlayMode" to "NORMAL", "RecQualityMode" to NOT_IMPLEMENTED)
            "GetCurrentTransportActions" -> mapOf("Actions" to control.snapshot().transportActions)
            else -> throw UpnpError.invalidAction(action)
        }
    }

    /**
     * Parses `Unit`/`Target` and forwards a millisecond position to [RendererControl.seekTo]. This layer
     * does not clamp a REL_TIME/ABS_TIME target to the media duration — that is the renderer's job
     * (`MediaRenderer.seekTo` already clamps to `[0, durationMs]`), so a sender seeking past the end is
     * handed through unchanged and left to the renderer's own bounds check.
     */
    private fun seek(args: Map<String, String>): Map<String, String> {
        if (!control.snapshot().hasMedia) throw UpnpError.transitionNotAvailable("no media loaded")
        val unit = SoapArgs.required(args, "Unit").trim()
        val target = SoapArgs.required(args, "Target").trim()
        when (unit) {
            "REL_TIME", "ABS_TIME" ->
                control.seekTo(UpnpTime.parse(target) ?: throw UpnpError.invalidArgs("bad time '$target'"))
            "TRACK_NR" ->
                if (target == "1") control.seekTo(0L) else throw UpnpError.invalidArgs("no track $target")
            else -> throw UpnpError.invalidArgs("unsupported seek unit $unit")
        }
        return emptyMap()
    }

    private fun mediaInfo(): Map<String, String> {
        val s = control.snapshot()
        return mapOf(
            "NrTracks" to s.numberOfTracks.toString(),
            "MediaDuration" to UpnpTime.format(s.durationMs),
            "CurrentURI" to s.currentUri,
            "CurrentURIMetaData" to s.currentMetadata,
            "NextURI" to s.nextUri,
            "NextURIMetaData" to s.nextMetadata,
            "PlayMedium" to if (s.hasMedia) "NETWORK" else "NONE",
            "RecordMedium" to NOT_IMPLEMENTED,
            "WriteStatus" to NOT_IMPLEMENTED
        )
    }

    private fun transportInfo(): Map<String, String> {
        val s = control.snapshot()
        return mapOf("CurrentTransportState" to s.state.name, "CurrentTransportStatus" to s.status, "CurrentSpeed" to PLAY_SPEED)
    }

    private fun positionInfo(): Map<String, String> {
        val s = control.snapshot()
        val position = UpnpTime.format(s.positionMs)
        return mapOf(
            "Track" to s.numberOfTracks.toString(),
            "TrackDuration" to UpnpTime.format(s.durationMs),
            "TrackMetaData" to s.currentMetadata,
            "TrackURI" to s.currentUri,
            "RelTime" to position,
            "AbsTime" to position,
            "RelCount" to COUNTER_NOT_IMPLEMENTED,
            "AbsCount" to COUNTER_NOT_IMPLEMENTED
        )
    }

    companion object {
        const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
        const val PLAY_SPEED = "1"
        /** AVTransport:1 says counters we do not track are reported as Int.MAX_VALUE. */
        const val COUNTER_NOT_IMPLEMENTED = "2147483647"
    }
}
