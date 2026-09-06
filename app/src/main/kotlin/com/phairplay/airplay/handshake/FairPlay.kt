package com.phairplay.airplay.handshake

/**
 * FairPlay — per-connection AirPlay FairPlay handshake (`POST /fp-setup`) and stream-key
 * decryption.
 *
 * Two fp-setup protocol versions appear in the wild, selected by byte 4 of the request:
 *   - v3 (`FPLY 03 …`): screen mirroring + Safari video. Phase 1 has four mode-specific
 *     142-byte replies (selected by `req[14]`).
 *   - v2 (`FPLY 02 …`): the RAOP audio path that Apple Music / iTunes use. Phase 1 has a
 *     single 142-byte reply whose mode byte (offset 13) is patched from `req[14]`.
 *
 * Both versions share the SAME phase-2 handshake and the SAME key-decryption core, so once
 * the right phase-1 reply gets the sender to hand over its key message + wrapped key, the
 * decrypt is identical. The reply blobs are fixed captures (no crypto), so they live here in
 * Kotlin; the final key decryption (`decrypt`) uses Apple's reverse-engineered FairPlay cipher
 * + 483 KB of lookup tables — too large/obfuscated to hand-port safely — so it delegates to the
 * proven RPiPlay C compiled into `libplayfair.so` via JNI.
 *
 * References:
 *   - RPiPlay lib/fairplay_playfair.c + lib/playfair/ (v3).
 *   - joerg-krause/shairplay @ fairplay_v2 (the v2 reply table + version branch).
 */
class FairPlay {

    /** The 164-byte phase-2 key message; required before [decrypt] can run. */
    private var keyMessage: ByteArray? = null

    /** The fp-setup version negotiated in phase 1 (0x02 or 0x03); for logging/diagnostics. */
    var negotiatedVersion: Int = 0
        private set

    /** fp-setup phase 1 (16-byte request) → fixed 142-byte reply (version- and mode-specific). */
    fun setup(req: ByteArray): ByteArray {
        require(req.size == 16) { "fp-setup phase 1 expects 16 bytes, got ${req.size}" }
        val version = req[4].toInt() and 0xFF
        val mode = req[14].toInt() and 0xFF
        require(mode in 0..3) { "invalid fp-setup mode $mode" }
        negotiatedVersion = version
        return when (version) {
            // v3 (mirroring / Safari): one captured reply per mode, used verbatim.
            0x03 -> REPLIES_V3[mode].copyOf()
            // v2 (Apple Music / iTunes audio): a single captured reply; patch the mode indicator
            // at offset 13 with the sender's mode so the sender accepts our challenge response.
            0x02 -> REPLY_V2.copyOf().also { it[13] = req[14] }
            else -> throw IllegalArgumentException("unsupported FairPlay version $version")
        }
    }

    /** fp-setup phase 2 (164-byte request) → fixed 12-byte header + bytes [144,164) = 32 bytes. */
    fun handshake(req: ByteArray): ByteArray {
        require(req.size == 164) { "fp-setup phase 2 expects 164 bytes, got ${req.size}" }
        val version = req[4].toInt() and 0xFF
        require(version == 0x03 || version == 0x02) { "unsupported FairPlay version $version" }
        keyMessage = req.copyOf()
        // The phase-2 header is the FIXED `FPLY 03 01 04 00 00 00 00 14` for BOTH protocol versions
        // (shairplay fairplay_v2 `fp_header`, used unchanged for v2 and v3). The sender wraps the audio
        // key (fpaeskey / ekey) from this reply, so echoing 0x02 here for v2 senders produced a wrong
        // key — and silence — for every Music.app session. The trailing 20 bytes echo the request.
        return FP_HEADER.copyOf() + req.copyOfRange(144, 164)
    }

    /** Decrypts the 72-byte FairPlay-wrapped key (the SETUP `ekey` / SDP `fpaeskey`) into the 16-byte AES key. */
    fun decrypt(ekey: ByteArray): ByteArray {
        val km = keyMessage ?: error("fp-setup handshake must complete before decrypt")
        require(ekey.size == 72) { "FairPlay ekey must be 72 bytes, got ${ekey.size}" }
        ensureNativeLoaded()
        return nativePlayfairDecrypt(km, ekey)
    }

    private external fun nativePlayfairDecrypt(keyMessage: ByteArray, cipher: ByteArray): ByteArray

    companion object {
        @Volatile private var nativeLoaded = false

        private fun ensureNativeLoaded() {
            if (!nativeLoaded) synchronized(this) {
                if (!nativeLoaded) { System.loadLibrary("playfair"); nativeLoaded = true }
            }
        }

        private fun hex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private val FP_HEADER = hex("46504c590301040000000014")

        // v3 phase-1 replies, one per mode (req[14] = 0..3). Used verbatim.
        private val REPLIES_V3 = arrayOf(
            hex("46504c59030102000000008202000f9f3f9e0a2521dbdf312ab2bfb29e8d232b6376a8c818701d22ae93d82737feaf9db4fdf41c2dba9d1f49caaabf6591ac1f7bc6f7e0663d21afe01565953eab81f418ceed095adb7c3d0e254909a79831d49c3982973434facb42c63a1cd911a6fe941a8a6d4a743b46c3a7649e44c78955e49d8155009549c4e2f7a3f6d5ba"),
            hex("46504c5903010200000000820201cf32a25714b2524f8aa0ad7af164e37bcf4424e200047efc0ad67afcd95ded1c2730bb591b962ed63a9c4ded88ba8fc78de64d91ccfd5c7b56da88e31f5cceafc7431995a01665a54e1939d25b94db64b9e45d8d063e1e6af07e9656162b0efa404275ea5a44d9591c7256b9fbe6513898b80227721988571650942ad946688a"),
            hex("46504c5903010200000000820202c169a352eeed35b18cdd9c58d64f16c1519a89eb5317bd0d4336cd68f638ff9d016a5b52b7fa9216b2b65482c78444118121a2c7fed83db7119e9182aad7d18c7063e2a457555910af9e0efc76347d164043807f581ee4fbe42ca9dedc1b5eb2a3aa3d2ecd59e7eee70b3629f22afd161d877353ddb99adc8e07006e56f850ce"),
            hex("46504c59030102000000008202039001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e93045251afb97eb9fc0011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a17f481487e3ae863c678325422e6f78e166d18aa7fd636258bce28726f661f738893ce44311e4be6c0535193e5ef72e8686233729c227d820c999445d89246c8c359")
        )

        // v2 phase-1 reply (Apple Music / iTunes audio). Single 142-byte capture; offset 13 is the
        // mode indicator, patched per request. Source: joerg-krause/shairplay @ fairplay_v2.
        private val REPLY_V2 = hex(
            "46504c59020102000000008202022f7b69e6b27ebbf0685f98547f37cecf8706996e7e6b0fb2fa712053e39483da22c783a072404ddd41aa3d4c6e302255aaa2da1eb477838c79d56517c3fa0154339ee3829f30f0a48f76df77117e569ef395e8e213b31eb670ec5a8af26afcbc8931e67ee8b9c5f2c71d78f3ef8d61f73bcc17c34023524a8b9cb1750566e6b3"
        )
    }
}
