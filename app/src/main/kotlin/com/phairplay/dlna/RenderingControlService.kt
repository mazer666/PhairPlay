package com.phairplay.dlna

import java.util.Locale

/**
 * RenderingControlService — RenderingControl:1: Master-channel volume, mute and one preset.
 *
 * WHY: Windows' "Cast to Device" volume slider and BubbleUPnP's mute button talk to this service. Volume
 * is applied to the DLNA stream through the renderer, deliberately not to the TV's system volume, so
 * casting never changes what the TV remote controls.
 *
 * HOW: Registered with [SoapDispatcher] for [UpnpService.RENDERING_CONTROL].
 */
class RenderingControlService(private val control: RendererControl) : SoapActionHandler {

    override fun handle(action: String, args: Map<String, String>, context: SoapContext): Map<String, String> {
        SoapArgs.requireInstanceZero(args)
        return when (action) {
            "ListPresets" -> mapOf("CurrentPresetNameList" to FACTORY_DEFAULTS)
            "SelectPreset" -> {
                if (SoapArgs.required(args, "PresetName") != FACTORY_DEFAULTS) {
                    throw UpnpError.invalidArgs("unknown preset")
                }
                control.setVolume(RendererSnapshot.MAX_VOLUME)
                control.setMute(false)
                emptyMap()
            }
            "GetVolume" -> {
                requireMaster(args)
                mapOf("CurrentVolume" to control.snapshot().volume.toString())
            }
            "SetVolume" -> {
                requireMaster(args)
                val desired = SoapArgs.required(args, "DesiredVolume").trim().toIntOrNull()
                    ?: throw UpnpError.invalidArgs("DesiredVolume must be numeric")
                control.setVolume(desired.coerceIn(0, RendererSnapshot.MAX_VOLUME))
                emptyMap()
            }
            "GetMute" -> {
                requireMaster(args)
                mapOf("CurrentMute" to if (control.snapshot().mute) "1" else "0")
            }
            "SetMute" -> {
                requireMaster(args)
                control.setMute(parseBoolean(SoapArgs.required(args, "DesiredMute")))
                emptyMap()
            }
            else -> throw UpnpError.invalidAction(action)
        }
    }

    private fun requireMaster(args: Map<String, String>) {
        val channel = args["Channel"] ?: MASTER
        if (channel != MASTER) throw UpnpError.invalidArgs("unsupported channel $channel")
    }

    /** UPnP booleans arrive as 1/0, true/false or yes/no. */
    private fun parseBoolean(raw: String): Boolean = when (raw.trim().lowercase(Locale.US)) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> throw UpnpError.invalidArgs("not a boolean: $raw")
    }

    companion object {
        const val MASTER = "Master"
        const val FACTORY_DEFAULTS = "FactoryDefaults"
    }
}
