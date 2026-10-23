package com.phairplay.airplay

/**
 * MdnsRegistrationTracker — bookkeeping for the mDNS service registrations of [MdnsService].
 *
 * WHY: The registration lifecycle of [MdnsService] decides whether the device shows up
 * in an AirPlay picker at all, and its failure modes are easy to get subtly wrong:
 *
 *  - A registration that fails leaves the receiver invisible, so it has to be retried.
 *  - A registration that succeeds must not be torn down because its *sibling* failed.
 *  - Callbacks arrive asynchronously, so a late callback of an outdated attempt must not
 *    be counted twice — and must not be thrown away either, because the registration it
 *    announces is genuinely live.
 *
 * The state is therefore tracked per service ("slot") instead of per round: `_airplay._tcp`
 * and `_raop._tcp` are registered independently, each with its own attempt counter.
 *
 * This class holds no Android types, so it can be unit-tested on the JVM.
 */
internal class MdnsRegistrationTracker(
    /** Number of mDNS services that must be registered before the receiver advertises. */
    private val serviceCount: Int = 2
) {

    /** Attempt counter per slot — the current attempt of a slot has the highest number. */
    private val generations = IntArray(serviceCount)

    /** Whether a live registration is known for a slot. */
    private val registered = BooleanArray(serviceCount)

    /** Consecutive failed attempts per slot — drives the retry limit. */
    private val failureCounts = IntArray(serviceCount)

    /**
     * Starts a new attempt for [slot] and returns its attempt number.
     *
     * Callbacks created for this attempt belong to it; a failure callback of an older
     * attempt is ignored once a newer one has started.
     */
    fun beginAttempt(slot: Int): Int {
        require(slot in generations.indices) { "Unknown mDNS slot $slot" }
        generations[slot]++
        return generations[slot]
    }

    /** True when [gen] is the current attempt of [slot]. */
    fun isCurrentAttempt(slot: Int, gen: Int): Boolean =
        slot in generations.indices && generations[slot] == gen

    /**
     * Records that the service of [slot] was registered successfully.
     *
     * WHY THE ATTEMPT IS NOT CHECKED: the registration really is live when this callback
     * arrives, even if a newer attempt for the same slot has meanwhile been started.
     * Discarding it would report an error for a receiver that is in fact advertised.
     *
     * @return true when every service is now registered — the receiver may advertise.
     */
    fun onRegistered(slot: Int, gen: Int): Boolean {
        if (slot !in registered.indices) return false
        registered[slot] = true
        return allRegistered()
    }

    /**
     * Records a failed registration attempt of [slot].
     *
     * A failed attempt does **not** clear the registered flag: a service that was
     * registered earlier stays registered, only the attempt failed.
     *
     * @return the number of consecutive failures of this slot, or -1 when the failure
     *   belongs to an outdated attempt and must be ignored.
     */
    fun onFailed(slot: Int, gen: Int): Int {
        if (slot !in generations.indices) return -1
        if (!isCurrentAttempt(slot, gen)) return -1
        failureCounts[slot]++
        return failureCounts[slot]
    }

    /** True when a live registration is known for [slot]. */
    fun isRegistered(slot: Int): Boolean = slot in registered.indices && registered[slot]

    /** True when every service has a live registration. */
    fun allRegistered(): Boolean = registered.all { it }

    /** Forgets everything — used when the advertisement is torn down and rebuilt. */
    fun reset() {
        for (i in generations.indices) {
            generations[i] = 0
            registered[i] = false
            failureCounts[i] = 0
        }
    }
}
