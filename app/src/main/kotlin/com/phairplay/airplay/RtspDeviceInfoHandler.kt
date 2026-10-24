package com.phairplay.airplay

import com.phairplay.airplay.handshake.InfoResponder
import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.util.Logger

/**
 * RtspDeviceInfoHandler — answers the "who are you?" and "are you still there?" endpoints.
 *
 * WHAT: Serves `GET /info` (receiver identity and capabilities), `GET /server-info` (the legacy
 * identity plist), `POST /feedback` (the sender's health check) and the buffered-audio control
 * verbs (`SETRATEANCHORTIME`, `SETPEERS`, `FLUSHBUFFERED`).
 *
 * WHY: These endpoints do not drive the media pipeline — they tell the sender what the receiver
 * can do and keep the session alive. Grouping them here keeps the acknowledgement logic in one
 * place and makes sure a missing answer never aborts a running session.
 *
 * Example:
 *   val info = RtspDeviceInfoHandler(context, 1920, 1080, pinAuthEnabled = false)
 *   val response = info.handleInfo(request)   // 200 OK with the capability plist
 */
internal class RtspDeviceInfoHandler(
    private val context: android.content.Context,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val pinAuthEnabled: Boolean
) {

    /** GET /info — advertises receiver identity + capabilities (binary plist). */
    internal fun handleInfo(request: RtspRequest): RtspResponse = RtspResponse(
        statusCode = 200,
        statusMessage = "OK",
        bodyBytes = InfoResponder.build(context, displayWidth, displayHeight, pinRequired = pinAuthEnabled),
        contentType = "application/x-apple-binary-plist",
        protocol = request.responseProtocol()
    )

    /** GET /server-info — legacy XML plist of receiver identity for AirPlay video senders. */
    internal fun handleServerInfo(request: RtspRequest): RtspResponse {
        val info = mapOf(
            "deviceid" to com.phairplay.util.NetworkUtils.getMacAddress(),
            "features" to 0x1E5A7FFFF7L,
            "model" to "AppleTV5,3",
            "protovers" to "1.1",
            "srcvers" to "220.68",
        )
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(info),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** POST /feedback — macOS health-checks the session every ~2 s; acknowledge with 200 OK. */
    internal fun handleFeedback(request: RtspRequest): RtspResponse {
        val n = request.bodyBytes.size
        if (n > 0) {
            runCatching {
                val p = PlistCodec.decode(request.bodyBytes)
                Logger.d("/feedback body ($n B): " + p.entries.joinToString { (k, v) ->
                    "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                })
            }.onFailure { Logger.d("/feedback body ($n B, non-plist)") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /**
     * Acknowledges an AirPlay 2 buffered-audio control verb (SETRATEANCHORTIME / SETPEERS /
     * FLUSHBUFFERED). Returning 200 keeps an audio-only session alive (a 501 would make macOS abort).
     */
    internal fun handleBufferedControl(request: RtspRequest, label: String): RtspResponse {
        val n = request.bodyBytes.size
        if (n > 0) {
            runCatching {
                val p = PlistCodec.decode(request.bodyBytes)
                Logger.d("$label body ($n B): " + p.entries.joinToString { (k, v) ->
                    "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                })
            }.onFailure { Logger.d("$label body ($n B, non-plist)") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }
}
