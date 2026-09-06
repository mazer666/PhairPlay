package com.phairplay.airplay.handshake

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket

/**
 * BufferedAudioServer — receives the AirPlay 2 *buffered* (audio-only) stream, i.e. AirPlaying from
 * Apple Music / Control Center → TV WITHOUT screen mirroring (stream type 103).
 *
 * Unlike the realtime mirroring audio (type 96, UDP), buffered audio arrives over a TCP data
 * connection: the sender streams ahead and the receiver plays from a buffer, scheduled against an
 * anchor time provided by SETRATEANCHORTIME.
 *
 * STATUS: accept-only. It accepts the TCP connection (so a sender that chooses this stream doesn't
 * abort) and logs the framing of the first packets. Playback isn't implemented: it needs the
 * HAP-encrypted control channel, PTP timing, the `shk` stream key and ChaCha20-Poly1305 packet
 * decryption (see Shairport Sync / openairplay/airplay2-receiver). macOS Music.app only takes this
 * path when the receiver advertises buffered audio (features bit 40); otherwise it falls back to
 * AirPlay 1 with FairPlay v2 key wrapping, which cannot be decrypted by open-source code. Implementing
 * this path is therefore the real fix for Music.app audio-only.
 */
class BufferedAudioServer {

    private val serverSocket = ServerSocket(0)   // OS-assigned port, returned in the SETUP response
    @Volatile private var running = false
    @Volatile private var client: Socket? = null

    /** TCP port macOS connects to for the buffered audio stream (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) { runReceive() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        Logger.i("BufferedAudioServer stopped")
    }

    private fun runReceive() {
        try {
            Logger.i("BufferedAudioServer (audio-only) listening on TCP $dataPort")
            val socket = serverSocket.accept().also { client = it }
            Logger.i("Buffered audio connection from ${socket.inetAddress.hostAddress}")
            val input = socket.getInputStream()
            val buf = ByteArray(16384)
            var reads = 0
            var totalBytes = 0L
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                totalBytes += n
                // Log the framing of the first few reads (length prefix / RTP header / payload).
                if (reads < 12) {
                    Logger.d("Buffered audio read[$reads] ${n}B head=${hex(buf, minOf(28, n))}")
                    reads++
                }
            }
            Logger.i("Buffered audio connection ended (total ${totalBytes}B over ${reads}+ reads)")
        } catch (e: Exception) {
            if (running) Logger.e("Buffered audio stream error", e)
        }
    }

    private companion object {
        fun hex(b: ByteArray, len: Int): String =
            (0 until minOf(len, b.size)).joinToString(" ") { "%02x".format(b[it]) }
    }
}
