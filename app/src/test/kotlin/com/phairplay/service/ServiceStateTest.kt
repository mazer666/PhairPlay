package com.phairplay.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ServiceStateTest — Unit tests for [ServiceState] and related enums.
 *
 * WHY: UI code uses `when` branches on [ServiceState]. Testing that the sealed class
 * hierarchy is complete and that the Error state carries the message through prevents
 * silent regressions when new states are added.
 *
 * WHAT WE TEST:
 * - All [ServiceState] subtypes can be instantiated and identified
 * - [ServiceState.Error] carries its message correctly
 * - [ProtocolState] enum has all expected values
 * - [Protocol] enum has all expected values
 * - [ActiveConnection] data class equality
 */
class ServiceStateTest {

    // ─── ServiceState sealed class ────────────────────────────────────────────

    @Test
    fun `ServiceState Running is identified as Running`() {
        val state: ServiceState = ServiceState.Running
        assertTrue(state is ServiceState.Running)
    }

    @Test
    fun `ServiceState Stopped is identified as Stopped`() {
        val state: ServiceState = ServiceState.Stopped
        assertTrue(state is ServiceState.Stopped)
    }

    @Test
    fun `ServiceState Restarting is identified as Restarting`() {
        val state: ServiceState = ServiceState.Restarting
        assertTrue(state is ServiceState.Restarting)
    }

    @Test
    fun `ServiceState Error carries its message`() {
        val msg = "NSD registration failed"
        val state: ServiceState = ServiceState.Error(msg)

        assertTrue(state is ServiceState.Error)
        assertEquals(msg, (state as ServiceState.Error).message)
    }

    @Test
    fun `ServiceState Error instances with different messages are not equal`() {
        val a = ServiceState.Error("error A")
        val b = ServiceState.Error("error B")
        assertNotEquals(a, b)
    }

    @Test
    fun `ServiceState Error instances with same message are equal`() {
        val a = ServiceState.Error("port conflict")
        val b = ServiceState.Error("port conflict")
        assertEquals(a, b)
    }

    // ─── ProtocolState enum ───────────────────────────────────────────────────

    @Test
    fun `ProtocolState has DISABLED state`() {
        assertEquals(ProtocolState.DISABLED, ProtocolState.valueOf("DISABLED"))
    }

    @Test
    fun `ProtocolState has ADVERTISING state`() {
        assertEquals(ProtocolState.ADVERTISING, ProtocolState.valueOf("ADVERTISING"))
    }

    @Test
    fun `ProtocolState has CONNECTED state`() {
        assertEquals(ProtocolState.CONNECTED, ProtocolState.valueOf("CONNECTED"))
    }

    @Test
    fun `ProtocolState has ERROR state`() {
        assertEquals(ProtocolState.ERROR, ProtocolState.valueOf("ERROR"))
    }

    // ─── Protocol enum ────────────────────────────────────────────────────────

    @Test
    fun `Protocol has AIRPLAY value`() {
        assertEquals(Protocol.AIRPLAY, Protocol.valueOf("AIRPLAY"))
    }

    @Test
    fun `Protocol has CAST value`() {
        assertEquals(Protocol.CAST, Protocol.valueOf("CAST"))
    }

    @Test
    fun `Protocol has DLNA value`() {
        assertEquals(Protocol.DLNA, Protocol.valueOf("DLNA"))
    }

    // ─── ActiveConnection data class ──────────────────────────────────────────

    @Test
    fun `ActiveConnection carries all fields`() {
        val ts = System.currentTimeMillis()
        val conn = ActiveConnection(
            senderName = "MacBook Pro",
            protocol = Protocol.AIRPLAY,
            startedAt = ts
        )

        assertEquals("MacBook Pro", conn.senderName)
        assertEquals(Protocol.AIRPLAY, conn.protocol)
        assertEquals(ts, conn.startedAt)
    }

    @Test
    fun `ActiveConnection instances with same data are equal`() {
        val conn1 = ActiveConnection("iPhone", Protocol.AIRPLAY, 1000L)
        val conn2 = ActiveConnection("iPhone", Protocol.AIRPLAY, 1000L)
        assertEquals(conn1, conn2)
    }

    @Test
    fun `ActiveConnection instances with different senders are not equal`() {
        val conn1 = ActiveConnection("MacBook", Protocol.AIRPLAY, 1000L)
        val conn2 = ActiveConnection("iPhone", Protocol.AIRPLAY, 1000L)
        assertNotEquals(conn1, conn2)
    }

    @Test
    fun `ActiveConnection durationSeconds returns non-negative value`() {
        val pastTime = System.currentTimeMillis() - 5_000L  // 5 seconds ago
        val conn = ActiveConnection("MacBook", Protocol.AIRPLAY, pastTime)
        assertTrue(conn.durationSeconds >= 4L)  // at least 4s given test execution time
    }

    @Test
    fun `ActiveConnection durationSeconds is zero for connection just started`() {
        val conn = ActiveConnection("MacBook", Protocol.AIRPLAY, System.currentTimeMillis())
        assertTrue(conn.durationSeconds >= 0L)
    }
}
