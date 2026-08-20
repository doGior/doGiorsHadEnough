package it.dogior.hadEnough.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

internal class StreamCenterTvTouchCursor(
    private val overlay: FrameLayout,
    private val target: View,
    accentColor: Int = Color.parseColor("#22C55E"),
) {
    private val density = overlay.resources.displayMetrics.density
    private fun dp(value: Float): Float = value * density

    private val diameter = dp(26f).toInt()
    private val slowStep = dp(12f)
    private val fastStep = dp(34f)

    private var cursorX = 0f
    private var cursorY = 0f

    private val dot = View(overlay.context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((accentColor and 0x00FFFFFF) or 0x55000000)
            setStroke(dp(2f).toInt(), accentColor)
        }
        layoutParams = FrameLayout.LayoutParams(diameter, diameter)
        isClickable = false
        isFocusable = false
        visibility = View.GONE
    }

    var isActive: Boolean = false
        private set

    var onStateChanged: ((Boolean) -> Unit)? = null

    init {
        overlay.addView(dot) 
    }

    fun toggle() {
        if (isActive) deactivate() else activate()
    }

    fun activate() {
        if (isActive) return
        isActive = true
        overlay.post {
            val w = overlay.width.takeIf { it > 0 } ?: dp(300f).toInt()
            val h = overlay.height.takeIf { it > 0 } ?: dp(300f).toInt()
            cursorX = w / 2f
            cursorY = h / 2f
            dot.visibility = View.VISIBLE
            updateDot()
        }
        onStateChanged?.invoke(true)
    }

    fun deactivate() {
        if (!isActive) return
        isActive = false
        dot.visibility = View.GONE
        onStateChanged?.invoke(false)
    }

    fun onKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        if (!isActive) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val step = if (event.repeatCount >= 2) fastStep else slowStep
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> cursorY -= step
                        KeyEvent.KEYCODE_DPAD_DOWN -> cursorY += step
                        KeyEvent.KEYCODE_DPAD_LEFT -> cursorX -= step
                        KeyEvent.KEYCODE_DPAD_RIGHT -> cursorX += step
                    }
                    clampToBounds()
                    updateDot()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) performTap()
                true
            }
            else -> false
        }
    }

    private fun clampToBounds() {
        val radius = diameter / 2f
        val w = overlay.width.takeIf { it > 0 } ?: return
        val h = overlay.height.takeIf { it > 0 } ?: return
        cursorX = cursorX.coerceIn(radius, w - radius)
        cursorY = cursorY.coerceIn(radius, h - radius)
    }

    private fun updateDot() {
        dot.translationX = cursorX - diameter / 2f
        dot.translationY = cursorY - diameter / 2f
    }

    private fun performTap() {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0)
        val up = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, cursorX, cursorY, 0)
        try {
            target.dispatchTouchEvent(down)
            target.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        dot.animate().scaleX(0.6f).scaleY(0.6f).setDuration(60L).withEndAction {
            dot.animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
        }.start()
    }
}
