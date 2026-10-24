package com.phairplay.dlna

import com.phairplay.util.HostAddressPolicy
import java.util.Locale

/**
 * DlnaMediaUri — validates the media URI a control point hands to `SetAVTransportURI`.
 *
 * WHY: The renderer must not be *too* strict here. A control point that offers a URI
 * the renderer refuses makes the whole device useless, and the classic mistake is to
 * accept only `file://` paths. At the same time the value is untrusted network input:
 * it must be bounded in length and free of control characters before it reaches the
 * media stack or a log line.
 *
 * The accepted scheme list is deliberately wide (HTTP(S) for the normal case, plus
 * the streaming and file schemes real control points emit). The *address* of the URI is
 * checked separately by [HostAddressPolicy], because the renderer fetches the media
 * itself and a hostile control point must not be able to aim the TV at the cloud
 * metadata service of the router.
 *
 * Example:
 *   DlnaMediaUri.validate("http://192.168.1.5/movie.mp4")  // Accepted
 *   DlnaMediaUri.validate("http://169.254.169.254/latest")  // Rejected("link-local address")
 *   DlnaMediaUri.validate("")                               // Rejected("empty")
 */
object DlnaMediaUri {

    /** Longest URI accepted; beyond this the request is refused instead of truncated. */
    const val MAX_URI_LENGTH = 4096

    private val ALLOWED_SCHEMES = setOf(
        "http", "https", "rtsp", "rtmp", "rtmpe", "rtmps", "ftp", "file", "smb",
        "mms", "mmsh", "mmst", "udp", "pnm"
    )

    /** Result of a URI validation. */
    sealed class Result {
        /** The URI is usable. */
        data class Accepted(val uri: String) : Result()

        /** The URI must not be used; [reason] is safe to log but never sent to the peer. */
        data class Rejected(val reason: String) : Result()
    }

    /**
     * Validates [uri] and returns the outcome.
     *
     * @param uri the raw `CurrentURI` value from the SOAP action.
     * @return [Result.Accepted] with the trimmed URI, or [Result.Rejected].
     */
    fun validate(uri: String?): Result {
        val trimmed = uri?.trim().orEmpty()
        if (trimmed.isEmpty()) return Result.Rejected("empty URI")
        if (trimmed.length > MAX_URI_LENGTH) return Result.Rejected("URI too long")
        if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) {
            return Result.Rejected("URI contains control characters")
        }
        val separator = trimmed.indexOf(':')
        if (separator <= 0) return Result.Rejected("URI has no scheme")
        val scheme = trimmed.substring(0, separator).lowercase(Locale.US)
        if (scheme.any { !it.isLetterOrDigit() && it != '+' && it != '-' && it != '.' }) {
            return Result.Rejected("URI scheme is malformed")
        }
        if (scheme !in ALLOWED_SCHEMES) return Result.Rejected("unsupported URI scheme")
        // The renderer fetches the URI itself, so the address has to be checked as well.
        // Without this, a hostile control point could point the TV at the cloud metadata
        // service of the router. See HostAddressPolicy for the rules.
        HostAddressPolicy.check(trimmed)?.let { return Result.Rejected(it) }
        return Result.Accepted(trimmed)
    }

    /** Convenience predicate around [validate]. */
    fun isAcceptable(uri: String?): Boolean = validate(uri) is Result.Accepted
}
