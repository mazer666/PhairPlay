package com.phairplay.dlna

import java.net.URI
import java.util.Locale

/**
 * DlnaMediaAddressPolicy — the address rules for the media URI a control point sends.
 *
 * WHY: `SetAVTransportURI` carries a URL that the *renderer* fetches itself. Without an
 * address policy, a hostile device on the network can point the TV at the cloud metadata
 * service of the router (`169.254.169.254`) and read the answer — which contains
 * credentials — as if the TV had asked for it. The same trick reaches the loopback
 * services of the TV itself. `GenaCallbackPolicy` guards the event callback address, but
 * the media address needed its own rules.
 *
 * The rules are deliberately precise:
 *
 * - a URI without a host (`file:///movie.mp4`) carries no address and is accepted; the
 *   known limitation of the `file:///` scheme is documented in the casting guide,
 * - a user info part (`http://expected@evil.example/`) is refused, because it hides the
 *   real host behind a harmless looking one,
 * - a literal IPv4 address is only accepted in the plain dotted-quad notation. The
 *   decimal (`3232235777`), octal (`0177.0.0.1`) and hexadecimal (`0x7f000001`) forms
 *   are refused, so an address always means exactly what it looks like,
 * - loopback, "this network", link-local, multicast, broadcast and the reserved range
 *   are refused: they point back at the renderer or at infrastructure that has no
 *   business serving media. The cloud metadata addresses are covered by the link-local
 *   rule and are named explicitly as well,
 * - every other address is accepted. The private ranges of the local network are the
 *   normal case, and a public address is accepted too, because the control point
 *   decides which media the owner wants to see. PhairPlay itself sends nothing.
 *
 * A name is not resolved. Resolving it would send a DNS query from the TV, and a name
 * can still point anywhere. The known metadata names are refused by name instead; a name
 * that resolves to a link-local address stays a documented residual risk.
 *
 * Example:
 *   DlnaMediaAddressPolicy.check("http://192.168.1.5/movie.mp4")  // null — allowed
 *   DlnaMediaAddressPolicy.check("http://169.254.169.254/latest")  // "link-local address"
 */
internal object DlnaMediaAddressPolicy {

    /**
     * Host names that are known to answer with cloud credentials.
     *
     * The list is short on purpose: every entry is a well documented name of a metadata
     * service, not a guess.
     */
    private val METADATA_NAMES = setOf(
        "metadata",
        "metadata.google.internal",
        "metadata.goog",
        "instance-data",
        "instance-data.ec2.internal"
    )

    /** A host written only as digits and dots, like `3232235777` or `0177.0.0.1`. */
    private val DECIMAL_NOTATION = Regex("^[0-9]+(\\.[0-9]+)*$")

    /** A host written as a hexadecimal number, like `0x7f000001`. */
    private val HEX_NOTATION = Regex("^0[xX][0-9a-fA-F]+$")

    /**
     * Checks the address of a media URI.
     *
     * @param uri the URI a control point sent as `CurrentURI`.
     * @return null when the URI may be used, otherwise the reason it was refused.
     */
    fun check(uri: String): String? {
        val parsed = try {
            URI(uri)
        } catch (error: Exception) {
            return "the URI cannot be parsed"
        }

        // "http://expected@evil.example/" hides the real host in the user info part.
        if (parsed.userInfo != null) return "the URI carries a user info part"

        val host = parsed.host
        if (host == null) {
            // A URI without an authority (file:///movie.mp4) has no address to check.
            // A URI with an authority that cannot be read (http://3232235777/) is refused.
            return if (parsed.rawAuthority == null) null else "the URI has no readable host"
        }

        return checkHost(host)
    }

    /**
     * Checks a single host part.
     *
     * @param host the host part of a URI, without the brackets of an IPv6 literal.
     * @return null when the host may be used, otherwise the reason it was refused.
     */
    fun checkHost(host: String): String? {
        val cleaned = host.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        if (cleaned.isEmpty()) return "the URI has no readable host"

        // A plain dotted quad is an IPv4 address and is checked as one. The check has to
        // come first: "192.168.1.5" consists of digits and dots as well, and a plain
        // address must never be mistaken for an obfuscated one.
        val octets = GenaCallbackPolicy.parseIpv4Literal(cleaned)
        if (octets != null) return checkIpv4(octets)

        // The decimal, octal and hexadecimal notations of an IPv4 address.
        if (DECIMAL_NOTATION.matches(cleaned)) return "the address uses a non-standard notation"
        if (HEX_NOTATION.matches(cleaned)) return "the address uses a non-standard notation"

        if (cleaned.contains(':')) return checkIpv6(cleaned)

        return if (cleaned in METADATA_NAMES) "the host is a metadata service" else null
    }

    /**
     * Applies the address rules to an IPv4 address.
     *
     * @param octets the four octets of the address.
     * @return null when the address may be used, otherwise the reason it was refused.
     */
    private fun checkIpv4(octets: IntArray): String? {
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 127 -> "the address is a loopback address"
            first == 0 -> "the address belongs to the reserved range 0.0.0.0/8"
            first == 169 && second == 254 -> "the address is a link-local address"
            first == 100 && second == 100 && octets[2] == 100 && octets[3] == 200 ->
                "the address is a cloud metadata address"
            first == 192 && second == 0 && octets[2] == 0 && octets[3] == 192 ->
                "the address is a cloud metadata address"
            first >= 224 && first <= 239 -> "the address is a multicast address"
            first >= 240 -> "the address is in the reserved range 240.0.0.0/4"
            else -> null
        }
    }

    /**
     * Applies the address rules to an IPv6 address.
     *
     * @param address the address without brackets.
     * @return null when the address may be used, otherwise the reason it was refused.
     */
    private fun checkIpv6(address: String): String? {
        // An IPv4-mapped address such as ::ffff:127.0.0.1 carries an IPv4 address inside.
        val mapped = MAPPED_IPV4.find(address)?.groupValues?.get(1)
        if (mapped != null) {
            val octets = GenaCallbackPolicy.parseIpv4Literal(mapped)
            if (octets != null) return checkIpv4(octets)
        }
        return when {
            address == "::1" -> "the address is a loopback address"
            address == "::" -> "the address is unspecified"
            address.startsWith("fe80:") -> "the address is a link-local address"
            address.startsWith("ff") -> "the address is a multicast address"
            address == "fd00:ec2::254" -> "the address is a cloud metadata address"
            else -> null
        }
    }

    /** Matches the IPv4 part of an IPv4-mapped IPv6 address such as `::ffff:127.0.0.1`. */
    private val MAPPED_IPV4 = Regex("^::ffff:([0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3})$")
}
