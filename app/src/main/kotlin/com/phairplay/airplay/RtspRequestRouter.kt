package com.phairplay.airplay

import com.phairplay.util.Logger

/**
 * RtspRequestRouter — dispatches one parsed RTSP request to the handler that answers it.
 *
 * WHAT: Maps the RTSP method — and the URI paths of the AirPlay 2 handshake — to the matching
 * handler: the classic verbs, the pairing endpoints, the mirroring SETUP, the media parameters
 * and the URL-video endpoints.
 *
 * WHY: Every AirPlay sender speaks a slightly different dialect of RTSP (classic verbs, HTTP-style
 * GET/POST, buffered-audio control verbs). Keeping that mapping in one dispatch table makes it
 * readable at a glance instead of spreading it over the socket loop.
 *
 * Example:
 *   val router = RtspRequestRouter(verbs, mirrorHandler, pairingHandler, ...)
 *   val response = router.routeRequest(request)   // 200 OK, or 501 for an unknown method
 */
internal class RtspRequestRouter(
    private val verbs: RtspHandler,
    private val mirrorHandler: RtspMirrorHandler,
    private val pairingHandler: RtspPairingHandler,
    private val parameterHandler: RtspParameterHandler,
    private val videoUrlHandler: RtspVideoUrlHandler,
    private val deviceInfoHandler: RtspDeviceInfoHandler,
    private val onRemoteControlInfo: (dacpId: String?, activeRemote: String?) -> Unit
) {

    internal fun routeRequest(request: RtspRequest): RtspResponse {
        Logger.d("RTSP ${request.method} ${request.uri}")
        // Senders attach their DACP reverse-control identity to most requests — capture it so the TV
        // remote can drive playback (DacpClient dedups, so this is cheap to call repeatedly).
        request.headers["Active-Remote"]?.let { onRemoteControlInfo(request.headers["DACP-ID"], it) }
        return when (request.method) {
            "OPTIONS"       -> verbs.handleOptionsInternal(request)
            "ANNOUNCE"      -> verbs.handleAnnounceInternal(request)
            // AirPlay 2 mirroring SETUP carries a binary plist; legacy audio SETUP carries SDP-ish text.
            "SETUP"         -> if (request.isPlistBody()) mirrorHandler.handleMirrorSetup(request) else verbs.handleSetupInternal(request)
            "RECORD"        -> verbs.handleRecordInternal(request)
            "TEARDOWN"      -> verbs.handleTeardownInternal(request)
            "GET_PARAMETER" -> parameterHandler.handleGetParameter(request)
            "SET_PARAMETER" -> parameterHandler.handleSetParameter(request)
            "FLUSH"         -> handleFlush(request)
            "PAUSE"         -> verbs.handlePauseInternal(request)
            // AirPlay 2 buffered-audio control verbs. Acknowledge them (a 501 would abort audio-only
            // playback) and log their bodies so the anchor/rate/peer formats can be implemented.
            "SETRATEANCHORTIME", "SETRATEANCHORTIM" -> deviceInfoHandler.handleBufferedControl(request, "SETRATEANCHORTIME")
            "SETPEERS", "SETPEERSX"                 -> deviceInfoHandler.handleBufferedControl(request, "SETPEERS")
            "FLUSHBUFFERED"                         -> deviceInfoHandler.handleBufferedControl(request, "FLUSHBUFFERED")
            "PUT"           -> verbs.handlePhotoPutInternal(request)
            "DELETE"        -> verbs.handlePhotoDeleteInternal(request)
            // AirPlay 2 handshake is HTTP-style (GET/POST with bodies) over the RTSP socket.
            "GET"           -> routeGet(request)
            "POST"          -> routePost(request)
            else            -> verbs.handleUnknownInternal(request)
        }
    }

    /** Routes AirPlay 2 GET requests by URI path. */
    private fun routeGet(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/info"          -> deviceInfoHandler.handleInfo(request)
        "/playback-info" -> videoUrlHandler.handlePlaybackInfo(request)
        "/scrub"         -> videoUrlHandler.handleScrubGet(request)
        "/server-info"   -> deviceInfoHandler.handleServerInfo(request)
        else             -> verbs.handleUnknownInternal(request)
    }

    /** Routes AirPlay 2 POST requests by URI path. */
    private fun routePost(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/pair-setup"  -> pairingHandler.handlePairSetup(request)
        "/pair-setup-pin" -> pairingHandler.handleLegacyPairSetupPin(request)   // legacy AirPlay PIN SRP (plist)
        "/pair-pin-start" -> pairingHandler.handlePairPinStart(request)
        "/pair-verify" -> pairingHandler.handlePairVerify(request)
        "/fp-setup"    -> mirrorHandler.handleFpSetup(request)
        "/feedback"    -> deviceInfoHandler.handleFeedback(request)
        "/audioMode"   -> RtspResponse(200, "OK", protocol = request.responseProtocol())
        // AirPlay video URL mode (non-mirroring): play a URL + drive transport.
        "/play"        -> videoUrlHandler.handleVideoPlay(request)
        "/rate"        -> videoUrlHandler.handleVideoRate(request)
        "/scrub"       -> videoUrlHandler.handleVideoScrubPost(request)
        "/stop"        -> videoUrlHandler.handleVideoStop(request)
        else           -> verbs.handleUnknownInternal(request)
    }

    /** Handles FLUSH — macOS requests we discard buffered media data (seek/pause). */
    private fun handleFlush(@Suppress("UNUSED_PARAMETER") request: RtspRequest): RtspResponse {
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }
}
