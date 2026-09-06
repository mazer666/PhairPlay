package com.phairplay.airplay

import com.phairplay.airplay.handshake.RaopRsa
import com.phairplay.util.Base64Util
import com.phairplay.util.Logger

/**
 * SdpParser — Parses the SDP (Session Description Protocol) body from an AirPlay ANNOUNCE request.
 *
 * WHY: The SDP body in the ANNOUNCE request carries all the information we need to set up
 * the media pipeline:
 * - Whether the stream is video+audio or audio-only
 * - Video codec parameters (SPS/PPS for H.264 MediaCodec initialization)
 * - Audio codec type (ALAC or AAC-ELD)
 * - Audio encryption key and IV (AES-128-CTR)
 * - RTP port numbers for each media section
 *
 * HOW: AirPlay SDP follows RFC 4566 with Apple-specific extensions (rsaaeskey, aesiv).
 * We parse line-by-line, grouping lines under their media section (`m=video` / `m=audio`).
 *
 * Thread safety: [parse] is a pure function — no shared state. Safe to call from any thread.
 *
 * Example SDP:
 * ```
 * v=0
 * o=AirTunes AA:BB:CC:DD:EE:FF 1 IN IP4 192.168.1.10
 * s=AirTunes
 * t=0 0
 * m=video 0 RTP/AVP 96
 * a=rtpmap:96 H264/90000
 * a=fmtp:96 packetization-mode=1;profile-level-id=640020;sprop-parameter-sets=Z2Q....,aM4...
 * m=audio 0 RTP/AVP 96
 * a=rtpmap:96 AppleLossless
 * a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
 * a=rsaaeskey:<base64>
 * a=aesiv:<base64>
 * ```
 */
object SdpParser {

    /**
     * Parses an SDP string from an AirPlay ANNOUNCE body.
     *
     * @param sdp The raw SDP text from the ANNOUNCE Content body.
     * @return [SessionDescription] containing all parsed media parameters,
     *         or null if the SDP is too malformed to extract useful data.
     */
    fun parse(sdp: String): SessionDescription? {
        if (sdp.isBlank()) {
            Logger.w("SdpParser: empty SDP body")
            return null
        }

        val lines = sdp.lines().map { it.trim() }

        var currentSection: String? = null
        var videoPort = 0
        var audioPort = 0
        var videoPayloadType = 96
        var audioPayloadType = 96
        var audioCodec = AudioCodec.UNKNOWN
        var sampleRate = DEFAULT_SAMPLE_RATE
        var channels = DEFAULT_CHANNELS

        // H.264 codec parameters
        var spsBytes: ByteArray? = null
        var ppsBytes: ByteArray? = null
        var h264ProfileLevelId: String? = null

        // Audio encryption: rsaaeskey (legacy RSA) or fpaeskey (FairPlay-wrapped, needs fp-setup decrypt)
        var aesKey: ByteArray? = null
        var audioKeyUnrecoverable = false
        var fpAesKey: ByteArray? = null
        var aesIv: ByteArray? = null

        // ALAC frame size
        var alacFramesPerPacket = 352

        for (line in lines) {
            when {
                // ── media section headers ────────────────────────────────────
                line.startsWith("m=video") -> {
                    currentSection = "video"
                    videoPort = parseMediaPort(line)
                    videoPayloadType = parsePayloadType(line)
                }
                line.startsWith("m=audio") -> {
                    currentSection = "audio"
                    audioPort = parseMediaPort(line)
                    audioPayloadType = parsePayloadType(line)
                }

                // ── attribute lines ──────────────────────────────────────────
                line.startsWith("a=") -> {
                    val attr = line.removePrefix("a=")
                    when (currentSection) {
                        "video" -> parseVideoAttribute(attr)?.let { (sps, pps, profile) ->
                            spsBytes = sps
                            ppsBytes = pps
                            h264ProfileLevelId = profile
                        }
                        "audio" -> {
                            parseAudioCodecFromRtpmap(attr)?.let { (codec, rate, ch) ->
                                audioCodec = codec
                                if (rate > 0) sampleRate = rate
                                if (ch > 0) channels = ch
                            }
                            parseAlacFmtp(attr)?.let { (frames, rate, ch) ->
                                alacFramesPerPacket = frames
                                if (rate > 0) sampleRate = rate
                                if (ch > 0) channels = ch
                            }
                            if (attr.startsWith(RSA_KEY_PREFIX)) {
                                val recovered = parseAesKey(attr)
                                if (recovered != null) aesKey = recovered else audioKeyUnrecoverable = true
                            }
                            parseFpAesKey(attr)?.let { fpAesKey = it }
                            parseAesIv(attr)?.let { aesIv = it }
                        }
                    }
                }
            }
        }

        val hasVideo = videoPort >= 0 && spsBytes != null
        val hasAudio = audioPort >= 0 && audioCodec != AudioCodec.UNKNOWN

        if (!hasVideo && !hasAudio) {
            Logger.w("SdpParser: could not parse any usable media section")
            return null
        }

        Logger.i("SdpParser: hasVideo=$hasVideo hasAudio=$hasAudio codec=$audioCodec")

        return SessionDescription(
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            videoPort = videoPort,
            videoPayloadType = videoPayloadType,
            spsBytes = spsBytes,
            ppsBytes = ppsBytes,
            h264ProfileLevelId = h264ProfileLevelId,
            audioPort = audioPort,
            audioPayloadType = audioPayloadType,
            audioCodec = audioCodec,
            sampleRate = sampleRate,
            channels = channels,
            aesKey = aesKey,
            fpAesKey = fpAesKey,
            aesIv = aesIv,
            alacFramesPerPacket = alacFramesPerPacket,
            audioKeyUnrecoverable = audioKeyUnrecoverable
        )
    }

    // ─── Private parsing helpers ─────────────────────────────────────────────

    /** Extracts the port number from `m=<type> <port> ...` */
    private fun parseMediaPort(line: String): Int {
        return line.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
    }

    /** Extracts the payload type from `m=<type> <port> RTP/AVP <pt>` */
    private fun parsePayloadType(line: String): Int {
        return line.split(" ").getOrNull(3)?.toIntOrNull() ?: 96
    }

    /**
     * Parses an `fmtp` attribute for H.264 and extracts SPS/PPS + profile-level-id.
     *
     * Expected format:
     * `fmtp:96 packetization-mode=1;profile-level-id=640020;sprop-parameter-sets=<SPS base64>,<PPS base64>`
     *
     * @return Triple(spsBytes, ppsBytes, profileLevelId) or null if not an fmtp line.
     */
    private fun parseVideoAttribute(attr: String): Triple<ByteArray, ByteArray, String>? {
        if (!attr.startsWith("fmtp:")) return null

        val params = attr.substringAfter(" ")
        val paramMap = params.split(";").associate {
            val kv = it.trim().split("=", limit = 2)
            kv[0].trim() to (kv.getOrNull(1)?.trim() ?: "")
        }

        val profileLevelId = paramMap["profile-level-id"] ?: return null
        val spropSets = paramMap["sprop-parameter-sets"] ?: return null
        val setParts = spropSets.split(",")

        val sps = decodeBase64Safely(setParts.getOrNull(0)) ?: return null
        val pps = decodeBase64Safely(setParts.getOrNull(1)) ?: return null

        return Triple(sps, pps, profileLevelId)
    }

    /**
     * Parses an `rtpmap` attribute to identify the audio codec, sample rate, and channels.
     *
     * Examples:
     * - `rtpmap:96 AppleLossless`            → ALAC, rate=0 (get from fmtp), ch=0
     * - `rtpmap:96 mpeg4-generic/44100/2`    → AAC_ELD, rate=44100, ch=2
     * - `rtpmap:96 mpeg4-generic/48000/1`    → AAC_ELD, rate=48000, ch=1
     *
     * @return Triple(codec, sampleRate, channels) or null if not an audio rtpmap.
     *   sampleRate/channels are 0 if not present in the rtpmap line.
     */
    private fun parseAudioCodecFromRtpmap(attr: String): Triple<AudioCodec, Int, Int>? {
        if (!attr.startsWith("rtpmap:")) return null
        val codecPart = attr.substringAfter(" ")  // e.g. "AppleLossless" or "mpeg4-generic/44100/2"
        val parts = codecPart.split("/")
        val codecName = parts[0]
        val rate = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val ch = parts.getOrNull(2)?.toIntOrNull() ?: 0

        val codec = when {
            codecName.contains("AppleLossless", ignoreCase = true) -> AudioCodec.ALAC
            codecName.contains("mpeg4-generic", ignoreCase = true) -> AudioCodec.AAC_ELD
            else -> return null
        }
        return Triple(codec, rate, ch)
    }

    /**
     * Parses an ALAC `fmtp` attribute to extract frames-per-packet, sample rate, and channels.
     *
     * ALAC fmtp format (RFC 3640 / Apple spec):
     * `fmtp:96 <frameLen> <version> <bitDepth> <ricePdBound> <riceDynamic> <riceHist>
     *          <numChannels> <maxRun> <maxFrameBytes> <avgBitRate> <sampleRate>`
     * Fields: index 0 = frameLen, index 6 = numChannels, index 10 = sampleRate
     *
     * @return Triple(alacFramesPerPacket, sampleRate, channels) or null if not an ALAC fmtp.
     */
    private fun parseAlacFmtp(attr: String): Triple<Int, Int, Int>? {
        if (!attr.startsWith("fmtp:")) return null
        val tokens = attr.substringAfter(" ").split(" ")
        val framesPerPacket = tokens.getOrNull(0)?.toIntOrNull() ?: return null
        val numChannels = tokens.getOrNull(6)?.toIntOrNull() ?: 0
        val sampleRate = tokens.getOrNull(10)?.toIntOrNull() ?: 0
        return Triple(framesPerPacket, sampleRate, numChannels)
    }

    /**
     * Parses the audio AES key from `rsaaeskey:<base64>` (legacy AirPlay-1 / iTunes).
     *
     * The blob is the 16-byte AES-128 stream key RSA-encrypted with the AirPort Express public key
     * (128 or 256 bytes after Base64 decode). We recover it with the known private key via
     * [RaopRsa]. An already-16-byte value is passed through unchanged (modern senders use FairPlay's
     * `fpaeskey` instead, decrypted later via fp-setup).
     */
    private fun parseAesKey(attr: String): ByteArray? {
        if (!attr.startsWith(RSA_KEY_PREFIX)) return null
        val blob = decodeBase64Safely(attr.removePrefix(RSA_KEY_PREFIX)) ?: return null
        if (blob.size == 16) return blob
        val key = RaopRsa.decryptAesKey(blob)
        if (key == null) {
            Logger.e("rsaaeskey: RSA recovery of ${blob.size}B blob failed — session will be rejected")
        } else {
            Logger.i("rsaaeskey: RSA-decrypted ${blob.size}B blob → 16B AES key (RSA audio path)")
        }
        return key
    }

    /**
     * Parses the AES IV from `aesiv:<base64-encoded-iv>`.
     *
     * The IV is 16 bytes, re-applied per packet for AES-128-CBC audio decryption.
     */
    private fun parseAesIv(attr: String): ByteArray? {
        if (!attr.startsWith("aesiv:")) return null
        val b64 = attr.removePrefix("aesiv:")
        return decodeBase64Safely(b64)?.also {
            if (it.size != 16) Logger.w("AES IV is ${it.size} bytes — expected 16")
        }
    }

    /** Parses `fpaeskey:<base64>` — the FairPlay-wrapped AES key (Apple Music). Needs fp-setup decrypt. */
    private fun parseFpAesKey(attr: String): ByteArray? {
        if (!attr.startsWith("fpaeskey:")) return null
        return decodeBase64Safely(attr.removePrefix("fpaeskey:"))
    }

    /**
     * Decodes a Base64 string safely, returning null on any decode error.
     *
     * SECURITY: We do not crash on malformed Base64. Return null and let the
     * caller handle the missing value gracefully.
     */
    private fun decodeBase64Safely(b64: String?): ByteArray? {
        if (b64.isNullOrBlank()) return null
        return try {
            Base64Util.decode(b64)
        } catch (e: IllegalArgumentException) {
            Logger.w("SdpParser: invalid Base64 in SDP attribute")
            null
        }
    }
}

/**
 * SessionDescription — Parsed result from an AirPlay ANNOUNCE SDP body.
 *
 * All fields have safe defaults so callers don't need null checks for optional fields.
 *
 * @param hasVideo          true if the SDP contains a parseable video section
 * @param hasAudio          true if the SDP contains a parseable audio section
 * @param videoPort         RTP port for video (interleaved over TCP in AirPlay mirroring)
 * @param videoPayloadType  RTP payload type for video (typically 96 for H.264)
 * @param spsBytes          H.264 SPS NAL unit (for MediaCodec CSD-0 initialization)
 * @param ppsBytes          H.264 PPS NAL unit (for MediaCodec CSD-1 initialization)
 * @param h264ProfileLevelId Profile-level-id string from the fmtp attribute
 * @param audioPort         RTP port for audio (UDP)
 * @param audioPayloadType  RTP payload type for audio (typically 96)
 * @param audioCodec        Detected audio codec ([AudioCodec])
 * @param sampleRate        Audio sample rate in Hz (default 44100)
 * @param channels          Number of audio channels (default 2 = stereo)
 * @param aesKey            16-byte AES-128 key for audio decryption (null if not encrypted)
 * @param aesIv             16-byte AES-128 IV for audio decryption (null if not encrypted)
 * @param alacFramesPerPacket ALAC frames-per-packet (only relevant for ALAC streams)
 */
data class SessionDescription(
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val videoPort: Int = 0,
    val videoPayloadType: Int = 96,
    val spsBytes: ByteArray? = null,
    val ppsBytes: ByteArray? = null,
    val h264ProfileLevelId: String? = null,
    val audioPort: Int = 0,
    val audioPayloadType: Int = 96,
    val audioCodec: AudioCodec = AudioCodec.UNKNOWN,
    val sampleRate: Int = 44100,
    val channels: Int = 2,
    val aesKey: ByteArray? = null,
    /** FairPlay-wrapped audio key (SDP `fpaeskey`); decrypted to [aesKey] via fp-setup before use. */
    val fpAesKey: ByteArray? = null,
    val aesIv: ByteArray? = null,
    val alacFramesPerPacket: Int = 352,
    /**
     * True when the SDP carried an `rsaaeskey` that [com.phairplay.airplay.handshake.RaopRsa]
     * could not recover. The handler rejects the session so the sender shows an error instead
     * of a silent stream.
     */
    val audioKeyUnrecoverable: Boolean = false,
    /**
     * The sender's identifier extracted from the RTSP ANNOUNCE `User-Agent` header.
     *
     * Example: `User-Agent: AirPlay/376.1.1` → senderName = `"AirPlay"`
     * Populated by [com.phairplay.airplay.RtspHandler] after ANNOUNCE is received.
     * Empty string if the header was absent or could not be parsed.
     */
    val senderName: String = ""
) {
    /** True if encryption keys are present and both key+IV have the correct AES-128 length. */
    val isAudioEncrypted: Boolean
        get() = aesKey?.size == 16 && aesIv?.size == 16

    /** True if this is a pure audio stream (no video — e.g., music from Apple Music). */
    val isAudioOnly: Boolean
        get() = !hasVideo && hasAudio

    // ByteArray fields break default data class equals/hashCode — override explicitly
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SessionDescription) return false
        return hasVideo == other.hasVideo &&
            hasAudio == other.hasAudio &&
            videoPort == other.videoPort &&
            audioPort == other.audioPort &&
            audioCodec == other.audioCodec &&
            spsBytes.contentEquals(other.spsBytes) &&
            ppsBytes.contentEquals(other.ppsBytes) &&
            aesKey.contentEquals(other.aesKey) &&
            aesIv.contentEquals(other.aesIv)
    }

    override fun hashCode(): Int {
        var result = hasVideo.hashCode()
        result = 31 * result + hasAudio.hashCode()
        result = 31 * result + videoPort
        result = 31 * result + audioPort
        result = 31 * result + audioCodec.hashCode()
        result = 31 * result + (spsBytes?.contentHashCode() ?: 0)
        result = 31 * result + (aesKey?.contentHashCode() ?: 0)
        return result
    }

    // Extension for nullable ByteArray comparison
    private fun ByteArray?.contentEquals(other: ByteArray?): Boolean {
        if (this == null && other == null) return true
        if (this == null || other == null) return false
        return this.contentEquals(other)
    }
}

// ─── Companion constants (used in SdpParser + tests) ────────────────────────
private const val DEFAULT_SAMPLE_RATE = 44100
private const val RSA_KEY_PREFIX = "rsaaeskey:"
private const val DEFAULT_CHANNELS = 2

/** Identifies the audio codec used in the AirPlay audio stream. */
enum class AudioCodec {
    /** Apple Lossless Audio Codec — lossless, higher bandwidth */
    ALAC,
    /** AAC Enhanced Low Delay — lossy, optimized for low-latency streaming */
    AAC_ELD,
    /** Not yet identified (ANNOUNCE not yet received) */
    UNKNOWN
}
