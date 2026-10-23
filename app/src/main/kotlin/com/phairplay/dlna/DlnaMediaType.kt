package com.phairplay.dlna

import java.util.Locale

/**
 * Lightweight media classification for DLNA URLs before handing them to Android playback.
 */
object DlnaMediaType {
    fun imageMimeFromUrl(uri: String): String? {
        val path = uri.substringBefore('?').lowercase(Locale.US)
        return when {
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".png") -> "image/png"
            else -> null
        }
    }
}
