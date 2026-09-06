package com.phairplay.dlna

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
import com.phairplay.R
import com.phairplay.util.Logger
import java.io.ByteArrayOutputStream

/**
 * DlnaIcon — renders the launcher icon to the PNG bytes advertised in the device description.
 *
 * WHY: Windows fetches the icon while validating a renderer; an unreachable icon is one of the reasons a
 * device silently fails to appear in "Cast to Device".
 *
 * HOW: `DlnaIcon.png(context)` once at receiver start; cache the bytes.
 */
object DlnaIcon {
    fun png(context: Context): ByteArray {
        val size = DlnaConstants.ICON_SIZE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val drawable = AppCompatResources.getDrawable(context, R.mipmap.ic_launcher)
            if (drawable != null) {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(bitmap))
            }
        } catch (e: Exception) {
            Logger.w("DlnaIcon: could not draw launcher icon, serving a blank PNG: ${e.message}")
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private const val PNG_QUALITY = 100
}
