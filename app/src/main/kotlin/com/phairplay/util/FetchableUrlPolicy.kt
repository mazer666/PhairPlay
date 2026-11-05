package com.phairplay.util

import java.net.URI
import java.util.Locale

/**
 * FetchableUrlPolicy — THE rule for a URL that the receiver is asked to fetch itself.
 *
 * WHAT: Decides whether a media URL that a peer sent may be handed to the media stack,
 * which opens the connection from the television.
 *
 * WHY: In the URL mode of AirPlay the sender names the address and the *television*
 * fetches it. Without a rule, any device in the network can turn the receiver into a
 * request proxy for the home network: a single `POST /play` with
 * `Content-Location: http://192.168.1.10:8080/secret` makes the TV open that address
 * and play the answer on the screen — a router admin page, a NAS, a printer, a camera.
 * The same trick reads the cloud metadata service of the router, whose answer contains
 * credentials. A `file://` URL or a local path goes one step further and turns the
 * receiver into a file reader for everything the app's own UID may read.
 *
 * WHY THIS CLASS IS THE ONLY PLACE: both protocol stacks let a peer name the address
 * that the *receiver* then opens — AirPlay through `POST /play`, DLNA through
 * `SetAVTransportURI`. Two independent scheme lists are two independent truths, and the
 * DLNA one was the wider of the two. There is therefore exactly one rule, here, and the
 * DLNA stack calls it instead of keeping its own list.
 *
 * The rules are deliberately narrow:
 *
 * - the scheme must be `http` or `https`. Everything else is refused: `file`, `content`,
 *   `smb`, `rtsp`, `ftp` and every other scheme would either read the local file system
 *   or reach a protocol the receiver does not speak,
 * - the URL must be free of control characters and at most [MAX_URL_LENGTH] characters
 *   long, so a hostile value cannot smuggle a second request or a log line into the
 *   media stack,
 * - the host must be readable and must pass [HostAddressPolicy]: the loopback of the
 *   receiver, the link-local range (and with it `169.254.169.254`), the multicast and
 *   broadcast ranges and the obfuscated notations of an address are refused,
 * - a **public** address stays allowed. The control point or the sender decides which
 *   medium the owner wants to see, and PhairPlay itself sends nothing. A restriction to
 *   the local subnet would be a product decision, not a security decision.
 *
 * A host *name* is not resolved — resolving it would send a DNS query from the
 * television, and a name can still point anywhere. That stays a documented residual
 * risk, exactly as it is for the DLNA address policy.
 *
 * Example:
 *   FetchableUrlPolicy.isFetchable("http://192.168.1.5/movie.mp4")   // true
 *   FetchableUrlPolicy.isFetchable("file:///data/data/com.phairplay/x")  // false
 *   FetchableUrlPolicy.isFetchable("http://169.254.169.254/latest")  // false
 */
object FetchableUrlPolicy {

    /** Longest URL accepted; a longer value is refused instead of truncated. */
    const val MAX_URL_LENGTH = 4096

    /** The only schemes the receiver is willing to fetch. */
    private val FETCHABLE_SCHEMES = setOf("http", "https")

    /**
     * Returns true when the receiver may fetch [url] itself.
     *
     * @param url the URL a peer sent (the `Content-Location` of an AirPlay sender, the
     *   `CurrentURI` of a DLNA control point).
     * @return true when the URL may be handed to the media stack.
     */
    fun isFetchable(url: String?): Boolean = check(url) == null

    /**
     * Checks [url] and returns the reason it must not be fetched.
     *
     * WHY a reason and not a boolean: the DLNA stack answers a refused URI with a SOAP
     * fault, and the AirPlay stack answers with `400 Bad Request`. Both need to know
     * *why* the value was refused — for the log line and for the decision of which
     * status code to send — without asking the rule twice.
     *
     * @param url the URL a peer sent.
     * @return null when the URL may be fetched, otherwise the reason it was refused.
     *   The reason is a fixed text of this class and never contains the URL itself,
     *   so it is safe to write into a log line.
     */
    fun check(url: String?): String? {
        if (url.isNullOrBlank()) return "the URL is empty"
        if (url.length > MAX_URL_LENGTH) return "the URL is too long"
        // A control character would end the header or the line early and could smuggle a
        // second request into the media stack.
        if (url.any { it.code < 0x20 || it.code == 0x7F }) {
            return "the URL contains control characters"
        }

        val parsed = try {
            URI(url)
        } catch (error: Exception) {
            return "the URL cannot be parsed"
        }

        val scheme = parsed.scheme?.lowercase(Locale.US) ?: return "the URL has no scheme"
        if (scheme !in FETCHABLE_SCHEMES) return "the URL scheme is not supported"

        // A URL without a host names nothing the receiver could fetch.
        val host = parsed.host ?: return "the URL has no readable host"
        if (host.isBlank()) return "the URL has no readable host"

        // The address rules: no loopback, no link-local, no metadata service, no
        // obfuscated notation and no user info part that hides the real host.
        return HostAddressPolicy.check(url)
    }
}
