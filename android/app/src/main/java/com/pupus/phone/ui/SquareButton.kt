package com.pupus.phone.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.Button

/** Button that stays square (min of the available width and height) so the oval is a circle. */
class SquareButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Button(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val side = if (height == 0) width else minOf(width, height)
        val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }
}
