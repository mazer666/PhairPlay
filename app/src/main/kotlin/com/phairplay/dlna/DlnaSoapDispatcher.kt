package com.phairplay.dlna

import com.phairplay.util.Logger
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * DlnaSoapDispatcher — routes a SOAP control request to the matching UPnP service.
 *
 * WHY: the dispatcher is the only place that knows how a UPnP control request is
 * built (HTTP path + `SOAPACTION` header + XML body). It parses the body with the
 * hardened parser, hands the arguments to the service that owns the action and
 * returns the answer the control point expects.
 *
 * The three services of a DLNA MediaRenderer are:
 * - `ConnectionManager:1` — what the renderer can fetch (`GetProtocolInfo`)
 * - `AVTransport:1` — the transport state machine
 * - `RenderingControl:1` — volume and mute
 *
 * Example:
 *   val result = DlnaSoapDispatcher().dispatch(
 *       controlPath = "/upnp/control/ConnectionManager",
 *       soapAction = "\"urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo\"",
 *       body = soapEnvelope
 *   )
 */
class DlnaSoapDispatcher(
    private val playbackController: DlnaPlaybackController = DlnaPlaybackController.NOOP,
    private val onLastChange: (String, String) -> Unit = { _, _ -> }
) {
    private val state = DlnaTransportState()
    private val avTransport = DlnaAvTransportService(state, playbackController, onLastChange)

    /**
     * Handles one control request.
     *
     * @param controlPath the HTTP path of the control URL, e.g. `/upnp/control/AVTransport`.
     * @param soapAction the value of the `SOAPACTION` header, may be null.
     * @param body the SOAP envelope.
     * @return the answer to send back to the control point.
     */
    fun dispatch(controlPath: String, soapAction: String?, body: String): SoapResult {
        val requestedAction = soapAction
            ?.substringAfter('#')
            ?.trim('"')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return try {
            val document = SecureSoapXml.parse(body)
            val action = requestedAction ?: SecureSoapXml.actionName(document)
            if (action.isNullOrEmpty()) {
                return SoapResponses.fault(
                    SoapResponses.ERROR_INVALID_ACTION,
                    "Missing SOAP action"
                )
            }
            val values = SecureSoapXml.directChildText(document, action)
            when (controlPath.substringBefore('?')) {
                CONNECTION_MANAGER_PATH -> connectionManager(action)
                AV_TRANSPORT_PATH -> avTransport.handle(action, values)
                RENDERING_CONTROL_PATH -> renderingControl(action, values)
                else -> SoapResult(
                    statusCode = 404,
                    statusMessage = "Not Found",
                    body = SoapResponses.fault(
                        SoapResponses.ERROR_INVALID_ACTION,
                        "Unknown control URL"
                    ).body
                )
            }
        } catch (e: Exception) {
            // The details stay in the log: a control point must not learn about
            // parser internals, file paths or library versions from an error answer.
            Logger.w("DLNA SOAP request rejected: ${e.javaClass.simpleName}")
            SoapResult(
                statusCode = 400,
                statusMessage = "Bad Request",
                body = SoapResponses.fault(
                    SoapResponses.ERROR_INVALID_ARGS,
                    "Malformed SOAP request"
                ).body
            )
        }
    }

    /**
     * Applies a transport command that came from the TV remote.
     *
     * WHY: the remote control of the TV has to move the same state machine the
     * network control point uses. Otherwise the control point would keep showing
     * "PLAYING" while the TV is paused, and the GENA event would carry a wrong state.
     *
     * @param command one of the [DlnaRemoteCommand] values.
     */
    fun onRemoteCommand(command: String) {
        when (command) {
            DlnaRemoteCommand.PLAY_PAUSE -> avTransport.remotePlayPause()
            DlnaRemoteCommand.PAUSE -> avTransport.remotePause()
            DlnaRemoteCommand.STOP -> avTransport.remoteStop()
        }
    }

    private fun connectionManager(action: String): SoapResult =
        when (action) {
            "GetProtocolInfo" -> SoapResponses.ok(
                action = action,
                service = "ConnectionManager",
                values = mapOf(
                    // The Source list must not be empty: a control point that gets an
                    // empty list treats the renderer as "cannot fetch anything" and
                    // hides it from the renderer picker (this is the classic reason
                    // a DLNA device never shows up in VLC).
                    "Source" to DlnaProtocolInfo.SOURCE,
                    "Sink" to DlnaProtocolInfo.SINK
                )
            )
            "GetCurrentConnectionIDs" -> SoapResponses.ok(
                action = action,
                service = "ConnectionManager",
                values = mapOf("ConnectionIDs" to "0")
            )
            "GetCurrentConnectionInfo" -> SoapResponses.ok(
                action = action,
                service = "ConnectionManager",
                values = mapOf(
                    "RcsID" to "0",
                    "AVTransportID" to "0",
                    "ProtocolInfo" to DlnaProtocolInfo.SOURCE,
                    "PeerConnectionManager" to "",
                    "PeerConnectionID" to "0",
                    "Direction" to "Input",
                    "Status" to "OK"
                )
            )
            else -> SoapResponses.fault(
                SoapResponses.ERROR_INVALID_ACTION,
                "Invalid action: $action"
            )
        }

    private fun renderingControl(action: String, values: Map<String, String>): SoapResult {
        return when (action) {
            "GetVolume" -> SoapResponses.ok(
                action = action,
                service = "RenderingControl",
                values = mapOf("CurrentVolume" to state.volume.toString())
            )
            "SetVolume" -> {
                val desired = values["DesiredVolume"]?.trim()?.toIntOrNull()
                if (desired == null || desired !in 0..100) {
                    return SoapResponses.fault(
                        SoapResponses.ERROR_INVALID_ARGS,
                        "Invalid DesiredVolume"
                    )
                }
                state.volume = desired
                publishRenderState()
                SoapResponses.ok(action = action, service = "RenderingControl", values = emptyMap())
            }
            "GetMute" -> SoapResponses.ok(
                action = action,
                service = "RenderingControl",
                values = mapOf("CurrentMute" to if (state.muted) "1" else "0")
            )
            "SetMute" -> {
                val desired = values["DesiredMute"]?.trim()
                if (desired != "0" && desired != "1" &&
                    !desired.equals("true", ignoreCase = true) &&
                    !desired.equals("false", ignoreCase = true)
                ) {
                    return SoapResponses.fault(
                        SoapResponses.ERROR_INVALID_ARGS,
                        "Invalid DesiredMute"
                    )
                }
                state.muted = desired == "1" || desired.equals("true", ignoreCase = true)
                publishRenderState()
                SoapResponses.ok(action = action, service = "RenderingControl", values = emptyMap())
            }
            "GetVolumeDB" -> SoapResponses.ok(
                action = action,
                service = "RenderingControl",
                values = mapOf("CurrentVolume" to volumeToDecibel(state.volume).toString())
            )
            else -> SoapResponses.fault(
                SoapResponses.ERROR_INVALID_ACTION,
                "Invalid action: $action"
            )
        }
    }

    /** Publishes the render state to the GENA subscribers. */
    private fun publishRenderState() {
        onLastChange(
            "/upnp/event/RenderingControl",
            GenaLastChange.renderingControl(
                "Volume" to state.volume.toString(),
                "Mute" to if (state.muted) "1" else "0"
            )
        )
    }

    /**
     * Converts a volume in percent into the decibel value the UPnP specification
     * uses (hundredths of a decibel, -10240 = silence).
     */
    private fun volumeToDecibel(volume: Int): Int {
        if (volume <= 0) return MIN_DECIBEL
        val decibel = 20 * log10(volume / 100.0)
        return (decibel * 100).roundToInt().coerceIn(MIN_DECIBEL, 0)
    }

    companion object {
        private const val CONNECTION_MANAGER_PATH = "/upnp/control/ConnectionManager"
        private const val AV_TRANSPORT_PATH = "/upnp/control/AVTransport"
        private const val RENDERING_CONTROL_PATH = "/upnp/control/RenderingControl"
        private const val MIN_DECIBEL = -10240
    }
}
