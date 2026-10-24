package com.phairplay.service

/**
 * PhotoFrame — the latest still image received via AirPlay `/photo`.
 *
 * WHAT: A data class that carries the raw bytes of a JPEG/PNG sent by an Apple device
 * together with its MIME type and the moment it arrived.
 *
 * WHY: The bytes are kept in memory only — no copy is written to disk — and the frame is
 * cleared on DELETE `/photo`, on streaming start, on receiver stop and on service
 * destruction. Keeping the payload in one small, immutable value object makes that
 * lifetime easy to see.
 *
 * HOW: [PhairPlayService] publishes it through the `photoFrame` state flow and the bound
 * Activity renders it in the photo screen.
 *
 * Example:
 *   val frame = PhotoFrame(bytes = jpegBytes, mimeType = "image/jpeg")
 *   service.photoFrame.value?.bytes?.size   // size of the received picture
 */
data class PhotoFrame(
    val bytes: ByteArray,
    val mimeType: String,
    val receivedAtMillis: Long = System.currentTimeMillis()
)
