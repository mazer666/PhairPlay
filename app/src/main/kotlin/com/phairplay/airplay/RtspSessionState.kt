package com.phairplay.airplay

import com.phairplay.airplay.handshake.FairPlay
import com.phairplay.airplay.handshake.PairingSession
import java.net.InetAddress

/**
 * RtspSessionState — holds the mutable state of one AirPlay RTSP control connection.
 *
 * WHAT: The shared state of a running AirPlay session: the negotiated session description, the
 * FairPlay and pairing contexts, the sender's address and the set of active mirror streams.
 *
 * WHY: The AirPlay control protocol is implemented by several small classes (classic RTSP verbs,
 * the AirPlay 2 handshake, mirroring, the socket server). They all need to see the *same* session
 * state, otherwise one of them would work on a stale copy. One shared state object gives them a
 * single source of truth without dragging the whole handler graph into every class.
 *
 * Example:
 *   val state = RtspSessionState()
 *   state.currentSession = parsedSession   // set while handling ANNOUNCE
 *   state.isMirrorSession = true            // set while handling an AirPlay 2 mirror SETUP
 */
internal class RtspSessionState {

    /** The session negotiated by ANNOUNCE/SDP — null until the sender announced one. */
    @Volatile var currentSession: SessionDescription? = null

    /** How many SETUP requests arrived on this connection — the first one may be the video channel. */
    var setupCount: Int = 0

    /** True once an AirPlay 2 mirroring SETUP has run on this connection (no ANNOUNCE/SDP). */
    @Volatile var isMirrorSession: Boolean = false

    /** Per-connection FairPlay state (fp-setup handshake + stream-key decrypt). */
    @Volatile var fairPlay: FairPlay? = null

    /** Per-connection AirPlay pairing state (pair-setup / pair-verify). */
    @Volatile var pairingSession: PairingSession? = null

    /** Remote (sender) address of the active control connection — needed for AirPlay 2 NTP. */
    @Volatile var currentRemoteAddress: InetAddress? = null

    /** Mirror stream types currently active (96 = audio, 110 = video). Drives TEARDOWN routing. */
    val activeStreamTypes = mutableSetOf<Int>()
}
