package com.phairplay.airplay

/**
 * NowPlayingInfo — what the audio-only "now playing" screen shows while audio (and no video) is
 * streaming. [senderName] is always present; the track metadata and [artwork] are filled in only
 * when the sender pushes them (Apple Music / Podcasts do; raw system audio from Chrome does not, so
 * the screen falls back to a generic "Audio from <sender>" card).
 */
data class NowPlayingInfo(
    val senderName: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: ByteArray? = null,
) {
    /** True when the sender supplied at least a track title (vs. a bare "audio is playing" state). */
    val hasMetadata: Boolean get() = !title.isNullOrBlank()

    // ByteArray needs hand-written equals/hashCode for value semantics in a StateFlow.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NowPlayingInfo) return false
        return senderName == other.senderName &&
            title == other.title &&
            artist == other.artist &&
            album == other.album &&
            artwork.contentEquals(other.artwork)
    }

    override fun hashCode(): Int {
        var result = senderName.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (artwork?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * DmapParser — minimal reader for the DMAP/DAAP "now playing" metadata macOS sends in a binary
 * `SET_PARAMETER` body (`Content-Type: application/x-dmap-tagged`).
 *
 * DMAP is a flat TLV stream: a 4-byte ASCII tag, a 4-byte big-endian length, then that many payload
 * bytes. Container tags (e.g. `mlit`, a listing item) hold nested tags. We only need three string
 * fields — track ([minm]), artist ([asar]), album ([asal]) — so we walk the tree and pick them out
 * wherever they appear, ignoring everything else.
 *
 * Reference: DAAP/DMAP content codes (minm/asar/asal) used by AirPlay now-playing.
 */
object DmapParser {

    private val CONTAINER_TAGS = setOf("mlit", "mlcl", "mshl", "adbs")
    private const val TRACK_TAG = "minm"
    private const val ARTIST_TAG = "asar"
    private const val ALBUM_TAG = "asal"

    /**
     * Metadata — the now-playing fields the DMAP parser extracted from a binary body.
     *
     * WHY: `parseNowPlaying` walks a mutable tag tree, so it must hand the caller a
     * value that cannot change afterwards. The mutable accumulator is therefore
     * converted into this immutable result at the end of the walk.
     *
     * Example:
     *   Metadata(title = "Song", artist = "Band", album = "Album")
     */
    data class Metadata(val title: String?, val artist: String?, val album: String?) {
        val isEmpty: Boolean get() = title == null && artist == null && album == null
    }

    fun parseNowPlaying(body: ByteArray): Metadata {
        val acc = MutableMetadata()
        runCatching { walk(body, 0, body.size, acc, depth = 0) }
        return Metadata(acc.title, acc.artist, acc.album)
    }

    /**
     * MutableMetadata — the accumulator the DMAP walk fills while it reads the tag tree.
     *
     * WHY: every field can appear in any container and the walk visits them in file
     * order, so the values are collected in a mutable holder and turned into the
     * immutable [Metadata] once the walk has finished.
     */
    private class MutableMetadata {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
    }

    private fun walk(buf: ByteArray, start: Int, end: Int, acc: MutableMetadata, depth: Int) {
        if (depth > MAX_DEPTH) return
        var i = start
        while (i + 8 <= end) {
            val tag = String(buf, i, 4, Charsets.US_ASCII)
            val len = readBE32(buf, i + 4)
            val payloadStart = i + 8
            // Malformed/overrun length → stop scanning this level rather than reading out of bounds.
            if (len < 0 || payloadStart + len > end) break
            when (tag) {
                TRACK_TAG  -> if (acc.title == null)  acc.title  = utf8(buf, payloadStart, len)
                ARTIST_TAG -> if (acc.artist == null) acc.artist = utf8(buf, payloadStart, len)
                ALBUM_TAG  -> if (acc.album == null)  acc.album  = utf8(buf, payloadStart, len)
                in CONTAINER_TAGS -> walk(buf, payloadStart, payloadStart + len, acc, depth + 1)
                else -> { /* unknown tag — skip its payload */ }
            }
            i = payloadStart + len
        }
    }

    private fun utf8(buf: ByteArray, start: Int, len: Int): String? =
        String(buf, start, len, Charsets.UTF_8).trim().ifEmpty { null }

    private fun readBE32(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
        ((buf[off + 1].toInt() and 0xFF) shl 16) or
        ((buf[off + 2].toInt() and 0xFF) shl 8) or
        (buf[off + 3].toInt() and 0xFF)

    private const val MAX_DEPTH = 8
}
