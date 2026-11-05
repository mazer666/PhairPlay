package com.phairplay.diagnostics

/**
 * DiagnosticsExportRedactor — removes the identifying data from a report that leaves
 * the device.
 *
 * WHY a second, stricter redactor: the report on the screen is meant for the owner of
 * the television. It has to name the address and the device — that is the whole point
 * of the screen ("which address do I look for?"). The **export** is a different thing:
 * it is copied into the clipboard and pasted into a bug tracker, a chat message or a
 * forum thread, where it becomes public and permanent. Everything that identifies the
 * household has to be gone by then.
 *
 * WHAT IS REMOVED
 *  - **IPv4 addresses**: the complete address, not only the host octet. The /24 prefix
 *    that survives in the log is of no use to a reader of a public bug report, and the
 *    full address names the household.
 *  - **IPv6 addresses**: the same, including the bracketed form.
 *  - **MAC addresses**: replaced completely. The vendor prefix is of no use to a reader.
 *  - **UUIDs**: replaced completely. A UUID is a persistent device identifier.
 *  - **Personal names**: the name of the television and the name of the sender are
 *    removed by the report builder itself — see [DiagnosticsReportBuilder.buildForExport].
 *    A name is free text, so a pattern cannot recognise it; the builder knows where the
 *    names are and removes them there.
 *
 * WHAT STAYS
 *  - the version of the build, the platform, the state of every protocol, the ports
 *    that are in use, the result of the self test, the memory and the CPU load. Those
 *    are the values that make a bug report useful, and none of them identifies anybody.
 *
 * Example:
 *   DiagnosticsExportRedactor.redact("192.168.1.42")           // "redacted"
 *   DiagnosticsExportRedactor.redact("mac aa:bb:cc:dd:ee:ff")  // "redacted"
 */
object DiagnosticsExportRedactor {

    /** The text that replaces a removed value. */
    const val PLACEHOLDER = "redacted"

    /**
     * A dotted quad. The look-arounds keep the match from starting or ending inside a
     * longer number, so "1.2.3.4567" is not matched as "1.2.3.456".
     */
    private val IPV4_PATTERN = Regex("""(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])""")

    /** Six colon-separated hex octets. */
    private val MAC_PATTERN = Regex("""(?<![\w:])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![\w:])""")

    /** The canonical 8-4-4-4-12 form of a UUID. */
    private val UUID_PATTERN =
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")

    /**
     * A run of hex groups and colons — the shape of an IPv6 address.
     *
     * WHY the candidates are checked afterwards: the pattern also matches a duration
     * such as "0:01:23", which is not an address. [isIpv6] decides.
     *
     * WHY the groups may be empty: the compressed form "fe80::1" has two colons in a
     * row. A pattern that demanded a hex digit before every colon would miss it.
     */
    private val IPV6_CANDIDATE =
        Regex("""(?<![0-9A-Za-z:.])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Za-z:.])""")

    /**
     * Removes the identifying data from [value].
     *
     * @param value the text of a single value of the report.
     * @return the text without addresses, MAC addresses and UUIDs.
     */
    fun redact(value: String): String {
        if (value.isEmpty()) return value
        // The order matters: the MAC pass runs before the IPv6 pass, because a MAC
        // address has the shape of an IPv6 address (six hex groups, five colons).
        var result = IPV4_PATTERN.replace(value) { if (isIpv4(it.value)) PLACEHOLDER else it.value }
        result = MAC_PATTERN.replace(result, PLACEHOLDER)
        result = UUID_PATTERN.replace(result, PLACEHOLDER)
        result = IPV6_CANDIDATE.replace(result) { if (isIpv6(it.value)) PLACEHOLDER else it.value }
        return result
    }

    /**
     * Returns true when [text] is a dotted quad with four valid octets.
     *
     * WHY the check: a version string such as "1.2.3.400" looks like an address but
     * is not one. Removing it would hide the version of a library without protecting
     * anything.
     */
    private fun isIpv4(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && part.toInt() <= 255
        }
    }

    /**
     * Returns true when [text] is an IPv6 address and not a duration.
     *
     * WHY the two rules: an IPv6 address either contains a "::" or has at least four
     * groups. A duration such as "0:01:23" has three groups and no "::", so it stays.
     */
    private fun isIpv6(text: String): Boolean {
        if (text.contains("::")) return true
        // Without a "::" an IPv6 address has at least four groups, so at least three
        // colons.
        return text.count { it == ':' } >= 3
    }
}
