package com.phairplay.dlna

import com.phairplay.util.FetchableUrlPolicy

/**
 * DlnaMediaUri — validates the media URI a control point hands to `SetAVTransportURI`.
 *
 * WHY the renderer must not be *too* strict here: a control point that offers a URI the
 * renderer refuses makes the whole device useless, and the classic mistake is to accept
 * only `file://` paths. At the same time the value is untrusted network input and the
 * renderer fetches it itself, so it has to be bounded and its address has to be checked.
 *
 * WHY there is no scheme list here: the receiver fetches the URI itself, so the question
 * "may the receiver fetch this URL?" is exactly the question [FetchableUrlPolicy]
 * answers for the AirPlay stack too. A second, wider list in this file was a second
 * truth: `file`, `smb`, `ftp`, `rtsp`, `udp` and friends were accepted here while the
 * AirPlay path refused them — and `file://` turned the television into a reader for
 * everything the app's own UID may read. The rule now stands in one place only, and
 * this class is the DLNA-shaped view of it.
 *
 * WHAT IS ACCEPTED: `http` and `https` with a readable host whose address passes the
 * shared address policy — loopback, link-local (and with it the cloud metadata address
 * `169.254.169.254`), the multicast and broadcast ranges and the obfuscated notations of
 * an address are refused. A **public** address stays allowed: the control point decides
 * which medium the owner wants to see, and PhairPlay itself sends nothing.
 *
 * THE TRADE-OFF: a control point that offers an `rtsp://`, `smb://` or `ftp://` URI now
 * gets the SOAP fault `402 Invalid Args` instead of a playback attempt. That is the
 * safer variant — the renderer cannot speak those protocols itself, and accepting the
 * scheme would mean handing the path to the media stack unchecked. It is recorded as an
 * open owner decision (see `docs/security/FINDINGS.md`), because the compatibility cost
 * lands on real control points.
 *
 * Example:
 *   DlnaMediaUri.validate("http://192.168.1.5/movie.mp4")  // Accepted
 *   DlnaMediaUri.validate("http://169.254.169.254/latest")  // Rejected("the address is a link-local address")
 *   DlnaMediaUri.validate("file:///etc/passwd")             // Rejected("the URL scheme is not supported")
 *   DlnaMediaUri.validate("")                               // Rejected("the URL is empty")
 */
object DlnaMediaUri {

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
        // The shared rule decides everything; this class only shapes the answer for the
        // DLNA stack. The rule is asked about the *trimmed* value, because a control
        // point may pad the URI and the trimmed value is what reaches the media stack.
        val reason = FetchableUrlPolicy.check(trimmed)
        return if (reason == null) {
            Result.Accepted(trimmed)
        } else {
            Result.Rejected(reason)
        }
    }

    /** Convenience predicate around [validate]. */
    fun isAcceptable(uri: String?): Boolean = validate(uri) is Result.Accepted
}
