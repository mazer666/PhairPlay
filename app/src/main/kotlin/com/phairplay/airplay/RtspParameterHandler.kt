package com.phairplay.airplay

import com.phairplay.util.Logger

/**
 * RtspParameterHandler — handles the RTSP media parameters (GET/SET_PARAMETER).
 *
 * WHAT: Answers `GET_PARAMETER` (the sender asks for the current volume) and `SET_PARAMETER`
 * (the sender reports volume, album artwork or DMAP now-playing metadata).
 *
 * WHY: These are the only place where the receiver learns what the sender is playing. They are
 * metadata, not control flow, so they live in their own handler and keep the now-playing state
 * (the last volume) next to the code that maintains it.
 *
 * Example:
 *   val parameters = RtspParameterHandler(onVolume = { }, onNowPlayingMetadata = { _, _, _ -> }, onArtwork = { })
 *   val response = parameters.handleGetParameter(request)   // "volume: -12.000000"
 */
internal class RtspParameterHandler(
    private val onVolume: (Float) -> Unit,
    private val onNowPlayingMetadata: (title: String?, artist: String?, album: String?) -> Unit,
    private val onArtwork: (ByteArray) -> Unit
) {

    /** Last volume the sender set (AirPlay dB); returned to GET_PARAMETER volume queries. */
    @Volatile private var currentVolume: Float = 0f

    internal fun handleGetParameter(request: RtspRequest): RtspResponse {
        val query = request.body.trim()
        Logger.i("GET_PARAMETER body='$query'")
        // macOS queries "volume" during setup and aborts if it gets no value back. Report the
        // last value the sender set so its volume slider reflects the receiver.
        return if (query.startsWith("volume")) {
            RtspResponse(
                statusCode = 200, statusMessage = "OK",
                body = "volume: %.6f\r\n".format(currentVolume),
                contentType = "text/parameters",
                protocol = request.responseProtocol()
            )
        } else {
            RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
        }
    }

    internal fun handleSetParameter(request: RtspRequest): RtspResponse {
        val body = request.body
        val contentType = request.headers["Content-Type"]?.lowercase() ?: ""
        // Text bodies carry "volume: <dB>"; binary bodies carry DMAP now-playing metadata or artwork.
        when {
            body.startsWith("volume") -> {
                body.substringAfter(":").trim().toFloatOrNull()?.let { v ->
                    currentVolume = v
                    onVolume(v)
                    Logger.d("SET_PARAMETER volume=$v")
                }
            }
            contentType.startsWith("image/") -> {
                // Album artwork (image/jpeg, image/png). A zero-length body clears it.
                onArtwork(request.bodyBytes)
                Logger.i("SET_PARAMETER artwork (${request.bodyBytes.size}B, $contentType)")
            }
            contentType.contains("dmap") || looksLikeDmap(request.bodyBytes) -> {
                val meta = DmapParser.parseNowPlaying(request.bodyBytes)
                onNowPlayingMetadata(meta.title, meta.artist, meta.album)
                Logger.i("SET_PARAMETER now-playing: title='${meta.title}' artist='${meta.artist}' album='${meta.album}'")
            }
            else -> Logger.d("SET_PARAMETER (${request.bodyBytes.size}B, $contentType, unhandled)")
        }
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Heuristic: a DMAP body starts with the `mlit` listing-item container tag. */
    private fun looksLikeDmap(body: ByteArray): Boolean =
        body.size >= 8 && String(body, 0, 4, Charsets.US_ASCII) == "mlit"
}
