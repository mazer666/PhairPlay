package com.phairplay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.phairplay.R
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.util.Logger
import com.phairplay.util.SafeBitmapDecoder

/**
 * NowPlayingScreen — full-screen card shown while AirPlay audio plays without video (system audio
 * from a Mac, Apple Music, podcasts). Mirroring shows the video [StreamingScreen] instead; this
 * screen fills the otherwise-black surface for audio-only sessions.
 *
 * Layout (centered): album art (or an AirPlay-glyph placeholder when the sender sends no artwork),
 * track title, artist, album, and a "♪ Audio from <sender>" footer. Built programmatically to match
 * the other overlay views ([PhotoScreen]/[StreamingScreen]).
 */
class NowPlayingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val artwork: ImageView
    private val titleView: TextView
    private val artistView: TextView
    private val albumView: TextView
    private val senderView: TextView

    init {
        setBackgroundColor(color(R.color.background_dark))

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        artwork = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ART_SIZE_DP), dp(ART_SIZE_DP))
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Rounded surface so the placeholder glyph (and any non-square art) sits on a card.
            background = GradientDrawable().apply {
                setColor(color(R.color.background_surface))
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
        }

        titleView = textView(TITLE_SP, R.color.text_primary, bold = true).apply {
            setPadding(0, dp(28), 0, 0)
        }
        artistView = textView(ARTIST_SP, R.color.text_secondary).apply {
            setPadding(0, dp(8), 0, 0)
        }
        albumView = textView(ALBUM_SP, R.color.text_tertiary).apply {
            setPadding(0, dp(4), 0, 0)
        }
        senderView = textView(SENDER_SP, R.color.protocol_airplay).apply {
            setPadding(0, dp(36), 0, 0)
        }

        column.addView(artwork)
        column.addView(titleView)
        column.addView(artistView)
        column.addView(albumView)
        column.addView(senderView)

        addView(
            column,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )
    }

    /** Updates the card to reflect [info]. Falls back to a placeholder glyph when no artwork. */
    fun update(info: NowPlayingInfo) {
        // The artwork comes from the sender and may announce a picture that is huge
        // when decoded (audit finding SEC-02). SafeBitmapDecoder refuses anything
        // outside the decode budget instead of allocating it.
        val bitmap = info.artwork?.let { SafeBitmapDecoder.decode(it) }
        if (bitmap != null) {
            artwork.scaleType = ImageView.ScaleType.CENTER_CROP
            artwork.setImageBitmap(bitmap)
            artwork.setColorFilter(null)
        } else {
            // No artwork from the sender (typical for raw system audio) — show the AirPlay glyph.
            artwork.scaleType = ImageView.ScaleType.CENTER_INSIDE
            artwork.setImageResource(R.drawable.ic_airplay)
            artwork.setColorFilter(color(R.color.text_secondary))
            // The artwork was there but could not be decoded — that is worth a line in
            // the log. The bitmap is null in this branch anyway, so only the sender's
            // bytes are checked.
            if (info.artwork != null) {
                Logger.w("NowPlayingScreen: artwork bytes (${info.artwork.size}B) failed to decode")
            }
        }

        titleView.text = info.title ?: context.getString(R.string.now_playing_audio)
        artistView.setTextVisible(info.artist)
        albumView.setTextVisible(info.album)
        senderView.text = context.getString(R.string.now_playing_from, info.senderName)
    }

    /** Releases the (potentially large) artwork bitmap when the card is hidden. */
    fun clear() {
        artwork.setImageDrawable(null)
    }

    private fun TextView.setTextVisible(value: String?) {
        if (value.isNullOrBlank()) {
            visibility = View.GONE
        } else {
            text = value
            visibility = View.VISIBLE
        }
    }

    private fun textView(sizeSp: Float, colorRes: Int, bold: Boolean = false) = TextView(context).apply {
        setTextColor(color(colorRes))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
        maxLines = 2
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun color(res: Int): Int = try { context.getColor(res) } catch (_: Exception) { Color.WHITE }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ART_SIZE_DP = 360
        private const val TITLE_SP = 30f
        private const val ARTIST_SP = 22f
        private const val ALBUM_SP = 17f
        private const val SENDER_SP = 16f
    }
}
