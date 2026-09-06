package com.phairplay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * RenderingControlServiceTest — Master-channel volume/mute and the single preset.
 *
 * WHY: The Windows volume slider sends SetVolume 0..100 on channel Master; anything else is a 402.
 * Volume is applied to the stream, never to the TV's system volume (that is the renderer's job).
 */
class RenderingControlServiceTest {

    private val control = FakeRendererControl()
    private val service = RenderingControlService(control)

    private fun call(action: String, vararg args: Pair<String, String>) =
        service.handle(action, mapOf("InstanceID" to "0", "Channel" to "Master", *args), SoapContext())

    private fun expectFault(code: Int, block: () -> Unit) {
        try { block(); fail("expected UPnP fault $code") } catch (e: UpnpError) { assertEquals(code, e.code) }
    }

    @Test
    fun `SetVolume clamps to 0-100 and GetVolume reads it back`() {
        call("SetVolume", "DesiredVolume" to "150")
        assertEquals("100", call("GetVolume")["CurrentVolume"])
        call("SetVolume", "DesiredVolume" to "37")
        assertEquals(listOf("volume:100", "volume:37"), control.calls)
    }

    @Test
    fun `SetMute accepts 1 0 true false and GetMute reports 1 or 0`() {
        call("SetMute", "DesiredMute" to "1")
        assertEquals("1", call("GetMute")["CurrentMute"])
        call("SetMute", "DesiredMute" to "false")
        assertEquals("0", call("GetMute")["CurrentMute"])
        expectFault(402) { call("SetMute", "DesiredMute" to "maybe") }
    }

    @Test
    fun `non-Master channel and non-numeric volume are 402`() {
        expectFault(402) { service.handle("GetVolume", mapOf("InstanceID" to "0", "Channel" to "LF"), SoapContext()) }
        expectFault(402) { call("SetVolume", "DesiredVolume" to "loud") }
    }

    @Test
    fun `presets list FactoryDefaults and selecting it resets volume and mute`() {
        assertEquals("FactoryDefaults", call("ListPresets")["CurrentPresetNameList"])
        call("SelectPreset", "PresetName" to "FactoryDefaults")
        assertEquals(listOf("volume:100", "mute:false"), control.calls)
        expectFault(402) { call("SelectPreset", "PresetName" to "Loud") }
    }

    @Test
    fun `non-zero InstanceID is 718 and unknown action is 401`() {
        expectFault(718) { service.handle("GetVolume", mapOf("InstanceID" to "1", "Channel" to "Master"), SoapContext()) }
        expectFault(401) { call("GetBrightness") }
    }
}
