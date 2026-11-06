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
    accentColor: Int = Color.parseColor(COLOR_ACCENT),
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
    var onLeaveToControls: (() -> Unit)? = null

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
                    val (dx, dy) = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> 0f to -step
                        KeyEvent.KEYCODE_DPAD_DOWN -> 0f to step
                        KeyEvent.KEYCODE_DPAD_LEFT -> -step to 0f
                        else -> step to 0f
                    }
                    val wantedX = cursorX + dx
                    val wantedY = cursorY + dy
                    cursorX = wantedX
                    cursorY = wantedY
                    clampToBounds()
                    scrollTargetPastEdge(wantedX - cursorX, wantedY - cursorY, step)
                    updateDot()
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                        cursorY >= overlay.height - diameter / 2f && !target.canScrollVertically(1)
                    ) {
                        deactivate()
                        onLeaveToControls?.invoke()
                    }
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

    private fun scrollTargetPastEdge(overflowX: Float, overflowY: Float, step: Float) {
        val horizontal = overflowX.compareTo(0f)
        val vertical = overflowY.compareTo(0f)
        val scrollX = if (horizontal != 0 && target.canScrollHorizontally(horizontal)) horizontal * step else 0f
        val scrollY = if (vertical != 0 && target.canScrollVertically(vertical)) vertical * step else 0f
        if (scrollX != 0f || scrollY != 0f) target.scrollBy(scrollX.toInt(), scrollY.toInt())
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
    }
}
