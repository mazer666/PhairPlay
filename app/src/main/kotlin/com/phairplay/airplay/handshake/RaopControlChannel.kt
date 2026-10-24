package com.phairplay.airplay.handshake

import java.net.SocketAddress

/**
 * RaopControlChannel — The UDP control channel of a RAOP audio session.
 *
 * WHAT: A one-method sink for control datagrams — the RAOP resend request (type 0x55)
 * that [AudioReorderBuffer] sends when it detects a gap in the audio stream.
 *
 * WHY: The reorder buffer needs to talk to the sender, but it must not know anything about
 * sockets, ports or threads. This tiny interface keeps the socket in [AudioStreamServer]
 * and the sequence-number logic in [AudioReorderBuffer], so both can be reasoned about —
 * and tested — on their own.
 *
 * HOW: Implement it with the control socket and hand the implementation to the buffer.
 *
 * Example:
 *   val channel = object : RaopControlChannel {
 *       override fun send(payload: ByteArray, address: SocketAddress) {
 *           controlSocket.send(DatagramPacket(payload, payload.size, address))
 *       }
 *   }
 */
internal interface RaopControlChannel {

    /**
     * Sends one control datagram to [address].
     *
     * @param payload The datagram bytes (the complete RAOP control packet).
     * @param address The sender's control address, learned from its first control packet.
     */
    fun send(payload: ByteArray, address: SocketAddress)
}
