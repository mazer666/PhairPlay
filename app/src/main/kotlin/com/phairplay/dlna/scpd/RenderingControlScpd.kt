package com.phairplay.dlna.scpd

/**
 * RenderingControlScpd — RenderingControl:1 description: Master-channel volume, mute and one preset.
 *
 * WHY: Windows' "Cast to Device" volume slider and BubbleUPnP's mute button call exactly these actions.
 *
 * HOW: `RenderingControlScpd.XML` is the lazily-built SCPD document; see [scpd] for the DSL.
 */
object RenderingControlScpd {
    private const val INSTANCE = "A_ARG_TYPE_InstanceID"
    private const val CHANNEL = "A_ARG_TYPE_Channel"

    val XML: String by lazy {
        scpd {
            action("ListPresets") { input("InstanceID", INSTANCE); output("CurrentPresetNameList", "PresetNameList") }
            action("SelectPreset") { input("InstanceID", INSTANCE); input("PresetName", "A_ARG_TYPE_PresetName") }
            action("GetVolume") { input("InstanceID", INSTANCE); input("Channel", CHANNEL); output("CurrentVolume", "Volume") }
            action("SetVolume") { input("InstanceID", INSTANCE); input("Channel", CHANNEL); input("DesiredVolume", "Volume") }
            action("GetMute") { input("InstanceID", INSTANCE); input("Channel", CHANNEL); output("CurrentMute", "Mute") }
            action("SetMute") { input("InstanceID", INSTANCE); input("Channel", CHANNEL); input("DesiredMute", "Mute") }

            variable("PresetNameList", default = "FactoryDefaults")
            variable("LastChange", evented = true)
            variable("Volume", dataType = "ui2", range = 0..100)
            variable("Mute", dataType = "boolean")
            variable(CHANNEL, allowed = listOf("Master"))
            variable(INSTANCE, dataType = "ui4")
            variable("A_ARG_TYPE_PresetName", allowed = listOf("FactoryDefaults"))
        }
    }
}
