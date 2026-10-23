package com.phairplay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import com.phairplay.airplay.StreamStats
import com.phairplay.util.Logger

/**
 * Compatibility display experiment: keep the TextureView's layout fixed while the stream rotates.
 * Aspect fitting changes only its presentation matrix, so the decoder's Surface is not replaced
 * by changing the child layout dimensions. The existing codec and transport remain unchanged.
 */
class StreamingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val textureView = TextureView(context).apply { isOpaque = false }
    @Volatile private var surface: Surface? = null
    private val debugView = TextView(context).apply {
        setTextColor(Color.parseColor("#FF00FF66"))
        setBackgroundColor(Color.parseColor("#A6000000"))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setPadding(24, 16, 24, 16)
        visibility = GONE
    }
    private var lastDimensions: List<Int> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            applyAspectFit()
            if (StreamStats.overlayEnabled) {
                debugView.visibility = VISIBLE
                debugView.text = StreamStats.summary()
            } else {
                debugView.visibility = GONE
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(debugView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START; topMargin = 48; leftMargin = 48
        })
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(texture)
                lastDimensions = emptyList()
                applyAspectFit()
                Logger.d("StreamingScreen: texture available ${width}x${height}")
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                lastDimensions = emptyList()
                applyAspectFit()
            }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val oldSurface = surface
                surface = null
                oldSurface?.release()
                Logger.d("StreamingScreen: texture destroyed")
                return true
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                StreamStats.videoTextureUpdates.incrementAndGet()
            }
        }
    }

    fun getSurface(): Surface? = surface

    private fun applyAspectFit() {
        StreamStats.displayRefreshHz = display?.refreshRate ?: 0f
        if (width <= 0 || height <= 0) return
        val dimensions = listOf(StreamStats.videoWidth, StreamStats.videoHeight, width, height)
        if (dimensions == lastDimensions) return
        lastDimensions = dimensions
        val (sx, sy) = VideoAspectFit.scale(dimensions[0], dimensions[1], width, height)
        textureView.setTransform(Matrix().apply {
            setScale(sx, sy, width / 2f, height / 2f)
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }
    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
    companion object { private const val REFRESH_MS = 200L }
}
