package com.phairplay.dlna.scpd

/**
 * ConnectionManagerScpd — ConnectionManager:1 description exposing the sink protocolInfo list.
 *
 * WHY: Windows calls GetProtocolInfo before casting to decide which formats it may send natively.
 *
 * HOW: `ConnectionManagerScpd.XML` is the lazily-built SCPD document; see [scpd] for the DSL.
 */
object ConnectionManagerScpd {
    val XML: String by lazy {
        scpd {
            action("GetProtocolInfo") { output("Source", "SourceProtocolInfo"); output("Sink", "SinkProtocolInfo") }
            action("GetCurrentConnectionIDs") { output("ConnectionIDs", "CurrentConnectionIDs") }
            action("GetCurrentConnectionInfo") {
                input("ConnectionID", "A_ARG_TYPE_ConnectionID"); output("RcsID", "A_ARG_TYPE_RcsID")
                output("AVTransportID", "A_ARG_TYPE_AVTransportID"); output("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo")
                output("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"); output("PeerConnectionID", "A_ARG_TYPE_ConnectionID")
                output("Direction", "A_ARG_TYPE_Direction"); output("Status", "A_ARG_TYPE_ConnectionStatus")
            }

            variable("SourceProtocolInfo", evented = true)
            variable("SinkProtocolInfo", evented = true)
            variable("CurrentConnectionIDs", evented = true)
            variable("A_ARG_TYPE_ConnectionStatus", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown"))
            variable("A_ARG_TYPE_ConnectionManager")
            variable("A_ARG_TYPE_Direction", allowed = listOf("Input", "Output"))
            variable("A_ARG_TYPE_ProtocolInfo")
            variable("A_ARG_TYPE_ConnectionID", dataType = "i4")
            variable("A_ARG_TYPE_AVTransportID", dataType = "i4")
            variable("A_ARG_TYPE_RcsID", dataType = "i4")
        }
    }
}
