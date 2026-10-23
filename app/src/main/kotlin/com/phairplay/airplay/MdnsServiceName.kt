package com.phairplay.airplay

/**
 * MdnsServiceName — makes a display name safe to use as an mDNS service instance name.
 *
 * WHY: The advertised name comes from the user (Settings → Device Name) or from the
 * Android system device name. Neither is guaranteed to be a name the platform's mDNS
 * daemon accepts. [android.net.nsd.NsdManager.registerService] rejects such a name by
 * throwing — and an unhandled throw inside the registration path takes the whole AirPlay
 * receiver down: the device silently vanishes from every AirPlay picker, with nothing on
 * screen but a generic error.
 *
 * This object holds no Android dependencies so it can be unit-tested on the JVM.
 */
internal object MdnsServiceName {

    /**
     * Maximum length of an mDNS service instance name (DNS-SD label limit).
     */
    const val MAX_LENGTH = 63

    /**
     * Sanitizes a display name for mDNS registration.
     *
     * - Control and non-printable characters are removed.
     * - The result is trimmed and capped at [MAX_LENGTH].
     * - If nothing usable is left, [fallback] is returned.
     *
     * @param raw      The name to sanitize.
     * @param fallback Name to use when [raw] contains nothing usable.
     */
    fun sanitize(raw: String, fallback: String): String {
        val printable = raw.filter { it.code in 0x20..0x7E }.trim()
        val truncated = printable.take(MAX_LENGTH).trim()
        return truncated.ifEmpty { fallback.trim().take(MAX_LENGTH).trim() }
    }
}
