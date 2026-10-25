package com.phairplay.diagnostics

/**
 * DiagnosticsRedactor — removes the identifying data from the values of a report.
 *
 * WHY: the report is meant to be copied into a bug tracker or a chat message. The
 * report itself only carries the local IP address and the device name — the owner
 * has to be able to read the address of the television off the screen. Everything
 * else that identifies a device (a MAC address, a UUID) has to be removed before the
 * text leaves the screen, because a pasted report ends up in public places.
 *
 * WHY the IPv4 address survives: the address is the one piece of information the
 * owner needs. It is a private address of the local network, and without it the
 * report cannot answer the question it exists for ("which address do I look for?").
 *
 * Example:
 *   DiagnosticsRedactor.redact("device aa:bb:cc:dd:ee:ff")   // "device redacted"
 *   DiagnosticsRedactor.redact("192.168.1.42")                  // "192.168.1.42"
 */
object DiagnosticsRedactor {

    /** The text that replaces a removed value. */
    const val PLACEHOLDER = "redacted"

    /** Six colon separated hex octets — the shape of a MAC address. */
    private val MAC_PATTERN = Regex("""(?<![\w:])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![\w:])""")

    /** The canonical 8-4-4-4-12 form of a UUID. */
    private val UUID_PATTERN =
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")

    /**
     * Removes the identifying data from [value].
     *
     * @param value the text of a single value of the report.
     * @return the text without MAC addresses and UUIDs.
     */
    fun redact(value: String): String {
        if (value.isEmpty()) return value
        var result = UUID_PATTERN.replace(value, PLACEHOLDER)
        result = MAC_PATTERN.replace(result, PLACEHOLDER)
        return result
    }
}
