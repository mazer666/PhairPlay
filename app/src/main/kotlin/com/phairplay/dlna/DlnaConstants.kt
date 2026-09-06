package com.phairplay.dlna

/**
 * DlnaConstants — every fixed number and protocol string the DLNA renderer relies on.
 *
 * WHY: CONTRIBUTING RULE 4 forbids magic numbers and ports scattered through the code. Keeping them
 * here also documents which values are protocol-mandated (SSDP address/port) and which are ours
 * (HTTP port, caps, intervals).
 *
 * HOW: `DlnaConstants.SSDP_PORT`, etc. Never inline these values elsewhere.
 */
object DlnaConstants {
    /** SSDP multicast group and port — fixed by the UPnP Device Architecture. */
    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900

    /** Our HTTP port for description/control/eventing; falls back to an OS-assigned port if taken. */
    const val DEFAULT_HTTP_PORT = 49494

    /** Per-connection read timeout for the HTTP server. */
    const val HTTP_READ_TIMEOUT_MS = 10_000

    /** Caps on network input (RULE 4). */
    const val MAX_HTTP_BODY_BYTES = 64 * 1024
    const val MAX_PHOTO_BYTES = 20 * 1024 * 1024
    const val PHOTO_TIMEOUT_MS = 10_000

    /** SSDP CACHE-CONTROL max-age; alive NOTIFYs are re-sent well inside it. */
    const val CACHE_MAX_AGE_SECONDS = 1800
    const val ALIVE_INTERVAL_MS = 5 * 60 * 1000L
    const val MAX_SEARCH_DELAY_SECONDS = 3

    /** GENA subscription lifetime, LastChange moderation, delivery limits. */
    const val SUBSCRIPTION_TIMEOUT_SECONDS = 1800
    const val EVENT_MODERATION_MS = 200L
    const val EVENT_DELIVERY_TIMEOUT_MS = 5_000
    const val EVENT_MAX_FAILURES = 2
    const val EXPIRY_SWEEP_MS = 60_000L

    /** Remote-key seek step (fast-forward / rewind). */
    const val SEEK_STEP_MS = 10_000L

    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val SERVER_HEADER = "Android/1.0 UPnP/1.0 PhairPlay/1.0"
    const val DESCRIPTION_PATH = "/description.xml"
    const val ICON_PATH = "/icon.png"
    const val ICON_SIZE_PX = 120
}
