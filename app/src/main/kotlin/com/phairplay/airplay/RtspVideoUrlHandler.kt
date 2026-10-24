package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.util.FetchableUrlPolicy
import com.phairplay.util.Logger

/**
 * RtspVideoUrlHandler — handles the AirPlay video URL mode (playing a media file, not mirroring).
 *
 * WHAT: Answers `POST /play`, `POST /rate`, `POST /scrub`, `POST /stop` and `GET /playback-info`
 * — the endpoints an Apple TV uses to play a URL and to drive its transport controls.
 *
 * WHY: URL mode is a completely different feature from screen mirroring, but it travels over the
 * same RTSP socket. Giving it its own handler keeps the mirroring code free of URL/transport
 * details and makes the transport state easy to follow.
 *
 * Example:
 *   val video = RtspVideoUrlHandler(onVideoPlay = { url, start -> }, ...)
 *   val response = video.handleVideoPlay(request)   // 200 OK, the player starts the URL
 */
internal class RtspVideoUrlHandler(
    private val onVideoPlay: (url: String, startFraction: Double) -> Unit,
    private val onVideoRate: (rate: Float) -> Unit,
    private val onVideoScrub: (positionSec: Double) -> Unit,
    private val onVideoStop: () -> Unit,
    private val onPlaybackInfo: () -> com.phairplay.airplay.PlaybackInfo?
) {

    /** POST /play — a media URL to play (binary/XML plist or legacy text body). */
    internal fun handleVideoPlay(request: RtspRequest): RtspResponse {
        val (url, start) = parsePlayBody(request)
        if (url.isNullOrBlank()) {
            Logger.w("POST /play with no Content-Location")
            return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
        // The television fetches the URL itself. A URL that must not be fetched — a
        // file:// path, a local address, the cloud metadata service of the router — would
        // turn the receiver into a request proxy or a file reader for the home network.
        // The URL itself is not logged: it can carry credentials.
        if (!FetchableUrlPolicy.isFetchable(url)) {
            Logger.w("POST /play rejected: the media URL is not fetchable")
            return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
        Logger.i("POST /play url=$url start=$start")
        onVideoPlay(url, start)
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** Extracts the media URL + start fraction from a /play body (plist `Content-Location` or text). */
    private fun parsePlayBody(request: RtspRequest): Pair<String?, Double> {
        if (request.isPlistBody()) {
            val p = runCatching { PlistCodec.decode(request.bodyBytes) }.getOrNull() ?: return null to 0.0
            val url = p["Content-Location"] as? String
            val start = (p["Start-Position"] as? Double) ?: 0.0
            return url to start
        }
        // Legacy text body: "Content-Location: <url>\r\nStart-Position: <float>\r\n"
        var url: String? = null
        var start = 0.0
        request.body.lineSequence().forEach { line ->
            when {
                line.startsWith("Content-Location:", true) -> url = line.substringAfter(":").trim()
                line.startsWith("Start-Position:", true) -> start = line.substringAfter(":").trim().toDoubleOrNull() ?: 0.0
            }
        }
        return url to start
    }

    /** POST /rate?value=X — X=0 pause, X≥1 resume. */
    internal fun handleVideoRate(request: RtspRequest): RtspResponse {
        val rate = queryParam(request.uri, "value")?.toFloatOrNull() ?: 1f
        Logger.d("POST /rate value=$rate")
        onVideoRate(rate)
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** POST /scrub?position=N — seek to N seconds. */
    internal fun handleVideoScrubPost(request: RtspRequest): RtspResponse {
        queryParam(request.uri, "position")?.toDoubleOrNull()?.let {
            Logger.d("POST /scrub position=$it")
            onVideoScrub(it)
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /scrub — current position + duration as text/parameters. */
    internal fun handleScrubGet(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val body = "duration: %.6f\r\nposition: %.6f\r\n".format(info?.durationSec ?: 0.0, info?.positionSec ?: 0.0)
        return RtspResponse(200, "OK", body = body, contentType = "text/parameters", protocol = request.responseProtocol())
    }

    /** POST /stop — stop URL playback. */
    internal fun handleVideoStop(request: RtspRequest): RtspResponse {
        Logger.i("POST /stop (video URL)")
        onVideoStop()
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /playback-info — XML plist describing current position/duration/rate/ready state. */
    internal fun handlePlaybackInfo(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val plist: Map<String, Any?> = if (info == null || !info.readyToPlay) {
            mapOf("readyToPlay" to false)
        } else {
            val ranges = listOf(mapOf("start" to 0.0, "duration" to info.durationSec))
            mapOf(
                "duration" to info.durationSec,
                "position" to info.positionSec,
                "rate" to info.rate,
                "readyToPlay" to true,
                "playbackBufferEmpty" to false,
                "playbackBufferFull" to true,
                "playbackLikelyToKeepUp" to true,
                "loadedTimeRanges" to ranges,
                "seekableTimeRanges" to ranges,
            )
        }
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(plist),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** Extracts a query-string parameter (`?k=v&...`) from a request URI. */
    private fun queryParam(uri: String, key: String): String? =
        uri.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
}
