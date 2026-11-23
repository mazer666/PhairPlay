package com.phairplay.airplay.handshake

import android.content.Context
import com.phairplay.util.NetworkUtils

/**
 * InfoResponder — builds the binary-plist body for `GET /info`, the first request a macOS
 * AirPlay sender makes. It advertises the receiver's identity and capability bits so the
 * sender knows to continue with pairing → FairPlay → mirroring.
 *
 * Values are kept consistent with what [com.phairplay.airplay.MdnsService] advertises so the
 * sender sees one coherent device.
 *
 * NOTE: the Ed25519 public key (`pk`) is added in the pairing phase once a persistent
 * identity exists; macOS still proceeds to pair-setup without it.
 */
object InfoResponder {

    fun build(context: Context, width: Int = 1920, height: Int = 1080, pinRequired: Boolean = false): ByteArray {
        val mac = NetworkUtils.getAdvertisedMac(context)
        // When PIN access control is on, set the "pairing/PIN required" status bit so the sender runs
        // the SRP pair-setup flow. NOTE: exact flag semantics are sender-version-dependent — verify
        // against macOS and adjust if pairing doesn't trigger.
        val statusFlags = if (pinRequired) STATUS_FLAGS or STATUS_FLAG_PIN_REQUIRED else STATUS_FLAGS
        val info = mapOf(
            "deviceID" to mac,
            "macAddress" to mac,
            "features" to AIRPLAY_FEATURES,
            "statusFlags" to statusFlags,
            "model" to MODEL,
            "name" to NetworkUtils.getDeviceName(context),
            "sourceVersion" to SOURCE_VERSION,
            "pi" to NetworkUtils.getPersistentUuid(context),
            "pk" to PairingKeys.get(context).edPublic,
            "vv" to 2L,
            "protovers" to "1.1",
            "keepAliveLowPower" to true,
            "keepAliveSendStatsAsBody" to true,
            // NOTE: macOS IGNORES this for system-audio AirPlay — it sends ALAC (ct=2) regardless of
            // what we advertise (verified: advertising AAC-only still got ALAC). So we keep the broad
            // set (mirroring negotiates AAC-ELD from it, which works). Audio-only would need a
            // software ALAC decoder since this TV has no hardware ALAC codec.
            "audioFormats" to listOf(
                mapOf("type" to 100L, "audioInputFormats" to 67108860L, "audioOutputFormats" to 67108860L),
                mapOf("type" to 101L, "audioInputFormats" to 67108860L, "audioOutputFormats" to 67108860L)
            ),
            "audioLatencies" to listOf(
                mapOf("type" to 100L, "audioType" to "default", "inputLatencyMicros" to 0L, "outputLatencyMicros" to 0L),
                mapOf("type" to 101L, "audioType" to "default", "inputLatencyMicros" to 0L, "outputLatencyMicros" to 0L)
            ),
            // Screen the sender can mirror to — without this, macOS aborts after key setup.
            "displays" to listOf(
                mapOf(
                    "uuid" to "e0ff8a27-6738-3d56-8a16-cc53aacee925",
                    "widthPhysical" to 0L,
                    "heightPhysical" to 0L,
                    "width" to width.toLong(),
                    "height" to height.toLong(),
                    "widthPixels" to width.toLong(),
                    "heightPixels" to height.toLong(),
                    "rotation" to false,
                    "refreshRate" to (1.0 / 60.0),
                    "overscanned" to false,   // false = macOS uses the full advertised resolution
                    "features" to 14L
                )
            )
        )
        return PlistCodec.encode(info)
    }

    /** 64-bit features value; mirrors MdnsService's "0x5A7FFFF7,0x1E" (low,high 32-bit halves). */
    private const val AIRPLAY_FEATURES = 0x1E5A7FFFF7L

    /** Matches RPiPlay's /info statusFlags (0x44). */
    private const val STATUS_FLAGS = 68L

    /** Status bit advertising that the receiver requires PIN pairing (0x8 — verify vs macOS). */
    private const val STATUS_FLAG_PIN_REQUIRED = 0x8L

    private const val MODEL = "AppleTV5,3"
    private const val SOURCE_VERSION = "220.68"
}
