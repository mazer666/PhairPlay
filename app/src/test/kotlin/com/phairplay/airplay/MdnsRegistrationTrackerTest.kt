package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MdnsRegistrationTrackerTest — Unit tests for the mDNS registration bookkeeping.
 *
 * WHY: The tracker decides when PhairPlay announces itself and, more importantly, which
 * callbacks are allowed to influence that decision. Getting it wrong produces exactly the
 * failure reported in issue #18: the UI says the receiver is advertising while the
 * platform holds no registration at all, or the reverse — a half-registered receiver that
 * never shows up in an AirPlay picker.
 *
 * Test naming convention: test_[methodName]_[scenario]_[expectedResult]
 */
class MdnsRegistrationTrackerTest {

    private val airplay = 0
    private val raop = 1

    /**
     * Test: Nothing is registered before the first attempt.
     */
    @Test
    fun `new tracker has no registration`() {
        val tracker = MdnsRegistrationTracker()

        assertFalse(tracker.allRegistered())
        assertFalse(tracker.isRegistered(airplay))
        assertFalse(tracker.isRegistered(raop))
    }

    /**
     * Test: The receiver advertises only when BOTH services are registered.
     */
    @Test
    fun `advertising requires both services`() {
        val tracker = MdnsRegistrationTracker()
        val a = tracker.beginAttempt(airplay)
        val r = tracker.beginAttempt(raop)

        assertFalse("one service is not enough", tracker.onRegistered(airplay, a))
        assertTrue("both services registered", tracker.onRegistered(raop, r))
        assertTrue(tracker.allRegistered())
    }

    /**
     * Test: A failed service does not tear down the service that registered fine.
     *
     * WHY: The two mDNS services are registered independently. If `_raop._tcp` fails,
     * `_airplay._tcp` is still live and must stay registered — tearing it down would
     * make the device disappear from the picker even though half the work succeeded.
     */
    @Test
    fun `a failure does not unregister the sibling`() {
        val tracker = MdnsRegistrationTracker()
        val a = tracker.beginAttempt(airplay)
        val r = tracker.beginAttempt(raop)

        tracker.onRegistered(airplay, a)
        tracker.onFailed(raop, r)

        assertTrue("airplay is still registered", tracker.isRegistered(airplay))
        assertFalse("raop is not registered", tracker.isRegistered(raop))
        assertFalse("not advertising yet", tracker.allRegistered())
    }

    /**
     * Test: A failure of an outdated attempt is ignored.
     *
     * WHY: NsdManager delivers its callbacks asynchronously. A failure callback of an
     * attempt that has already been replaced must not count towards the retry limit of
     * the attempt that is currently running.
     */
    @Test
    fun `failure of an outdated attempt is ignored`() {
        val tracker = MdnsRegistrationTracker()
        val stale = tracker.beginAttempt(airplay)
        tracker.beginAttempt(airplay)   // a newer attempt takes over

        assertEquals(-1, tracker.onFailed(airplay, stale))
    }

    /**
     * Test: Consecutive failures are counted so the retry limit can be enforced.
     */
    @Test
    fun `consecutive failures are counted`() {
        val tracker = MdnsRegistrationTracker()

        assertEquals(1, tracker.onFailed(airplay, tracker.beginAttempt(airplay)))
        assertEquals(2, tracker.onFailed(airplay, tracker.beginAttempt(airplay)))
        assertEquals(3, tracker.onFailed(airplay, tracker.beginAttempt(airplay)))
    }

    /**
     * Test: A late success callback is still honoured.
     *
     * WHY: The registration really is live when the callback arrives. Discarding it
     * because a newer attempt has meanwhile started would report an error for a
     * receiver that is in fact advertised — the device would be missing from every
     * AirPlay picker while the UI claims a failure.
     */
    @Test
    fun `late success is still honoured`() {
        val tracker = MdnsRegistrationTracker()
        val stale = tracker.beginAttempt(airplay)
        tracker.beginAttempt(airplay)   // a newer attempt is in flight

        tracker.onRegistered(airplay, stale)

        assertTrue("the live registration is known", tracker.isRegistered(airplay))
        assertFalse("the other service is still missing", tracker.allRegistered())
    }

    /**
     * Test: A failed attempt does not clear an earlier successful registration.
     */
    @Test
    fun `failed attempt keeps an earlier registration`() {
        val tracker = MdnsRegistrationTracker()
        val first = tracker.beginAttempt(airplay)
        tracker.onRegistered(airplay, first)

        tracker.onFailed(airplay, tracker.beginAttempt(airplay))

        assertTrue("the live registration is still known", tracker.isRegistered(airplay))
    }

    /**
     * Test: Each attempt gets its own number.
     */
    @Test
    fun `each attempt has its own number`() {
        val tracker = MdnsRegistrationTracker()

        val first = tracker.beginAttempt(airplay)
        val second = tracker.beginAttempt(airplay)

        assertTrue(first != second)
        assertTrue(tracker.isCurrentAttempt(airplay, second))
        assertFalse(tracker.isCurrentAttempt(airplay, first))
    }

    /**
     * Test: The attempts of the two services are independent.
     */
    @Test
    fun `attempts of the two services are independent`() {
        val tracker = MdnsRegistrationTracker()
        val a = tracker.beginAttempt(airplay)
        val r = tracker.beginAttempt(raop)

        assertTrue(tracker.isCurrentAttempt(airplay, a))
        assertTrue(tracker.isCurrentAttempt(raop, r))
    }

    /**
     * Test: reset() forgets everything — used when the advertisement is rebuilt.
     */
    @Test
    fun `reset forgets the registrations`() {
        val tracker = MdnsRegistrationTracker()
        tracker.onRegistered(airplay, tracker.beginAttempt(airplay))
        tracker.onRegistered(raop, tracker.beginAttempt(raop))
        assertTrue(tracker.allRegistered())

        tracker.reset()

        assertFalse(tracker.allRegistered())
        assertEquals(0, tracker.onFailed(airplay, tracker.beginAttempt(airplay)) - 1)
    }
}
