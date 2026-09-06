package com.phairplay.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.phairplay.util.Logger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AudioPlayer — Decrypts and plays the AirPlay audio stream.
 *
 * WHY: AirPlay audio arrives as encrypted RTP packets over UDP.
 * Before the audio can be played, it must be:
 * 1. Received from the UDP socket
 * 2. Decrypted (AES-128-CBC cipher, IV reset per packet)
 * 3. Decoded (AAC-ELD or ALAC frames → PCM audio)
 * 4. Played through the TV's audio output (AudioTrack)
 *
 * This class handles steps 2-4. Step 1 (UDP receiving) is handled by RtspHandler
 * which calls [playAudioPacket] for each received packet.
 *
 * HOW:
 *   val player = AudioPlayer()
 *   player.initialize(aesKey, aesIv, sampleRate, channels)  // call once with SDP params
 *   player.playAudioPacket(encryptedRtpPayload)              // call for each UDP packet
 *   player.release()                                          // call when done
 */
class AudioPlayer {

    // Android's audio output — writes decoded PCM audio to hardware
    private var audioTrack: AudioTrack? = null

    // RAOP audio is AES-128-CBC: whole 16-byte blocks per packet (fresh IV each packet), trailing
    // partial block in cleartext. Cipher reused; key/IV null for unencrypted streams.
    private val cbcCipher = Cipher.getInstance("AES/CBC/NoPadding")
    private var aesKeySpec: SecretKeySpec? = null
    private var aesIvSpec: IvParameterSpec? = null

    @Volatile
    private var isInitialized = false

    // Software ALAC decoder for the legacy RAOP/SDP path. Apple Lossless is compressed,
    // so the payload must be decoded to PCM — writing raw ALAC to AudioTrack is silence/noise.
    private var alac: com.phairplay.airplay.handshake.AlacDecoder? = null

    // Decode-health gate: a correct key decodes ~every ALAC frame; a wrong key (e.g. an
    // unsupported FairPlay v2 mode whose reply table isn't public) mostly fails and the few
    // "successes" are garbage. We sample the first frames and mute the stream if the success
    // rate is too low, so a wrong key yields silence instead of blasted noise.
    private var decodeAttempts = 0
    private var decodeSuccesses = 0
    private var decodeHealthDecided = false
    @Volatile private var muted = false

    /**
     * Initializes the AudioPlayer with the stream parameters from the SDP.
     *
     * AES key and IV come from the SDP body in the RTSP ANNOUNCE message.
     * When the SDP does not include encryption keys (unencrypted stream), pass null for
     * both — the cipher is skipped entirely and audio payload is written directly to AudioTrack.
     *
     * SECURITY: When provided, both key and IV MUST be exactly 16 bytes each (AES-128).
     * We validate this before use (RULE 4). Passing null skips validation and cipher setup.
     *
     * @param aesKey     16-byte AES-128 key, or null for unencrypted streams
     * @param aesIv      16-byte initialization vector, or null for unencrypted streams
     * @param sampleRate Audio sample rate in Hz (typically 44100 or 48000)
     * @param channels   Number of audio channels (1 = mono, 2 = stereo)
     * @param codec      Audio codec from the SDP (ALAC is decoded in software; others pass through)
     * @param alacFramesPerPacket ALAC frameLength from the SDP fmtp (samples per packet)
     */
    fun initialize(
        aesKey: ByteArray?, aesIv: ByteArray?, sampleRate: Int, channels: Int,
        codec: AudioCodec = AudioCodec.UNKNOWN, alacFramesPerPacket: Int = 352,
    ) {
        if (isInitialized) {
            Logger.w("AudioPlayer.initialize() called twice — ignoring")
            return
        }

        if (codec == AudioCodec.ALAC) {
            alac = runCatching {
                com.phairplay.airplay.handshake.AlacDecoder(sampleRate, channels, alacFramesPerPacket)
            }.onFailure { Logger.e("AudioPlayer: ALAC decoder init failed", it) }.getOrNull()
        }

        if (aesKey != null || aesIv != null) {
            // SECURITY: Validate key length before using for cryptography (RULE 4)
            require(aesKey != null && aesKey.size == AES_KEY_LENGTH_BYTES) {
                "AES key must be exactly $AES_KEY_LENGTH_BYTES bytes, got ${aesKey?.size}"
            }
            require(aesIv != null && aesIv.size == AES_KEY_LENGTH_BYTES) {
                "AES IV must be exactly $AES_KEY_LENGTH_BYTES bytes, got ${aesIv?.size}"
            }
            initializeCipher(aesKey, aesIv)
            Logger.i("Initializing AudioPlayer (encrypted): ${sampleRate}Hz, $channels channels")
        } else {
            Logger.i("Initializing AudioPlayer (unencrypted): ${sampleRate}Hz, $channels channels")
        }

        initializeAudioTrack(sampleRate, channels)

        isInitialized = true
    }

    /**
     * Decrypts and plays a single audio RTP packet.
     *
     * This method is called from the IO dispatcher (network thread) for each
     * UDP audio packet received from the AirPlay sender.
     *
     * Steps:
     * 1. Strip the RTP header (12 bytes fixed + optional extensions)
     * 2. Decrypt the payload using AES-128-CBC (whole 16-byte blocks, fresh IV per packet)
     * 3. Write the decrypted PCM data to AudioTrack
     *
     * PERFORMANCE: AudioTrack.write() in WRITE_NON_BLOCKING mode returns immediately
     * if the buffer is full, rather than blocking. This prevents network I/O from
     * being stalled by audio output.
     *
     * @param rtpPacket The complete RTP packet bytes (header + encrypted payload)
     */
    fun playAudioPacket(rtpPacket: ByteArray) {
        if (!isInitialized) {
            Logger.w("playAudioPacket() called but AudioPlayer not initialized")
            return
        }

        try {
            // Step 1: Strip the RTP header to get the encrypted audio payload
            // RTP header is always at least 12 bytes (RFC 3550)
            if (rtpPacket.size <= RTP_HEADER_MIN_BYTES) {
                Logger.w("RTP packet too small (${rtpPacket.size} bytes), skipping")
                return
            }
            // Only decode audio (payload type 96). Skip timing/sync packets (e.g. 0xd4) that share the
            // port — feeding them to the ALAC decoder produces errors and no audio.
            if ((rtpPacket[1].toInt() and 0x7F) != AUDIO_PAYLOAD_TYPE) return
            val encryptedPayload = rtpPacket.copyOfRange(RTP_HEADER_MIN_BYTES, rtpPacket.size)

            // Step 2: Decrypt if encrypted (cipher is null for unencrypted streams → pass-through)
            val decryptedPayload = decrypt(encryptedPayload)

            // Step 3: Decode ALAC to PCM if this is a lossless stream; otherwise the payload is
            // already PCM (LPCM) and passes through. Skip the packet if ALAC decode fails, and mute
            // the stream entirely if the first frames mostly fail (wrong key → noise suppression).
            // Once the decode-health gate has muted the stream, stop feeding the native decoder:
            // garbage input eventually crashes libalac (observed SIGSEGV in ALACDecoder::Decode).
            if (muted) return
            val pcm = alac?.let { dec ->
                val out = dec.decode(decryptedPayload)
                if (!decodeHealthDecided) updateDecodeHealth(out != null)
                if (muted) return
                out ?: return
            } ?: decryptedPayload

            // Step 4: Write to AudioTrack for playback
            // WRITE_NON_BLOCKING returns immediately if the buffer is full (prevents stalls)
            audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)

        } catch (e: Exception) {
            Logger.e("Error playing audio packet", e)
        }
    }

    /**
     * Releases all audio resources.
     *
     * MUST be called when streaming ends to free the AudioTrack hardware buffer.
     * After release(), call initialize() again before using the player.
     *
     * RULE 5: All resources released — AudioTrack holds exclusive hardware audio output.
     */
    fun release() {
        Logger.d("Releasing AudioPlayer")
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing AudioTrack (non-fatal)", e)
        } finally {
            runCatching { alac?.close() }
            alac = null
            audioTrack = null
            aesKeySpec = null
            aesIvSpec = null
            isInitialized = false
            decodeAttempts = 0
            decodeSuccesses = 0
            decodeHealthDecided = false
            muted = false
        }
    }

    /**
     * Sets up the AES-128-CBC cipher key/IV.
     *
     * Why CBC? RAOP/AirPlay audio encrypts the payload with AES-128-CBC over whole 16-byte
     * blocks; the IV is reset to the SDP `aesiv` for every packet (the cipher does not chain
     * across packets), and any trailing bytes shorter than a block are left in cleartext. The
     * actual init() happens per packet in [decrypt] so each packet starts from the fixed IV.
     *
     * @param key 16-byte AES key
     * @param iv  16-byte initialization vector (re-applied per packet)
     */
    private fun initializeCipher(key: ByteArray, iv: ByteArray) {
        aesKeySpec = SecretKeySpec(key, "AES")
        aesIvSpec = IvParameterSpec(iv)
        Logger.d("AES-128-CBC cipher initialized")
    }

    /**
     * Configures the AudioTrack for audio output.
     *
     * AudioTrack is Android's low-level audio output API — it writes raw PCM
     * audio data directly to the hardware audio mixer.
     *
     * @param sampleRate Audio sample rate (e.g., 44100 Hz)
     * @param channels   Number of channels (1 = mono, 2 = stereo)
     */
    private fun initializeAudioTrack(sampleRate: Int, channels: Int) {
        // Map channel count to Android's AudioFormat constant
        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                Logger.w("Unsupported channel count: $channels — defaulting to stereo")
                AudioFormat.CHANNEL_OUT_STEREO
            }
        }

        // Calculate minimum buffer size — this is the smallest buffer that won't cause dropout
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // Use 2x the minimum buffer size for more stability
        val bufferSize = minBufferSize * 2

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)  // STREAM = for continuous audio
            .build()

        audioTrack!!.play()
        Logger.d("AudioTrack initialized: ${sampleRate}Hz, $channels ch, buffer=$bufferSize bytes")
    }

    /**
     * Decrypts a byte array using AES-128-CBC over its whole 16-byte blocks.
     *
     * The IV is re-applied from the SDP `aesiv` on every call (no chaining across packets), and a
     * trailing sub-block remainder is returned unchanged — matching how AirPlay senders encrypt.
     *
     * @param data The encrypted bytes to decrypt.
     * @return The decrypted bytes (same length as input).
     */
    private fun decrypt(data: ByteArray): ByteArray {
        val key = aesKeySpec ?: return data        // unencrypted stream → pass-through
        val iv = aesIvSpec ?: return data
        // RAOP: AES-128-CBC over whole 16-byte blocks; trailing < 16 bytes stay cleartext. Fresh IV per packet.
        val encryptedLen = (data.size / 16) * 16
        if (encryptedLen == 0) return data
        cbcCipher.init(Cipher.DECRYPT_MODE, key, iv)
        val out = data.copyOf()
        cbcCipher.doFinal(data, 0, encryptedLen, out, 0)
        return out
    }

    /**
     * Samples ALAC decode success over the first frames and decides once whether the stream key is
     * usable. A healthy stream decodes nearly every frame; a wrong key decodes few (and to garbage).
     * On a poor rate we mute so the user hears silence rather than noise.
     */
    private fun updateDecodeHealth(success: Boolean) {
        decodeAttempts++
        if (success) decodeSuccesses++
        if (decodeAttempts < DECODE_HEALTH_SAMPLE) return
        decodeHealthDecided = true
        val rate = decodeSuccesses.toDouble() / decodeAttempts
        if (rate < DECODE_HEALTH_MIN_RATE) {
            muted = true
            Logger.w("Audio muted: ALAC decoded only $decodeSuccesses/$decodeAttempts frames — " +
                     "stream key looks wrong (FairPlay v2 key wrapping is not decryptable; see README)")
        } else {
            Logger.i("Audio decode healthy ($decodeSuccesses/$decodeAttempts frames)")
        }
    }

    companion object {
        // AES-128 key length in bytes (128 bits / 8 = 16 bytes)
        private const val AES_KEY_LENGTH_BYTES = 16

        // Minimum RTP header size per RFC 3550 (12 bytes)
        // Real packets may have extensions, but we always skip at least 12 bytes
        private const val RTP_HEADER_MIN_BYTES = 12
        private const val AUDIO_PAYLOAD_TYPE = 96   // RTP payload type for the RAOP ALAC/AAC audio

        // Decode-health gate: sample this many frames, then mute if fewer than this fraction decoded.
        private const val DECODE_HEALTH_SAMPLE = 24
        private const val DECODE_HEALTH_MIN_RATE = 0.8
    }
}
