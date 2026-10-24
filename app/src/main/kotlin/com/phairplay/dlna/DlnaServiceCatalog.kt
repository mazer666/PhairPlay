package com.phairplay.dlna

/**
 * DlnaActionSpec — one action of a UPnP service, with its arguments.
 */
internal data class DlnaActionSpec(
    val name: String,
    val arguments: List<DlnaArgumentSpec>
)

/**
 * DlnaArgumentSpec — one argument of a UPnP action.
 *
 * @param direction `in` or `out`.
 * @param stateVariable the state variable the argument is bound to.
 */
internal data class DlnaArgumentSpec(
    val name: String,
    val direction: String,
    val stateVariable: String
)

/**
 * DlnaServiceCatalog — the actions and state variables of the three services.
 *
 * WHY: a control point reads the service description (SCPD) to learn which actions
 * a service offers and which arguments they take. A description that only lists the
 * action names is enough for VLC, but other control points (Windows Media Player,
 * BubbleUPnP) build their proxy from the argument list and refuse a service whose
 * arguments they cannot resolve.
 *
 * Example:
 *   DlnaServiceCatalog.actionsFor("AVTransport")
 */
internal object DlnaServiceCatalog {

    private val CONNECTION_MANAGER = listOf(
        action("GetProtocolInfo", outArg("Source"), outArg("Sink")),
        action("GetCurrentConnectionIDs", outArg("ConnectionIDs")),
        action(
            "GetCurrentConnectionInfo",
            outArg("RcsID"),
            outArg("AVTransportID"),
            outArg("ProtocolInfo"),
            outArg("PeerConnectionManager"),
            outArg("PeerConnectionID"),
            outArg("Direction"),
            outArg("Status")
        )
    )

    private val AV_TRANSPORT = listOf(
        action("SetAVTransportURI", inArg("CurrentURI"), inArg("CurrentURIMetaData")),
        action("SetNextAVTransportURI", inArg("NextURI"), inArg("NextURIMetaData")),
        action("GetMediaInfo", outArg("NrTracks"), outArg("MediaDuration"), outArg("CurrentURI"), outArg("CurrentURIMetaData"), outArg("PlayMedium"), outArg("RecordMedium"), outArg("WriteStatus")),
        action("GetTransportInfo", outArg("CurrentTransportState"), outArg("CurrentTransportStatus"), outArg("CurrentSpeed")),
        action("GetPositionInfo", outArg("Track"), outArg("TrackDuration"), outArg("TrackMetaData"), outArg("TrackURI"), outArg("RelTime"), outArg("AbsTime"), outArg("RelCount"), outArg("AbsCount")),
        action("GetDeviceCapabilities", outArg("PlayMedia"), outArg("RecMedia"), outArg("RecQualityModes")),
        action("GetCurrentTransportActions", outArg("Actions")),
        action("Play", inArg("Speed")),
        action("Pause"),
        action("Stop"),
        action("Seek", inArg("Unit"), inArg("Target"))
    )

    private val RENDERING_CONTROL = listOf(
        action("GetVolume", outArg("CurrentVolume")),
        action("SetVolume", inArg("DesiredVolume")),
        action("GetVolumeDB", outArg("CurrentVolume")),
        action("SetVolumeDB", inArg("DesiredVolume")),
        action("GetMute", outArg("CurrentMute")),
        action("SetMute", inArg("DesiredMute"))
    )

    /** The data type of every state variable the actions refer to. */
    private val STATE_VARIABLE_TYPES = mapOf(
        "Source" to "string",
        "Sink" to "string",
        "ConnectionIDs" to "string",
        "RcsID" to "i4",
        "AVTransportID" to "i4",
        "ProtocolInfo" to "string",
        "PeerConnectionManager" to "string",
        "PeerConnectionID" to "i4",
        "Direction" to "string",
        "Status" to "string",
        "CurrentURI" to "string",
        "CurrentURIMetaData" to "string",
        "NextURI" to "string",
        "NextURIMetaData" to "string",
        "NrTracks" to "i4",
        "MediaDuration" to "string",
        "PlayMedium" to "string",
        "RecordMedium" to "string",
        "WriteStatus" to "string",
        "CurrentTransportState" to "string",
        "CurrentTransportStatus" to "string",
        "CurrentSpeed" to "string",
        "Track" to "i4",
        "TrackDuration" to "string",
        "TrackMetaData" to "string",
        "TrackURI" to "string",
        "RelTime" to "string",
        "AbsTime" to "string",
        "RelCount" to "i4",
        "AbsCount" to "i4",
        "PlayMedia" to "string",
        "RecMedia" to "string",
        "RecQualityModes" to "string",
        "Actions" to "string",
        "Unit" to "string",
        "Target" to "string",
        "Speed" to "string",
        "CurrentVolume" to "i2",
        "DesiredVolume" to "i2",
        "CurrentMute" to "boolean",
        "DesiredMute" to "boolean"
    )

    /** Returns the actions of the service [serviceName], or an empty list. */
    fun actionsFor(serviceName: String): List<DlnaActionSpec> =
        when (serviceName) {
            "ConnectionManager" -> CONNECTION_MANAGER
            "AVTransport" -> AV_TRANSPORT
            "RenderingControl" -> RENDERING_CONTROL
            else -> emptyList()
        }

    /** Returns the names of the state variables the service uses. */
    fun stateVariablesFor(serviceName: String): List<String> =
        actionsFor(serviceName)
            .flatMap { spec -> spec.arguments.map { it.stateVariable } }
            .distinct()

    /** Returns the data type of a state variable (defaults to `string`). */
    fun typeOf(stateVariable: String): String =
        STATE_VARIABLE_TYPES[stateVariable] ?: "string"

    private fun action(name: String, vararg arguments: DlnaArgumentSpec) =
        DlnaActionSpec(name, arguments.toList())

    private fun inArg(name: String) = DlnaArgumentSpec(name, "in", name)

    private fun outArg(name: String) = DlnaArgumentSpec(name, "out", name)
}
