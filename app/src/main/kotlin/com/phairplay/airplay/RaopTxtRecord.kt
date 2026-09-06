package com.phairplay.airplay

/**
 * RaopTxtRecord — builds the `_raop._tcp` mDNS service name and TXT attributes.
 *
 * WHY: macOS decides which audio flow to use from this record. A record with only the AirPlay 1
 * keys (`cn`, `et`, `tp`, …) makes Music.app run the legacy ANNOUNCE/SDP flow with FairPlay v2,
 * whose key unwrap this receiver gets wrong (silence). Adding the AirPlay 2 keys — the `ft`
 * features flags, the Ed25519 `pk`, `sf`, `vv`, `txtvers`, `ch`/`sr`/`ss` — makes Music.app use
 * the same fp-setup v3 + plist `SETUP` flow that macOS system audio already uses successfully
 * (verified on-device 2026-09-06). UxPlay publishes exactly this shape and plays Music.app audio.
 *
 * HOW: Pure Kotlin, no Android imports, so the record is unit-tested in the JVM test-runner.
 * [MdnsService] applies [Record.attributes] to an `NsdServiceInfo`.
 */
object RaopTxtRecord {

    data class Record(val serviceName: String, val attributes: Map<String, String>)

    /**
     * @param macHex      Device MAC with colons removed, upper-case (e.g. `AABBCCDDEEFF`).
     * @param displayName Name shown in sender pickers.
     * @param features    AirPlay features flags as advertised in `_airplay._tcp` (`"0x…,0x…"`).
     * @param publicKeyHex Lower-case hex of the receiver's 32-byte Ed25519 pairing public key.
     */
    fun build(macHex: String, displayName: String, features: String, publicKeyHex: String): Record =
        Record(
            serviceName = "$macHex@$displayName",
            attributes = mapOf(
                "txtvers" to "1",
                "ch" to CHANNELS,
                "cn" to CODECS,
                "da" to "true",
                "et" to ENCRYPTION_TYPES,
                "ft" to features,
                "md" to METADATA_TYPES,
                "pk" to publicKeyHex,
                "sf" to STATUS_FLAGS,
                "sr" to SAMPLE_RATE,
                "ss" to SAMPLE_SIZE,
                "sv" to "false",
                "tp" to "UDP",
                "vn" to RAOP_VERSION,
                "vs" to SERVER_VERSION,
                "vv" to "2",
                "am" to MODEL
            )
        )

    private const val CHANNELS = "2"

    /** Codec numbers: 0 PCM, 1 ALAC, 2 AAC, 3 AAC-ELD. */
    private const val CODECS = "0,1,2,3"

    /** Encryption types: 0 none, 3 FairPlay, 5 FairPlay SAPv2.5 — the reference (UxPlay) set. */
    private const val ENCRYPTION_TYPES = "0,3,5"

    /** Metadata types: 0 text, 1 artwork, 2 progress. */
    private const val METADATA_TYPES = "0,1,2"

    /** Status flags: 0x4 = screen-mirroring receiver, matching `_airplay._tcp` `flags`. */
    private const val STATUS_FLAGS = "0x4"
    private const val SAMPLE_RATE = "44100"
    private const val SAMPLE_SIZE = "16"
    private const val RAOP_VERSION = "65537"

    /** Must match [MdnsService]'s `_airplay._tcp` `srcvers` and `model`. */
    private const val SERVER_VERSION = "220.68"
    private const val MODEL = "AppleTV5,3"
}
