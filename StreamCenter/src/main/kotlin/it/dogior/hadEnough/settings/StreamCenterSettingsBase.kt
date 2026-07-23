package it.dogior.hadEnough.settings

import it.dogior.hadEnough.*

import android.animation.ArgbEvaluator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private class SettingsCardBackgroundDrawable(
    private var fillColor: Int,
    private val accentColor: Int,
    private val strokeColor: Int,
    private val radius: Float,
    private val strokeWidth: Float,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topLightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentBloomPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var drawableAlpha = 255
    private var drawableColorFilter: ColorFilter? = null

    fun setColor(color: Int) {
        fillColor = color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val card = RectF(bounds)
        if (card.isEmpty) return
        val accent = ColorUtils.setAlphaComponent(accentColor, 255)
        val fillStart = ColorUtils.blendARGB(fillColor, accent, 0.045f)
        val fillEnd = ColorUtils.blendARGB(fillColor, Color.BLACK, 0.05f)
        fillPaint.shader = LinearGradient(
            card.left,
            card.top,
            card.right,
            card.bottom,
            intArrayOf(fillStart, fillColor, fillEnd),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        fillPaint.alpha = drawableAlpha
        fillPaint.colorFilter = drawableColorFilter
        canvas.drawRoundRect(card, radius, radius, fillPaint)

        topLightPaint.shader = LinearGradient(
            0f,
            card.top,
            0f,
            card.top + minOf(card.height() * 0.42f, radius * 3f),
            intArrayOf(withAlpha(accent, 24), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
        topLightPaint.colorFilter = drawableColorFilter
        canvas.drawRoundRect(card, radius, radius, topLightPaint)

        accentBloomPaint.shader = RadialGradient(
            card.left + minOf(card.width() * 0.18f, card.height() * 0.72f),
            card.centerY(),
            maxOf(card.height() * 1.1f, card.width() * 0.34f),
            intArrayOf(withAlpha(accent, 34), withAlpha(accent, 8), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        accentBloomPaint.colorFilter = drawableColorFilter
        canvas.drawRoundRect(card, radius, radius, accentBloomPaint)

        strokePaint.strokeWidth = strokeWidth
        strokePaint.color = withAlpha(strokeColor, Color.alpha(strokeColor))
        strokePaint.colorFilter = drawableColorFilter
        canvas.drawRoundRect(
            card.left + strokeWidth / 2f,
            card.top + strokeWidth / 2f,
            card.right - strokeWidth / 2f,
            card.bottom - strokeWidth / 2f,
            (radius - strokeWidth / 2f).coerceAtLeast(0f),
            (radius - strokeWidth / 2f).coerceAtLeast(0f),
            strokePaint,
        )

        fillPaint.shader = null
        topLightPaint.shader = null
        accentBloomPaint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawableColorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android SDK")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun withAlpha(color: Int, alpha: Int): Int {
        return ColorUtils.setAlphaComponent(color, (alpha * drawableAlpha / 255f).toInt())
    }
}

private class SettingsIconBadgeDrawable(
    private val accentColor: Int,
    private val radius: Float,
    private val strokeWidth: Float,
) : Drawable() {
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var drawableAlpha = 255
    private var drawableColorFilter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        val badge = RectF(bounds)
        if (badge.isEmpty) return
        val accent = ColorUtils.setAlphaComponent(accentColor, 255)
        val centerX = badge.centerX()
        val centerY = badge.centerY()
        val glowRadius = maxOf(badge.width(), badge.height()) * 0.78f
        glowPaint.shader = RadialGradient(
            centerX,
            centerY,
            glowRadius,
            intArrayOf(withAlpha(accent, 76), withAlpha(accent, 14), Color.TRANSPARENT),
            floatArrayOf(0f, 0.54f, 1f),
            Shader.TileMode.CLAMP,
        )
        glowPaint.colorFilter = drawableColorFilter
        canvas.drawCircle(centerX, centerY, glowRadius, glowPaint)

        badgePaint.color = withAlpha(accent, 30)
        badgePaint.colorFilter = drawableColorFilter
        canvas.drawRoundRect(badge, radius, radius, badgePaint)

        strokePaint.strokeWidth = strokeWidth
        strokePaint.color = withAlpha(accent, 82)
        strokePaint.colorFilter = drawableColorFilter
        canvas.drawRoundRect(
            badge.left + strokeWidth / 2f,
            badge.top + strokeWidth / 2f,
            badge.right - strokeWidth / 2f,
            badge.bottom - strokeWidth / 2f,
            (radius - strokeWidth / 2f).coerceAtLeast(0f),
            (radius - strokeWidth / 2f).coerceAtLeast(0f),
            strokePaint,
        )
        glowPaint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawableColorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android SDK")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun withAlpha(color: Int, alpha: Int): Int {
        return ColorUtils.setAlphaComponent(color, (alpha * drawableAlpha / 255f).toInt())
    }
}

private class HeaderTitleHaloDrawable(private val accentColor: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawableAlpha = 255
    private var drawableColorFilter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        val halo = RectF(bounds)
        if (halo.isEmpty) return
        val cornerRadius = halo.height() * 0.26f
        paint.color = Color.argb(38, 8, 14, 35)
        paint.colorFilter = drawableColorFilter
        canvas.drawRoundRect(halo, cornerRadius, cornerRadius, paint)
        val radius = maxOf(halo.width(), halo.height()) * 0.72f
        paint.shader = RadialGradient(
            halo.centerX(),
            halo.top + halo.height() * 0.34f,
            radius,
            intArrayOf(
                withAlpha(accentColor, 54),
                withAlpha(accentColor, 12),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.colorFilter = drawableColorFilter
        canvas.drawRoundRect(halo, cornerRadius, cornerRadius, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawableColorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android SDK")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun withAlpha(color: Int, alpha: Int): Int {
        return ColorUtils.setAlphaComponent(color, (alpha * drawableAlpha / 255f).toInt())
    }
}

private class HeaderConnectorView(context: Context, private val accentColor: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val centerX = width / 2f
        val density = resources.displayMetrics.density
        paint.shader = LinearGradient(
            centerX,
            0f,
            centerX,
            height.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(accentColor, 68),
                ColorUtils.setAlphaComponent(accentColor, 150),
            ),
            floatArrayOf(0f, 0.34f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(
            centerX - density,
            0f,
            centerX + density,
            height.toFloat(),
            density,
            density,
            paint,
        )
        paint.shader = null
        paint.color = ColorUtils.setAlphaComponent(accentColor, 112)
        canvas.drawCircle(centerX, height - density * 1.5f, density * 2.1f, paint)
        paint.color = ColorUtils.setAlphaComponent(accentColor, 228)
        canvas.drawCircle(centerX, height - density * 1.5f, density * 0.9f, paint)
    }
}

private class SettingsParticleBackground(
    context: Context,
    private val showOrbitalDecoration: Boolean,
) : View(context) {
    private data class Particle(
        var x: Float,
        var y: Float,
        val radius: Float,
        val alpha: Int,
        val color: Int,
        val twinkleSpeed: Float,
        val twinkleOffset: Float,
        val driftRadius: Float,
        val driftSpeed: Float,
    )

    private val random = Random(481516)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 0.9f
    }
    private val colors = intArrayOf(
        Color.parseColor("#9CC8FF"),
        Color.parseColor("#D0B2FF"),
        Color.parseColor("#8DEBFF"),
    )
    private val particles = MutableList(72) { newParticle() }
    private var active = false
    private var motionPhase = 0f
    private var blueNebula: Shader? = null
    private var violetNebula: Shader? = null
    private var planetShader: Shader? = null
    private val animationFrame = object : Runnable {
        override fun run() {
            if (!active || !isAttachedToWindow) return
            advanceParticles()
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setActive(enabled: Boolean) {
        active = enabled
        visibility = if (enabled) VISIBLE else GONE
        if (enabled) {
            seedParticles()
            startAnimation()
        } else {
            removeCallbacks(animationFrame)
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) startAnimation()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animationFrame)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        seedParticles()
        createCosmicShaders()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return
        drawNebulae(canvas)
        if (showOrbitalDecoration) {
            drawOrbit(
                canvas,
                width * 0.82f,
                height * 0.22f,
                width * 0.24f,
                height * 0.075f,
                22f,
                2.7f,
                colors[0],
                34,
            )
            drawOrbit(
                canvas,
                width * 0.82f,
                height * 0.22f,
                width * 0.16f,
                height * 0.13f,
                -34f,
                -1.9f,
                colors[1],
                28,
            )
            drawPlanet(canvas)
        }
        particles.forEach { particle ->
            val twinkle = ((sin(motionPhase * particle.twinkleSpeed + particle.twinkleOffset) + 1f) / 2f)
            val driftPhase = motionPhase * particle.driftSpeed + particle.twinkleOffset
            val x = particle.x + cos(driftPhase) * particle.driftRadius
            val y = particle.y + sin(driftPhase) * particle.driftRadius * 0.58f
            paint.color = particle.color
            paint.alpha = (particle.alpha * (0.18f + twinkle * 0.20f)).toInt().coerceAtLeast(1)
            canvas.drawCircle(x, y, particle.radius * 3.2f, paint)
            paint.alpha = (particle.alpha * (0.55f + twinkle * 0.45f)).toInt()
            canvas.drawCircle(x, y, particle.radius * (0.72f + twinkle * 0.36f), paint)
        }
    }

    private fun createCosmicShaders() {
        if (width <= 0 || height <= 0) return
        val scale = minOf(width, height).toFloat()
        blueNebula = RadialGradient(
            width * 0.14f,
            height * 0.20f,
            scale * 0.76f,
            intArrayOf(
                Color.argb(72, 46, 109, 255),
                Color.argb(24, 41, 86, 201),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        violetNebula = RadialGradient(
            width * 0.76f,
            height * 0.74f,
            scale * 0.70f,
            intArrayOf(
                Color.argb(66, 139, 69, 214),
                Color.argb(20, 90, 53, 155),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.54f, 1f),
            Shader.TileMode.CLAMP,
        )
        planetShader = RadialGradient(
            width * 0.78f,
            height * 0.18f,
            scale * 0.16f,
            intArrayOf(
                Color.argb(118, 186, 221, 255),
                Color.argb(82, 89, 115, 214),
                Color.argb(0, 16, 20, 46),
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun drawNebulae(canvas: Canvas) {
        paint.shader = blueNebula
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = violetNebula
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun drawOrbit(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        rotation: Float,
        angularVelocity: Float,
        color: Int,
        alpha: Int,
    ) {
        orbitPaint.color = color
        orbitPaint.alpha = alpha
        canvas.save()
        canvas.rotate(rotation + motionPhase * angularVelocity, centerX, centerY)
        canvas.drawOval(
            centerX - radiusX,
            centerY - radiusY,
            centerX + radiusX,
            centerY + radiusY,
            orbitPaint,
        )
        canvas.restore()
    }

    private fun drawPlanet(canvas: Canvas) {
        val scale = minOf(width, height).toFloat()
        paint.shader = planetShader
        paint.alpha = 255
        canvas.drawCircle(width * 0.78f, height * 0.18f, scale * 0.16f, paint)
        paint.shader = null
    }

    private fun newParticle(): Particle {
        return Particle(
            x = 0f,
            y = 0f,
            radius = random.nextInt(1, 5).toFloat(),
            alpha = random.nextInt(32, 92),
            color = colors[random.nextInt(colors.size)],
            twinkleSpeed = random.nextFloat() * 1.1f + 0.35f,
            twinkleOffset = random.nextFloat() * (Math.PI * 2).toFloat(),
            driftRadius = resources.displayMetrics.density * (random.nextFloat() * 4.5f + 1.5f),
            driftSpeed = random.nextFloat() * 0.45f + 0.18f,
        )
    }

    private fun seedParticles() {
        if (width <= 0 || height <= 0) return
        particles.forEach { particle ->
            particle.x = random.nextFloat() * width
            particle.y = random.nextFloat() * height
        }
    }

    private fun advanceParticles() {
        motionPhase += 0.012f
    }

    private fun startAnimation() {
        if (!isAttachedToWindow) return
        removeCallbacks(animationFrame)
        postOnAnimation(animationFrame)
    }
}

private class BorderSparkleOverlay(context: Context, private val color: Int) : View(context) {
    private val routePath = Path()
    private val segmentPath = Path()
    private val routeMeasure = PathMeasure()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val position = FloatArray(2)
    private var routeLength = 0f
    private var startFraction = 0f
    private var progress = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun show(start: Float, value: Float) {
        startFraction = start
        progress = value
        visibility = VISIBLE
        invalidate()
    }

    fun clear() {
        visibility = GONE
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val inset = resources.displayMetrics.density * 1.5f
        val radius = resources.displayMetrics.density * 16f
        routePath.reset()
        routePath.addRoundRect(
            RectF(inset, inset, width - inset, height - inset),
            radius,
            radius,
            Path.Direction.CW,
        )
        routeMeasure.setPath(routePath, false)
        routeLength = routeMeasure.length
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (routeLength <= 0f || visibility != VISIBLE) return
        val strength = when {
            progress < 0.13f -> progress / 0.13f
            progress > 0.82f -> (1f - progress) / 0.18f
            else -> 1f
        }.coerceIn(0f, 1f)
        val headDistance = ((startFraction + progress) % 1f) * routeLength
        val tailLength = (routeLength * 0.10f).coerceAtMost(resources.displayMetrics.density * 58f)
        val tailDistance = headDistance - tailLength
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density * 1.8f
        paint.color = color
        paint.alpha = (220 * strength).toInt()
        if (tailDistance >= 0f) {
            drawSegment(canvas, tailDistance, headDistance)
        } else {
            drawSegment(canvas, routeLength + tailDistance, routeLength)
            drawSegment(canvas, 0f, headDistance)
        }
        routeMeasure.getPosTan(headDistance, position, null)
        paint.style = Paint.Style.FILL
        paint.alpha = (64 * strength).toInt()
        canvas.drawCircle(position[0], position[1], resources.displayMetrics.density * 6f, paint)
        paint.alpha = (255 * strength).toInt()
        canvas.drawCircle(position[0], position[1], resources.displayMetrics.density * 2.1f, paint)
    }

    private fun drawSegment(canvas: Canvas, start: Float, end: Float) {
        segmentPath.reset()
        if (routeMeasure.getSegment(start, end, segmentPath, true)) {
            canvas.drawPath(segmentPath, paint)
        }
    }
}

class BorderSparkleTarget(
    val view: ViewGroup,
    accent: String,
) {
    private val random = Random.Default
    private val overlay = BorderSparkleOverlay(view.context, Color.parseColor(accent))
    private var animator: ValueAnimator? = null
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        overlay.layout(0, 0, view.width, view.height)
    }

    init {
        view.overlay.add(overlay)
        view.addOnLayoutChangeListener(layoutListener)
    }

    fun isAvailable(): Boolean = view.isAttachedToWindow && view.visibility == View.VISIBLE

    fun play(onFinished: () -> Unit) {
        animator?.cancel()
        if (view.width <= 0 || view.height <= 0) {
            onFinished()
            return
        }
        overlay.layout(0, 0, view.width, view.height)
        val start = random.nextFloat()
        var cancelled = false
        val currentAnimator = ValueAnimator.ofFloat(0f, 1f)
        currentAnimator.duration = random.nextInt(1800, 2601).toLong()
        currentAnimator.addUpdateListener { overlay.show(start, it.animatedValue as Float) }
        currentAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                overlay.clear()
                if (animator === currentAnimator) animator = null
                if (!cancelled) onFinished()
            }
        })
        animator = currentAnimator
        overlay.show(start, 0f)
        currentAnimator.start()
    }

    fun stop() {
        animator?.cancel()
        animator = null
        overlay.clear()
    }

    fun dispose() {
        stop()
        view.removeOnLayoutChangeListener(layoutListener)
        view.overlay.remove(overlay)
    }
}

private class BorderSparkleCycle(
    private val targets: List<BorderSparkleTarget>,
    private val isEnabled: () -> Boolean,
) {
    private val random = Random.Default
    private var active = false
    private var previousTarget: BorderSparkleTarget? = null
    private val nextSparkle = Runnable { playNext() }

    fun setActive(enabled: Boolean) {
        active = enabled
        host()?.removeCallbacks(nextSparkle)
        targets.forEach { it.stop() }
        if (!enabled) {
            return
        }
        scheduleNext(random.nextInt(1000, 2201).toLong())
    }

    fun dispose() {
        active = false
        host()?.removeCallbacks(nextSparkle)
        targets.forEach { it.dispose() }
    }

    private fun playNext() {
        if (!active || !isEnabled()) return
        val available = targets.filter { it.isAvailable() }
        if (available.isEmpty()) {
            scheduleNext(1400L)
            return
        }
        val candidates = available.filter { it !== previousTarget }.ifEmpty { available }
        val selected = candidates[random.nextInt(candidates.size)]
        previousTarget = selected
        selected.play {
            if (active && isEnabled()) {
                scheduleNext(random.nextInt(1800, 4601).toLong())
            }
        }
    }

    private fun scheduleNext(delay: Long) {
        host()?.postDelayed(nextSparkle, delay)
    }

    private fun host(): View? = targets.firstOrNull()?.view
}

private class SettingsInteractionFrame(
    context: Context,
    private val onInteraction: () -> Unit,
) : FrameLayout(context) {
    private var lockedHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val particleBackground = getChildAt(0)
        val scrollContent = getChildAt(1)
        if (particleBackground == null || scrollContent == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        if (lockedHeight == 0) {
            measureChildWithMargins(scrollContent, widthMeasureSpec, 0, heightMeasureSpec, 0)
            lockedHeight = scrollContent.measuredHeight
        }
        val layoutParams = scrollContent.layoutParams as ViewGroup.MarginLayoutParams
        val contentWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight -
            layoutParams.leftMargin - layoutParams.rightMargin).coerceAtLeast(0)
        scrollContent.measure(
            MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(lockedHeight.coerceAtLeast(0), MeasureSpec.EXACTLY),
        )
        val desiredWidth = scrollContent.measuredWidth + paddingLeft + paddingRight +
            layoutParams.leftMargin + layoutParams.rightMargin
        val desiredHeight = lockedHeight + paddingTop + paddingBottom +
            layoutParams.topMargin + layoutParams.bottomMargin
        setMeasuredDimension(
            resolveSizeAndState(desiredWidth, widthMeasureSpec, 0),
            resolveSizeAndState(desiredHeight, heightMeasureSpec, 0),
        )
        particleBackground.measure(
            MeasureSpec.makeMeasureSpec(
                (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0),
                MeasureSpec.EXACTLY,
            ),
            MeasureSpec.makeMeasureSpec(
                (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0),
                MeasureSpec.EXACTLY,
            ),
        )
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onInteraction()
        return super.dispatchTouchEvent(event)
    }
}

abstract class StreamCenterBaseSettingsFragment : BottomSheetDialogFragment() {
    companion object {
        private var restartPromptPending = false
        private var activeSettingsToast: Toast? = null
        private const val SETTINGS_ROW_HEIGHT_DP = 72
        private const val SETTINGS_ROW_RADIUS_DP = 16
        private const val SETTINGS_ROW_ICON_DP = 42
        private const val SETTINGS_ROW_SPACING_DP = 10
        private const val SETTINGS_CATEGORY_HEIGHT_DP = 64
        private const val SETTINGS_CATEGORY_RADIUS_DP = 14
        private const val SETTINGS_CATEGORY_ICON_DP = 40
        private const val SETTINGS_DIALOG_TILE_HEIGHT_DP = 126

        private fun dismissActiveSettingsToast() {
            activeSettingsToast?.cancel()
            activeSettingsToast = null
        }
    }

    protected fun markRestartNeeded() {
        restartPromptPending = true
    }

    protected fun resetRestartNeeded() {
        restartPromptPending = false
    }

    protected fun consumeRestartNeeded(): Boolean {
        val value = restartPromptPending
        restartPromptPending = false
        return value
    }

    private var dismissCallback: (() -> Unit)? = null
    private var playedEnterAnimation = false
    private val dialogBackdropLayers = mutableListOf<DialogBackdropLayer>()
    private val titleEffectTargets = mutableMapOf<TextView, Pair<String, String>>()
    private val titleGradientAnimations = mutableMapOf<TextView, ValueAnimator>()
    private val titleHaloTargets = mutableMapOf<TextView, View>()
    private val headerInfoEffectTargets = mutableListOf<HeaderInfoEffectTarget>()
    private val particleBackgrounds = mutableListOf<SettingsParticleBackground>()
    private val borderSparkleCycles = mutableListOf<BorderSparkleCycle>()
    private val dynamicBorderSparkleCycles = mutableMapOf<String, BorderSparkleCycle>()

    protected enum class HeaderInfoEffectStyle {
        COMMIT,
        BUILD,
    }

    protected data class SettingsRowViews(
        val view: LinearLayout,
        val badge: View?,
        val title: TextView,
        val summary: TextView?,
    )

    protected data class SettingsChoiceOption<T>(
        val label: String,
        val value: T,
        val badge: String,
    )

    private data class HeaderInfoEffectTarget(
        val container: LinearLayout,
        val label: TextView,
        val value: TextView,
        val style: HeaderInfoEffectStyle,
    )

    private data class DialogBackdropLayer(
        val dialog: AlertDialog,
        val backdrop: View?,
    )

    protected val sharedPref: SharedPreferences?
        get() = StreamCenterPlugin.activeSharedPref

    protected val visualAnimationsEnabled: Boolean
        get() = StreamCenterPlugin.areVisualAnimationsEnabled(sharedPref)

    protected val visualBlurEnabled: Boolean
        get() = StreamCenterPlugin.areVisualBlursEnabled(sharedPref)

    protected val visualTitleEffectsEnabled: Boolean
        get() = StreamCenterPlugin.areVisualTitleEffectsEnabled(sharedPref)

    protected val visualParticlesEnabled: Boolean
        get() = StreamCenterPlugin.areVisualParticlesEnabled(sharedPref)

    protected val reduceMotion: Boolean
        get() = !visualAnimationsEnabled

    private fun updateTitleEffect(target: TextView, title: String, accent: String) {
        if (!visualTitleEffectsEnabled) {
            titleGradientAnimations.remove(target)?.cancel()
            titleHaloTargets[target]?.background = null
            target.paint.shader = null
            target.paint.clearShadowLayer()
            target.setLayerType(View.LAYER_TYPE_NONE, null)
            target.invalidate()
            return
        }
        target.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        target.setShadowLayer(
            dp(7).toFloat(),
            0f,
            dp(1).toFloat(),
            Color.parseColor(tint(accent, "A8")),
        )
        titleHaloTargets[target]?.background = HeaderTitleHaloDrawable(Color.parseColor(accent))
        if (target.width == 0) return
        startTitleGradientAnimation(target, title, accent)
    }

    private fun startTitleGradientAnimation(target: TextView, title: String, accent: String) {
        if (titleGradientAnimations[target]?.isRunning == true) return
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 9_000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                updateTitleGradient(target, title, accent, it.animatedValue as Float)
            }
        }
        titleGradientAnimations[target] = animator
        animator.start()
    }

    private fun updateTitleGradient(target: TextView, title: String, accent: String, phase: Float) {
        if (target.width == 0) return
        val textWidth = target.paint.measureText(title)
        val centeredStart = ((target.width - textWidth) / 2f).coerceAtLeast(0f)
        val shift = sin(phase * Math.PI.toFloat() * 2f) * textWidth * 0.12f
        val start = centeredStart + shift
        target.paint.shader = LinearGradient(
            start,
            0f,
            start + textWidth,
            0f,
            intArrayOf(
                Color.parseColor(accent),
                Color.parseColor("#7DD3FC"),
                Color.parseColor("#C084FC"),
                Color.parseColor(accent),
            ),
            floatArrayOf(0f, 0.30f, 0.68f, 1f),
            Shader.TileMode.CLAMP,
        )
        target.invalidate()
    }

    private fun updateHeaderInfoEffect(target: HeaderInfoEffectTarget) {
        if (!visualTitleEffectsEnabled) {
            target.container.background = null
            target.label.setTextColor(Color.parseColor(COLOR_MUTED))
            target.value.setTextColor(Color.parseColor(COLOR_MUTED))
            target.value.typeface = Typeface.DEFAULT
            target.value.letterSpacing = 0f
            return
        }
        when (target.style) {
            HeaderInfoEffectStyle.COMMIT -> {
                val accent = "#A78BFA"
                target.container.background = outlined(tint(accent, "A8"), tint(accent, "16"), 999)
                target.label.setTextColor(Color.parseColor("#C4B5FD"))
                target.value.setTextColor(Color.parseColor("#EDE9FE"))
                target.value.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                target.value.letterSpacing = 0.04f
            }
            HeaderInfoEffectStyle.BUILD -> {
                target.container.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#132B3D"), Color.parseColor("#251A40")),
                ).apply {
                    setStroke(dp(1), Color.parseColor("#4CC9F0"))
                    cornerRadius = dp(999).toFloat()
                }
                target.label.setTextColor(Color.parseColor("#7DD3FC"))
                target.value.setTextColor(Color.parseColor("#E0F2FE"))
                target.value.typeface = Typeface.DEFAULT_BOLD
                target.value.letterSpacing = 0f
            }
        }
    }

    protected fun headerInfoBadge(
        label: String,
        value: String,
        style: HeaderInfoEffectStyle,
    ): LinearLayout {
        val labelView = bodyText(label.uppercase(), 10).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        val valueView = bodyText(value, 11)
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(6), dp(11), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
            }
            addView(labelView)
            addView(valueView.apply { setPadding(dp(7), 0, 0, 0) })
            HeaderInfoEffectTarget(this, labelView, valueView, style).also {
                headerInfoEffectTargets += it
                updateHeaderInfoEffect(it)
            }
        }
    }

    protected open fun refreshVisualEffectBackdrops() {
        dialogBackdropLayers.forEach { layer ->
            layer.backdrop?.apply {
                alpha = 1f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRenderEffect(
                        if (visualBlurEnabled) {
                            RenderEffect.createBlurEffect(
                                dp(7).toFloat(),
                                dp(7).toFloat(),
                                Shader.TileMode.CLAMP,
                            )
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    fun refreshVisualEffectsImmediately() {
        (dialog as? BottomSheetDialog)?.dismissWithAnimation = !reduceMotion
        if (reduceMotion) {
            view?.animate()?.cancel()
            view?.apply {
                alpha = 1f
                translationY = 0f
            }
        }
        titleEffectTargets.forEach { (target, values) ->
            updateTitleEffect(target, values.first, values.second)
        }
        headerInfoEffectTargets.forEach(::updateHeaderInfoEffect)
        particleBackgrounds.forEach { it.setActive(visualParticlesEnabled) }
        borderSparkleCycles.forEach { it.setActive(visualAnimationsEnabled) }
        dynamicBorderSparkleCycles.values.forEach { it.setActive(visualAnimationsEnabled) }
        refreshVisualEffectBackdrops()
    }

    protected fun startBorderSparkleCycle(targets: List<BorderSparkleTarget>) {
        if (targets.isEmpty()) return
        BorderSparkleCycle(targets) { visualAnimationsEnabled }.also {
            borderSparkleCycles += it
            it.setActive(visualAnimationsEnabled)
        }
    }

    protected fun replaceBorderSparkleCycle(key: String, targets: List<BorderSparkleTarget>) {
        dynamicBorderSparkleCycles.remove(key)?.dispose()
        if (targets.isEmpty()) return
        BorderSparkleCycle(targets) { visualAnimationsEnabled }.also {
            dynamicBorderSparkleCycles[key] = it
            it.setActive(visualAnimationsEnabled)
        }
    }

    protected fun refreshVisibleSettingsEffects() {
        parentFragmentManager.fragments
            .filterIsInstance<StreamCenterBaseSettingsFragment>()
            .forEach { it.refreshVisualEffectsImmediately() }
    }

    protected fun applyDialogBackdrop(
        alertDialog: AlertDialog,
        onShow: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ) {
        var layer: DialogBackdropLayer? = null
        alertDialog.setOnShowListener {
            val backdrop = dialogBackdropLayers.lastOrNull()?.dialog?.window?.decorView ?: view
            layer = DialogBackdropLayer(alertDialog, backdrop).also(dialogBackdropLayers::add)
            backdrop?.apply {
                alpha = 1f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRenderEffect(
                        if (visualBlurEnabled) {
                            RenderEffect.createBlurEffect(
                                dp(7).toFloat(),
                                dp(7).toFloat(),
                                Shader.TileMode.CLAMP,
                            )
                        } else {
                            null
                        },
                    )
                }
            }
            onShow?.invoke()
        }
        alertDialog.setOnDismissListener {
            layer?.let { dismissedLayer ->
                dismissedLayer.backdrop?.apply {
                    alpha = 1f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
                }
                dialogBackdropLayers.remove(dismissedLayer)
            }
            layer = null
            onDismiss?.invoke()
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.apply {
            dismissWithAnimation = !reduceMotion
            behavior.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isDraggable = false
            }
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        if (!playedEnterAnimation) {
            playedEnterAnimation = true
            if (!reduceMotion) {
                view?.let { content ->
                    content.alpha = 0f
                    content.translationY = dp(28).toFloat()
                    content.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(220L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        dismissCallback?.invoke()
        dismissCallback = null
    }

    override fun onDestroyView() {
        particleBackgrounds.forEach { it.setActive(false) }
        particleBackgrounds.clear()
        borderSparkleCycles.forEach { it.dispose() }
        borderSparkleCycles.clear()
        dynamicBorderSparkleCycles.values.forEach { it.dispose() }
        dynamicBorderSparkleCycles.clear()
        titleGradientAnimations.values.forEach { it.cancel() }
        titleGradientAnimations.clear()
        titleEffectTargets.clear()
        titleHaloTargets.clear()
        headerInfoEffectTargets.clear()
        super.onDestroyView()
    }

    fun onDismissed(callback: () -> Unit): StreamCenterBaseSettingsFragment {
        dismissCallback = callback
        return this
    }

    protected fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    protected fun standardSubmenuMinimumHeight(): Int {
        return minOf(dp(520), (resources.displayMetrics.heightPixels * 0.82f).toInt())
    }

    protected fun rootContainer(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
        }
    }

    protected fun headerConnector(accent: String = COLOR_ACCENT): View {
        return HeaderConnectorView(requireContext(), Color.parseColor(accent)).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(16),
            )
        }
    }

    protected fun scroll(content: LinearLayout, fixedSubmenuHeight: Boolean = false): View {
        val frameHeight = if (fixedSubmenuHeight) {
            standardSubmenuMinimumHeight()
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val frameBackground = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#070B1B"),
                Color.parseColor("#111A38"),
                Color.parseColor("#1A1030"),
            ),
        ).apply {
            val radius = dp(22).toFloat()
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        val particleBackground = SettingsParticleBackground(
            requireContext(),
            showOrbitalDecoration = !fixedSubmenuHeight,
        )
        val scrollView = ScrollView(requireContext()).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        requestInitialControlFocus(scrollView)
        return SettingsInteractionFrame(requireContext()) { dismissActiveSettingsToast() }.apply {
            background = frameBackground
            clipToOutline = true
            addView(
                particleBackground,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                scrollView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    frameHeight,
                ),
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                frameHeight,
            )
            particleBackgrounds += particleBackground
            particleBackground.setActive(visualParticlesEnabled)
        }
    }

    private fun requestInitialControlFocus(root: ViewGroup) {
        root.post {
            if (!root.isAttachedToWindow) return@post
            val currentFocus = root.findFocus()
            if (currentFocus != null && currentFocus !== root) return@post
            findFirstFocusableDescendant(root)?.requestFocus()
        }
    }

    private fun findFirstFocusableDescendant(parent: ViewGroup): View? {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child.visibility != View.VISIBLE || !child.isEnabled) continue
            if (child.isFocusable) return child
            if (child is ViewGroup) {
                findFirstFocusableDescendant(child)?.let { return it }
            }
        }
        return null
    }

    protected fun header(
        title: String,
        eyebrow: String? = null,
        subtitle: String? = null,
        metadata: List<View> = emptyList(),
        icon: String? = null,
        accent: String = COLOR_ACCENT,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
        actionWidthDp: Int? = null,
        actionHeightDp: Int? = null,
        actionGravity: Int = Gravity.CENTER_VERTICAL,
        actionTopMarginDp: Int = 0,
        centered: Boolean = false,
        titleEffect: Boolean = false,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = if (centered) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (centered) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(12))

            icon?.let {
                addView(iconBadge(it, accent, size = 46, marginEnd = 12))
            }

            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (centered) Gravity.CENTER else Gravity.START
                if (titleEffect) setPadding(dp(16), dp(9), dp(16), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    if (centered) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (centered) 0f else 1f,
                ).apply {
                    if (!centered) marginEnd = dp(8)
                }
            }
            eyebrow?.let {
                texts.addView(counterText(it.uppercase(), 9).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.12f
                    setTextColor(Color.parseColor(tint(accent, "B8")))
                    setPadding(0, 0, 0, dp(2))
                    if (centered) gravity = Gravity.CENTER
                })
            }
            val headerTitle = titleText(title, if (titleEffect) 24 else 22, true).apply {
                if (centered) gravity = Gravity.CENTER
            }
            if (titleEffect) {
                titleEffectTargets[headerTitle] = title to accent
                headerTitle.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    updateTitleEffect(headerTitle, title, accent)
                }
                updateTitleEffect(headerTitle, title, accent)
            }
            texts.addView(headerTitle)
            subtitle?.let {
                texts.addView(bodyText(it, 12).apply {
                    alpha = 0.82f
                    setPadding(0, dp(2), 0, 0)
                    if (centered) gravity = Gravity.CENTER
                })
            }
            metadata.forEach { texts.addView(it) }
            addView(texts)

            if (actionText != null && onAction != null) {
                val compactAction = actionText.length <= 2
                val actionWidth = actionWidthDp?.let(::dp)
                val actionHeight = actionHeightDp?.let(::dp)
                addView(actionButton(actionText, accent, onAction).apply {
                    minWidth = actionWidth ?: dp(if (compactAction) 42 else 82)
                    minimumHeight = actionHeight ?: dp(40)
                    textSize = if (compactAction) 18f else 13f
                    setPadding(
                        dp(if (compactAction) 10 else 18),
                        dp(8),
                        dp(if (compactAction) 10 else 18),
                        dp(8),
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        actionWidth ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                        actionHeight ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginStart = dp(12)
                        topMargin = dp(actionTopMarginDp)
                        gravity = actionGravity
                    }
                })
            }
        }
    }

    protected fun titleText(value: String, size: Int = 18, bold: Boolean = true): TextView {
        return TextView(requireContext()).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(Color.parseColor(COLOR_TEXT))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    protected fun bodyText(value: String, size: Int = 13): TextView {
        return TextView(requireContext()).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(Color.parseColor(COLOR_MUTED))
            setLineSpacing(dp(1).toFloat(), 1.0f)
        }
    }

    protected fun counterText(value: String, size: Int = 10): TextView {
        return bodyText(value, size).apply {
            alpha = 0.80f
            letterSpacing = 0.015f
        }
    }

    protected fun sectionLabel(value: String): TextView {
        return bodyText(value.uppercase(), 11).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
    }

    protected fun chip(value: String, color: String = COLOR_ACCENT): TextView {
        return TextView(requireContext()).apply {
            text = value
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(tint(color, "C7")))
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = outlined(tint(color, "4D"), tint(color, "14"), 999)
        }
    }

    protected fun iconBadge(
        emoji: String,
        accent: String = COLOR_ACCENT,
        size: Int = 40,
        marginEnd: Int = 0,
    ): TextView {
        return TextView(requireContext()).apply {
            text = emoji
            textSize = if (size >= 44) 20f else 16f
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = SettingsIconBadgeDrawable(
                accentColor = Color.parseColor(accent),
                radius = dp(13).toFloat(),
                strokeWidth = dp(1).toFloat(),
            )
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                this.marginEnd = dp(marginEnd)
            }
        }
    }

    protected fun chevron(accent: String): TextView {
        return TextView(requireContext()).apply {
            text = "›"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(accent))
            setPadding(dp(10), 0, dp(4), dp(2))
        }
    }

    protected fun siteIconBadge(
        fallback: String,
        accent: String,
        contentDescription: String,
        iconUrl: String? = null,
        websiteUrl: String? = null,
        size: Int = 42,
        marginEnd: Int = 12,
    ): FrameLayout {
        return FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                this.marginEnd = dp(marginEnd)
            }
            addView(iconBadge(fallback, accent, size = size).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
            val logoView = ImageView(requireContext()).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                this.contentDescription = contentDescription
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(8).toFloat()
                }
                clipToOutline = true
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    val inset = dp(4)
                    setMargins(inset, inset, inset, inset)
                }
            }
            addView(logoView)
            val resolvedIcon = iconUrl ?: websiteUrl?.let(StreamCenterSiteIcons::cached)
            if (resolvedIcon != null) {
                ImageLoader.run { logoView.loadImage(resolvedIcon) }
            } else if (websiteUrl != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    val remoteIcon = StreamCenterSiteIcons.resolve(websiteUrl)
                    withContext(Dispatchers.Main) {
                        if (!isAdded || logoView.parent == null || remoteIcon == null) return@withContext
                        ImageLoader.run { logoView.loadImage(remoteIcon) }
                    }
                }
            }
        }
    }

    protected fun addCardTouchFeedback(
        card: ViewGroup,
        accent: String,
        icon: View? = null,
        chevron: View? = null,
    ) {
        if (reduceMotion) return
        card.clipChildren = false
        card.clipToPadding = false

        fun animateState(pressed: Boolean) {
            val scale = if (pressed) 0.98f else 1f
            val duration = if (pressed) 80L else 140L
            card.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(duration)
                .setInterpolator(DecelerateInterpolator())
                .start()
            icon?.animate()
                ?.scaleX(if (pressed) 1.035f else 1f)
                ?.scaleY(if (pressed) 1.035f else 1f)
                ?.setDuration(duration)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
            chevron?.animate()
                ?.translationX(if (pressed) dp(4).toFloat() else 0f)
                ?.setDuration(duration)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
        }

        card.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animateState(pressed = true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateState(pressed = false)
            }
            false
        }
    }

    protected fun styledSwitch(
        checked: Boolean,
        accent: String = COLOR_ACCENT,
        onChanged: (Boolean) -> Unit,
    ): SwitchCompat {
        return SwitchCompat(requireContext()).apply {
            isChecked = checked
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(
                states,
                intArrayOf(Color.parseColor(accent), Color.parseColor(COLOR_THUMB_OFF)),
            )
            trackTintList = ColorStateList(
                states,
                intArrayOf(Color.parseColor(tint(accent, "66")), Color.parseColor(COLOR_TRACK_OFF)),
            )
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    outlined(tint(accent, "D9"), tint(accent, "20"), 999, strokeWidth = 2),
                )
                addState(intArrayOf(), solid("#00000000", 999))
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(12)
            }
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }
    }

    protected fun switchRow(
        title: String,
        summary: String? = null,
        checked: Boolean,
        accent: String = COLOR_ACCENT,
        icon: String? = null,
        strokeColor: String = COLOR_STROKE,
        topMargin: Int = SETTINGS_ROW_SPACING_DP,
        fixedHeight: Boolean = false,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout {
        lateinit var views: SettingsRowViews
        val toggle = styledSwitch(checked, accent) { isChecked ->
            playToggleFeedback(views.view, views.badge, accent, isChecked)
            onChanged(isChecked)
        }
        views = settingsRow(
            title = title,
            summary = summary,
            icon = icon,
            accent = accent,
            fillColor = COLOR_CARD_ALT,
            strokeColor = strokeColor,
            trailingViews = listOf(toggle),
            topMargin = topMargin,
            fixedHeight = fixedHeight,
        ) { toggle.toggle() }
        return views.view
    }

    protected fun settingsRow(
        title: String,
        summary: String? = null,
        icon: String? = null,
        accent: String = COLOR_ACCENT,
        fillColor: String = COLOR_CARD,
        strokeColor: String = COLOR_STROKE,
        leadingView: View? = null,
        summaryView: TextView? = null,
        statusView: View? = null,
        trailingViews: List<View> = emptyList(),
        topMargin: Int = SETTINGS_ROW_SPACING_DP,
        fixedHeight: Boolean = false,
        enabledAppearance: Boolean = true,
        disabledAlpha: Float = 0.52f,
        touchTarget: View? = trailingViews.lastOrNull(),
        onClick: (() -> Unit)? = null,
    ): SettingsRowViews {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(SETTINGS_ROW_HEIGHT_DP)
            val verticalPadding = if (fixedHeight) 9 else 12
            setPadding(dp(14), dp(verticalPadding), dp(10), dp(verticalPadding))
            background = interactiveBackground(fillColor, accent, SETTINGS_ROW_RADIUS_DP, strokeColor)
            layoutParams = verticalParams(top = topMargin).apply {
                if (fixedHeight) height = dp(SETTINGS_ROW_HEIGHT_DP)
            }
            clipChildren = false
            clipToPadding = false
            alpha = if (enabledAppearance) 1f else disabledAlpha
            contentDescription = title
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        val badge = leadingView ?: icon?.let {
            iconBadge(it, accent, size = SETTINGS_ROW_ICON_DP, marginEnd = 12)
        }
        badge?.let(row::addView)
        val titleView = titleText(title, 15, true)
        val resolvedSummary = summaryView ?: summary
            ?.takeIf(String::isNotBlank)
            ?.let { bodyText(it, 12) }
        row.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            }
            addView(titleView)
            resolvedSummary?.let {
                it.setPadding(0, dp(3), 0, 0)
                addView(it)
            }
        })
        statusView?.let(row::addView)
        trailingViews.forEach(row::addView)
        if (onClick != null) addCardTouchFeedback(row, accent, badge, touchTarget)
        return SettingsRowViews(row, badge, titleView, resolvedSummary)
    }

    protected fun <T> showSettingsChoiceDialog(
        title: String,
        options: List<SettingsChoiceOption<T>>,
        selectedValue: T,
        accent: String,
        onSelected: (SettingsChoiceOption<T>) -> Unit,
    ) {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(20))
        }
        lateinit var dialog: AlertDialog
        options.forEach { option ->
            val selected = option.value == selectedValue
            val selectedBadge = iconBadge("✓", accent, size = 30, marginEnd = 0).apply {
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
            }
            content.addView(settingsRow(
                title = option.label,
                accent = accent,
                fillColor = COLOR_CARD_ALT,
                strokeColor = if (selected) accent else tint(accent, "55"),
                leadingView = iconBadge(option.badge, accent, size = 38, marginEnd = 12),
                trailingViews = listOf(selectedBadge),
                topMargin = 8,
            ) {
                onSelected(option)
                dialog.dismiss()
            }.view)
        }
        dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle(title))
            .setView(content)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    protected fun categoryHeaderRow(
        title: String,
        summaryView: TextView,
        icon: String,
        accent: String,
        trailingViews: List<View>,
        titleCompanion: View? = null,
        strokeColor: String = tint(accent, "66"),
        enabledAppearance: Boolean = true,
        onClick: () -> Unit,
    ): SettingsRowViews {
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(SETTINGS_CATEGORY_HEIGHT_DP)
            setPadding(dp(10), dp(8), dp(6), dp(8))
            background = interactiveBackground(
                COLOR_CARD_ALT,
                accent,
                SETTINGS_CATEGORY_RADIUS_DP,
                strokeColor,
            )
            clipChildren = false
            clipToPadding = false
            alpha = if (enabledAppearance) 1f else 0.55f
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { onClick() }
        }
        val badge = iconBadge(icon, accent, size = SETTINGS_CATEGORY_ICON_DP, marginEnd = 10)
        header.addView(badge)
        val titleView = titleText(title, 16, true)
        header.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(titleView)
                titleCompanion?.let(::addView)
            })
            summaryView.setPadding(0, dp(2), 0, 0)
            addView(summaryView)
        })
        trailingViews.forEach(header::addView)
        addCardTouchFeedback(header, accent, badge, trailingViews.lastOrNull())
        return SettingsRowViews(header, badge, titleView, summaryView)
    }

    protected fun categoryContainer(
        accent: String,
        fillColor: String = COLOR_CARD_ALT,
        strokeColor: String = tint(accent, "88"),
        topMargin: Int = 8,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = cardBackground(fillColor, strokeColor, 18)
            layoutParams = verticalParams(top = topMargin)
        }
    }

    protected fun emptyStateCard(
        title: String,
        accent: String,
        icon: String? = null,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(SETTINGS_CATEGORY_HEIGHT_DP)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(COLOR_CARD, tint(accent, "55"), SETTINGS_ROW_RADIUS_DP)
            layoutParams = verticalParams(top = 8)
            icon?.let { addView(iconBadge(it, accent, size = 36, marginEnd = 10)) }
            addView(titleText(title, 14, true))
        }
    }

    protected fun dialogActionTile(
        icon: String,
        label: String,
        accent: String,
        onClick: () -> Unit,
    ): LinearLayout = dialogActionTile(
        badge = iconBadge(icon, accent, size = 38),
        label = label,
        accent = accent,
        onClick = onClick,
    )

    protected fun dialogActionTile(
        badge: View,
        label: String,
        accent: String,
        onClick: () -> Unit,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(SETTINGS_DIALOG_TILE_HEIGHT_DP)
            setPadding(dp(10), dp(14), dp(10), dp(14))
            background = interactiveBackground(tint(accent, "16"), accent, 16, tint(accent, "72"))
            isClickable = true
            isFocusable = true
            contentDescription = label.replace("\n", " ")
            setOnClickListener { onClick() }
            addView(badge)
            addView(titleText(label, 14, true).apply {
                gravity = Gravity.CENTER
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            })
            addCardTouchFeedback(this, accent, badge)
        }
    }

    protected fun dialogTitle(value: String, color: String = COLOR_TEXT): TextView {
        return titleText(value, 20, true).apply {
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(22), dp(24), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    protected fun dialogBrandTitle(
        value: String,
        leadingView: View,
        accent: String,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(18), dp(24), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(leadingView)
            addView(titleText(value, 20, true).apply {
                setTextColor(Color.parseColor(accent))
                gravity = Gravity.CENTER
            })
        }
    }

    protected fun playToggleFeedback(row: View, badge: View?, accent: String, enabled: Boolean) {
        if (reduceMotion) return
        badge?.apply {
            animate().cancel()
            scaleX = 1f
            scaleY = 1f
            rotation = 0f
            val targetScale = if (enabled) 1.35f else 0.82f
            animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .rotation(if (enabled) 14f else 0f)
                .setDuration(150L)
                .withEndAction {
                    animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .rotation(0f)
                        .setDuration(220L)
                        .start()
                }
                .start()
        }
        val background = row.background as? GradientDrawable ?: return
        val base = Color.parseColor(COLOR_CARD_ALT)
        val flash = if (enabled) {
            ColorUtils.blendARGB(base, Color.parseColor(accent), 0.30f)
        } else {
            ColorUtils.blendARGB(base, Color.parseColor(COLOR_THUMB_OFF), 0.25f)
        }
        ValueAnimator.ofObject(ArgbEvaluator(), base, flash, base).apply {
            duration = 550L
            addUpdateListener { animator -> background.setColor(animator.animatedValue as Int) }
            start()
        }
    }

    protected fun animateCategoryExpansion(content: View) {
        if (reduceMotion) return
        content.alpha = 0f
        content.translationY = -dp(8).toFloat()
        content.post {
            val targetHeight = content.height
            val layoutParams = content.layoutParams
            if (targetHeight <= 0 || layoutParams == null) {
                content.alpha = 1f
                content.translationY = 0f
                return@post
            }
            layoutParams.height = 0
            content.layoutParams = layoutParams
            ValueAnimator.ofInt(0, targetHeight).apply {
                duration = 260L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    layoutParams.height = animator.animatedValue as Int
                    content.layoutParams = layoutParams
                }
                start()
            }
            content.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(210L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    protected fun animateCategoryCollapse(content: View, onFinished: () -> Unit) {
        if (reduceMotion) {
            onFinished()
            return
        }
        val startHeight = content.height
        val layoutParams = content.layoutParams
        if (startHeight <= 0 || layoutParams == null) {
            onFinished()
            return
        }
        content.animate().cancel()
        ValueAnimator.ofInt(startHeight, 0).apply {
            duration = 210L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                layoutParams.height = animator.animatedValue as Int
                content.layoutParams = layoutParams
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onFinished()
                }
            })
            start()
        }
        content.animate()
            .alpha(0f)
            .translationY(-dp(6).toFloat())
            .setDuration(170L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    protected fun animateCardFill(
        view: View,
        fromColor: String,
        toColor: String,
        strokeColor: String = COLOR_STROKE,
        radius: Int = 14,
    ) {
        if (reduceMotion) {
            view.background = cardBackground(toColor, strokeColor, radius)
            return
        }
        val drawable = cardBackground(fromColor, strokeColor, radius)
        view.background = drawable
        ValueAnimator.ofObject(
            ArgbEvaluator(),
            Color.parseColor(fromColor),
            Color.parseColor(toColor),
        ).apply {
            duration = 260L
            addUpdateListener { animator ->
                (drawable as? SettingsCardBackgroundDrawable)?.setColor(animator.animatedValue as Int)
            }
            start()
        }
    }

    protected fun actionButton(textValue: String, color: String, onClick: () -> Unit): TextView {
        return TextView(requireContext()).apply {
            text = textValue
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = interactiveBackground(tint(color, "18"), color, 12, strokeColor = tint(color, "88"))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    protected fun iconButton(
        symbol: String,
        description: String? = null,
        accent: String = COLOR_ACCENT,
        size: Int = 34,
        onClick: () -> Unit,
    ): TextView {
        return TextView(requireContext()).apply {
            text = symbol
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(accent))
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = description
            isClickable = true
            isFocusable = true
            background = interactiveBackground(tint(accent, "14"), accent, 999, strokeColor = tint(accent, "55"))
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                marginStart = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    protected fun deleteIconButton(
        description: String,
        accent: String = COLOR_DANGER,
        size: Int = 34,
        onClick: () -> Unit,
    ): TextView {
        return iconButton("×", description, accent, size, onClick)
    }

    protected fun reorderIconButton(
        symbol: String,
        description: String,
        accent: String,
        enabled: Boolean,
        size: Int = 34,
        onClick: () -> Unit,
    ): TextView {
        return iconButton(symbol, description, accent, size, onClick).apply {
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.35f
        }
    }

    protected fun categoryExpandButton(
        expanded: Boolean,
        description: String,
        accent: String,
        size: Int = 34,
        onClick: () -> Unit,
    ): FrameLayout {
        return FrameLayout(requireContext()).apply {
            var transforming = false
            contentDescription = description
            isClickable = true
            isFocusable = true
            background = interactiveBackground(tint(accent, "14"), accent, 999, strokeColor = tint(accent, "55"))
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                marginStart = dp(8)
            }
            val horizontalBar = View(requireContext()).apply {
                background = solid(accent, 999)
                layoutParams = FrameLayout.LayoutParams(dp(14), dp(2), Gravity.CENTER)
            }
            val verticalBar = View(requireContext()).apply {
                background = solid(accent, 999)
                scaleY = if (expanded) 0f else 1f
                alpha = if (expanded) 0f else 1f
                layoutParams = FrameLayout.LayoutParams(dp(2), dp(14), Gravity.CENTER)
            }
            addView(horizontalBar)
            addView(verticalBar)
            setOnClickListener {
                if (transforming) return@setOnClickListener
                if (reduceMotion) {
                    onClick()
                    return@setOnClickListener
                }
                transforming = true
                verticalBar.animate().cancel()
                verticalBar.animate()
                    .scaleY(if (expanded) 1f else 0f)
                    .alpha(if (expanded) 1f else 0f)
                    .setDuration(170L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction(onClick)
                    .start()
            }
        }
    }

    protected fun input(value: String, widthDp: Int? = null): EditText {
        return EditText(requireContext()).apply {
            setText(value)
            setTextColor(Color.parseColor(COLOR_TEXT))
            setHintTextColor(Color.parseColor(COLOR_MUTED))
            textSize = 13f
            setSingleLine(true)
            maxLines = 1
            setSelectAllOnFocus(false)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    outlined(COLOR_ACCENT, COLOR_INPUT_FILL, 10),
                )
                addState(intArrayOf(), outlined(COLOR_STROKE, COLOR_INPUT_FILL, 10))
            }
            layoutParams = LinearLayout.LayoutParams(
                widthDp?.let(::dp) ?: 0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                if (widthDp == null) 1f else 0f,
            )
        }
    }

    protected fun verticalParams(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(top)
        }
    }

    protected fun solid(color: String, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radius).toFloat()
        }
    }

    protected fun outlined(
        strokeColor: String,
        fillColor: String,
        radius: Int,
        strokeWidth: Int = 1,
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillColor))
            setStroke(dp(strokeWidth), Color.parseColor(strokeColor))
            cornerRadius = dp(radius).toFloat()
        }
    }

    protected fun cardBackground(
        fillColor: String = COLOR_CARD,
        strokeColor: String = COLOR_STROKE,
        radius: Int = 14,
    ): Drawable {
        return cardBackground(fillColor, strokeColor, radius, strokeColor)
    }

    protected fun interactiveBackground(
        fill: String,
        accent: String,
        radius: Int,
        strokeColor: String = COLOR_STROKE,
    ): Drawable {
        if (radius >= 100) {
            val states = StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_focused, android.R.attr.state_pressed),
                    outlined(accent, fill, radius, strokeWidth = 3),
                )
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    outlined(accent, fill, radius, strokeWidth = 3),
                )
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    outlined(accent, fill, radius, strokeWidth = 2),
                )
                addState(intArrayOf(), outlined(strokeColor, fill, radius))
            }
            return RippleDrawable(
                ColorStateList.valueOf(Color.parseColor(tint(accent, "1F"))),
                states,
                solid("#FFFFFF", radius),
            )
        }
        val states = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused, android.R.attr.state_pressed),
                cardBackground(fill, accent, radius, accent, strokeWidth = 3),
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                cardBackground(fill, accent, radius, accent, strokeWidth = 3),
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                cardBackground(fill, accent, radius, accent, strokeWidth = 2),
            )
            addState(intArrayOf(), cardBackground(fill, strokeColor, radius, accent))
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.parseColor(tint(accent, "1F"))),
            states,
            solid("#FFFFFF", radius),
        )
    }

    private fun cardBackground(
        fillColor: String,
        strokeColor: String,
        radius: Int,
        accentColor: String,
        strokeWidth: Int = 1,
    ): SettingsCardBackgroundDrawable {
        return SettingsCardBackgroundDrawable(
            fillColor = Color.parseColor(fillColor),
            accentColor = Color.parseColor(accentColor),
            strokeColor = Color.parseColor(strokeColor),
            radius = dp(radius).toFloat(),
            strokeWidth = dp(strokeWidth).toFloat(),
        )
    }

    protected fun saveToast(message: String) {
        dismissActiveSettingsToast()
        activeSettingsToast = Toast.makeText(requireContext().applicationContext, message, Toast.LENGTH_SHORT).also {
            it.show()
        }
    }

    protected fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            saveToast("Impossibile aprire il link.")
        }
    }

    protected fun offerRestartPrompt(message: String) {
        val dialogContext = context ?: return
        val appContext = dialogContext.applicationContext
        val alertDialog = AlertDialog.Builder(dialogContext)
            .setCustomTitle(dialogTitle("Riavvia l'app"))
            .setMessage(message)
            .setPositiveButton("Riavvia") { _, _ ->
                restartApp(appContext)
            }
            .setNegativeButton("Più tardi", null)
            .create()
        applyDialogBackdrop(alertDialog)
        alertDialog.show()
    }

    private fun restartApp(appContext: Context) {
        val packageManager = appContext.packageManager
        val intent = packageManager.getLaunchIntentForPackage(appContext.packageName)
        val component = intent?.component ?: return
        val restartIntent = Intent.makeRestartActivityTask(component)
        appContext.startActivity(restartIntent)
        android.os.Process.killProcess(android.os.Process.myPid())
        Runtime.getRuntime().exit(0)
    }
}

