package com.phairplay.airplay

import com.phairplay.airplay.handshake.FairPlay
import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.util.Logger

/**
 * RtspMirrorHandler — handles the AirPlay 2 mirroring handshake (SETUP + FairPlay fp-setup).
 *
 * WHAT: Answers the binary-plist `SETUP` of an AirPlay 2 mirror session (it decrypts the AES
 * stream key, starts the video/audio data servers and replies with the allocated ports) and the
 * FairPlay `fp-setup` key exchange.
 *
 * WHY: Mirroring is the most protocol-heavy part of AirPlay: it mixes a binary plist, FairPlay
 * key material and three different stream types. Keeping it in its own class means the classic
 * RTSP verbs stay free of FairPlay details.
 *
 * Example:
 *   val mirror = RtspMirrorHandler(sessionState, audioEnabled = true, ...)
 *   val response = mirror.handleMirrorSetup(request)   // 200 OK with the stream ports
 */
internal class RtspMirrorHandler(
    private val sessionState: RtspSessionState,
    private val audioEnabled: Boolean,
    private val onMirrorSetupKeys: (
        aesKey: ByteArray, ecdhSecret: ByteArray, aesIv: ByteArray,
        remoteAddress: java.net.InetAddress, senderTimingPort: Int
    ) -> Pair<Int, Int>,
    private val onMirrorStreamStart: (streamConnectionId: Long) -> Int,
    private val onMirrorAudioStart: (sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int) -> Pair<Int, Int>,
    private val onBufferedAudioStart: () -> Int
) {

    // ─── Shared session state (see [RtspSessionState]) ──────────────────────

    /** True once an AirPlay 2 mirroring SETUP has run on this connection. */
    private var isMirrorSession: Boolean
        get() = sessionState.isMirrorSession
        set(value) { sessionState.isMirrorSession = value }

    /** The FairPlay context of this connection (fp-setup + key decrypt). */
    private val fairPlay: FairPlay?
        get() = sessionState.fairPlay

    /** The pairing context of this connection — provides the ECDH secret for the stream key. */
    private val pairingSession: com.phairplay.airplay.handshake.PairingSession?
        get() = sessionState.pairingSession

    /** The sender's address — needed for AirPlay 2 NTP. */
    private val currentRemoteAddress: java.net.InetAddress?
        get() = sessionState.currentRemoteAddress

    /** The mirror streams that are currently running (96 = audio, 110 = video). */
    private val activeStreamTypes: MutableSet<Int>
        get() = sessionState.activeStreamTypes

    /** POST /fp-setup — FairPlay: 16-byte phase 1 → 142-byte reply; 164-byte phase 2 → 32-byte reply. */
    internal fun handleFpSetup(request: RtspRequest): RtspResponse = try {
        val fp = fairPlay!!
        val b = request.bodyBytes
        // Diagnostics: byte 4 is the FairPlay version (0x03 mirroring/Safari, 0x02 Apple Music audio);
        // for phase 1, byte 14 is the mode (0..3). Confirms which path a given sender uses.
        val verMode = if (b.size >= 16) " v=0x%02x mode=%d".format(b[4].toInt() and 0xFF, b[14].toInt() and 0xFF)
                      else if (b.size >= 5) " v=0x%02x".format(b[4].toInt() and 0xFF) else ""
        val body = when (b.size) {
            16 -> fp.setup(b)
            164 -> fp.handshake(b)
            else -> throw IllegalArgumentException("unexpected fp-setup size ${b.size}")
        }
        Logger.i("fp-setup phase (${b.size}B in → ${body.size}B out)$verMode OK")
        RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
    } catch (e: Exception) {
        Logger.e("fp-setup failed", e)
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }

    /**
     * AirPlay 2 mirroring SETUP (binary plist). Two messages arrive on one connection:
     *  - msg 1 carries `ekey`+`eiv`+`timingPort` → FairPlay-decrypt the AES key, hand it
     *    (with the pairing secret) to the receiver, reply with event/timing ports.
     *  - msg 2 carries `streams`[type 110] → start the mirror data server, reply with its port.
     */
    internal fun handleMirrorSetup(request: RtspRequest): RtspResponse = try {
        val req = PlistCodec.decode(request.bodyBytes)
        Logger.i("mirror SETUP plist: " + req.entries.joinToString { (k, v) ->
            "$k=" + when (v) {
                is ByteArray -> "${v.size}B"
                is List<*> -> "list[${v.size}]"
                else -> v.toString()
            }
        })
        val response = mutableMapOf<String, Any?>()

        isMirrorSession = true
        val ekey = req["ekey"] as? ByteArray
        if (ekey != null) {
            val aesKey = fairPlay!!.decrypt(ekey)
            val ecdhSecret = pairingSession?.sharedSecret ?: error("mirror SETUP before pair-verify")
            val aesIv = (req["eiv"] as? ByteArray) ?: ByteArray(16)
            val senderTimingPort = (req["timingPort"] as? Long)?.toInt() ?: 0
            val remoteAddr = currentRemoteAddress ?: error("mirror SETUP without remote address")
            val (eventPort, timingPort) = onMirrorSetupKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            response["eventPort"] = eventPort.toLong()
            response["timingPort"] = timingPort.toLong()
            Logger.i("mirror SETUP keys OK — eventPort=$eventPort timingPort=$timingPort (sender timing $senderTimingPort)")
        }

        val streams = req["streams"] as? List<*>
        if (streams != null) {
            val resStreams = streams.mapNotNull { s ->
                val stream = s as? Map<*, *> ?: return@mapNotNull null
                when ((stream["type"] as? Long)?.toInt()) {
                    110 -> {
                        val scid = (stream["streamConnectionID"] as? Long) ?: 0L
                        val dataPort = onMirrorStreamStart(scid)
                        activeStreamTypes.add(110)
                        Logger.i("mirror stream type=110 streamConnectionID=$scid dataPort=$dataPort")
                        mapOf("type" to 110L, "dataPort" to dataPort.toLong())
                    }
                    96 -> {
                        // Realtime-audio stream fields (codec type ct, samples-per-frame spf, latencies, …).
                        Logger.d("mirror stream type=96 dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                        })
                        if (!audioEnabled) {
                            Logger.i("mirror stream type=96 ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val sr = (stream["sr"] as? Long)?.toInt() ?: 44100
                        val ch = (stream["channels"] as? Long)?.toInt() ?: 2
                        val ct = (stream["ct"] as? Long)?.toInt() ?: 8   // 8 = AAC-ELD (mirror), 4 = AAC-LC, 2 = ALAC
                        val spf = (stream["spf"] as? Long)?.toInt() ?: 352   // ALAC frameLength (samples/frame)
                        val (dataPort, controlPort) = onMirrorAudioStart(sr, ch, ct, spf)
                        activeStreamTypes.add(96)
                        Logger.i("audio stream type=96 (ct=$ct ${sr}Hz x$ch spf=$spf) dataPort=$dataPort controlPort=$controlPort")
                        mapOf("type" to 96L, "dataPort" to dataPort.toLong(), "controlPort" to controlPort.toLong())
                    }
                    103 -> {
                        // Buffered (audio-only) AirPlay 2 — accepted + instrumented, but the macOS
                        // Music stream stays FairPlay-encrypted (undecryptable), so playback is not
                        // wired. Stream fields (codec ct, audioFormat, shk/shiv, latencies) logged for ref.
                        Logger.d("buffered audio stream type=103 dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                        })
                        if (!audioEnabled) {
                            Logger.i("buffered audio (type=103) ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val dataPort = onBufferedAudioStart()
                        activeStreamTypes.add(103)
                        Logger.i("buffered audio stream type=103 dataPort=$dataPort")
                        mapOf("type" to 103L, "dataPort" to dataPort.toLong())
                    }
                    else -> {
                        Logger.i("mirror SETUP stream dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; else -> v.toString() }
                        })
                        null
                    }
                }
            }
            response["streams"] = resStreams
        }

        RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encode(response),
            contentType = "application/x-apple-binary-plist",
            protocol = request.responseProtocol()
        )
    } catch (e: Exception) {
        Logger.e("mirror SETUP failed", e)
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }
}
