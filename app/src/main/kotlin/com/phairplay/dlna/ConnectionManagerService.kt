package com.phairplay.dlna

/**
 * ConnectionManagerService — ConnectionManager:1 with the sink protocolInfo list and one static connection.
 *
 * WHY: Control points (Windows in particular) read GetProtocolInfo before casting to learn which formats
 * we accept natively. We never negotiate connections, so the single connection 0 is always present.
 *
 * HOW: Registered with [SoapDispatcher] for [UpnpService.CONNECTION_MANAGER]. No InstanceID here.
 */
class ConnectionManagerService : SoapActionHandler {

    override fun handle(action: String, args: Map<String, String>, context: SoapContext): Map<String, String> =
        when (action) {
            "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to ProtocolInfoList.sinkString())
            "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to CONNECTION_ID)
            "GetCurrentConnectionInfo" -> {
                val id = SoapArgs.required(args, "ConnectionID").trim()
                if (id != CONNECTION_ID) throw UpnpError.invalidConnectionReference(id)
                mapOf(
                    "RcsID" to CONNECTION_ID,
                    "AVTransportID" to CONNECTION_ID,
                    "ProtocolInfo" to "",
                    "PeerConnectionManager" to "",
                    "PeerConnectionID" to NO_PEER,
                    "Direction" to "Input",
                    "Status" to "OK"
                )
            }
            else -> throw UpnpError.invalidAction(action)
        }

    companion object {
        const val CONNECTION_ID = "0"
        const val NO_PEER = "-1"
    }
}
