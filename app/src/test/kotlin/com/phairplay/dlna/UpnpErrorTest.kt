package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpnpErrorTest — the SOAP `UPnPError` fault codes control points parse must match the UPnP spec exactly.
 *
 * WHY: Windows and VLC branch on the numeric code (e.g. 701 vs. 402); a wrong literal here silently turns
 * into the wrong on-screen error or a control point that stops retrying when it should.
 */
class UpnpErrorTest {

    @Test
    fun `error codes match the UPnP AVTransport spec literals`() {
        assertEquals(401, UpnpError.invalidAction("Play").code)
        assertEquals(402, UpnpError.invalidArgs("bad").code)
        assertEquals(501, UpnpError.actionFailed("bad").code)
        assertEquals(701, UpnpError.transitionNotAvailable("bad").code)
        assertEquals(714, UpnpError.illegalMimeType("bad").code)
        assertEquals(716, UpnpError.resourceNotFound("bad").code)
        assertEquals(718, UpnpError.invalidInstanceId("0").code)
        assertEquals(706, UpnpError.invalidConnectionReference("0").code)
    }

    @Test
    fun `message contains the description`() {
        assertTrue(UpnpError.invalidAction("Play").message!!.contains("Invalid Action: Play"))
        assertTrue(UpnpError.transitionNotAvailable("no media").message!!.contains("Transition not available: no media"))
        assertTrue(UpnpError.invalidInstanceId("7").message!!.contains("Invalid InstanceID: 7"))
    }
}
