package it.dogior.hadEnough.settings

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import kotlin.math.abs

internal class StreamCenterResetHold {
    companion object { const val DURATION_MS = 5_000L }
    private var startedAt: Long? = null
    private var completed = false

    fun begin(now: Long) {
        if (startedAt != null) return
        startedAt = now
        completed = false
    }

    fun completeIfReady(now: Long): Boolean {
        val start = startedAt ?: return false
        if (completed || now - start < DURATION_MS) return false
        completed = true
        return true
    }

    fun release(): Boolean {
        val click = startedAt != null && !completed
        cancel()
        return click
    }

    fun cancel() { startedAt = null; completed = false }
}

private class StreamCenterHoldProgressDrawable(color: Int, private val density: Float) : Drawable() {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = 60 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val line = RectF()
    var progress = 0f
        set(value) { field = value; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        if (progress <= 0f) return
        val inset = 16 * density
        val height = 4 * density
        val bottom = bounds.bottom - 6 * density
        line.set(bounds.left + inset, bottom - height, bounds.right - inset, bottom)
        canvas.drawRoundRect(line, height, height, track)
        line.right = line.left + line.width() * progress
        canvas.drawRoundRect(line, height, height, fill)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

@SuppressLint("ClickableViewAccessibility")
internal fun bindCompleteResetHold(view: View, color: Int, onComplete: () -> Unit) {
    val hold = StreamCenterResetHold()
    val indicator = StreamCenterHoldProgressDrawable(color, view.resources.displayMetrics.density)
    view.foreground = indicator
    val progress = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = StreamCenterResetHold.DURATION_MS
        interpolator = LinearInterpolator()
        addUpdateListener { indicator.progress = it.animatedValue as Float }
    }
    val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    var keyboard = false
    val finish = Runnable {
        if (view.isAttachedToWindow && view.hasWindowFocus() && (!keyboard || view.hasFocus()) &&
            hold.completeIfReady(SystemClock.uptimeMillis())) {
            view.isPressed = false
            progress.cancel()
            indicator.progress = 0f
            onComplete()
        }
    }
    fun cancel() {
        view.removeCallbacks(finish)
        hold.cancel()
        view.isPressed = false
        progress.cancel()
        indicator.progress = 0f
    }
    fun begin() {
        hold.begin(SystemClock.uptimeMillis())
        view.isPressed = true
        if (!progress.isRunning) progress.start()
        view.postDelayed(finish, StreamCenterResetHold.DURATION_MS)
    }
    fun release() {
        finish.run()
        view.removeCallbacks(finish)
        view.isPressed = false
        progress.cancel()
        indicator.progress = 0f
        if (hold.release()) view.performClick()
    }
    view.isLongClickable = false
    view.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                keyboard = false
                downX = event.x
                downY = event.y
                begin()
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) cancel()
            MotionEvent.ACTION_UP -> release()
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancel()
        }
        true
    }
    view.setOnKeyListener { _, key, event ->
        if (key !in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)) return@setOnKeyListener false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) { keyboard = true; begin() }
            KeyEvent.ACTION_UP -> if (event.isCanceled) cancel() else release()
        }
        true
    }
    val focusChanged = ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
        if (old === view && new !== view) cancel()
    }
    val windowChanged = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) cancel() }
    fun observeFocus() {
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusChanged)
        view.viewTreeObserver.addOnWindowFocusChangeListener(windowChanged)
    }
    if (view.isAttachedToWindow) observeFocus()
    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = observeFocus()
        override fun onViewDetachedFromWindow(v: View) {
            cancel()
            if (v.viewTreeObserver.isAlive) {
                v.viewTreeObserver.removeOnGlobalFocusChangeListener(focusChanged)
                v.viewTreeObserver.removeOnWindowFocusChangeListener(windowChanged)
            }
        }
    })
}
