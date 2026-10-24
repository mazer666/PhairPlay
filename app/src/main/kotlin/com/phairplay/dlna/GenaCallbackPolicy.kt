package com.phairplay.dlna

import java.net.URI
import java.util.Locale

/**
 * GenaCallbackPolicy — the SSRF guard for GENA event callbacks.
 *
 * WHY: the callback URL comes straight from a control point. If the renderer posts
 * events to an arbitrary URL, a hostile device on the LAN can use the TV as a
 * request forwarder into the cloud metadata service of a router, into a loopback
 * port of the TV itself, or into another private machine.
 *
 * The rules are deliberately strict:
 *
 * - only the `http` scheme is allowed,
 * - the host must be a **literal** IPv4 address in dotted-quad notation — no DNS
 *   names, because a name can be re-resolved to a different address later,
 * - obfuscated notations are rejected: the decimal form (`3232235777`), the octal
 *   form (`0177.0.0.1`) and the hexadecimal form (`0x7f000001`) are not dotted quads
 *   with plain decimal octets,
 * - only the private ranges 10/8, 172.16/12 and 192.168/16 are allowed;
 *   loopback, link-local (169.254/16), multicast, broadcast, "this network" and
 *   every IPv6 form (including IPv4-mapped IPv6) are rejected,
 * - the address has to be in the same /24 as the renderer itself.
 *
 * Example:
 *   GenaCallbackPolicy.isAllowedCallback(URI("http://192.168.1.44:1400/event"))  // true
 *   GenaCallbackPolicy.isAllowedCallback(URI("http://169.254.169.254/latest"))  // false
 */
internal object GenaCallbackPolicy {

    /**
     * Parses a strict dotted-quad IPv4 literal.
     *
     * @param host the host part of a URL.
     * @return the four octets, or null when [host] is not a plain decimal dotted quad.
     */
    fun parseIpv4Literal(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (index in parts.indices) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3) return null
            if (!part.all { it.isDigit() }) return null
            // A leading zero means an octal notation in most resolvers ("0177.0.0.1").
            // It is rejected so that a literal can never mean something else than it looks.
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[index] = value
        }
        return octets
    }

    /**
     * Returns true when the callback URL may be used for event notifications.
     *
     * @param uri the callback URL of the control point.
     * @param localHost the renderer's own IPv4 address.
     */
    fun isAllowedCallback(uri: URI, localHost: String): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http") return false
        // "http://expected@evil.example/" hides the real host in the user info part.
        if (uri.userInfo != null) return false
        val host = uri.host ?: return false
        return isAllowedHost(host, localHost)
    }

    /**
     * Returns true when [host] is a private IPv4 literal in the renderer's subnet.
     *
     * @param host the host part of a URL.
     * @param localHost the renderer's own IPv4 address.
     */
    fun isAllowedHost(host: String, localHost: String): Boolean {
        val remote = parseIpv4Literal(host) ?: return false
        val local = parseIpv4Literal(localHost) ?: return false
        if (!isPrivate(remote)) return false
        return isSameSubnet(remote, local)
    }

    /**
     * Returns true when the octets belong to one of the private IPv4 ranges.
     *
     * Everything else is rejected, including loopback (127/8), link-local
     * (169.254/16), "this network" (0/8), multicast (224/4) and the reserved
     * range 240/4 that contains the broadcast address.
     */
    fun isPrivate(octets: IntArray): Boolean {
        if (octets.size != 4) return false
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 10 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            else -> false
        }
    }

    /** Returns true when both addresses are in the same /24. */
    fun isSameSubnet(left: IntArray, right: IntArray): Boolean =
        left.size == 4 && right.size == 4 &&
            left[0] == right[0] &&
            left[1] == right[1] &&
            left[2] == right[2]
}
