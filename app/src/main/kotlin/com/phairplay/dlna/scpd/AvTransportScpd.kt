package com.phairplay.dlna.scpd

/**
 * AvTransportScpd — AVTransport:1 service description, trimmed to the actions this renderer implements.
 *
 * WHY: Control points only call actions declared here; declaring what we do not implement would invite
 * 401 faults, declaring less would hide Seek/Next from BubbleUPnP and VLC.
 *
 * HOW: `AvTransportScpd.XML` is the lazily-built SCPD document; see [scpd] for the DSL.
 */
object AvTransportScpd {
    private const val INSTANCE = "A_ARG_TYPE_InstanceID"

    val XML: String by lazy {
        scpd {
            action("SetAVTransportURI") {
                input("InstanceID", INSTANCE); input("CurrentURI", "AVTransportURI"); input("CurrentURIMetaData", "AVTransportURIMetaData")
            }
            action("SetNextAVTransportURI") {
                input("InstanceID", INSTANCE); input("NextURI", "NextAVTransportURI"); input("NextURIMetaData", "NextAVTransportURIMetaData")
            }
            action("GetMediaInfo") {
                input("InstanceID", INSTANCE); output("NrTracks", "NumberOfTracks"); output("MediaDuration", "CurrentMediaDuration")
                output("CurrentURI", "AVTransportURI"); output("CurrentURIMetaData", "AVTransportURIMetaData")
                output("NextURI", "NextAVTransportURI"); output("NextURIMetaData", "NextAVTransportURIMetaData")
                output("PlayMedium", "PlaybackStorageMedium"); output("RecordMedium", "RecordStorageMedium"); output("WriteStatus", "RecordMediumWriteStatus")
            }
            action("GetTransportInfo") {
                input("InstanceID", INSTANCE); output("CurrentTransportState", "TransportState")
                output("CurrentTransportStatus", "TransportStatus"); output("CurrentSpeed", "TransportPlaySpeed")
            }
            action("GetPositionInfo") {
                input("InstanceID", INSTANCE); output("Track", "CurrentTrack"); output("TrackDuration", "CurrentTrackDuration")
                output("TrackMetaData", "CurrentTrackMetaData"); output("TrackURI", "CurrentTrackURI")
                output("RelTime", "RelativeTimePosition"); output("AbsTime", "AbsoluteTimePosition")
                output("RelCount", "RelativeCounterPosition"); output("AbsCount", "AbsoluteCounterPosition")
            }
            action("GetDeviceCapabilities") {
                input("InstanceID", INSTANCE); output("PlayMedia", "PossiblePlaybackStorageMedia")
                output("RecMedia", "PossibleRecordStorageMedia"); output("RecQualityModes", "PossibleRecordQualityModes")
            }
            action("GetTransportSettings") {
                input("InstanceID", INSTANCE); output("PlayMode", "CurrentPlayMode"); output("RecQualityMode", "CurrentRecordQualityMode")
            }
            action("Stop") { input("InstanceID", INSTANCE) }
            action("Play") { input("InstanceID", INSTANCE); input("Speed", "TransportPlaySpeed") }
            action("Pause") { input("InstanceID", INSTANCE) }
            action("Seek") { input("InstanceID", INSTANCE); input("Unit", "A_ARG_TYPE_SeekMode"); input("Target", "A_ARG_TYPE_SeekTarget") }
            action("Next") { input("InstanceID", INSTANCE) }
            action("Previous") { input("InstanceID", INSTANCE) }
            action("GetCurrentTransportActions") { input("InstanceID", INSTANCE); output("Actions", "CurrentTransportActions") }

            variable("TransportState", allowed = listOf("STOPPED", "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "NO_MEDIA_PRESENT"))
            variable("TransportStatus", allowed = listOf("OK", "ERROR_OCCURRED"))
            variable("PlaybackStorageMedium", allowed = listOf("NONE", "NETWORK"))
            variable("RecordStorageMedium", allowed = listOf("NOT_IMPLEMENTED"))
            variable("PossiblePlaybackStorageMedia")
            variable("PossibleRecordStorageMedia")
            variable("CurrentPlayMode", allowed = listOf("NORMAL"), default = "NORMAL")
            variable("TransportPlaySpeed", allowed = listOf("1"))
            variable("RecordMediumWriteStatus", allowed = listOf("NOT_IMPLEMENTED"))
            variable("CurrentRecordQualityMode", allowed = listOf("NOT_IMPLEMENTED"))
            variable("PossibleRecordQualityModes")
            variable("NumberOfTracks", dataType = "ui4", range = 0..1)
            variable("CurrentTrack", dataType = "ui4", range = 0..1)
            variable("CurrentTrackDuration")
            variable("CurrentMediaDuration")
            variable("CurrentTrackMetaData")
            variable("CurrentTrackURI")
            variable("AVTransportURI")
            variable("AVTransportURIMetaData")
            variable("NextAVTransportURI")
            variable("NextAVTransportURIMetaData")
            variable("RelativeTimePosition")
            variable("AbsoluteTimePosition")
            variable("RelativeCounterPosition", dataType = "i4")
            variable("AbsoluteCounterPosition", dataType = "i4")
            variable("CurrentTransportActions")
            variable("LastChange", evented = true)
            variable("A_ARG_TYPE_SeekMode", allowed = listOf("TRACK_NR", "REL_TIME", "ABS_TIME"))
            variable("A_ARG_TYPE_SeekTarget")
            variable(INSTANCE, dataType = "ui4")
        }
    }
}
