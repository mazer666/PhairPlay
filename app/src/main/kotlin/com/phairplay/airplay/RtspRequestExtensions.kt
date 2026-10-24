package com.phairplay.airplay

/**
 * Small helpers that every RTSP handler needs to look at a parsed [RtspRequest].
 *
 * WHAT: Three top-level extensions on [RtspRequest] — "is this the photo endpoint?", "which
 * protocol line do I answer with?" and "is the body an Apple binary plist?".
 *
 * WHY: These checks are needed by several handlers (the photo endpoint, the AirPlay 2 handshake,
 * the mirror SETUP, the URL-video mode). As top-level extensions in the same package every
 * handler can use them, while the RTSP classes stay free of copy-pasted string checks.
 *
 * Example:
 *   val isMirrorSetup = request.isPlistBody()   // true for an AirPlay 2 mirror SETUP
 */
internal fun RtspRequest.isPhotoRequest(): Boolean =
    uri.substringBefore("?") == PhotoHandler.PHOTO_PATH

internal fun RtspRequest.responseProtocol(): String =
    if (protocol.startsWith("HTTP/")) protocol else "RTSP/1.0"

/** True if the body is an Apple binary plist (AirPlay 2 mirroring SETUP), vs legacy SDP. */
internal fun RtspRequest.isPlistBody(): Boolean =
    bodyBytes.size >= 8 && String(bodyBytes, 0, 8, Charsets.US_ASCII) == "bplist00"
