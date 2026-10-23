package it.dogior.hadEnough.subtitles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

@SuppressLint("ViewConstructor")
internal class AssOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var images: Array<AssImage>? = null
    private var renderWidth = 0
    private var renderHeight = 0

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(frame: Array<AssImage>?, width: Int, height: Int) {
        images = frame
        renderWidth = width
        renderHeight = height
        invalidate()
    }

    fun clear() {
        if (images == null) return
        images = null
        invalidate()
    }

    fun mirrorTransform(source: View?) {
        source ?: return
        if (scaleX != source.scaleX) scaleX = source.scaleX
        if (scaleY != source.scaleY) scaleY = source.scaleY
        if (translationX != source.translationX) translationX = source.translationX
        if (translationY != source.translationY) translationY = source.translationY
    }

    override fun onDraw(canvas: Canvas) {
        val frame = images ?: return
        if (renderWidth <= 0 || renderHeight <= 0) return
        val save = canvas.save()
        canvas.scale(width / renderWidth.toFloat(), height / renderHeight.toFloat())
        frame.forEach { image ->
            paint.color = libassColor(image.color)
            canvas.drawBitmap(image.bitmap, image.x.toFloat(), image.y.toFloat(), paint)
        }
        canvas.restoreToCount(save)
    }

    private fun libassColor(rgba: Int): Int {
        val red = rgba ushr 24 and 0xFF
        val green = rgba ushr 16 and 0xFF
        val blue = rgba ushr 8 and 0xFF
        val alpha = 0xFF - (rgba and 0xFF)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
