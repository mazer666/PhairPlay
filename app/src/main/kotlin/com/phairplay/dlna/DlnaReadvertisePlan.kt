package com.phairplay.dlna

/**
 * DlnaReadvertisePlan — decides what a DLNA re-advertisement after a network loss has to do.
 *
 * WHAT: Compares the address the renderer advertised with the address the device has
 * now and returns the action the receiver has to take.
 *
 * WHY: the description URL of a UPnP renderer contains the IP address of the device.
 * When the device comes back with a different address — a new DHCP lease, another
 * Wi-Fi network — the advertised URL points to an address that does not exist any
 * more. The control point finds the renderer and then waits for a description that
 * never arrives. Whether the description server has to be rebuilt is therefore not a
 * matter of taste: it depends on whether the address changed.
 *
 * The decision is a pure function without an Android or a socket dependency, because
 * the part of the recovery that can be tested on the JVM is exactly this decision —
 * the socket behaviour cannot be tested without a device.
 *
 * Example:
 *   DlnaReadvertisePlan.decide(previousAddress = "192.168.1.5", currentAddress = "192.168.1.6")
 *   // → DlnaReadvertisePlan.Action.REBUILD_DESCRIPTION
 */
internal object DlnaReadvertisePlan {

    /** What a re-advertisement after a network loss has to do. */
    enum class Action {
        /**
         * The announcements are repeated: the address did not change, or there is no
         * usable address at all.
         */
        ANNOUNCE_ONLY,

        /** The address changed — the description server has to be rebuilt. */
        REBUILD_DESCRIPTION
    }

    /**
     * Decides what the re-advertisement has to do.
     *
     * WHY an unusable address means "announce only": a device that has no LAN address
     * right now — the interface is probably still coming up — cannot be described by
     * a new URL either. The description server that already runs keeps serving, and
     * the renderer stays visible in the device list of the control points.
     *
     * @param previousAddress the address the renderer advertised, null when it never did.
     * @param currentAddress the address the device has now.
     * @return the action to take; the function never throws.
     */
    fun decide(previousAddress: String?, currentAddress: String?): Action {
        val current = approveAddress(currentAddress) ?: return Action.ANNOUNCE_ONLY
        val previous = approveAddress(previousAddress)
        return if (previous == current) Action.ANNOUNCE_ONLY else Action.REBUILD_DESCRIPTION
    }

    /**
     * Returns the address a description URL may be built from.
     *
     * WHY the address has to be a plain IPv4 literal: the address is written into a
     * URL that a control point fetches. An address that is not a literal — a name, an
     * obfuscated notation — or that cannot be reached — the unspecified address, the
     * loopback, multicast, the reserved range — would produce a description the
     * control point can never fetch. The renderer would be found and then time out,
     * which is worse than staying invisible.
     *
     * @param candidate the address to check.
     * @return the address when it may be advertised, otherwise null.
     */
    fun approveAddress(candidate: String?): String? {
        val address = candidate?.trim().orEmpty()
        if (address.isEmpty()) return null
        // The syntax rules live in one place: a plain decimal dotted quad, no
        // octal, no hexadecimal, no leading zero (see GenaCallbackPolicy).
        val octets = GenaCallbackPolicy.parseIpv4Literal(address) ?: return null
        val first = octets[0]
        val unreachable = first == 0 ||      // "this network" — the unspecified address
            first == 127 ||                  // loopback — the network cannot reach it
            first in 224..239 ||             // multicast — not a host address
            first >= 240                     // reserved, contains the broadcast address
        if (unreachable) return null
        return address
    }
}
