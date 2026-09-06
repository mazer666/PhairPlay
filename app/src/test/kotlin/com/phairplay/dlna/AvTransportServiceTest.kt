package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AvTransportServiceTest — every AVTransport:1 action against the renderer contract.
 *
 * WHY: This is the protocol surface Windows, BubbleUPnP and VLC drive. Wrong 701/402 behaviour makes
 * senders show errors; wrong GetPositionInfo formatting freezes their progress bars.
 */
class AvTransportServiceTest {

    private val control = FakeRendererControl()
    private val service = AvTransportService(control)
    private val ctx = SoapContext(userAgent = "Microsoft-Windows/10.0 UPnP/1.0")
    private val uri = "http://192.168.1.10:2869/movie.mp4"

    private fun call(action: String, vararg args: Pair<String, String>) =
        service.handle(action, mapOf("InstanceID" to "0", *args), ctx)

    private fun expectFault(code: Int, block: () -> Unit) {
        try { block(); fail("expected UPnP fault $code") } catch (e: UpnpError) { assertEquals(code, e.code) }
    }

    @Test
    fun `SetAVTransportURI loads the item and forwards the sender agent`() {
        call("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to "<DIDL-Lite/>")
        assertEquals(listOf("load:$uri:Microsoft-Windows/10.0 UPnP/1.0"), control.calls)
        assertEquals("<DIDL-Lite/>", control.snapshot.currentMetadata)
    }

    @Test
    fun `SetAVTransportURI without CurrentURI is 402`() {
        expectFault(402) { call("SetAVTransportURI") }
    }

    @Test
    fun `a renderer fault from load reaches the sender unchanged`() {
        control.loadError = UpnpError.illegalMimeType("wmv")
        expectFault(714) { call("SetAVTransportURI", "CurrentURI" to uri) }
    }

    @Test
    fun `Play with no media is 701 and after a load calls the renderer`() {
        expectFault(701) { call("Play", "Speed" to "1") }
        call("SetAVTransportURI", "CurrentURI" to uri)
        call("Play", "Speed" to "1")
        assertTrue(control.calls.contains("play"))
    }

    @Test
    fun `Pause is 701 unless playing or transitioning`() {
        expectFault(701) { call("Pause") }
        control.snapshot = RendererSnapshot(state = TransportState.STOPPED, currentUri = uri)
        expectFault(701) { call("Pause") }
        control.snapshot = control.snapshot.copy(state = TransportState.PLAYING)
        call("Pause")
        assertTrue(control.calls.contains("pause"))
    }

    @Test
    fun `Stop always succeeds`() {
        call("Stop")
        assertEquals(listOf("stop"), control.calls)
    }

    @Test
    fun `Seek REL_TIME parses the target and TRACK_NR restarts`() {
        control.snapshot = RendererSnapshot(state = TransportState.PLAYING, currentUri = uri, durationMs = 600_000)
        call("Seek", "Unit" to "REL_TIME", "Target" to "0:01:30")
        call("Seek", "Unit" to "TRACK_NR", "Target" to "1")
        assertEquals(listOf("seek:90000", "seek:0"), control.calls)
    }

    @Test
    fun `Seek ABS_TIME parses the target`() {
        control.snapshot = RendererSnapshot(state = TransportState.PLAYING, currentUri = uri, durationMs = 600_000)
        call("Seek", "Unit" to "ABS_TIME", "Target" to "0:02:05")
        assertEquals(listOf("seek:125000"), control.calls)
    }

    @Test
    fun `Seek rejects bad units targets and no media`() {
        expectFault(701) { call("Seek", "Unit" to "REL_TIME", "Target" to "0:00:10") }
        control.snapshot = RendererSnapshot(state = TransportState.PLAYING, currentUri = uri)
        expectFault(402) { call("Seek", "Unit" to "REL_TIME", "Target" to "later") }
        expectFault(402) { call("Seek", "Unit" to "X_FOO", "Target" to "0:00:10") }
        expectFault(402) { call("Seek", "Unit" to "TRACK_NR", "Target" to "2") }
    }

    @Test
    fun `SetNextAVTransportURI stores and Next advances only when set`() {
        control.snapshot = RendererSnapshot(state = TransportState.PLAYING, currentUri = uri)
        expectFault(701) { call("Next") }
        call("SetNextAVTransportURI", "NextURI" to "http://x/2.mp3", "NextURIMetaData" to "")
        call("Next")
        assertEquals(listOf("next:http://x/2.mp3", "advance"), control.calls)
    }

    @Test
    fun `SetNextAVTransportURI without NextURI is 402`() {
        expectFault(402) { call("SetNextAVTransportURI") }
    }

    @Test
    fun `Previous restarts the current item`() {
        control.snapshot = RendererSnapshot(state = TransportState.PLAYING, currentUri = uri)
        call("Previous")
        assertEquals(listOf("seek:0"), control.calls)
    }

    @Test
    fun `Previous with no media is 701`() {
        expectFault(701) { call("Previous") }
    }

    @Test
    fun `GetTransportInfo and GetPositionInfo report formatted state`() {
        control.snapshot = RendererSnapshot(
            state = TransportState.PLAYING, currentUri = uri, currentMetadata = "<m/>",
            durationMs = 3_723_000, positionMs = 61_000
        )
        val transport = call("GetTransportInfo")
        assertEquals("PLAYING", transport["CurrentTransportState"])
        assertEquals("OK", transport["CurrentTransportStatus"])
        assertEquals("1", transport["CurrentSpeed"])

        val position = call("GetPositionInfo")
        assertEquals("1", position["Track"])
        assertEquals("1:02:03", position["TrackDuration"])
        assertEquals("0:01:01", position["RelTime"])
        assertEquals("0:01:01", position["AbsTime"])
        assertEquals(uri, position["TrackURI"])
        assertEquals("<m/>", position["TrackMetaData"])
        assertEquals("2147483647", position["RelCount"])
    }

    @Test
    fun `GetMediaInfo reflects media presence`() {
        assertEquals("0", call("GetMediaInfo")["NrTracks"])
        assertEquals("NONE", call("GetMediaInfo")["PlayMedium"])
        control.snapshot = RendererSnapshot(state = TransportState.STOPPED, currentUri = uri, nextUri = "http://x/2")
        val info = call("GetMediaInfo")
        assertEquals("1", info["NrTracks"])
        assertEquals("NETWORK", info["PlayMedium"])
        assertEquals("http://x/2", info["NextURI"])
    }

    @Test
    fun `GetCurrentTransportActions follows the state`() {
        assertEquals("", call("GetCurrentTransportActions")["Actions"])
        control.snapshot = RendererSnapshot(state = TransportState.PLAYING, currentUri = uri, nextUri = "http://x/2")
        assertEquals("Pause,Stop,Seek,Next", call("GetCurrentTransportActions")["Actions"])
        control.snapshot = control.snapshot.copy(state = TransportState.PAUSED_PLAYBACK, nextUri = "")
        assertEquals("Play,Stop,Seek", call("GetCurrentTransportActions")["Actions"])
    }

    @Test
    fun `capabilities and settings are static`() {
        assertEquals("NETWORK,NONE", call("GetDeviceCapabilities")["PlayMedia"])
        assertEquals("NORMAL", call("GetTransportSettings")["PlayMode"])
    }

    @Test
    fun `non-zero InstanceID is 718 and unknown action is 401`() {
        expectFault(718) { service.handle("Play", mapOf("InstanceID" to "3"), ctx) }
        expectFault(401) { call("Record") }
    }
}
