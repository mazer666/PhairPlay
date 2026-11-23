package com.phairplay.airplay

/**
 * MdnsServiceName — makes a display name safe to use as an mDNS service instance name
 * and gives the two AirPlay services the names they need to be found.
 *
 * WHY: The advertised name comes from the user (Settings → Device Name) or from the
 * Android system device name. Neither is guaranteed to be a name the platform's mDNS
 * daemon accepts. [android.net.nsd.NsdManager.registerService] rejects such a name by
 * throwing — and an unhandled throw inside the registration path takes the whole AirPlay
 * receiver down: the device silently vanishes from every AirPlay picker, with nothing on
 * screen but a generic error.
 *
 * WHY THE AIRPLAY SERVICE NEEDS A SUFFIX: on Android 14 the television registers its
 * **own** remote-control service (`_androidtvremote2._tcp`) under the **device name**,
 * and the mDNS daemon of that platform version checks the *instance name* for
 * uniqueness **across all service types** — not only within one type. A receiver that
 * advertises `_airplay._tcp` under the bare device name therefore collides with that
 * system registration, the daemon throws a `NameConflictException`, and the AirPlay
 * service is never advertised: the television stays invisible in every AirPlay picker
 * while the app itself looks perfectly healthy. Reported as issue #23, reproduced on an
 * XGIMI MoGo 3 Pro (Google TV, Android 14) with an iPhone 15 Pro as the sender.
 *
 * The suffix makes the instance name unique on the device. The name the owner chose
 * stays the first part of it, so the receiver remains recognisable in the picker of the
 * sender.
 *
 * This object holds no Android dependencies so it can be unit-tested on the JVM.
 */
internal object MdnsServiceName {

    /**
     * Maximum length of an mDNS service instance name (DNS-SD label limit).
     */
    const val MAX_LENGTH = 63

    /**
     * The suffix that makes the `_airplay._tcp` instance name unique on the device.
     */
    const val AIRPLAY_SUFFIX = " (AirPlay)"

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

    /**
     * The instance name of the `_airplay._tcp` service.
     *
     * WHY the suffix is always there: the bare device name is exactly the name the
     * platform registers its own services under, so it is the name most likely to
     * collide (issue #23). The suffix is not a workaround for a broken platform — it
     * is the name a receiver that shares the device with other services needs.
     *
     * @param displayName The name the owner gave the television.
     * @param attempt     The number of the registration attempt. The first attempt is
     *   `0` and carries the plain suffix; every further attempt gets its own number,
     *   so that a name that is already taken on the network is not tried twice.
     * @return An instance name that is unique on the device and at most [MAX_LENGTH]
     *   characters long.
     */
    fun airPlayInstanceName(displayName: String, attempt: Int = 0): String {
        // The number belongs inside the brackets: "Living Room TV (AirPlay 2)"
        // reads like a name, "Living Room TV (AirPlay) 2" reads like a log line.
        val suffix = if (attempt <= 0) AIRPLAY_SUFFIX else " (AirPlay ${attempt + 1})"
        return withSuffix(displayName, suffix)
    }

    /**
     * The instance name of the `_raop._tcp` service.
     *
     * The RAOP format required by the AirPlay protocol is `"<MACADDRESS_NOCOLONS>@<DeviceName>"`.
     * The MAC part makes the name unique on the network; the device part is the same
     * name the `_airplay._tcp` service uses, so that both entries of the receiver
     * show the same thing in the picker of the sender.
     *
     * WHY the length is limited here: the MAC prefix eats 13 of the 63 characters of
     * the label. A long device name would otherwise produce a label that the daemon
     * rejects — and the receiver would stay invisible for the same reason as in
     * issue #23.
     *
     * @param macHex      The MAC address of the receiver, without colons.
     * @param displayName The name the owner gave the television.
     * @return An instance name in the RAOP format, at most [MAX_LENGTH] characters.
     */
    fun raopInstanceName(macHex: String, displayName: String): String {
        val prefix = "$macHex@"
        val room = (MAX_LENGTH - prefix.length).coerceAtLeast(1)
        val name = airPlayInstanceName(displayName).take(room).trim()
        return prefix + name
    }

    /**
     * Appends [suffix] to [displayName] and keeps the result within [MAX_LENGTH].
     *
     * WHY the name is shortened and not the suffix: the suffix carries the information
     * that makes the name unique — the part that may be cut is the name the owner
     * chose, and it is still readable.
     */
    private fun withSuffix(displayName: String, suffix: String): String {
        val room = (MAX_LENGTH - suffix.length).coerceAtLeast(1)
        val base = displayName.take(room).trim()
        return base + suffix
    }
}
