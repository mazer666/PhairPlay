package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.phairplay.service.ProtocolState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MdnsServiceTest — Unit tests for MdnsService.
 *
 * WHY: MdnsService is the gateway between PhairPlay and macOS discovery.
 * If the mDNS registration is wrong (wrong service type, missing TXT records),
 * macOS will never show PhairPlay in the AirPlay menu. These tests verify that
 * the registration is correct without actually using the network.
 *
 * HOW: We mock the Android [NsdManager] and [Context] to avoid needing a real
 * Android device. MockK is used to create mock objects and verify interactions.
 *
 * Test naming convention: test_[methodName]_[scenario]_[expectedResult]
 */
class MdnsServiceTest {

    // The class under test
    private lateinit var mdnsService: MdnsService

    // Mock objects — these simulate Android system services without real hardware
    private lateinit var mockContext: Context
    private lateinit var mockNsdManager: NsdManager

    @Before
    fun setup() {
        // Create mocks for Android dependencies
        mockContext = mockk(relaxed = true)
        mockNsdManager = mockk(relaxed = true)

        // Tell the mock context to return our mock NsdManager
        every { mockContext.getSystemService(Context.NSD_SERVICE) } returns mockNsdManager

        mdnsService = MdnsService(mockContext)
    }

    /**
     * Test: When start() is called, MdnsService registers exactly 2 mDNS services.
     *
     * WHY: AirPlay requires both _airplay._tcp AND _raop._tcp to be registered.
     * If either is missing, macOS may not show the device or may fail to connect.
     */
    @Test
    fun `start registers two mDNS services`() {
        mdnsService.start()

        // Verify that NsdManager.registerService was called exactly 2 times
        verify(exactly = 2) {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        }
    }

    /**
     * Test: Calling start() twice does not register services twice.
     *
     * WHY: If start() is accidentally called twice, we'd have duplicate mDNS
     * registrations, which could cause conflicts. The idempotency check must work.
     */
    @Test
    fun `start is idempotent when called twice`() {
        mdnsService.start()
        mdnsService.start()  // Second call should be ignored

        // Should still only be registered once (2 services from the first call)
        verify(exactly = 2) {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        }
    }

    /**
     * Test: When stop() is called after start(), both services are unregistered.
     *
     * WHY: When the app closes, mDNS services must be unregistered so they
     * disappear from the macOS AirPlay menu. Failing to unregister means the
     * device stays in the menu even when PhairPlay is not running.
     */
    @Test
    fun `stop unregisters services after start`() {
        mdnsService.start()
        mdnsService.stop()

        // Verify that unregisterService was called for each registered listener
        verify(exactly = 2) {
            mockNsdManager.unregisterService(any())
        }
    }

    /**
     * Test: Calling stop() without a prior start() does not crash.
     *
     * WHY: MainActivity.onDestroy() always calls receiver.stop(), even if
     * onCreate() failed before start() was called. Stop must be safe to call
     * in any state.
     */
    @Test
    fun `stop without start does not crash`() {
        // This must not throw any exception
        mdnsService.stop()
    }

    /**
     * Test: AIRPLAY_PORT is 7000 (the standard AirPlay port).
     *
     * WHY: AirPlay requires exactly port 7000. Using any other port means
     * macOS won't be able to connect to PhairPlay.
     */
    @Test
    fun `AIRPLAY_PORT is 7000`() {
        assertEquals(7000, MdnsService.AIRPLAY_PORT)
    }

    /**
     * Test: stop() without start() emits DISABLED state.
     *
     * WHY: After stop(), the UI should show the protocol as disabled
     * even if start() was never called.
     */
    @Test
    fun `stop emits DISABLED protocol state`() {
        val states = mutableListOf<com.phairplay.service.ProtocolState>()
        val service = MdnsService(mockContext, onStateChange = { states.add(it) })

        service.stop()

        assertTrue(states.contains(com.phairplay.service.ProtocolState.DISABLED))
    }

    /**
     * Test: restart() calls stop then start (2 unregistrations + 2 registrations).
     *
     * WHY: Restart must fully tear down and re-advertise so the device name
     * change from Settings takes effect immediately.
     */
    @Test
    fun `restart unregisters then re-registers services`() {
        val service = MdnsService(mockContext)
        service.start()
        service.restart()

        // After restart: stop (2 unregisters) + start (2 registers) = 4 register calls total
        // But first start = 2, restart start = 2 more
        verify(atLeast = 4) {
            mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any())
        }
    }

    /**
     * Test: restart() does NOT publish the DISABLED state.
     *
     * WHY: A restart is a re-advertisement, not a shutdown — the receiver stays enabled.
     * Publishing DISABLED in between made the Home screen render "Disabled — Enable in
     * Settings" for a receiver that was enabled all along (issue #18), which sent users
     * to the wrong place to fix a problem they did not have.
     */
    @Test
    fun `restart does not report DISABLED`() {
        val states = mutableListOf<ProtocolState>()
        val service = MdnsService(mockContext, onStateChange = { states.add(it) })

        service.start()
        service.restart()

        assertFalse(
            "a restart must not publish DISABLED — got $states",
            states.contains(ProtocolState.DISABLED)
        )
    }

    /**
     * Test: A failed registration is retried instead of being left as a dead receiver.
     *
     * WHY: The platform rejects registrations for all sorts of transient reasons (the
     * previous registration has not been released yet, the daemon is busy, the network
     * just came back). Without a retry the receiver stays invisible in every AirPlay
     * picker until the user restarts the app by hand.
     */
    @Test
    fun `failed registration is retried`() {
        val listeners = mutableListOf<NsdManager.RegistrationListener>()
        every { mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any()) } answers {
            listeners.add(thirdArg())
        }

        val service = MdnsService(mockContext)
        service.start()

        // Fail the first service of the first round.
        listeners.first().onRegistrationFailed(mockServiceInfo(), NsdManager.FAILURE_INTERNAL_ERROR)

        // The failed service must be registered again on its own.
        waitUntil("the registration is retried") {
            listeners.size >= 3
        }
    }

    /**
     * Test: A failed registration never leaves the receiver in the DISABLED state.
     *
     * WHY: DISABLED means "the user switched this protocol off". A failed registration
     * is an error and must be reported as such — otherwise the UI asks the user to
     * change a setting that is already correct.
     */
    @Test
    fun `failed registration does not report DISABLED`() {
        val listeners = mutableListOf<NsdManager.RegistrationListener>()
        every { mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any()) } answers {
            listeners.add(thirdArg())
        }

        val states = mutableListOf<ProtocolState>()
        val service = MdnsService(mockContext, onStateChange = { states.add(it) })
        service.start()

        listeners.first().onRegistrationFailed(mockServiceInfo(), NsdManager.FAILURE_INTERNAL_ERROR)

        waitUntil("the registration is retried") { listeners.size >= 3 }
        assertFalse(
            "a failed registration must not report DISABLED — got $states",
            states.contains(ProtocolState.DISABLED)
        )
    }

    /**
     * Test: A failed service is retried without touching the service that registered fine.
     *
     * WHY: The two mDNS services are registered independently. A failure of one must not
     * unregister the other — that would make the device disappear from every AirPlay
     * picker even though half of the work succeeded.
     */
    @Test
    fun `failed service is retried without unregistering the sibling`() {
        val listeners = mutableListOf<NsdManager.RegistrationListener>()
        every { mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any()) } answers {
            listeners.add(thirdArg())
        }

        val service = MdnsService(mockContext)
        service.start()

        // The AirPlay service registers, the RAOP service fails.
        listeners[0].onServiceRegistered(mockServiceInfo())
        listeners[1].onRegistrationFailed(mockServiceInfo(), NsdManager.FAILURE_INTERNAL_ERROR)

        // Only the failed service is registered again — the AirPlay service keeps its
        // registration, so exactly one additional registerService call is expected.
        waitUntil("the failed service is retried") { listeners.size >= 3 }

        // Nothing is unregistered — the sibling service keeps its live registration.
        verify(exactly = 0) {
            mockNsdManager.unregisterService(any())
        }
    }

    /**
     * Test: Both services of the current round must register before advertising.
     */
    @Test
    fun `advertising requires both services of the current round`() {
        val listeners = mutableListOf<NsdManager.RegistrationListener>()
        every { mockNsdManager.registerService(any(), NsdManager.PROTOCOL_DNS_SD, any()) } answers {
            listeners.add(thirdArg())
        }

        val states = mutableListOf<ProtocolState>()
        val service = MdnsService(mockContext, onStateChange = { states.add(it) })
        service.start()

        listeners[0].onServiceRegistered(mockServiceInfo())
        assertFalse("one service is not enough", states.contains(ProtocolState.ADVERTISING))

        listeners[1].onServiceRegistered(mockServiceInfo())
        assertTrue("both services registered", states.contains(ProtocolState.ADVERTISING))
    }

    // ─── Test helpers ───────────────────────────────────────────────────────

    private fun mockServiceInfo(): NsdServiceInfo {
        val info = mockk<NsdServiceInfo>()
        every { info.serviceName } returns "PhairPlay"
        return info
    }

    /**
     * Waits until [condition] holds, or fails the test after [timeoutMs].
     *
     * The registration retry runs on a background coroutine with a backoff, so the
     * assertions above have to wait for it.
     */
    private fun waitUntil(message: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $message")
    }
}
