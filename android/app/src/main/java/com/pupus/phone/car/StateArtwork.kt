package com.pupus.phone.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.pupus.phone.R
import com.pupus.phone.ui.PttStyle

/**
 * Media-card artwork: the state colour as background with the microphone on top, the same colour
 * code as the floating bubble. One bitmap per style, built on first use.
 */
class StateArtwork(private val context: Context) {
    private val cache = HashMap<PttStyle, Bitmap>()

    fun of(style: PttStyle): Bitmap = cache.getOrPut(style) { draw(style) }

    private fun draw(style: PttStyle): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(context.getColor(style.colorRes))
        val mic = requireNotNull(context.getDrawable(R.drawable.ic_mic)) { "ic_mic missing" }.mutate()
        mic.setTint(context.getColor(style.iconColorRes))
        val inset = (SIZE_PX - ICON_PX) / 2
        mic.setBounds(inset, inset, inset + ICON_PX, inset + ICON_PX)
        mic.draw(canvas)
        return bitmap
    }

    private companion object {
        const val SIZE_PX = 480
        const val ICON_PX = 288
    }
}
