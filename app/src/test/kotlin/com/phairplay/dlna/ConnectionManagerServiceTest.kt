package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * ConnectionManagerServiceTest — the sink list and the single static connection.
 *
 * WHY: Windows calls GetProtocolInfo first; an empty or malformed Sink means it offers to transcode
 * everything or refuses to cast.
 */
class ConnectionManagerServiceTest {

    private val service = ConnectionManagerService()

    private fun call(action: String, vararg args: Pair<String, String>) = service.handle(action, mapOf(*args), SoapContext())

    @Test
    fun `GetProtocolInfo returns an empty source and the sink list`() {
        val out = call("GetProtocolInfo")
        assertEquals("", out["Source"])
        assertEquals(ProtocolInfoList.sinkString(), out["Sink"])
    }

    @Test
    fun `connection ids and info describe the single input connection`() {
        assertEquals("0", call("GetCurrentConnectionIDs")["ConnectionIDs"])
        val info = call("GetCurrentConnectionInfo", "ConnectionID" to "0")
        assertEquals("0", info["RcsID"])
        assertEquals("0", info["AVTransportID"])
        assertEquals("Input", info["Direction"])
        assertEquals("OK", info["Status"])
        assertEquals("-1", info["PeerConnectionID"])
    }

    @Test
    fun `unknown connection is 706 and unknown action is 401`() {
        try { call("GetCurrentConnectionInfo", "ConnectionID" to "7"); fail() } catch (e: UpnpError) { assertEquals(706, e.code) }
        try { call("PrepareForConnection"); fail() } catch (e: UpnpError) { assertEquals(401, e.code) }
    }
}
