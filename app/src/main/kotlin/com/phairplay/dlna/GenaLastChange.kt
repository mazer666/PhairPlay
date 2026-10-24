package com.phairplay.dlna

/**
 * GenaLastChange — builds the `LastChange` payloads of the GENA eventing protocol.
 *
 * WHY: the UPnP eventing specification does not send a flat "key=value" list. The
 * `LastChange` property carries an XML document that is *escaped* inside the
 * property set, e.g.
 *
 * ```
 * <e:property><LastChange>&lt;EventValue xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"&gt;
 *   &lt;InstanceID val="0"&gt;&lt;TransportState val="PLAYING"/&gt;&lt;/InstanceID&gt;
 * &lt;/EventValue&gt;</LastChange>
 * ```
 *
 * A control point that receives a flat list ignores the event, which is why the
 * state changes never became visible in Windows Media Player.
 *
 * Example:
 *   GenaLastChange.avTransport("TransportState" to "PLAYING")
 */
internal object GenaLastChange {

    /** Builds the AVTransport `LastChange` document for [entries]. */
    fun avTransport(vararg entries: Pair<String, String>): String =
        build("urn:schemas-upnp-org:metadata-1-0/AVT/", entries.map { (name, value) ->
            """<$name val="${SoapResponses.escape(value)}"/>"""
        })

    /** Builds the RenderingControl `LastChange` document for [entries]. */
    fun renderingControl(vararg entries: Pair<String, String>): String =
        build("urn:schemas-upnp-org:metadata-1-0/RCS/", entries.map { (name, value) ->
            """<$name channel="Master" val="${SoapResponses.escape(value)}"/>"""
        })

    private fun build(namespace: String, elements: List<String>): String =
        buildString {
            append("""<EventValue xmlns="$namespace">""")
            append("""<InstanceID val="0">""")
            elements.forEach { append(it) }
            append("</InstanceID>")
            append("</EventValue>")
        }
}
