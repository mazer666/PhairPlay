package com.phairplay.dlna

/**
 * DlnaRemoteCommand — the transport commands the TV remote can trigger.
 *
 * WHY: the remote control of the TV has to be able to steer the DLNA transport, and
 * the transport state lives in the control plane. The command travels from the
 * activity through the receiver into the description server and back into the
 * dispatcher, so that the GENA subscribers see the new state as well.
 *
 * Example:
 *   receiver.sendRemoteCommand(DlnaRemoteCommand.PLAY_PAUSE)
 */
object DlnaRemoteCommand {
    /** Toggle between playing and paused. */
    const val PLAY_PAUSE = "play-pause"

    /** Pause the playback. */
    const val PAUSE = "pause"

    /** Stop the playback and release the media. */
    const val STOP = "stop"
}
