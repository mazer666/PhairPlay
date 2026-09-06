package com.phairplay.dlna

/**
 * LastChangeEncoder — builds the `LastChange` event document for AVTransport and RenderingControl.
 *
 * WHY: UPnP AV services do not event individual variables; they event one `LastChange` XML blob listing
 * the variables that changed. Control points (Windows, BubbleUPnP) parse it to update their UI.
 *
 * HOW: `LastChangeEncoder.avTransport(LastChangeEncoder.avTransportVars(snapshot))` → XML that goes into
 * the `LastChange` property of a GENA NOTIFY (which escapes it once more).
 */
object LastChangeEncoder {
    const val AVT_NAMESPACE = "urn:schemas-upnp-org:metadata-1-0/AVT/"
    const val RCS_NAMESPACE = "urn:schemas-upnp-org:metadata-1-0/RCS/"
    private const val CHANNEL_ATTRIBUTE = "channel=\"Master\" "

    fun avTransport(vars: Map<String, String>): String = encode(AVT_NAMESPACE, vars, withChannel = false)

    fun renderingControl(vars: Map<String, String>): String = encode(RCS_NAMESPACE, vars, withChannel = true)

    fun avTransportVars(s: RendererSnapshot): Map<String, String> = linkedMapOf(
        "TransportState" to s.state.name,
        "TransportStatus" to s.status,
        "CurrentTransportActions" to s.transportActions,
        "NumberOfTracks" to s.numberOfTracks.toString(),
        "CurrentTrack" to s.numberOfTracks.toString(),
        "CurrentTrackDuration" to UpnpTime.format(s.durationMs),
        "CurrentMediaDuration" to UpnpTime.format(s.durationMs),
        "CurrentTrackURI" to s.currentUri,
        "AVTransportURI" to s.currentUri,
        "CurrentTrackMetaData" to s.currentMetadata,
        "AVTransportURIMetaData" to s.currentMetadata,
        "NextAVTransportURI" to s.nextUri,
        "NextAVTransportURIMetaData" to s.nextMetadata
    )

    fun renderingControlVars(s: RendererSnapshot): Map<String, String> = linkedMapOf(
        "Volume" to s.volume.toString(),
        "Mute" to if (s.mute) "1" else "0"
    )

    private fun encode(namespace: String, vars: Map<String, String>, withChannel: Boolean): String {
        val body = vars.entries.joinToString("") { (name, value) ->
            "<$name ${if (withChannel) CHANNEL_ATTRIBUTE else ""}val=\"${SecureXml.escape(value)}\"/>"
        }
        return "<Event xmlns=\"$namespace\"><InstanceID val=\"0\">$body</InstanceID></Event>"
    }
}
