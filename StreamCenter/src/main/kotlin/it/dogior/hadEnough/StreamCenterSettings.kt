package it.dogior.hadEnough

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
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.util.Calendar
import java.util.Locale
import kotlin.math.sin
import kotlin.random.Random

private const val COLOR_BACKGROUND = "#11141A"
private const val COLOR_CARD = "#1A2029"
private const val COLOR_CARD_ALT = "#202733"
private const val COLOR_CARD_DISABLED = "#151922"
private const val COLOR_TEXT = "#F4F7FB"
private const val COLOR_MUTED = "#AAB3C2"
private const val COLOR_STROKE = "#2E3746"
private const val COLOR_ACCENT = "#4CC9F0"
private const val COLOR_SUCCESS = "#7CFF9D"
private const val COLOR_DANGER = "#FF7F7F"
private const val COLOR_THUMB_OFF = "#8A94A6"
private const val COLOR_TRACK_OFF = "#39424F"
private const val COLOR_INPUT_FILL = "#10151D"
private const val COLOR_PERFORMANCE = "#A3E635"
private const val COLOR_DISPLAY = "#14B8A6"
private const val COLOR_HOME = "#60A5FA"
private const val COLOR_SOURCES = "#FBBF24"
private const val COLOR_SUPPORT = "#FB7185"
private const val COLOR_SCORE = "#FDE047"
private const val COLOR_ANIME_VARIANTS = "#C084FC"
private const val COLOR_EPISODES = "#FB923C"
private const val COLOR_SOURCE_UPDATE = "#2DD4BF"
private const val COLOR_SOURCE_ANIME = "#8B5CF6"
private const val COLOR_SOURCE_TV = "#0EA5E9"
private const val COLOR_FEEDBACK = "#818CF8"
private const val COLOR_VISUAL_EFFECTS = "#E879F9"
private const val COLOR_VISUAL_BLUR = "#D8B4FE"
private const val COLOR_VISUAL_HEADER = "#FDE68A"
private const val COLOR_PARTICLES = "#38BDF8"
private const val COLOR_API_CHECK = "#06B6D4"
private const val COLOR_CLOUDSTREAM_SERVICES = "#7DD3FC"
private const val COLOR_RESET = "#F97316"
private const val COLOR_HOME_ANIME = "#F472B6"
private const val COLOR_HOME_TV = "#34D399"
private const val COLOR_HOME_MOVIE = "#F59E0B"
private const val COLOR_HOME_TRACKING = "#A78BFA"
private const val COLOR_HOME_CHANNELS = "#22D3EE"
private const val STREAMING_COMMUNITY_UPDATED_LINK_PAGE =
    "https://telegra.ph/Link-Aggiornato-StreamingCommunity-09-29"

private fun tint(color: String, alphaHex: String): String = "#$alphaHex${color.removePrefix("#")}"

private data class HomeRowState(
    val section: StreamCenterHomeSectionDefinition,
    var title: String,
    var enabled: Boolean,
    var count: Int,
)

private class SettingsParticleBackground(context: Context) : View(context) {
    private data class Particle(
        var x: Float,
        var y: Float,
        val radius: Float,
        val speed: Float,
        val drift: Float,
        val alpha: Int,
        val color: Int,
    )

    private val random = Random(481516)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.2f
    }
    private val wavePath = Path()
    private val colors = intArrayOf(
        Color.parseColor("#60A5FA"),
        Color.parseColor("#A78BFA"),
        Color.parseColor("#22D3EE"),
    )
    private val particles = MutableList(22) { newParticle() }
    private var active = false
    private var wavePhase = 0f
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
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return
        drawWave(canvas, height * 0.22f, height * 0.012f, colors[0], 15, 0f)
        drawWave(canvas, height * 0.54f, height * 0.017f, colors[1], 12, 1.8f)
        drawWave(canvas, height * 0.83f, height * 0.010f, colors[2], 10, 3.6f)
        particles.forEach { particle ->
            paint.color = particle.color
            paint.alpha = (particle.alpha / 3).coerceAtLeast(1)
            canvas.drawCircle(particle.x, particle.y, particle.radius * 2.4f, paint)
            paint.alpha = particle.alpha
            canvas.drawCircle(particle.x, particle.y, particle.radius, paint)
        }
    }

    private fun drawWave(
        canvas: Canvas,
        baseY: Float,
        amplitude: Float,
        color: Int,
        alpha: Int,
        offset: Float,
    ) {
        if (width <= 0) return
        wavePaint.color = color
        wavePaint.alpha = alpha
        wavePath.reset()
        val segments = 24
        for (index in 0..segments) {
            val x = width * index / segments.toFloat()
            val y = baseY + sin(wavePhase + offset + index * 0.56f) * amplitude
            if (index == 0) {
                wavePath.moveTo(x, y)
            } else {
                wavePath.lineTo(x, y)
            }
        }
        canvas.drawPath(wavePath, wavePaint)
    }

    private fun newParticle(): Particle {
        return Particle(
            x = 0f,
            y = 0f,
            radius = random.nextInt(2, 6).toFloat(),
            speed = random.nextFloat() * 0.34f + 0.12f,
            drift = random.nextFloat() * 0.22f - 0.11f,
            alpha = random.nextInt(24, 62),
            color = colors[random.nextInt(colors.size)],
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
        if (width <= 0 || height <= 0) return
        wavePhase = (wavePhase + 0.0025f) % (Math.PI * 2).toFloat()
        particles.forEach { particle ->
            particle.y -= particle.speed
            particle.x += particle.drift
            if (particle.y < -particle.radius * 3f) {
                particle.y = height + particle.radius * 3f
                particle.x = random.nextFloat() * width
            }
            if (particle.x < -particle.radius * 3f) particle.x = width + particle.radius * 3f
            if (particle.x > width + particle.radius * 3f) particle.x = -particle.radius * 3f
        }
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
    private var openDialogBackdrops = 0
    private var activeDialogBackdrop: View? = null
    private val titleEffectTargets = mutableMapOf<TextView, Pair<String, String>>()
    private val headerInfoEffectTargets = mutableListOf<HeaderInfoEffectTarget>()
    private val particleBackgrounds = mutableListOf<SettingsParticleBackground>()
    private val borderSparkleCycles = mutableListOf<BorderSparkleCycle>()
    private val dynamicBorderSparkleCycles = mutableMapOf<String, BorderSparkleCycle>()

    protected enum class HeaderInfoEffectStyle {
        COMMIT,
        BUILD,
    }

    private data class HeaderInfoEffectTarget(
        val container: LinearLayout,
        val label: TextView,
        val value: TextView,
        val style: HeaderInfoEffectStyle,
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
        if (target.width == 0) return
        val textWidth = target.paint.measureText(title)
        val start = ((target.width - textWidth) / 2f).coerceAtLeast(0f)
        target.paint.shader = LinearGradient(
            start,
            0f,
            start + textWidth,
            0f,
            intArrayOf(
                Color.parseColor(accent),
                Color.parseColor("#C084FC"),
            ),
            null,
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
        val backdrop = activeDialogBackdrop ?: return
        if (openDialogBackdrops == 0) return
        backdrop.alpha = 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(
                if (visualBlurEnabled) {
                    RenderEffect.createBlurEffect(dp(7).toFloat(), dp(7).toFloat(), Shader.TileMode.CLAMP)
                } else {
                    null
                },
            )
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
        onDismiss: (() -> Unit)? = null,
    ) {
        val backdrop = view
        alertDialog.setOnShowListener {
            openDialogBackdrops += 1
            activeDialogBackdrop = backdrop
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
        }
        alertDialog.setOnDismissListener {
            openDialogBackdrops = (openDialogBackdrops - 1).coerceAtLeast(0)
            if (openDialogBackdrops == 0) {
                backdrop?.apply {
                    alpha = 1f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRenderEffect(null)
                    }
                }
                activeDialogBackdrop = null
            }
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
        titleEffectTargets.clear()
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

    protected fun scroll(content: LinearLayout): View {
        val frameBackground = GradientDrawable().apply {
            setColor(Color.parseColor(COLOR_BACKGROUND))
            val radius = dp(22).toFloat()
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        val particleBackground = SettingsParticleBackground(requireContext())
        val scrollView = ScrollView(requireContext()).apply {
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
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            particleBackgrounds += particleBackground
            particleBackground.setActive(visualParticlesEnabled)
        }
    }

    protected fun header(
        title: String,
        subtitle: String? = null,
        metadata: List<View> = emptyList(),
        icon: String? = null,
        accent: String = COLOR_ACCENT,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
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
                layoutParams = LinearLayout.LayoutParams(
                    if (centered) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (centered) 0f else 1f,
                ).apply {
                    if (!centered) marginEnd = dp(8)
                }
            }
            val headerTitle = titleText(title, 22, true).apply {
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
                addView(actionButton(actionText, accent, onAction).apply {
                    minWidth = dp(if (compactAction) 42 else 82)
                    minimumHeight = dp(40)
                    textSize = if (compactAction) 18f else 13f
                    setPadding(
                        dp(if (compactAction) 10 else 18),
                        dp(8),
                        dp(if (compactAction) 10 else 18),
                        dp(8),
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginStart = dp(12)
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

    protected fun sectionLabel(value: String): TextView {
        return bodyText(value.uppercase(), 11).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
    }

    protected fun chip(value: String, color: String = COLOR_ACCENT): TextView {
        return TextView(requireContext()).apply {
            text = value
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(5), dp(11), dp(5))
            background = outlined(tint(color, "66"), tint(color, "1A"), 999)
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
            background = outlined(tint(accent, "40"), tint(accent, "1C"), 13)
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
        summary: String,
        checked: Boolean,
        accent: String = COLOR_ACCENT,
        icon: String? = null,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            val rowView = this
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(COLOR_CARD_ALT, radius = 14)
            layoutParams = verticalParams(top = 10)
            clipChildren = false
            clipToPadding = false

            val badge = icon?.let {
                iconBadge(it, accent, size = 38, marginEnd = 12).also(::addView)
            }

            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(titleText(title, 15, true))
            texts.addView(bodyText(summary, 12).apply { setPadding(0, dp(3), 0, 0) })
            addView(texts)

            addView(styledSwitch(checked, accent) { isChecked ->
                playToggleFeedback(rowView, badge, accent, isChecked)
                onChanged(isChecked)
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
            addUpdateListener { animator -> drawable.setColor(animator.animatedValue as Int) }
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
    ): GradientDrawable {
        return outlined(strokeColor, fillColor, radius)
    }

    protected fun interactiveBackground(
        fill: String,
        accent: String,
        radius: Int,
        strokeColor: String = COLOR_STROKE,
    ): Drawable {
        val states = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                outlined(accent, tint(accent, "2E"), radius, strokeWidth = 2),
            )
            addState(intArrayOf(), outlined(strokeColor, fill, radius))
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.parseColor(tint(accent, "33"))),
            states,
            solid("#FFFFFF", radius),
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
            .setTitle("Riavvia l'app")
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

class StreamCenterSettings : StreamCenterBaseSettingsFragment() {
    private var homeStatus: TextView? = null
    private var sourcesStatus: TextView? = null
    private var mainContent: View? = null
    private var openSubmenus = 0
    private val menuSparkleTargets = mutableListOf<BorderSparkleTarget>()
    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        markRestartNeeded()
        refreshStatusStrip()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        resetRestartNeeded()
        menuSparkleTargets.clear()
        val content = rootContainer()
        mainContent = content
        content.addView(
            header(
                title = "StreamCenter",
                metadata = buildInfoBadges(),
                centered = true,
                titleEffect = true,
            ),
        )

        val performanceCard = switchRow(
            title = "Modalità Prestazioni",
            summary = "Carica solo Home, Episodi e la prima Fonte disponibile.",
            checked = StreamCenterPlugin.isPerformanceModeEnabled(sharedPref),
            accent = COLOR_PERFORMANCE,
            icon = "⚡",
        ) { enabled ->
            sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_PERFORMANCE_MODE, enabled) }
            refreshVisibleSettingsEffects()
            saveToast(if (enabled) "Modalità Prestazioni attivata" else "Modalità Prestazioni disattivata")
        }
        menuSparkleTargets += BorderSparkleTarget(performanceCard, COLOR_PERFORMANCE)
        content.addView(performanceCard)
        content.addView(
            settingsMenuCard(
                title = "Schede",
                summary = "Gestisci i dettagli mostrati nelle schede di Home e Ricerca.",
                icon = "🖼️",
                accent = COLOR_DISPLAY,
            ) {
                showSubmenu(StreamCenterDisplaySettingsFragment(), "StreamCenterDisplaySettings")
            },
        )
        content.addView(
            settingsMenuCard(
                title = "Home",
                summary = "Personalizza sezioni, ordine, nomi e numero di titoli mostrati.",
                icon = "🏠",
                accent = COLOR_HOME,
                status = "",
                onStatusReady = { homeStatus = it },
            ) {
                showSubmenu(StreamCenterHomeSettingsFragment(), "StreamCenterHomeSettings")
            },
        )
        content.addView(
            settingsMenuCard(
                title = "Fonti streaming",
                summary = "Scegli le fonti attive, aggiorna i link e definisci la priorità.",
                icon = "📡",
                accent = COLOR_SOURCES,
                status = "",
                onStatusReady = { sourcesStatus = it },
            ) {
                showSubmenu(StreamCenterSourcesSettingsFragment(), "StreamCenterSourcesSettings")
            },
        )
        content.addView(
            settingsMenuCard(
                title = "Supporto",
                summary = "Controlla lo stato delle fonti, invia feedback o ripristina le impostazioni.",
                icon = "🛟",
                accent = COLOR_SUPPORT,
            ) {
                showSubmenu(StreamCenterSupportSettingsFragment(), "StreamCenterSupportSettings")
            },
        )

        startBorderSparkleCycle(menuSparkleTargets)
        return scroll(content)
    }

    override fun onStart() {
        super.onStart()
        sharedPref?.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        refreshStatusStrip()
    }

    override fun onStop() {
        sharedPref?.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        super.onStop()
    }

    override fun onDestroyView() {
        homeStatus = null
        sourcesStatus = null
        mainContent = null
        super.onDestroyView()
        menuSparkleTargets.clear()
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (consumeRestartNeeded()) {
            offerRestartPrompt(
                "Per applicare le modifiche è necessario riavviare l'app.\nVuoi riavviarla adesso?",
            )
        }
        super.onDismiss(dialog)
    }

    private fun refreshStatusStrip() {
        val enabledSources = StreamCenterPlugin.streamingSources.count {
            StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, it.key)
        }
        val enabledSections = StreamCenterPlugin.getConfiguredHomeSections(sharedPref).size
        homeStatus?.text = "$enabledSections attive"
        sourcesStatus?.text = "$enabledSources attive"
    }

    private fun buildInfoBadges(): List<View> {
        val infoLines = StreamCenterPlugin.getBuildInfoText().lines()
        val badges = mutableListOf<View>()
        infoLines.firstOrNull { it.startsWith("Commit ") }?.let { line ->
            badges += headerInfoBadge(
                label = "Commit",
                value = line.removePrefix("Commit "),
                style = HeaderInfoEffectStyle.COMMIT,
            )
        }
        infoLines.firstOrNull { it.startsWith("Build ") }?.let { line ->
            badges += headerInfoBadge(
                label = "Build",
                value = line.removePrefix("Build "),
                style = HeaderInfoEffectStyle.BUILD,
            )
        }
        if (badges.size > 1) {
            return listOf(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(6)
                }
                badges.forEachIndexed { index, badge ->
                    addView(
                        badge,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            if (index < badges.lastIndex) marginEnd = dp(6)
                        },
                    )
                }
            })
        }
        if (badges.isEmpty()) {
            badges += bodyText(infoLines.firstOrNull() ?: "Informazioni build non disponibili", 12).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(6)
                }
            }
        }
        return badges
    }

    private fun settingsMenuCard(
        title: String,
        summary: String,
        icon: String,
        accent: String,
        status: String? = null,
        onStatusReady: ((TextView) -> Unit)? = null,
        onClick: () -> Unit,
    ): LinearLayout {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(84)
            setPadding(dp(14), dp(13), dp(10), dp(13))
            background = interactiveBackground(COLOR_CARD, accent, 16)
            layoutParams = verticalParams(top = 10)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(iconBadge(icon, accent, size = 44, marginEnd = 12))
            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(titleText(title, 16, true))
            texts.addView(bodyText(summary, 12).apply { setPadding(0, dp(4), 0, 0) })
            addView(texts)

            if (status != null) {
                val statusView = chip(status, accent)
                addView(statusView)
                onStatusReady?.invoke(statusView)
            }
            addView(chevron(accent))
        }
        menuSparkleTargets += BorderSparkleTarget(card, accent)
        return card
    }

    private fun showSubmenu(fragment: StreamCenterBaseSettingsFragment, tag: String) {
        openSubmenus += 1
        updateMainBackdrop()
        fragment.onDismissed {
            openSubmenus = (openSubmenus - 1).coerceAtLeast(0)
            updateMainBackdrop()
        }.show(parentFragmentManager, tag)
    }

    private fun updateMainBackdrop() {
        val content = mainContent ?: return
        val hasSubmenu = openSubmenus > 0
        content.animate().cancel()
        content.alpha = 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            content.setRenderEffect(
                if (hasSubmenu && visualBlurEnabled) {
                    RenderEffect.createBlurEffect(dp(5).toFloat(), dp(5).toFloat(), Shader.TileMode.CLAMP)
                } else {
                    null
                },
            )
        }
    }

    override fun refreshVisualEffectBackdrops() {
        updateMainBackdrop()
    }
}

class StreamCenterDisplaySettingsFragment : StreamCenterBaseSettingsFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer()
        content.minimumHeight = standardSubmenuMinimumHeight()
        content.addView(
            header(
                title = "Schede",
                subtitle = "Gestisci i dettagli mostrati nelle schede di Home e Ricerca.",
                icon = "🖼️",
                accent = COLOR_DISPLAY,
            ),
        )
        content.addView(
            switchRow(
                title = "Valutazione",
                summary = "Mostra il voto nelle schede di Anime, Film e Serie TV.",
                checked = StreamCenterPlugin.shouldShowHomeScore(sharedPref),
                accent = COLOR_SCORE,
                icon = "⭐",
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_HOME_SCORE, enabled) }
            },
        )
        content.addView(
            switchRow(
                title = "SUB/DUB",
                summary = "Mostra se l'Anime è SUB, DUB o entrambe le versioni.",
                checked = StreamCenterPlugin.shouldShowAnimeHomeDubStatus(sharedPref),
                accent = COLOR_ANIME_VARIANTS,
                icon = "🎙️",
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_DUB_STATUS, enabled) }
            },
        )
        content.addView(
            switchRow(
                title = "Unifica SUB e DUB",
                summary = "Raggruppa le versioni sottotitolata e doppiata nella stessa scheda.",
                checked = StreamCenterPlugin.shouldGroupAnimeVariants(sharedPref),
                accent = COLOR_ANIME_VARIANTS,
                icon = "🔗",
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_GROUP_ANIME_DUB_SUB, enabled) }
            },
        )
        content.addView(
            switchRow(
                title = "Numero episodi",
                summary = "Mostra nelle schede Anime l'ultimo episodio disponibile.",
                checked = StreamCenterPlugin.shouldShowAnimeHomeEpisodeNumber(sharedPref),
                accent = COLOR_EPISODES,
                icon = "🔢",
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_EPISODE_NUMBER, enabled) }
            },
        )
        content.addView(animeCardTitleRow())
        val displayAccents = listOf(
            COLOR_SCORE,
            COLOR_ANIME_VARIANTS,
            COLOR_ANIME_VARIANTS,
            COLOR_EPISODES,
            COLOR_DISPLAY,
        )
        startBorderSparkleCycle(displayAccents.mapIndexedNotNull { index, accent ->
            (content.getChildAt(index + 1) as? ViewGroup)?.let { BorderSparkleTarget(it, accent) }
        })
        return scroll(content)
    }

    private fun animeCardTitleRow(): LinearLayout {
        val selectedTitle = bodyText(animeCardTitleLabel(), 12)
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(COLOR_CARD_ALT, radius = 14)
            layoutParams = verticalParams(top = 10)
            isClickable = true
            isFocusable = true

            addView(iconBadge("✍️", COLOR_DISPLAY, size = 38, marginEnd = 12))
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(titleText("Titolo Anime", 15, true))
                addView(selectedTitle.apply { setPadding(0, dp(3), 0, 0) })
            })
            addView(bodyText("›", 30).apply {
                setTextColor(Color.parseColor(COLOR_DISPLAY))
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, 0, 0)
            })
            setOnClickListener { showAnimeCardTitlePicker(selectedTitle) }
        }
    }

    private fun animeCardTitleLabel(): String {
        return when (StreamCenterPlugin.getAnimeCardTitle(sharedPref)) {
            StreamCenterPlugin.ANIME_CARD_TITLE_ROMAJI -> "Romaji"
            StreamCenterPlugin.ANIME_CARD_TITLE_ENGLISH -> "Inglese"
            StreamCenterPlugin.ANIME_CARD_TITLE_NATIVE -> "Nativo"
            else -> "Titolo da AnimeUnity"
        }
    }

    private fun showAnimeCardTitlePicker(selectedTitle: TextView) {
        val options = listOf(
            "Titolo da AnimeUnity" to StreamCenterPlugin.ANIME_CARD_TITLE_ANIMEUNITY,
            "Romaji" to StreamCenterPlugin.ANIME_CARD_TITLE_ROMAJI,
            "Inglese" to StreamCenterPlugin.ANIME_CARD_TITLE_ENGLISH,
            "Nativo" to StreamCenterPlugin.ANIME_CARD_TITLE_NATIVE,
        )
        val current = StreamCenterPlugin.getAnimeCardTitle(sharedPref)
        val selectedIndex = options.indexOfFirst { it.second == current }.coerceAtLeast(0)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Titolo Anime")
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), selectedIndex) { alert, which ->
                sharedPref?.edit { putString(StreamCenterPlugin.PREF_ANIME_CARD_TITLE, options[which].second) }
                selectedTitle.text = options[which].first
                alert.dismiss()
            }
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }
}

private data class SourceRowState(
    val source: StreamCenterStreamingSource,
    var enabled: Boolean,
    var url: String,
)

private data class SourceCategory(
    val key: String,
    val title: String,
    val icon: String,
    val accent: String,
)

class StreamCenterSourcesSettingsFragment : StreamCenterBaseSettingsFragment() {
    private val rows = mutableListOf<SourceRowState>()
    private val sourceCategories = listOf(
        SourceCategory("anime", "Anime", "🎌", COLOR_SOURCE_ANIME),
        SourceCategory("tv", "Serie TV", "📺", COLOR_SOURCE_TV),
    )
    private val categoryStatusViews = mutableMapOf<String, TextView>()
    private var expandedCategoryKey: String? = null
    private var pendingCategoryExpansionKey: String? = null
    private var categoryTransitionRunning = false
    private var rowsContainer: LinearLayout? = null
    private var isStreamingCommunityLinkCheckRunning = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        loadRows()

        val content = rootContainer()
        content.minimumHeight = standardSubmenuMinimumHeight()
        content.addView(
            header(
                title = "Fonti streaming",
                subtitle = "Scegli le fonti attive, aggiorna i link e definisci la priorità.",
                icon = "📡",
                accent = COLOR_SOURCES,
                actionText = "Ripristina",
                onAction = { resetSources() },
            ),
        )

        content.addView(
            switchRow(
                title = "Aggiornamento automatico dei domini",
                summary = "Quando una fonte cambia dominio, il nuovo link viene rilevato e salvato automaticamente.",
                checked = StreamCenterPlugin.isSourceUrlAutoUpdateEnabled(sharedPref),
                accent = COLOR_SOURCE_UPDATE,
                icon = "🔄",
            ) { enabled ->
                sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_AUTO_UPDATE_SOURCE_URLS, enabled) }
            },
        )

        val rowsView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 10)
        }
        rowsContainer = rowsView
        content.addView(rowsView)
        renderRows()

        return scroll(content)
    }

    private fun loadRows() {
        rows.clear()
        val byKey = StreamCenterPlugin.streamingSources.associateBy { it.key }
        StreamCenterPlugin.getSourcePriorityOrder(sharedPref)
            .mapNotNull { byKey[it] }
            .forEach { source ->
                rows += SourceRowState(
                    source = source,
                    enabled = StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, source.key),
                    url = StreamCenterPlugin.getSourceBaseUrl(sharedPref, source.key),
                )
            }
    }

    private fun renderRows() {
        val container = rowsContainer ?: return
        replaceBorderSparkleCycle("source-categories", emptyList())
        container.removeAllViews()
        categoryStatusViews.clear()
        val sparkleTargets = mutableListOf<BorderSparkleTarget>()
        sourceCategories.forEach { category ->
            val categoryRows = rows.filter { it.source.category == category.key }
            if (categoryRows.isNotEmpty()) {
                val categoryCard = sourceCategoryCard(category, categoryRows)
                sparkleTargets += BorderSparkleTarget(categoryCard, category.accent)
                container.addView(categoryCard)
            }
        }
        replaceBorderSparkleCycle("source-categories", sparkleTargets)
    }

    private fun sourceCategoryCard(
        category: SourceCategory,
        categoryRows: List<SourceRowState>,
    ): LinearLayout {
        val expanded = expandedCategoryKey == category.key
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = cardBackground(COLOR_CARD_ALT, tint(category.accent, "88"), 18)
            layoutParams = verticalParams(top = 8)

            val header = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(8), dp(4), dp(5), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener { toggleCategory(category.key) }
            }
            header.addView(iconBadge(category.icon, category.accent, size = 40, marginEnd = 10))
            header.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(titleText(category.title, 17, true))
                addView(bodyText(categoryStatus(category.key), 12).apply {
                    categoryStatusViews[category.key] = this
                    setPadding(0, dp(2), 0, 0)
                })
            })
            header.addView(iconButton(
                symbol = if (expanded) "−" else "+",
                description = if (expanded) "Chiudi ${category.title}" else "Apri ${category.title}",
                accent = category.accent,
                size = 34,
            ) { toggleCategory(category.key) })
            addView(header)

            if (expanded) {
                val expandedContent = LinearLayout(requireContext()).apply {
                    tag = "source-category-content:${category.key}"
                    orientation = LinearLayout.VERTICAL
                }
                categoryRows.forEachIndexed { index, row ->
                    expandedContent.addView(sourceRow(index, row, category.accent, categoryRows.size > 1))
                }
                addView(expandedContent)
                if (pendingCategoryExpansionKey == category.key) {
                    animateCategoryExpansion(expandedContent)
                }
            }
        }
    }

    private fun categoryStatus(categoryKey: String): String {
        val categoryRows = rows.filter { it.source.category == categoryKey }
        val count = categoryRows.count { it.enabled }
        val sourceLabel = if (categoryRows.size == 1) "fonte" else "fonti"
        val activeLabel = if (count == 1) "attiva" else "attive"
        return "$count/${categoryRows.size} $sourceLabel $activeLabel"
    }

    private fun refreshCategoryStatus(categoryKey: String) {
        categoryStatusViews[categoryKey]?.text = categoryStatus(categoryKey)
    }

    private fun toggleCategory(categoryKey: String) {
        if (categoryTransitionRunning) return
        val currentExpanded = expandedCategoryKey
        if (currentExpanded != null) {
            val expandedContent = rowsContainer
                ?.findViewWithTag<View>("source-category-content:$currentExpanded")
            if (expandedContent != null) {
                categoryTransitionRunning = true
                animateCategoryCollapse(expandedContent) {
                    val opening = currentExpanded != categoryKey
                    expandedCategoryKey = categoryKey.takeIf { opening }
                    pendingCategoryExpansionKey = categoryKey.takeIf { opening }
                    renderRows()
                    pendingCategoryExpansionKey = null
                    categoryTransitionRunning = false
                }
                return
            }
        }
        val opening = expandedCategoryKey != categoryKey
        expandedCategoryKey = if (opening) categoryKey else null
        pendingCategoryExpansionKey = categoryKey.takeIf { opening }
        renderRows()
        pendingCategoryExpansionKey = null
    }

    private fun priorityBadge(index: Int, enabled: Boolean, accent: String): TextView {
        val color = if (enabled) accent else COLOR_MUTED
        return TextView(requireContext()).apply {
            text = "${index + 1}"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = outlined(tint(color, "55"), tint(color, "1C"), 999)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginEnd = dp(10)
            }
        }
    }

    private fun sourceRow(
        index: Int,
        row: SourceRowState,
        accent: String,
        canReorder: Boolean,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            val rowView = this
            lateinit var linkInput: EditText
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(12))
            background = cardBackground(if (row.enabled) COLOR_CARD else COLOR_CARD_DISABLED)
            layoutParams = verticalParams(top = 8)

            val topLine = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topLine.addView(priorityBadge(index, row.enabled, accent))
            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(titleText(row.source.title, 15, true))
            topLine.addView(texts)
            if (row.source.key == StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY) {
                lateinit var checkLinkButton: TextView
                checkLinkButton = iconButton(
                    symbol = "↻",
                    description = "Verifica il link aggiornato di StreamingCommunity",
                    accent = accent,
                    size = 32,
                ) {
                    checkStreamingCommunityLink(row, linkInput, checkLinkButton)
                }
                topLine.addView(checkLinkButton)
            }
            topLine.addView(styledSwitch(row.enabled, accent) { checked ->
                row.enabled = checked
                animateCardFill(
                    rowView,
                    fromColor = if (checked) COLOR_CARD_DISABLED else COLOR_CARD,
                    toColor = if (checked) COLOR_CARD else COLOR_CARD_DISABLED,
                )
                sharedPref?.edit { putBoolean(row.source.key, checked) }
                refreshCategoryStatus(row.source.category)
            })
            addView(topLine)

            val linkRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = verticalParams(top = 10)
            }
            linkRow.addView(bodyText("Link", 12).apply {
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = dp(10)
                }
            })
            linkInput = input(row.url).apply {
                filters = arrayOf(InputFilter.LengthFilter(120))
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val value = text?.toString()?.trim().orEmpty()
                        val previousUrl = row.url
                        StreamCenterPlugin.setSourceBaseUrl(sharedPref, row.source.key, value)
                        row.url = StreamCenterPlugin.getSourceBaseUrl(sharedPref, row.source.key)
                        if (row.url != previousUrl) {
                            StreamCenter.resetSourceDomainChecks()
                        }
                    }
                }
            }
            linkRow.addView(linkInput)
            if (canReorder) {
                linkRow.addView(iconButton(
                    symbol = "↑",
                    description = "Alza la priorità di ${row.source.title}",
                    accent = accent,
                    size = 30,
                ) {
                    moveRow(row.source.category, index, -1)
                })
                linkRow.addView(iconButton(
                    symbol = "↓",
                    description = "Abbassa la priorità di ${row.source.title}",
                    accent = accent,
                    size = 30,
                ) {
                    moveRow(row.source.category, index, 1)
                })
            }
            addView(linkRow)

        }
    }

    private fun moveRow(categoryKey: String, index: Int, direction: Int) {
        val categoryIndices = rows.indices.filter { rows[it].source.category == categoryKey }
        val target = index + direction
        if (target !in categoryIndices.indices) return
        rowsContainer?.clearFocus()
        val currentIndex = categoryIndices[index]
        val targetIndex = categoryIndices[target]
        rows[currentIndex] = rows[targetIndex].also { rows[targetIndex] = rows[currentIndex] }
        StreamCenterPlugin.setSourcePriorityOrder(sharedPref, rows.map { it.source.key })
        renderRows()
    }

    private fun checkStreamingCommunityLink(
        row: SourceRowState,
        linkInput: EditText,
        button: TextView,
    ) {
        if (isStreamingCommunityLinkCheckRunning) return
        rowsContainer?.clearFocus()
        isStreamingCommunityLinkCheckRunning = true
        button.isEnabled = false
        button.text = "…"
        CoroutineScope(Dispatchers.IO).launch {
            val publishedUrl = runCatching { streamingCommunityPublishedUrl() }.getOrNull()
            withContext(Dispatchers.Main) {
                isStreamingCommunityLinkCheckRunning = false
                if (!isAdded) return@withContext
                button.isEnabled = true
                button.text = "↻"
                if (publishedUrl == null) {
                    saveToast("Impossibile leggere il link aggiornato di StreamingCommunity")
                    return@withContext
                }

                val configuredUrl = StreamCenterPlugin.getSourceBaseUrl(
                    sharedPref,
                    StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY,
                )
                if (sameSourceHost(configuredUrl, publishedUrl)) {
                    saveToast("Il link di StreamingCommunity è già aggiornato")
                    return@withContext
                }

                val alertDialog = AlertDialog.Builder(requireContext())
                    .setTitle("Nuovo link StreamingCommunity")
                    .setMessage(
                        "Link configurato:\n$configuredUrl\n\n" +
                            "Link attuale:\n$publishedUrl",
                    )
                    .setPositiveButton("Aggiorna") { _, _ ->
                        StreamCenterPlugin.setSourceBaseUrl(
                            sharedPref,
                            StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY,
                            publishedUrl,
                        )
                        row.url = StreamCenterPlugin.getSourceBaseUrl(
                            sharedPref,
                            StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY,
                        )
                        linkInput.setText(row.url)
                        StreamCenter.resetSourceDomainChecks()
                        saveToast("Link di StreamingCommunity aggiornato")
                    }
                    .setNegativeButton("Ok", null)
                    .create()
                applyDialogBackdrop(alertDialog)
                alertDialog.show()
            }
        }
    }

    private fun streamingCommunityPublishedUrl(): String? {
        val page = Jsoup.connect(STREAMING_COMMUNITY_UPDATED_LINK_PAGE)
            .userAgent("Mozilla/5.0 (Android 14; Mobile)")
            .timeout(15_000)
            .get()
        return page.select("a[href]")
            .asSequence()
            .map { it.absUrl("href").trim() }
            .mapNotNull { url ->
                val host = Uri.parse(url).host?.lowercase() ?: return@mapNotNull null
                if (host.matches(Regex("""streamingcommunityz\.[a-z0-9-]+(?:\.[a-z0-9-]+)*"""))) {
                    "https://$host"
                } else {
                    null
                }
            }
            .firstOrNull()
    }

    private fun sameSourceHost(first: String, second: String): Boolean {
        val firstHost = Uri.parse(first).host?.lowercase()?.trimEnd('.')
        val secondHost = Uri.parse(second).host?.lowercase()?.trimEnd('.')
        return firstHost != null && firstHost == secondHost
    }

    private fun resetSources() {
        rowsContainer?.clearFocus()
        StreamCenterPlugin.resetSourceUrls(sharedPref)
        sharedPref?.edit { remove(StreamCenterPlugin.PREF_SOURCE_PRIORITY) }
        StreamCenter.resetSourceDomainChecks()
        loadRows()
        renderRows()
        saveToast("Fonti ripristinate")
    }

    override fun onDestroyView() {
        categoryStatusViews.clear()
        rowsContainer = null
        super.onDestroyView()
    }
}

class StreamCenterHomeSettingsFragment : StreamCenterBaseSettingsFragment() {
    private val rows = mutableListOf<HomeRowState>()
    private val categoryOrder = mutableListOf<String>()
    private val categoryEnabled = mutableMapOf<String, Boolean>()
    private var expandedCategoryKey: String? = null
    private var pendingCategoryExpansionKey: String? = null
    private var categoryTransitionRunning = false
    private var rowsContainer: LinearLayout? = null
    private var homeContent: View? = null
    private var openHomeDialogs = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        loadRows()

        val content = rootContainer()
        homeContent = content
        content.addView(
            header(
                title = "Home",
                subtitle = "Personalizza sezioni, ordine, nomi e numero di titoli mostrati.",
                icon = "🏠",
                accent = COLOR_HOME,
                actionText = "ⓘ",
                onAction = { showTitlePlaceholdersDialog() },
            ),
        )

        content.addView(bodyText("Tocca una categoria per aprirla.", 12).apply {
            layoutParams = verticalParams(top = 8)
        })

        val rowsView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 8)
        }
        rowsContainer = rowsView
        content.addView(rowsView)
        renderRows()

        content.addView(actionButton("Ripristina Home predefinita", COLOR_HOME) {
            resetHome()
        }.apply {
            layoutParams = verticalParams(top = 14)
        })

        return scroll(content)
    }

    private fun showTitlePlaceholdersDialog() {
        val ctx = context ?: return
        val accent = COLOR_HOME
        val calendar = Calendar.getInstance(Locale.ITALY).apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
        }
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val monthNumber = calendar.get(Calendar.MONTH) + 1
        val weekday = listOf(
            "Domenica", "Lunedi", "Martedi", "Mercoledi",
            "Giovedi", "Venerdi", "Sabato",
        )[calendar.get(Calendar.DAY_OF_WEEK) - 1]
        val month = listOf(
            "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
            "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre",
        )[calendar.get(Calendar.MONTH)]
        val year = calendar.get(Calendar.YEAR)
        val paddedMonth = String.format(Locale.ITALY, "%02d", monthNumber)
        val placeholders = listOf(
            Triple(
                "%Data%",
                "Data completa",
                String.format(Locale.ITALY, "%02d/%02d/%04d", dayOfMonth, monthNumber, year),
            ),
            Triple("%d%", "Giorno del mese", "4"),
            Triple("%dd%", "Giorno del mese a due cifre", "04"),
            Triple("%ddd%", "Giorno della settimana abbreviato", weekday.take(3)),
            Triple("%dddd%", "Giorno della settimana completo", weekday),
            Triple("%m%", "Mese numerico", "5"),
            Triple("%mm%", "Mese numerico a due cifre", "05"),
            Triple("%mmm%", "Mese abbreviato", month.take(3)),
            Triple("%mmmm%", "Nome del mese completo", month),
            Triple("%yy%", "Anno a due cifre", String.format(Locale.ITALY, "%02d", year % 100)),
            Triple("%yyyy%", "Anno a quattro cifre", year.toString()),
            Triple("%Giorno%", "Giorno della settimana", weekday),
            Triple("%GiornoNumerico%", "Numero del giorno del mese", dayOfMonth.toString()),
            Triple("%Mese%", "Nome del mese", month),
            Triple("%MeseNumerico%", "Numero del mese a due cifre", paddedMonth),
            Triple("%Anno%", "Anno corrente", year.toString()),
            Triple("%Settimana%", "Numero della settimana corrente", calendar.get(Calendar.WEEK_OF_YEAR).toString()),
            Triple("%Canali%", "Numero dei canali selezionati: disponibile solo nelle sezioni Canali.", ""),
        )
        fun resolvePreview(value: String): String {
            return StreamCenterPlugin.resolveHomeTitlePlaceholders(value, calendar)
        }
        val exampleTitle = "Anime - Calendario %giorno%"
        val previewText = titleText(resolvePreview(exampleTitle), 14, false).apply {
            setTextColor(Color.parseColor(COLOR_TEXT))
            setPadding(0, dp(4), 0, 0)
        }
        val titleInput = input("").apply {
            hint = "Es. Anime - Calendario %giorno%"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(5)
            }
            doAfterTextChanged { editable ->
                val value = editable?.toString().orEmpty()
                previewText.text = resolvePreview(value.ifBlank { exampleTitle })
                previewText.setTextColor(Color.parseColor(COLOR_TEXT))
            }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(10), dp(13), dp(11))
                background = cardBackground(COLOR_CARD_ALT, tint(accent, "55"), 12)
                addView(bodyText("Prova un nome", 11).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor(accent))
                })
                addView(titleInput)
                addView(bodyText("Anteprima", 11).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(10), 0, 0)
                })
                addView(previewText)
            })
            addView(bodyText("Tocca per copiare • Tieni premuto per inserire.", 12).apply {
                setPadding(dp(2), dp(12), dp(2), dp(2))
            })
            placeholders.forEach { (token, description, example) ->
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    minimumHeight = dp(56)
                    setPadding(dp(13), dp(9), dp(13), dp(9))
                    background = interactiveBackground(
                        COLOR_INPUT_FILL,
                        accent,
                        12,
                        strokeColor = tint(accent, "55"),
                    )
                    layoutParams = verticalParams(top = 6)
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Copia $token. Tieni premuto per inserirlo nel nome"
                    setOnClickListener {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.setPrimaryClip(ClipData.newPlainText("Segnaposto", token))
                        saveToast("$token copiato")
                    }
                    setOnLongClickListener {
                        val editable = titleInput.text
                        val start = titleInput.selectionStart.takeIf { it >= 0 } ?: editable.length
                        val end = titleInput.selectionEnd.takeIf { it >= 0 } ?: start
                        val insertionStart = minOf(start, end)
                        editable.replace(insertionStart, maxOf(start, end), token)
                        titleInput.requestFocus()
                        titleInput.setSelection((insertionStart + token.length).coerceAtMost(editable.length))
                        saveToast("$token inserito")
                        true
                    }
                    addView(titleText(token, 13, true).apply {
                        setTextColor(Color.parseColor(accent))
                        typeface = Typeface.MONOSPACE
                    })
                    addView(bodyText(description, 11).apply {
                        if (example.isNotBlank()) {
                            text = SpannableString("$description, ad esempio $example").apply {
                                val exampleStart = length - example.length
                                setSpan(
                                    StyleSpan(Typeface.BOLD),
                                    exampleStart,
                                    length,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                                setSpan(
                                    ForegroundColorSpan(Color.parseColor(accent)),
                                    exampleStart,
                                    length,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                )
                            }
                        }
                        setPadding(0, dp(2), 0, 0)
                    })
                })
            }
        }
        val editor = content.getChildAt(0)
        content.removeViewAt(0)
        content.setPadding(0, 0, 0, 0)
        val placeholderScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        val dialogContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(editor)
            addView(
                placeholderScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400)).apply {
                    topMargin = dp(8)
                },
            )
        }
        val title = titleText("Segnaposto disponibili", 20).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(22), dp(24), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(title)
            .setView(dialogContent)
            .setPositiveButton("Chiudi", null)
            .create()
        applyHomeDialogBackdrop(dialog)
        dialog.show()
    }

    private fun loadRows() {
        rows.clear()
        categoryOrder.clear()
        categoryOrder += StreamCenterPlugin.getHomeCategoryOrder(sharedPref)
        categoryEnabled.clear()
        StreamCenterPlugin.homeCategories.forEach { categoryKey ->
            categoryEnabled[categoryKey] = StreamCenterPlugin.isHomeCategoryEnabled(sharedPref, categoryKey)
        }
        val allSections = StreamCenterPlugin.getAllHomeSections(sharedPref)
        val byKey = allSections.associateBy { it.key }
        val orderedKeys = sharedPref
            ?.getString(StreamCenterPlugin.PREF_HOME_ORDER, null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it in byKey }
            ?.distinct()
            .orEmpty()
        val order = orderedKeys + allSections.map { it.key }.filterNot { it in orderedKeys }
        order.mapNotNull { byKey[it] }
            .sortedBy { categoryOrder.indexOf(StreamCenterPlugin.homeSectionCategoryKey(it)) }
            .forEach { section ->
            rows += HomeRowState(
                section = section,
                title = StreamCenterPlugin.getHomeSectionTitleTemplate(sharedPref, section),
                enabled = StreamCenterPlugin.isHomeSectionEnabled(sharedPref, section),
                count = normalizedCount(
                    StreamCenterPlugin.getHomeSectionCount(sharedPref, section).toString(),
                    section,
                ),
            )
        }
    }

    private fun renderRows() {
        val container = rowsContainer ?: return
        replaceBorderSparkleCycle("home-categories", emptyList())
        container.removeAllViews()
        val sparkleTargets = mutableListOf<BorderSparkleTarget>()
        categoryOrder.forEachIndexed { categoryIndex, categoryKey ->
            val categoryRows = rows.withIndex().filter {
                StreamCenterPlugin.homeSectionCategoryKey(it.value.section) == categoryKey
            }
            if (categoryRows.isEmpty() && categoryKey !in setOf("live", "tracking")) return@forEachIndexed
            val accent = categoryAccent(categoryKey)
            val enabled = categoryEnabled[categoryKey] ?: true
            val categoryContainer = LinearLayout(requireContext()).apply {
                tag = "home-category-container:$categoryKey"
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(dp(6), dp(6), dp(6), dp(8))
                background = cardBackground(
                    if (enabled) COLOR_CARD_ALT else "#151920",
                    if (enabled) tint(accent, "88") else COLOR_STROKE,
                    radius = 20,
                )
                layoutParams = verticalParams(top = if (categoryIndex == 0) 0 else 10)
            }
            categoryContainer.addView(categoryHeader(categoryKey, categoryContainer))
            if (expandedCategoryKey == categoryKey) {
                val expandedContent = LinearLayout(requireContext()).apply {
                    tag = "home-category-content:$categoryKey"
                    orientation = LinearLayout.VERTICAL
                }
                if (categoryKey == "live" && categoryRows.isEmpty()) {
                    expandedContent.addView(tvEmptyState())
                }
                if (categoryKey == "tracking" && categoryRows.isEmpty()) {
                    expandedContent.addView(trackingEmptyState())
                }
                categoryRows.forEach { indexedRow ->
                    expandedContent.addView(homeRow(indexedRow.index, indexedRow.value))
                }
                if (categoryKey == "live") {
                    expandedContent.addView(tvCategoryFooter())
                }
                if (categoryKey == "anime") {
                    expandedContent.addView(animeCategoryFooter())
                }
                if (categoryKey == "tracking") {
                    expandedContent.addView(trackingCategoryFooter())
                }
                categoryContainer.addView(expandedContent)
                if (pendingCategoryExpansionKey == categoryKey) {
                    animateCategoryExpansion(expandedContent)
                }
            }
            sparkleTargets += BorderSparkleTarget(categoryContainer, accent)
            container.addView(categoryContainer)
        }
        replaceBorderSparkleCycle("home-categories", sparkleTargets)
    }

    private fun categoryHeader(categoryKey: String, categoryContainer: LinearLayout): LinearLayout {
        return LinearLayout(requireContext()).apply {
            val headerView = this
            val accent = categoryAccent(categoryKey)
            val enabled = categoryEnabled[categoryKey] ?: true
            val expanded = expandedCategoryKey == categoryKey
            tag = "home-category:$categoryKey"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            minimumHeight = dp(62)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            alpha = if (enabled) 1f else 0.55f
            background = null
            layoutParams = verticalParams()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                toggleHomeCategory(categoryKey)
            }

            val badge = iconBadge(categoryEmoji(categoryKey), accent, size = 38, marginEnd = 10)
            addView(badge)
            val labels = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            labels.addView(titleText(categoryTitle(categoryKey), 16, true))
            labels.addView(bodyText(categorySectionsLabel(categoryKey), 11).apply {
                tag = "home-category-count:$categoryKey"
                setPadding(0, dp(2), 0, 0)
            })
            addView(labels)
            addView(styledSwitch(categoryEnabled[categoryKey] ?: true, accent) { checked ->
                categoryEnabled[categoryKey] = checked
                saveRows()
                playToggleFeedback(headerView, badge, accent, checked)
                updateCategoryAppearance(categoryContainer, categoryKey, checked)
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            })
            addView(iconButton("↑", "Sposta ${categoryTitle(categoryKey)} in alto", accent) {
                moveCategory(categoryKey, -1)
            })
            addView(iconButton("↓", "Sposta ${categoryTitle(categoryKey)} in basso", accent) {
                moveCategory(categoryKey, 1)
            })
            addView(iconButton(
                symbol = if (expanded) "−" else "+",
                description = if (expanded) "Chiudi ${categoryTitle(categoryKey)}" else "Apri ${categoryTitle(categoryKey)}",
                accent = accent,
            ) {
                toggleHomeCategory(categoryKey)
            })
        }
    }

    private fun toggleHomeCategory(categoryKey: String) {
        if (categoryTransitionRunning) return
        val currentExpanded = expandedCategoryKey
        if (currentExpanded != null) {
            val expandedContent = rowsContainer
                ?.findViewWithTag<View>("home-category-content:$currentExpanded")
            if (expandedContent != null) {
                categoryTransitionRunning = true
                animateCategoryCollapse(expandedContent) {
                    val opening = currentExpanded != categoryKey
                    expandedCategoryKey = categoryKey.takeIf { opening }
                    pendingCategoryExpansionKey = categoryKey.takeIf { opening }
                    renderRows()
                    pendingCategoryExpansionKey = null
                    categoryTransitionRunning = false
                }
                return
            }
        }
        val opening = expandedCategoryKey != categoryKey
        expandedCategoryKey = if (opening) categoryKey else null
        pendingCategoryExpansionKey = categoryKey.takeIf { opening }
        renderRows()
        pendingCategoryExpansionKey = null
    }

    private fun categorySectionsLabel(categoryKey: String): String {
        val categoryRows = rows.filter { StreamCenterPlugin.homeSectionCategoryKey(it.section) == categoryKey }
        if (categoryRows.isEmpty()) return "Nessuna sezione"
        return "${categoryRows.count { it.enabled }}/${categoryRows.size} sezioni"
    }

    private fun categoryTitle(categoryKey: String): String {
        return when (categoryKey) {
            "anime" -> "Anime"
            "tv" -> "Serie TV"
            "movie" -> "Film"
            "tracking" -> "Tracciamento"
            "live" -> "TV"
            else -> "Altro"
        }
    }

    private fun categoryEmoji(categoryKey: String): String {
        return when (categoryKey) {
            "anime" -> "🎌"
            "tv" -> "📺"
            "movie" -> "🎬"
            "tracking" -> "\uD83D\uDCDA"
            else -> "📁"
        }
    }

    private fun categoryAccent(categoryKey: String): String {
        return when (categoryKey) {
            "anime" -> COLOR_HOME_ANIME
            "tv" -> COLOR_HOME_TV
            "movie" -> COLOR_HOME_MOVIE
            "tracking" -> COLOR_HOME_TRACKING
            "live" -> COLOR_HOME_CHANNELS
            else -> COLOR_MUTED
        }
    }

    private fun moveCategory(categoryKey: String, direction: Int) {
        val index = categoryOrder.indexOf(categoryKey)
        val target = index + direction
        if (index < 0 || target !in categoryOrder.indices) return
        categoryOrder[index] = categoryOrder[target].also { categoryOrder[target] = categoryOrder[index] }
        rows.sortBy { categoryOrder.indexOf(StreamCenterPlugin.homeSectionCategoryKey(it.section)) }
        saveRows()
        renderRows()
    }

    private fun updateCategoryAppearance(
        categoryContainer: LinearLayout,
        categoryKey: String,
        enabled: Boolean,
    ) {
        val accent = categoryAccent(categoryKey)
        categoryContainer.getChildAt(0)?.let { header ->
            if (reduceMotion) {
                header.alpha = if (enabled) 1f else 0.55f
            } else {
                header.animate().cancel()
                header.animate().alpha(if (enabled) 1f else 0.55f).setDuration(220L).start()
            }
        }
        for (index in 1 until categoryContainer.childCount) {
            val view = categoryContainer.getChildAt(index)
            val sectionKey = (view.tag as? String)
                ?.removePrefix("home-section:")
                ?.takeIf { view.tag == "home-section:$it" }
            val row = sectionKey?.let { key -> rows.firstOrNull { it.section.key == key } }
            val targetAlpha = if (enabled) 1f else 0.5f
            if (reduceMotion) {
                view.alpha = targetAlpha
            } else {
                view.animate().cancel()
                view.animate().alpha(targetAlpha).setDuration(220L).start()
            }
            if (row != null) {
                animateCardFill(
                    view,
                    fromColor = if (!enabled && row.enabled) COLOR_CARD else COLOR_CARD_DISABLED,
                    toColor = if (enabled && row.enabled) COLOR_CARD else COLOR_CARD_DISABLED,
                )
            }
        }
        animateCardFill(
            categoryContainer,
            fromColor = if (enabled) "#151920" else COLOR_CARD_ALT,
            toColor = if (enabled) COLOR_CARD_ALT else "#151920",
            strokeColor = if (enabled) tint(accent, "88") else COLOR_STROKE,
            radius = 20,
        )
    }

    private fun homeRow(index: Int, row: HomeRowState): LinearLayout {
        return LinearLayout(requireContext()).apply {
            val rowView = this
            val categoryKey = StreamCenterPlugin.homeSectionCategoryKey(row.section)
            val accent = categoryAccent(categoryKey)
            val categoryIsEnabled = categoryEnabled[categoryKey] ?: true
            tag = "home-section:${row.section.key}"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(12))
            alpha = if (categoryIsEnabled) 1f else 0.5f
            background = cardBackground(
                if (row.enabled && categoryIsEnabled) COLOR_CARD else COLOR_CARD_DISABLED,
            )
            layoutParams = verticalParams(top = 8)

            val isLiveRow = categoryKey == "live"
            val isAnimeCalendarRow = row.section.key == "anime_calendar"
            val isAnimeCustomRow = row.section.key.startsWith(StreamCenterPlugin.ANIME_CUSTOM_SECTION_PREFIX)
            val isTrackingCustomRow = row.section.key.startsWith(StreamCenterPlugin.TRACKING_CUSTOM_SECTION_PREFIX)
            val topLine = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val channelCount = if (isLiveRow) {
                StreamCenterPlugin.getIptvSectionChannelIds(sharedPref, row.section.key).size
            } else {
                0
            }
            val trackingConfig = if (isTrackingCustomRow) {
                StreamCenterPlugin.getTrackingListConfig(sharedPref, row.section.key)
            } else {
                null
            }
            val sectionLabel = if (isLiveRow) {
                if (channelCount == 0) "Sezione TV · nessun canale" else "Sezione TV · $channelCount canali"
            } else if (isAnimeCalendarRow) {
                "Anime: calendario"
            } else if (trackingConfig != null) {
                "${trackingConfig.service.title} · ${trackingConfig.status.title}"
            } else {
                StreamCenterPlugin.getDefaultHomeSectionTitle(row.section.key).substringBefore(" (")
            }
            var persistCount: () -> Unit = {}
            val titleInput: EditText = input(row.title).apply {
                filters = arrayOf(InputFilter.LengthFilter(58))
                hint = "Es. %Giorno%, %Data%, %Mese%, %Canali%"
                doAfterTextChanged {
                    row.title = it?.toString()?.trim().orEmpty()
                    saveRows()
                }
            }
            val sectionLabelView = titleText(sectionLabel, 12, true).apply {
                setTextColor(Color.parseColor(if (row.enabled) accent else COLOR_MUTED))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            topLine.addView(sectionLabelView)
            topLine.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            topLine.addView(styledSwitch(row.enabled, accent) { checked ->
                row.enabled = checked
                saveRows()
                rowsContainer?.findViewWithTag<TextView>("home-category-count:$categoryKey")
                    ?.text = categorySectionsLabel(categoryKey)
                val categoryIsOn = categoryEnabled[categoryKey] ?: true
                sectionLabelView.setTextColor(Color.parseColor(if (checked) accent else COLOR_MUTED))
                animateCardFill(
                    rowView,
                    fromColor = if (!checked && categoryIsOn) COLOR_CARD else COLOR_CARD_DISABLED,
                    toColor = if (checked && categoryIsOn) COLOR_CARD else COLOR_CARD_DISABLED,
                )
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
            topLine.addView(iconButton("↑", "Sposta la sezione in alto", accent) {
                row.title = titleInput.text?.toString()?.trim().orEmpty()
                persistCount()
                moveRow(index, -1)
            })
            topLine.addView(iconButton("↓", "Sposta la sezione in basso", accent) {
                row.title = titleInput.text?.toString()?.trim().orEmpty()
                persistCount()
                moveRow(index, 1)
            })
            addView(topLine)

            val controls = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (isLiveRow) {
                fun liveButton(
                    text: String,
                    color: String,
                    first: Boolean = false,
                    onClick: () -> Unit,
                ) = actionButton(text, color, onClick).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (!first) marginStart = dp(8) }
                }
                controls.addView(liveButton(
                    if (channelCount == 0) "Scegli canali" else "Canali",
                    accent,
                    first = true,
                ) {
                    row.title = titleInput.text?.toString()?.trim().orEmpty()
                    showIptvChannelPicker(row.section.key)
                })
                if (channelCount > 0) {
                    controls.addView(liveButton("Selezionati", accent) {
                        row.title = titleInput.text?.toString()?.trim().orEmpty()
                        showIptvSelectedChannels(row.section.key)
                    })
                }
                controls.addView(iconButton("X", "Elimina la sezione TV", COLOR_DANGER) {
                    confirmDeleteTvSection(row.section.key)
                })
            } else {
                val countInput: EditText = input(row.count.toString(), widthDp = 70).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    gravity = Gravity.CENTER
                    setSelectAllOnFocus(true)
                    contentDescription = "Numero di titoli"
                    setOnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) {
                            row.count = normalizedCount(text?.toString(), row.section)
                            val normalizedText = row.count.toString()
                            if (text?.toString() != normalizedText) setText(normalizedText)
                            saveRows()
                        }
                    }
                    (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
                }
                controls.addView(countInput)
                if (isAnimeCustomRow) {
                    controls.addView(iconButton("E", "Modifica i filtri della sezione", accent) {
                        row.title = titleInput.text?.toString()?.trim().orEmpty()
                        row.count = normalizedCount(countInput.text?.toString(), row.section)
                        StreamCenterPlugin.updateAnimeCustomSection(
                            sharedPref,
                            row.section.key,
                            StreamCenterPlugin.getAnimeCustomSectionFilters(sharedPref, row.section.key)
                                ?: StreamCenterAnimeArchiveFilters(),
                            row.count,
                            row.title,
                        )
                        promptCreateAnimeSection(row.section.key)
                    }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
                    controls.addView(iconButton("X", "Elimina la sezione", COLOR_DANGER) {
                        confirmDeleteAnimeSection(row.section.key)
                    }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
                }
                if (isTrackingCustomRow) {
                    controls.addView(iconButton("X", "Elimina la lista", COLOR_DANGER) {
                        confirmDeleteTrackingSection(row.section.key)
                    }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
                }
                persistCount = { row.count = normalizedCount(countInput.text?.toString(), row.section) }
            }
            if (isLiveRow) {
                addView(titleInput.apply { layoutParams = verticalParams(top = 10) })
                addView(controls.apply { layoutParams = verticalParams(top = 10) })
            } else {
                val titleAndCountRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = verticalParams(top = 10)
                }
                titleAndCountRow.addView(titleInput.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply { marginEnd = dp(2) }
                })
                titleAndCountRow.addView(controls)
                addView(titleAndCountRow)
            }
        }
    }

    private fun moveRow(index: Int, direction: Int) {
        val target = index + direction
        if (target !in rows.indices) return
        if (StreamCenterPlugin.homeSectionCategory(rows[index].section) !=
            StreamCenterPlugin.homeSectionCategory(rows[target].section)
        ) return
        rows[index] = rows[target].also { rows[target] = rows[index] }
        saveRows()
        renderRows()
    }

    private fun maxCountFor(section: StreamCenterHomeSectionDefinition): Int {
        return when (section.key) {
            "tv_top10", "movie_top10" -> 10
            else -> StreamCenterPlugin.MAX_HOME_COUNT
        }
    }

    private fun normalizedCount(raw: String?, section: StreamCenterHomeSectionDefinition): Int {
        return normalizedCount(raw, maxCountFor(section))
    }

    private fun normalizedCount(raw: String?, maxCount: Int): Int {
        val fallback = minOf(StreamCenterPlugin.DEFAULT_HOME_COUNT, maxCount)
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.any { !it.isDigit() }) return fallback

        val digits = value.trimStart('0').ifEmpty { "0" }
        val maxText = maxCount.toString()
        val exceedsMaximum = digits.length > maxText.length ||
            (digits.length == maxText.length && digits > maxText)
        if (exceedsMaximum) return maxCount

        return digits.toIntOrNull()
            ?.coerceAtLeast(StreamCenterPlugin.MIN_HOME_COUNT)
            ?: maxCount
    }

    private fun saveRows() {
        sharedPref?.edit {
            putString(StreamCenterPlugin.PREF_HOME_ORDER, rows.joinToString(",") { it.section.key })
            putString(StreamCenterPlugin.PREF_HOME_CATEGORY_ORDER, categoryOrder.joinToString(","))
            categoryEnabled.forEach { (categoryKey, enabled) ->
                putBoolean(StreamCenterPlugin.homeCategoryEnabledKey(categoryKey), enabled)
            }
            rows.forEach { row ->
                row.count = normalizedCount(row.count.toString(), row.section)
                putBoolean(StreamCenterPlugin.sectionEnabledKey(row.section.key), row.enabled)
                putString(
                    StreamCenterPlugin.sectionTitleKey(row.section.key),
                    row.title.takeIf { it.isNotBlank() }
                        ?: StreamCenterPlugin.getDefaultHomeSectionTitle(row.section.key),
                )
                putInt(StreamCenterPlugin.sectionCountKey(row.section.key), row.count)
            }
        }
    }

    private fun applyHomeDialogBackdrop(dialog: AlertDialog, onDismiss: (() -> Unit)? = null) {
        dialog.setOnShowListener {
            openHomeDialogs += 1
            homeContent?.apply {
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
        dialog.setOnDismissListener {
            openHomeDialogs = (openHomeDialogs - 1).coerceAtLeast(0)
            if (openHomeDialogs == 0) {
                homeContent?.apply {
                    alpha = 1f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
                }
            }
            onDismiss?.invoke()
        }
    }

    private fun resetHome() {
        sharedPref?.edit {
            remove(StreamCenterPlugin.PREF_HOME_ORDER)
            remove(StreamCenterPlugin.PREF_HOME_CATEGORY_ORDER)
            StreamCenterPlugin.homeCategories.forEach { categoryKey ->
                remove(StreamCenterPlugin.homeCategoryEnabledKey(categoryKey))
            }
            StreamCenterPlugin.getAllHomeSections(sharedPref).forEach { section ->
                remove(StreamCenterPlugin.sectionEnabledKey(section.key))
                remove(StreamCenterPlugin.sectionTitleKey(section.key))
                remove(StreamCenterPlugin.sectionCountKey(section.key))
            }
            StreamCenterPlugin.getIptvCustomSectionKeys(sharedPref).forEach { sectionKey ->
                remove(StreamCenterPlugin.iptvSectionChannelsKey(sectionKey))
                remove(StreamCenterPlugin.iptvSectionOrderKey(sectionKey))
            }
            StreamCenterPlugin.getAnimeCustomSectionKeys(sharedPref).forEach { sectionKey ->
                StreamCenterPlugin.deleteAnimeCustomSection(sharedPref, sectionKey)
            }
            StreamCenterPlugin.getTrackingCustomSectionKeys(sharedPref).forEach { sectionKey ->
                StreamCenterPlugin.deleteTrackingCustomSection(sharedPref, sectionKey)
            }
            remove(StreamCenterPlugin.PREF_IPTV_CUSTOM_SECTIONS)
            remove(StreamCenterPlugin.PREF_TRACKING_CUSTOM_SECTIONS)
        }
        loadRows()
        renderRows()
        saveToast("Home ripristinata")
    }
    private fun tvSectionTitle(sectionKey: String): String {
        return StreamCenterPlugin.getHomeSectionTitle(
            sharedPref,
            StreamCenterPlugin.iptvCustomSectionDefinition(sectionKey),
        )
    }

    private fun animeCategoryFooter(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 8)
            addView(actionButton("+ Crea sezione Anime", categoryAccent("anime")) {
                promptCreateAnimeSection()
            })
        }
    }

    private fun normalizedCustomSectionName(value: String): String {
        return value.trim().replace(Regex("\\s+"), " ")
    }

    private fun savedCustomSectionNames(
        keys: List<String>,
        definitionForKey: (String) -> StreamCenterHomeSectionDefinition,
        excludedSectionKey: String? = null,
    ): List<String> {
        return keys
            .asSequence()
            .filter { it != excludedSectionKey }
            .map { key -> StreamCenterPlugin.getHomeSectionTitle(sharedPref, definitionForKey(key)) }
            .map(::normalizedCustomSectionName)
            .toList()
    }

    private fun resolvedCustomSectionName(
        value: String,
        fallbackName: String,
        keys: List<String>,
        definitionForKey: (String) -> StreamCenterHomeSectionDefinition,
        excludedSectionKey: String? = null,
    ): String {
        val requestedName = normalizedCustomSectionName(value)
        if (requestedName.isNotBlank()) return requestedName
        val usedNames = savedCustomSectionNames(keys, definitionForKey, excludedSectionKey)
        var suffix = 1
        var candidate = fallbackName
        while (usedNames.any { it.equals(candidate, ignoreCase = true) }) {
            suffix += 1
            candidate = "$fallbackName $suffix"
        }
        return candidate
    }

    private fun hasDuplicateCustomSectionName(
        value: String,
        keys: List<String>,
        definitionForKey: (String) -> StreamCenterHomeSectionDefinition,
        excludedSectionKey: String? = null,
    ): Boolean {
        val candidate = normalizedCustomSectionName(value)
        return candidate.isNotBlank() && savedCustomSectionNames(
            keys,
            definitionForKey,
            excludedSectionKey,
        ).any { it.equals(candidate, ignoreCase = true) }
    }

    private fun promptCreateAnimeSection(sectionKey: String? = null) {
        val ctx = context ?: return
        val existing = sectionKey?.let { StreamCenterPlugin.getAnimeCustomSectionFilters(sharedPref, it) }
        var genreId: Int? = existing?.genreId
        var order: String? = existing?.order
        var status: String? = existing?.status
        var type: String? = existing?.type
        var season: String? = existing?.season
        val filterRowHeight = dp(64)
        val accent = categoryAccent("anime")

        fun filterText(label: String, value: String): SpannableString {
            val text = SpannableString("$label\n$value")
            text.setSpan(
                ForegroundColorSpan(Color.parseColor(COLOR_MUTED)),
                0,
                label.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            text.setSpan(RelativeSizeSpan(0.84f), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(
                StyleSpan(Typeface.BOLD),
                label.length + 1,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            return text
        }

        fun filterChoice(
            label: String,
            options: List<String>,
            currentValue: () -> String?,
            interactive: Boolean = true,
            onSelected: (String?) -> Unit,
        ): TextView {
            return TextView(ctx).apply {
                val any = "Qualsiasi"
                text = filterText(label, currentValue() ?: any)
                textSize = 13f
                setTextColor(Color.parseColor(COLOR_TEXT))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = cardBackground(COLOR_INPUT_FILL, COLOR_STROKE, 12)
                layoutParams = verticalParams(top = 8).apply { height = filterRowHeight }
                if (interactive) {
                    setOnClickListener {
                        val labels = listOf(any) + options
                        val selectedIndex = labels.indexOf(currentValue()).coerceAtLeast(0)
                        AlertDialog.Builder(ctx)
                            .setTitle(label)
                            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { dialog, which ->
                                val value = labels[which].takeUnless { it == any }
                                text = filterText(label, value ?: any)
                                onSelected(value)
                                dialog.dismiss()
                            }
                            .show()
                    }
                }
            }
        }

        fun formInput(label: String, field: EditText, hint: String): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = cardBackground(COLOR_INPUT_FILL, COLOR_STROKE, 12)
                layoutParams = verticalParams(top = 8).apply { height = filterRowHeight }
                addView(bodyText(label, 11).apply {
                    setTextColor(Color.parseColor(COLOR_MUTED))
                })
                addView(field.apply {
                    this.hint = hint
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setPadding(0, 0, 0, 0)
                    background = null
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(28),
                    )
                })
            }
        }

        val nameInput = input(sectionKey?.let {
            StreamCenterPlugin.getHomeSectionTitle(sharedPref, StreamCenterPlugin.animeCustomSectionDefinition(it))
        }.orEmpty()).apply {
            filters = arrayOf(InputFilter.LengthFilter(58))
        }
        val nameField = formInput("Nome della sezione", nameInput, "Inserisci un nome")
        val yearInput = input(existing?.year?.toString().orEmpty()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        val yearField = formInput("Anno", yearInput, "Qualsiasi")

        fun resolvedAnimeSectionName(value: String): String {
            return resolvedCustomSectionName(
                value = value,
                fallbackName = "Anime: sezione personalizzata",
                keys = StreamCenterPlugin.getAnimeCustomSectionKeys(sharedPref),
                definitionForKey = { key -> StreamCenterPlugin.animeCustomSectionDefinition(key) },
                excludedSectionKey = sectionKey,
            )
        }

        fun hasDuplicateAnimeSectionName(value: String): Boolean {
            return hasDuplicateCustomSectionName(
                value = value,
                keys = StreamCenterPlugin.getAnimeCustomSectionKeys(sharedPref),
                definitionForKey = { key -> StreamCenterPlugin.animeCustomSectionDefinition(key) },
                excludedSectionKey = sectionKey,
            )
        }

        val checkedAnimeUnityYears = mutableMapOf<Int, Boolean>()
        var yearValidationRequest = 0
        var yearValidationInProgress: Int? = null

        fun isAnimeUnityYearAvailable(year: Int): Boolean {
            val baseUrl = StreamCenterPlugin.getSourceBaseUrl(
                sharedPref,
                StreamCenterPlugin.PREF_SOURCE_ANIMEUNITY,
            ).ifBlank { StreamCenterPlugin.DEFAULT_URL_ANIMEUNITY }.trimEnd('/')
            val archiveUrl = "$baseUrl/archivio"
            val archiveResponse = Jsoup.connect(archiveUrl)
                .userAgent("Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 Chrome/131 Safari/537.36")
                .header("Accept-Language", "it-IT,it;q=0.9")
                .timeout(15_000)
                .execute()
            val csrfToken = Jsoup.parse(archiveResponse.body(), archiveUrl)
                .selectFirst("meta[name=csrf-token]")
                ?.attr("content")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: error("Token di AnimeUnity non disponibile")
            val requestBody = JSONObject().apply {
                put("title", "")
                put("type", false)
                put("year", year)
                put("order", false)
                put("status", false)
                put("genres", false)
                put("season", false)
                put("dubbed", 0)
                put("offset", 0)
            }.toString()
            val response = Jsoup.connect("$archiveUrl/get-animes")
                .method(Connection.Method.POST)
                .userAgent("Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 Chrome/131 Safari/537.36")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("X-CSRF-TOKEN", csrfToken)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", archiveUrl)
                .cookies(archiveResponse.cookies())
                .requestBody(requestBody)
                .ignoreContentType(true)
                .timeout(15_000)
                .execute()
            return JSONObject(response.body()).optJSONArray("records")?.length()?.let { it > 0 } == true
        }

        fun validateAnimeUnityYear(rawValue: String) {
            val value = rawValue.trim()
            if (value.length != 4) {
                yearValidationRequest += 1
                yearValidationInProgress = null
                yearInput.error = null
                return
            }
            val year = value.toIntOrNull() ?: return
            checkedAnimeUnityYears[year]?.let { available ->
                yearInput.error = if (available) null else "Anno non disponibile"
                return
            }
            if (yearValidationInProgress == year) return

            yearValidationInProgress = year
            val request = ++yearValidationRequest
            yearInput.error = null
            CoroutineScope(Dispatchers.IO).launch {
                val result = runCatching { isAnimeUnityYearAvailable(year) }
                withContext(Dispatchers.Main) {
                    if (request != yearValidationRequest || yearInput.text?.toString()?.trim() != value) {
                        return@withContext
                    }
                    yearValidationInProgress = null
                    result.getOrNull()?.let { available ->
                        checkedAnimeUnityYears[year] = available
                        yearInput.error = if (available) null else "Anno non disponibile"
                    } ?: run {
                        yearInput.error = "Impossibile verificare l'anno su AnimeUnity"
                    }
                }
            }
        }

        fun hasValidAnimeUnityYear(): Boolean {
            val value = yearInput.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) return true
            if (value.length != 4) {
                yearInput.error = "Inserisci un anno di quattro cifre"
                yearInput.requestFocus()
                return false
            }
            val year = value.toIntOrNull() ?: return false
            return when (checkedAnimeUnityYears[year]) {
                true -> true
                false -> {
                    yearInput.error = "Anno non disponibile"
                    yearInput.requestFocus()
                    false
                }
                null -> {
                    validateAnimeUnityYear(value)
                    yearInput.error = "Attendi la verifica dell'anno"
                    yearInput.requestFocus()
                    false
                }
            }
        }

        yearInput.doAfterTextChanged { validateAnimeUnityYear(it?.toString().orEmpty()) }
        yearInput.text?.toString()?.takeIf(String::isNotBlank)?.let(::validateAnimeUnityYear)
        val genres = listOf(
            51 to "Action", 21 to "Adventure", 43 to "Avant Garde", 59 to "Boys Love",
            37 to "Comedy", 13 to "Demons", 22 to "Drama", 5 to "Ecchi", 9 to "Fantasy",
            44 to "Game", 58 to "Girls Love", 52 to "Gore", 56 to "Gourmet", 15 to "Harem",
            30 to "Historical", 3 to "Horror", 53 to "Isekai", 45 to "Josei", 14 to "Kids",
            57 to "Mahou Shoujo", 31 to "Martial Arts", 38 to "Mecha", 46 to "Military",
            16 to "Music", 24 to "Mystery", 32 to "Parody", 39 to "Police", 47 to "Psychological",
            29 to "Racing", 54 to "Reincarnation", 17 to "Romance", 25 to "Samurai", 33 to "School",
            40 to "Sci-fi", 49 to "Seinen", 18 to "Shoujo", 34 to "Shounen", 50 to "Slice of Life",
            19 to "Space", 27 to "Sports", 35 to "Super Power", 42 to "Supernatural", 55 to "Survival",
            48 to "Thriller", 20 to "Vampire",
        )
        val genreChoice = filterChoice("Genere", genres.map { it.second }, {
            genres.firstOrNull { it.first == genreId }?.second
        }, interactive = false) { selected ->
            genreId = genres.firstOrNull { it.second == selected }?.first
        }
        genreChoice.setOnClickListener {
            val searchInput = input("").apply {
                hint = "Cerca un genere"
                layoutParams = verticalParams()
            }
            val genreList = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            val genreScroll = ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(genreList)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(300),
                ).apply { topMargin = dp(8) }
            }
            lateinit var picker: AlertDialog

            fun selectGenre(selected: Pair<Int, String>?) {
                genreId = selected?.first
                genreChoice.text = filterText("Genere", selected?.second ?: "Qualsiasi")
                picker.dismiss()
            }

            fun renderGenres(query: String = "") {
                val shownGenres = genres.filter { it.second.contains(query, ignoreCase = true) }
                genreList.removeAllViews()
                val choices = listOf<Pair<Int, String>?>(null) + shownGenres
                choices.forEachIndexed { index, choice ->
                    val selected = choice?.first == genreId
                    genreList.addView(TextView(ctx).apply {
                        text = if (selected) "✓  ${choice?.second ?: "Qualsiasi"}" else choice?.second ?: "Qualsiasi"
                        textSize = 14f
                        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        setTextColor(Color.parseColor(if (selected) accent else COLOR_TEXT))
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(48)
                        setPadding(dp(14), dp(8), dp(14), dp(8))
                        background = interactiveBackground(
                            if (selected) tint(accent, "20") else COLOR_INPUT_FILL,
                            accent,
                            12,
                            strokeColor = if (selected) tint(accent, "AA") else COLOR_STROKE,
                        )
                        layoutParams = verticalParams(top = if (index == 0) 0 else 6)
                        setOnClickListener { selectGenre(choice) }
                    })
                }
            }
            val pickerContent = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(8))
                background = cardBackground(COLOR_CARD_ALT, tint(accent, "66"), 18)
                addView(searchInput)
                addView(genreScroll)
            }
            val pickerTitle = titleText("Genere", 20).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(22), dp(24), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            picker = AlertDialog.Builder(ctx)
                .setCustomTitle(pickerTitle)
                .setView(pickerContent)
                .setNegativeButton("Annulla", null)
                .create()
            searchInput.doAfterTextChanged { renderGenres(it?.toString().orEmpty()) }
            renderGenres()
            applyHomeDialogBackdrop(picker)
            picker.show()
        }
        val orderChoice = filterChoice("Ordine", listOf("A-Z", "Z-A", "Popolarità", "Valutazione"), { order }) {
            order = it
        }
        val statusChoice = filterChoice("Stato", listOf("In Corso", "Terminato", "In Uscita", "Droppato"), { status }) {
            status = it
        }
        val typeChoice = filterChoice("Tipo", listOf("TV", "TV Short", "OVA", "ONA", "Special", "Movie"), { type }) {
            type = it
        }
        val seasonChoice = filterChoice("Stagione", listOf("Inverno", "Primavera", "Estate", "Autunno"), { season }) {
            season = it
        }
        val dubSwitch = styledSwitch(existing?.dubbed == true, categoryAccent("anime")) { }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        }
        val dubRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
            layoutParams = verticalParams(top = 6)
            addView(bodyText("DUB ITA", 13).apply {
                setTextColor(Color.parseColor(COLOR_TEXT))
            })
            addView(dubSwitch)
        }
        val countInput = input(
            sectionKey?.let { StreamCenterPlugin.getHomeSectionCount(sharedPref, StreamCenterPlugin.animeCustomSectionDefinition(it)) }
                ?.toString()
                ?: StreamCenterPlugin.DEFAULT_HOME_COUNT.toString(),
            widthDp = 70,
        ).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            setSelectAllOnFocus(true)
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val normalized = normalizedCount(text?.toString(), StreamCenterPlugin.MAX_HOME_COUNT)
                    if (text?.toString() != normalized.toString()) setText(normalized.toString())
                }
            }
        }
        val countRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(bodyText("Elementi da mostrare", 13).apply {
                setTextColor(Color.parseColor(COLOR_TEXT))
            })
            addView(countInput.apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            })
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(12))
            background = cardBackground(COLOR_CARD_ALT, tint(categoryAccent("anime"), "66"), 20)
            addView(nameField)
            addView(genreChoice)
            addView(yearField)
            addView(orderChoice)
            addView(statusChoice)
            addView(typeChoice)
            addView(seasonChoice)
            addView(dubRow)
            addView(countRow)
        }
        val scroll = ScrollView(ctx).apply {
            addView(content)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val dialogTitle = titleText(
            if (sectionKey == null) "Nuova sezione Anime" else "Modifica sezione Anime",
            20,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(22), dp(24), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        fun saveAnimeSection(): Boolean {
            val requestedName = nameInput.text?.toString().orEmpty()
            val sectionName = resolvedAnimeSectionName(requestedName)
            if (hasDuplicateAnimeSectionName(sectionName)) {
                nameInput.error = "Esiste già una sezione Anime con questo nome"
                nameInput.requestFocus()
                return false
            }
            if (!hasValidAnimeUnityYear()) return false
            val filters = StreamCenterAnimeArchiveFilters(
                genreId = genreId,
                year = yearInput.text?.toString()?.toIntOrNull(),
                order = order,
                status = status,
                type = type,
                season = season,
                dubbed = dubSwitch.isChecked,
            )
            val count = normalizedCount(
                countInput.text?.toString(),
                StreamCenterPlugin.MAX_HOME_COUNT,
            )
            val saved = if (sectionKey == null) {
                StreamCenterPlugin.createAnimeCustomSection(
                    sharedPref,
                    filters,
                    count,
                    sectionName,
                ) != null
            } else {
                StreamCenterPlugin.updateAnimeCustomSection(
                    sharedPref,
                    sectionKey,
                    filters,
                    count,
                    sectionName,
                )
            }
            if (!saved) {
                saveToast("Impossibile creare la sezione")
                return false
            }
            loadRows()
            renderRows()
            saveToast(if (sectionKey == null) "Sezione Anime creata" else "Sezione Anime aggiornata")
            return true
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle)
            .setView(scroll)
            .setPositiveButton(if (sectionKey == null) "Crea" else "Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        applyHomeDialogBackdrop(dialog)
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (saveAnimeSection()) dialog.dismiss()
        }
    }

    private fun confirmDeleteAnimeSection(sectionKey: String) {
        val name = StreamCenterPlugin.getHomeSectionTitle(
            sharedPref,
            StreamCenterPlugin.animeCustomSectionDefinition(sectionKey),
        )
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Elimina sezione Anime")
            .setMessage("Vuoi eliminare la sezione \"$name\"?")
            .setPositiveButton("Elimina") { _, _ ->
                StreamCenterPlugin.deleteAnimeCustomSection(sharedPref, sectionKey)
                loadRows()
                renderRows()
                saveToast("Sezione eliminata")
            }
            .setNegativeButton("Annulla", null)
            .create()
        applyHomeDialogBackdrop(dialog)
        dialog.show()
    }

    private fun trackingEmptyState(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(COLOR_CARD, tint(categoryAccent("tracking"), "55"), 16)
            layoutParams = verticalParams(top = 8)
            addView(titleText("Nessuna lista di tracciamento", 14, true))
            addView(bodyText(
                "Aggiungi una lista da un servizio collegato per mostrarla nella Home.",
                12,
            ).apply { setPadding(0, dp(4), 0, 0) })
        }
    }

    private fun trackingCategoryFooter(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 8)
            addView(actionButton("+ Aggiungi lista di tracciamento", categoryAccent("tracking")) {
                promptCreateTrackingSection()
            })
        }
    }

    private fun isTrackingServiceConnected(service: StreamCenterTrackingService): Boolean {
        return runCatching {
            AccountManager.syncApis
                .firstOrNull { it.syncIdName == service.syncIdName }
                ?.authData() != null
        }.getOrDefault(false)
    }

    private fun promptCreateTrackingSection() {
        val ctx = context ?: return
        val accent = categoryAccent("tracking")
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        lateinit var dialog: AlertDialog
        StreamCenterPlugin.trackingServices.forEach { service ->
            val connected = isTrackingServiceConnected(service)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(66)
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = cardBackground(
                    if (connected) COLOR_CARD_ALT else COLOR_CARD_DISABLED,
                    if (connected) tint(accent, "66") else COLOR_STROKE,
                    14,
                )
                layoutParams = verticalParams(top = 8)
                alpha = if (connected) 1f else 0.48f
                isClickable = connected
                isFocusable = connected
                addView(titleText(service.title, 15, true).apply {
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(if (connected) COLOR_TEXT else COLOR_MUTED))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                })
                addView(bodyText(
                    if (connected) "Collegato" else "Non collegato nelle impostazioni di CloudStream",
                    12,
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(3), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                })
                if (connected) {
                    setOnClickListener {
                        dialog.dismiss()
                        promptTrackingListType(service)
                    }
                }
            }
            list.addView(row)
        }
        val dialogTitle = titleText("Servizio di tracciamento", 18, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(22), dp(24), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle)
            .setView(list)
            .setNegativeButton("Annulla", null)
            .create()
        applyHomeDialogBackdrop(dialog)
        dialog.show()
    }

    private fun promptTrackingListType(service: StreamCenterTrackingService) {
        val ctx = context ?: return
        val accent = categoryAccent("tracking")
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(titleText(service.title, 15, true).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(accent))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
            addView(bodyText("Scegli la lista da mostrare nella Home.", 12).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
        }
        lateinit var dialog: AlertDialog
        service.statuses.forEach { status ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(58)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = cardBackground(COLOR_CARD_ALT, tint(accent, "66"), 14)
                layoutParams = verticalParams(top = 8)
                isClickable = true
                isFocusable = true
                addView(TextView(ctx).apply {
                    text = "\u2022"
                    gravity = Gravity.CENTER
                    textSize = 22f
                    setTextColor(Color.parseColor(accent))
                    background = cardBackground(tint(accent, "22"), tint(accent, "99"), 17)
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                        marginEnd = dp(10)
                    }
                })
                addView(titleText(status.title, 14, true).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                })
                addView(bodyText("›", 26).apply {
                    setTextColor(Color.parseColor(accent))
                    gravity = Gravity.CENTER
                })
                setOnClickListener {
                    dialog.dismiss()
                    promptTrackingSectionName(service, status)
                }
            }
            content.addView(row)
        }
        dialog = AlertDialog.Builder(ctx)
            .setView(content)
            .setNegativeButton("Indietro", null)
            .create()
        applyHomeDialogBackdrop(dialog)
        dialog.show()
    }

    private fun promptTrackingSectionName(
        service: StreamCenterTrackingService,
        status: StreamCenterTrackingListStatus,
    ) {
        val ctx = context ?: return
        val defaultName = "${service.title} - ${status.title}"
        val nameInput = input(defaultName).apply {
            hint = "Nome della sezione"
            filters = arrayOf(InputFilter.LengthFilter(58))
            layoutParams = verticalParams()
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(nameInput)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Nome della sezione")
            .setView(content)
            .setPositiveButton("Crea", null)
            .setNegativeButton("Annulla", null)
            .create()
        applyHomeDialogBackdrop(dialog)
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val requestedName = nameInput.text?.toString().orEmpty()
            val sectionName = resolvedCustomSectionName(
                value = requestedName,
                fallbackName = defaultName,
                keys = StreamCenterPlugin.getTrackingCustomSectionKeys(sharedPref),
                definitionForKey = { key -> StreamCenterPlugin.trackingCustomSectionDefinition(key) },
            )
            if (hasDuplicateCustomSectionName(
                    value = sectionName,
                    keys = StreamCenterPlugin.getTrackingCustomSectionKeys(sharedPref),
                    definitionForKey = { key -> StreamCenterPlugin.trackingCustomSectionDefinition(key) },
                )
            ) {
                nameInput.error = "Esiste già una lista di tracciamento con questo nome"
                nameInput.requestFocus()
                return@setOnClickListener
            }
            val sectionKey = StreamCenterPlugin.createTrackingCustomSection(
                sharedPref,
                service,
                status,
                sectionName,
            )
            if (sectionKey == null) {
                saveToast("Impossibile creare la lista")
                return@setOnClickListener
            }
            categoryEnabled["tracking"] = true
            loadRows()
            renderRows()
            saveToast("Lista di tracciamento creata")
            dialog.dismiss()
        }
    }

    private fun confirmDeleteTrackingSection(sectionKey: String) {
        val name = StreamCenterPlugin.getHomeSectionTitle(
            sharedPref,
            StreamCenterPlugin.trackingCustomSectionDefinition(sectionKey),
        )
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Elimina lista di tracciamento")
            .setMessage("Vuoi eliminare la sezione \"$name\"?")
            .setPositiveButton("Elimina") { _, _ ->
                StreamCenterPlugin.deleteTrackingCustomSection(sharedPref, sectionKey)
                loadRows()
                renderRows()
                saveToast("Lista eliminata")
            }
            .setNegativeButton("Annulla", null)
            .create()
        applyHomeDialogBackdrop(dialog)
        dialog.show()
    }

    private fun tvEmptyState(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(COLOR_CARD, tint(categoryAccent("live"), "55"), 16)
            layoutParams = verticalParams(top = 8)
            addView(titleText("Nessuna sezione TV", 14, true))
            addView(bodyText(
                "Crea una sezione, dalle un nome e scegli i canali da mostrare nella Home.",
                12,
            ).apply { setPadding(0, dp(4), 0, 0) })
        }
    }

    private fun tvCategoryFooter(): LinearLayout {
        val accent = categoryAccent("live")
        val hasChannels = StreamCenterPlugin.getAllIptvSelectedChannelIds(sharedPref).isNotEmpty()
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 8)
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(actionButton("+ Crea sezione TV", accent) { promptCreateTvSection() }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(4)
                    }
                })
                addView(actionButton("Crea da preset", accent) { showTvPresetPicker() }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(4)
                    }
                })
            })
            if (hasChannels) {
                addView(actionButton("Testa i canali", COLOR_SUCCESS) {
                    testSelectedIptvChannels()
                }.apply { layoutParams = verticalParams(top = 8) })
            }
        }
    }

    private fun showTvPresetPicker() {
        val ctx = context ?: return
        lateinit var dialog: AlertDialog
        val presetList = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        StreamCenterIptv.presets.forEach { preset ->
            val presetAccent = tvPresetAccent(preset.key)
            presetList.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(72)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = interactiveBackground(
                    COLOR_CARD_ALT,
                    presetAccent,
                    14,
                    tint(presetAccent, "72"),
                )
                layoutParams = verticalParams(top = 8)
                isClickable = true
                isFocusable = true
                contentDescription = preset.title
                setOnClickListener {
                    dialog.dismiss()
                    createTvSectionFromPreset(preset)
                }
                addView(iconBadge(preset.icon, presetAccent, size = 38, marginEnd = 12))
                 addView(LinearLayout(ctx).apply {
                     orientation = LinearLayout.VERTICAL
                     layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                     addView(titleText(preset.title, 15, true))
                 })
                addView(chevron(presetAccent))
            })
        }
        val title = titleText("Preset TV", 20, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(22), dp(24), 0)
        }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(title)
            .setView(ScrollView(ctx).apply { addView(presetList) })
            .setNegativeButton("Annulla", null)
            .create()
        applyHomeDialogBackdrop(dialog)
        dialog.show()
    }

    private fun tvPresetAccent(key: String): String {
        return when (key) {
            "rai" -> "#60A5FA"
            "mediaset" -> "#FB923C"
            "discovery" -> "#34D399"
            "sky" -> "#A78BFA"
            "la7" -> "#FACC15"
            else -> categoryAccent("live")
        }
    }

    private fun createTvSectionFromPreset(preset: StreamCenterIptv.Preset) {
        val prefs = sharedPref ?: return
        val sectionKeys = StreamCenterPlugin.getIptvCustomSectionKeys(prefs)
        val definitionForKey = { key: String -> StreamCenterPlugin.iptvCustomSectionDefinition(key) }
        if (hasDuplicateCustomSectionName(preset.title, sectionKeys, definitionForKey)) {
            saveToast("Esiste già una sezione TV con questo nome")
            return
        }
        saveToast("Caricamento ${preset.title}...")
        CoroutineScope(Dispatchers.IO).launch {
            val availableChannels = runCatching { StreamCenterIptv.fetchChannels(preset.regionKey) }
                .getOrDefault(emptyList())
            val presetChannels = StreamCenterIptv.resolvePresetChannels(availableChannels, preset)
            withContext(Dispatchers.Main) {
                if (context == null) return@withContext
                val currentKeys = StreamCenterPlugin.getIptvCustomSectionKeys(prefs)
                if (hasDuplicateCustomSectionName(preset.title, currentKeys, definitionForKey)) {
                    saveToast("Esiste già una sezione TV con questo nome")
                    return@withContext
                }
                if (presetChannels.isEmpty()) {
                    saveToast("Preset TV non disponibile")
                    return@withContext
                }
                val sectionKey = StreamCenterPlugin.createIptvCustomSection(prefs, preset.title)
                if (sectionKey == null) {
                    saveToast("Impossibile creare la sezione")
                    return@withContext
                }
                StreamCenterPlugin.setIptvSectionChannels(prefs, sectionKey, presetChannels.map { it.id })
                prefs.edit {
                    putBoolean(StreamCenterPlugin.homeCategoryEnabledKey("live"), true)
                }
                categoryEnabled["live"] = true
                loadRows()
                renderRows()
                val unavailable = preset.channels.size - presetChannels.size
                saveToast(
                    if (unavailable == 0) {
                        "${preset.title} creata: ${presetChannels.size} canali"
                    } else {
                        "${preset.title} creata: ${presetChannels.size}/${preset.channels.size} canali disponibili"
                    },
                )
            }
        }
    }

    private fun promptCreateTvSection() {
        val ctx = context ?: return
        val nameInput = input("").apply {
            hint = "Nome della sezione"
            layoutParams = verticalParams()
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(nameInput)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Nuova sezione TV")
            .setView(container)
            .setPositiveButton("Crea", null)
            .setNegativeButton("Annulla", null)
            .create()
        applyHomeDialogBackdrop(dialog)
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val requestedName = nameInput.text?.toString().orEmpty()
            val sectionName = resolvedCustomSectionName(
                value = requestedName,
                fallbackName = "TV - i miei canali",
                keys = StreamCenterPlugin.getIptvCustomSectionKeys(sharedPref),
                definitionForKey = { key -> StreamCenterPlugin.iptvCustomSectionDefinition(key) },
            )
            if (hasDuplicateCustomSectionName(
                    value = sectionName,
                    keys = StreamCenterPlugin.getIptvCustomSectionKeys(sharedPref),
                    definitionForKey = { key -> StreamCenterPlugin.iptvCustomSectionDefinition(key) },
                )
            ) {
                nameInput.error = "Esiste già una sezione TV con questo nome"
                nameInput.requestFocus()
                return@setOnClickListener
            }
            val sectionKey = StreamCenterPlugin.createIptvCustomSection(sharedPref, sectionName)
            if (sectionKey == null) {
                saveToast("Impossibile creare la sezione")
                return@setOnClickListener
            }
            loadRows()
            renderRows()
            dialog.dismiss()
            showIptvChannelPicker(sectionKey)
        }
    }

    private fun confirmDeleteTvSection(sectionKey: String) {
        val count = StreamCenterPlugin.getIptvSectionChannelIds(sharedPref, sectionKey).size
        val name = tvSectionTitle(sectionKey)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Elimina sezione TV")
            .setMessage(
                if (count == 0) "Vuoi eliminare la sezione \"$name\"?"
                else "Vuoi eliminare la sezione \"$name\" e i suoi $count canali?",
            )
            .setPositiveButton("Elimina") { _, _ ->
                StreamCenterPlugin.deleteIptvCustomSection(sharedPref, sectionKey)
                saveToast("Sezione eliminata")
                loadRows()
                renderRows()
            }
            .setNegativeButton("Annulla", null)
            .create()
        applyHomeDialogBackdrop(dialog)
        dialog.show()
    }

    private fun testSelectedIptvChannels() {
        val selected = StreamCenterPlugin.getAllIptvSelectedChannelIds(sharedPref)
        if (selected.isEmpty()) {
            saveToast("Nessun canale da testare")
            return
        }
        val ctx = context ?: return
        val progressText = TextView(ctx).apply {
            setTextColor(Color.parseColor(COLOR_TEXT))
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(20), dp(14), dp(20), dp(12))
        }
        val progressScroll = ScrollView(ctx).apply {
            addView(progressText)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320))
        }
        val active = linkedMapOf<String, String>()
        val events = mutableListOf<String>()
        var completed = 0
        var working = 0
        var failedCount = 0
        var failedIds = emptySet<String>()
        var dialogVisible = false

        fun renderProgress() {
            val activeNames = active.values.sorted()
            progressText.text = buildString {
                append("Testati: $completed/${selected.size} - OK: $working - KO: $failedCount")
                append("\n\n")
                if (activeNames.isEmpty()) {
                    append("Preparazione della lista canali...")
                } else {
                    append("In test adesso:\n")
                    append(activeNames.joinToString("\n") { "- $it" })
                }
                if (events.isNotEmpty()) {
                    append("\n\nUltimi risultati:\n")
                    append(events.takeLast(18).joinToString("\n"))
                }
            }
        }

        renderProgress()
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Test TV (0/${selected.size})")
            .setView(progressScroll)
            .setPositiveButton("Rimuovi KO", null)
            .setNeutralButton("Riprova", null)
            .setNegativeButton("Chiudi", null)
            .create()
        showCompactIptvDialog(dialog) { dialogVisible = false }
        dialogVisible = true

        val removeButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE).apply { visibility = View.GONE }
        val retryButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL).apply { visibility = View.GONE }
        removeButton.setOnClickListener {
            if (failedIds.isNotEmpty()) {
                StreamCenterPlugin.getIptvCustomSectionKeys(sharedPref).forEach { sectionKey ->
                    val sectionChannels = StreamCenterPlugin.getIptvSectionChannelIds(sharedPref, sectionKey)
                    if (sectionChannels.any { it in failedIds }) {
                        saveIptvSelection(sectionChannels - failedIds, sectionKey)
                    }
                }
                saveToast("Canali non funzionanti rimossi")
                dialog.dismiss()
            }
        }
        retryButton.setOnClickListener {
            dialog.dismiss()
            testSelectedIptvChannels()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val results = StreamCenterIptv.testChannels(selected) { progress ->
                withContext(Dispatchers.Main) {
                    if (!dialogVisible) return@withContext
                    if (progress.testing) {
                        active[progress.id] = progress.name
                    } else {
                        active.remove(progress.id)
                        progress.result?.let { result ->
                            completed += 1
                            if (result.working) working += 1 else failedCount += 1
                            events += "${if (result.working) "OK" else "KO"} - ${result.name}: ${result.detail}"
                        }
                    }
                    dialog.setTitle("Test TV ($completed/${selected.size})")
                    renderProgress()
                }
            }
            withContext(Dispatchers.Main) {
                val finalWorking = results.count { it.working }
                val failed = results.filterNot { it.working }
                failedIds = failed.map { it.id }.toSet()
                val details = if (failed.isEmpty()) {
                    "Tutti i $finalWorking canali hanno risposto correttamente."
                } else buildString {
                    append("Funzionanti: $finalWorking\nNon funzionanti: ${failed.size}\n\n")
                    append(failed.take(25).joinToString("\n") { "- ${it.name}: ${it.detail}" })
                    if (failed.size > 25) append("\n...e altri ${failed.size - 25}")
                }
                if (!dialogVisible) {
                    saveToast("Test TV concluso: $finalWorking/${results.size} funzionanti")
                    return@withContext
                }
                active.clear()
                completed = results.size
                working = finalWorking
                failedCount = failed.size
                dialog.setTitle("Risultato test TV")
                progressText.text = details
                removeButton.visibility = if (failed.isEmpty()) View.GONE else View.VISIBLE
                retryButton.visibility = if (failed.isEmpty()) View.GONE else View.VISIBLE
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.text = "Chiudi"
            }
        }
    }

    private fun showIptvChannelPicker(sectionKey: String) {
        val currentKey = StreamCenterPlugin.getIptvRegion(sharedPref)
        val regions = StreamCenterIptv.regions.sortedWith(
            compareBy<StreamCenterIptv.Region> { it.key != currentKey }.thenBy { it.name },
        )
        showIptvRegionList(regions, sectionKey)
    }

    private fun showIptvRegionList(regions: List<StreamCenterIptv.Region>, sectionKey: String) {
        val ctx = context ?: return
        val selected = StreamCenterPlugin.getIptvSectionChannelIds(sharedPref, sectionKey)
        var visible = regions

        val adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val listView = ListView(ctx).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(330),
            ).apply { topMargin = dp(8) }
        }

        fun renderList(query: String) {
            val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
            visible = if (terms.isEmpty()) regions else regions.filter { region ->
                val text = "${region.name} ${region.key}".lowercase()
                terms.all(text::contains)
            }
            adapter.clear()
            adapter.addAll(visible.map { region ->
                val count = selected.count { it.startsWith("${region.key}:") }
                if (count > 0) "${region.name} · $count selezionati" else region.name
            })
        }

        val searchInput = input("").apply {
            hint = "Cerca regione..."
            layoutParams = verticalParams()
            doAfterTextChanged { renderList(it?.toString().orEmpty()) }
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(searchInput)
            addView(listView)
        }
        renderList("")

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Regione · ${tvSectionTitle(sectionKey)}")
            .setView(container)
            .setNegativeButton("Annulla", null)
            .create()
        listView.setOnItemClickListener { _, _, index, _ ->
            val region = visible.getOrNull(index) ?: return@setOnItemClickListener
            dialog.dismiss()
            StreamCenterPlugin.setIptvRegion(sharedPref, region.key)
            loadIptvRegion(region, sectionKey)
        }
        showCompactIptvDialog(dialog)
    }

    private fun loadIptvRegion(region: StreamCenterIptv.Region, sectionKey: String) {
        saveToast("Caricamento canali: ${region.name}...")
        val prefs = sharedPref
        CoroutineScope(Dispatchers.IO).launch {
            val channels = runCatching { StreamCenterIptv.fetchChannels(region.key) }
                .getOrDefault(emptyList())
                .sortedBy { it.name.lowercase() }
            withContext(Dispatchers.Main) {
                if (channels.isEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("TV · ${region.name}")
                        .setMessage("Nessun canale disponibile o playlist non raggiungibile.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@withContext
                }
                val selected = StreamCenterPlugin.getIptvSectionChannelIds(prefs, sectionKey).toMutableSet()
                showIptvChannels(region, channels, selected, sectionKey)
            }
        }
    }

    private fun showIptvChannels(
        region: StreamCenterIptv.Region,
        allChannels: List<StreamCenterIptv.Channel>,
        selected: MutableSet<String>,
        sectionKey: String,
    ) {
        val ctx = context ?: return
        var visible = allChannels

        val adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_multiple_choice, mutableListOf<String>())
        val listView = ListView(ctx).apply {
            this.adapter = adapter
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(330),
            ).apply { topMargin = dp(8) }
        }
        val counter = bodyText("", 12).apply { setPadding(0, dp(8), 0, 0) }

        fun updateCounter() {
            val inRegion = selected.count { it.startsWith("${region.key}:") }
            counter.text = when {
                visible.isEmpty() -> "Nessun canale trovato · $inRegion selezionati"
                selected.isEmpty() -> "${visible.size} canali disponibili"
                else -> "${visible.size} canali · $inRegion selezionati qui · ${selected.size} in totale"
            }
        }

        fun renderList(query: String) {
            val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
            visible = if (terms.isEmpty()) allChannels else allChannels.filter { channel ->
                val text = "${channel.name} ${channel.group}".lowercase()
                terms.all(text::contains)
            }
            adapter.clear()
            adapter.addAll(visible.map { it.name })
            listView.clearChoices()
            visible.forEachIndexed { index, channel ->
                listView.setItemChecked(index, channel.id in selected)
            }
            updateCounter()
        }

        listView.setOnItemClickListener { _, _, index, _ ->
            val channel = visible.getOrNull(index) ?: return@setOnItemClickListener
            if (listView.isItemChecked(index)) selected += channel.id else selected -= channel.id
            updateCounter()
        }

        val searchInput = input("").apply {
            hint = "Cerca canale..."
            layoutParams = verticalParams()
            doAfterTextChanged { renderList(it?.toString().orEmpty()) }
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(searchInput)
            addView(counter)
            addView(listView)
        }
        renderList("")

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("${tvSectionTitle(sectionKey)} · ${region.name}")
            .setView(container)
            .setPositiveButton("Salva") { _, _ ->
                saveIptvSelection(selected, sectionKey)
                saveToast("Canali TV salvati")
                showIptvSavedActions(sectionKey)
            }
            .setNegativeButton("Indietro") { _, _ -> showIptvChannelPicker(sectionKey) }
            .create()
        showCompactIptvDialog(dialog)
    }

    private data class SelectedChannelEntry(
        val id: String,
        val name: String,
        var removed: Boolean = false,
    )

    private fun showIptvSelectedChannels(sectionKey: String) {
        val ordered = StreamCenterPlugin.getIptvSectionChannelOrder(sharedPref, sectionKey)
        if (ordered.isEmpty()) {
            saveToast("Nessun canale selezionato")
            return
        }
        saveToast("Caricamento canali...")
        CoroutineScope(Dispatchers.IO).launch {
            val regionKeys = ordered.map { it.substringBefore(':') }.distinct()
            val namesById = regionKeys.flatMap { regionKey ->
                runCatching { StreamCenterIptv.fetchChannels(regionKey) }.getOrDefault(emptyList())
            }.associate { it.id to it.name }
            withContext(Dispatchers.Main) {
                showIptvSelectedChannelsDialog(sectionKey, ordered, namesById)
            }
        }
    }

    private fun showIptvSelectedChannelsDialog(
        sectionKey: String,
        ordered: List<String>,
        namesById: Map<String, String>,
    ) {
        val ctx = context ?: return
        val accent = categoryAccent("live")
        val entries = ordered.map { id ->
            SelectedChannelEntry(id, namesById[id] ?: id.substringAfter(':'))
        }.toMutableList()
        val listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun renderEntries() {
            listContainer.removeAllViews()
            entries.forEachIndexed { index, entry ->
                listContainer.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(4), dp(4))
                    background = cardBackground(
                        if (entry.removed) COLOR_CARD_DISABLED else COLOR_CARD_ALT,
                        COLOR_STROKE,
                        10,
                    )
                    layoutParams = verticalParams(top = if (index == 0) 0 else 6)
                    addView(TextView(ctx).apply {
                        text = "${index + 1}. ${entry.name}"
                        textSize = 13f
                        setTextColor(Color.parseColor(if (entry.removed) COLOR_MUTED else COLOR_TEXT))
                        paintFlags = if (entry.removed) {
                            paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                        } else {
                            paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                        }
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(iconButton("↑", "Sposta ${entry.name} in alto", accent, size = 30) {
                        if (index > 0) {
                            entries[index] = entries[index - 1].also { entries[index - 1] = entries[index] }
                            renderEntries()
                        }
                    })
                    addView(iconButton("↓", "Sposta ${entry.name} in basso", accent, size = 30) {
                        if (index < entries.lastIndex) {
                            entries[index] = entries[index + 1].also { entries[index + 1] = entries[index] }
                            renderEntries()
                        }
                    })
                    addView(iconButton(
                        if (entry.removed) "↩" else "✕",
                        if (entry.removed) "Ripristina ${entry.name}" else "Rimuovi ${entry.name}",
                        if (entry.removed) COLOR_SUCCESS else COLOR_DANGER,
                        size = 30,
                    ) {
                        entry.removed = !entry.removed
                        renderEntries()
                    })
                })
            }
        }

        val scroll = ScrollView(ctx).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320),
            ).apply { topMargin = dp(8) }
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(scroll)
        }
        renderEntries()

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Selezionati · ${tvSectionTitle(sectionKey)}")
            .setView(container)
            .setPositiveButton("Salva") { _, _ ->
                val kept = entries.filterNot { it.removed }.map { it.id }
                StreamCenterPlugin.setIptvSectionChannels(sharedPref, sectionKey, kept)
                sharedPref?.edit {
                    putBoolean(StreamCenterPlugin.sectionEnabledKey(sectionKey), kept.isNotEmpty())
                }
                rows.firstOrNull { it.section.key == sectionKey }?.enabled = kept.isNotEmpty()
                renderRows()
                saveToast(
                    if (kept.size == entries.size) "Ordine dei canali salvato"
                    else "Canali aggiornati",
                )
            }
            .setNegativeButton("Annulla", null)
            .create()
        showCompactIptvDialog(dialog)
    }

    private fun showIptvSavedActions(sectionKey: String) {
        val selected = StreamCenterPlugin.getIptvSectionChannelIds(sharedPref, sectionKey)
        val regionCount = selected.map { it.substringBefore(':') }.distinct().size
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Selezione salvata")
            .setMessage("\"${tvSectionTitle(sectionKey)}\": ${selected.size} canali da $regionCount regioni. Vuoi aggiungere canali da un'altra regione?")
            .setPositiveButton("Fine", null)
            .setNeutralButton("Altra regione") { _, _ -> showIptvChannelPicker(sectionKey) }
            .create()
        showCompactIptvDialog(dialog)
    }

    private fun showCompactIptvDialog(
        dialog: AlertDialog,
        onDismiss: (() -> Unit)? = null,
    ) {
        dialog.setOnShowListener {
            openHomeDialogs += 1
            homeContent?.apply {
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
            listOf(
                DialogInterface.BUTTON_POSITIVE,
                DialogInterface.BUTTON_NEUTRAL,
                DialogInterface.BUTTON_NEGATIVE,
            ).forEach { buttonId ->
                dialog.getButton(buttonId)?.apply {
                    minWidth = 0
                    setPadding(dp(6), 0, dp(6), 0)
                    textSize = 12f
                    setAllCaps(false)
                }
            }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.parent?.let { parent ->
                (parent as? View)?.requestLayout()
            }
        }
        dialog.setOnDismissListener {
            openHomeDialogs = (openHomeDialogs - 1).coerceAtLeast(0)
            if (openHomeDialogs == 0) {
                homeContent?.apply {
                    alpha = 1f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
                }
            }
            onDismiss?.invoke()
        }
        dialog.show()
    }

    private fun saveIptvSelection(selected: Set<String>, sectionKey: String) {
        val previousOrder = StreamCenterPlugin.getIptvSectionChannelOrder(sharedPref, sectionKey)
        val ordered = previousOrder.filter { it in selected } +
            selected.filterNot { it in previousOrder }
        sharedPref?.edit {
            putStringSet(StreamCenterPlugin.iptvSectionChannelsKey(sectionKey), selected.toSet())
            putString(StreamCenterPlugin.iptvSectionOrderKey(sectionKey), ordered.joinToString(","))
            putBoolean(StreamCenterPlugin.sectionEnabledKey(sectionKey), selected.isNotEmpty())
            if (selected.isNotEmpty()) {
                putBoolean(StreamCenterPlugin.homeCategoryEnabledKey("live"), true)
            }
        }
        rows.firstOrNull { it.section.key == sectionKey }?.enabled = selected.isNotEmpty()
        if (selected.isNotEmpty()) categoryEnabled["live"] = true
        renderRows()
    }

    override fun onDestroyView() {
        rowsContainer = null
        homeContent = null
        super.onDestroyView()
    }

}

class StreamCenterSupportSettingsFragment : StreamCenterBaseSettingsFragment() {
    private val supportSparkleTargets = mutableListOf<BorderSparkleTarget>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        supportSparkleTargets.clear()
        val content = rootContainer()
        content.minimumHeight = standardSubmenuMinimumHeight()
        content.addView(
            header(
                title = "Supporto",
                subtitle = "Verifica lo stato, invia feedback o ripristina le impostazioni.",
                icon = "🛟",
                accent = COLOR_SUPPORT,
            ),
        )

        content.addView(supportCard(
            icon = "💬",
            title = "Invia feedback",
            summary = "Segnala un problema o proponi un miglioramento.",
            accent = COLOR_FEEDBACK,
        ) {
            showFeedbackChoiceDialog()
        })
        content.addView(supportCard(
            icon = "✨",
            title = "Effetti visivi",
            summary = "Animazioni, sfocature e bagliori.",
            accent = COLOR_VISUAL_EFFECTS,
        ) {
            showVisualEffectsDialog()
        })
        content.addView(supportCard(
            icon = "📡",
            title = "Verifica API e Fonti",
            summary = "Controlla la raggiungibilità dei servizi e delle fonti.",
            accent = COLOR_API_CHECK,
        ) {
            checkApis()
        })
        content.addView(supportCard(
            icon = "♻️",
            title = "Ripristina tutte le impostazioni",
            summary = "Cancella preferenze, sessioni e dati salvati.",
            accent = COLOR_RESET,
        ) {
            val alertDialog = AlertDialog.Builder(requireContext())
                .setTitle("Ripristina impostazioni")
                .setMessage(
                    "Vuoi riportare StreamCenter alle impostazioni iniziali? " +
                        "Verranno cancellati preferenze, sessioni e dati temporanei salvati.",
                )
                .setPositiveButton("Ripristina") { _, _ ->
                    sharedPref?.edit { clear() }
                    StreamCenter.resetSourceDomainChecks()
                    markRestartNeeded()
                    saveToast("Impostazioni ripristinate e dati salvati puliti")
                }
                .setNegativeButton("Annulla", null)
                .create()
            applyDialogBackdrop(alertDialog)
            alertDialog.show()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18)
        })

        startBorderSparkleCycle(supportSparkleTargets)
        return scroll(content)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        supportSparkleTargets.clear()
    }

    private fun supportCard(
        icon: String,
        title: String,
        summary: String,
        accent: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = interactiveBackground(COLOR_CARD, accent, 16)
            layoutParams = verticalParams(top = 10)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(iconBadge(icon, accent, size = 42, marginEnd = 12))
            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(titleText(title, 15, true))
            texts.addView(bodyText(summary, 12).apply { setPadding(0, dp(3), 0, 0) })
            addView(texts)
            addView(chevron(accent))
        }
        supportSparkleTargets += BorderSparkleTarget(card, accent)
        return card
    }

    private fun showFeedbackChoiceDialog() {
        val ctx = requireContext()
        var dialog: AlertDialog? = null

        fun feedbackChoice(
            icon: String,
            label: String,
            accent: String,
            prefix: String,
        ): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(126)
                setPadding(dp(10), dp(14), dp(10), dp(14))
                background = interactiveBackground(tint(accent, "16"), accent, 16, tint(accent, "72"))
                isClickable = true
                isFocusable = true
                contentDescription = label.replace("\n", " ")
                setOnClickListener {
                    dialog?.dismiss()
                    openFeedback(prefix)
                }
                addView(iconBadge(icon, accent, size = 38))
                addView(titleText(label, 15, true).apply {
                    gravity = Gravity.CENTER
                    setLineSpacing(dp(2).toFloat(), 1f)
                    setPadding(0, dp(8), 0, 0)
                })
            }
        }

        val choices = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(
                feedbackChoice(
                    icon = "💡",
                    label = "Proponi un\nmiglioramento",
                    accent = COLOR_SUCCESS,
                    prefix = "[suggerimento]: ",
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                },
            )
            addView(
                feedbackChoice(
                    icon = "🐞",
                    label = "Segnala un\nproblema",
                    accent = COLOR_DANGER,
                    prefix = "[problema]: ",
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                },
            )
        }
        val title = titleText("Invia feedback", 20).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(22), dp(24), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val alertDialog = AlertDialog.Builder(ctx)
            .setCustomTitle(title)
            .setView(choices)
            .setNegativeButton("Annulla", null)
            .create()
        dialog = alertDialog
        applyDialogBackdrop(alertDialog)
        alertDialog.show()
    }

    private fun showVisualEffectsDialog() {
        val ctx = context ?: return
        fun effectSelected(preferenceKey: String): Boolean {
            return sharedPref?.getBoolean(preferenceKey, true) ?: true
        }

        fun effectOptionRow(
            icon: String,
            title: String,
            summary: String,
            preferenceKey: String,
            optionAccent: String,
        ): LinearLayout {
            val effectSwitch = styledSwitch(effectSelected(preferenceKey), optionAccent) { enabled ->
                sharedPref?.edit { putBoolean(preferenceKey, enabled) }
                refreshVisibleSettingsEffects()
            }
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(14), dp(10), dp(10), dp(10))
                background = cardBackground(COLOR_CARD_ALT, tint(optionAccent, "55"), 14)
                layoutParams = verticalParams(top = 8)
                addView(iconBadge(icon, optionAccent, size = 36, marginEnd = 10))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(titleText(title, 14, true))
                    addView(bodyText(summary, 11).apply { setPadding(0, dp(2), 0, 0) })
                })
                addView(effectSwitch)
            }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(effectOptionRow(
                icon = "↔",
                title = "Animazioni",
                summary = "Apertura delle finestre, transizioni, feedback e scintille sui bordi.",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_ANIMATIONS,
                optionAccent = COLOR_VISUAL_EFFECTS,
            ))
            addView(effectOptionRow(
                icon = "◌",
                title = "Sfocatura finestre",
                summary = "Sfoca lo sfondo quando apri menu e finestre di dialogo.",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_BLUR,
                optionAccent = COLOR_VISUAL_BLUR,
            ))
            addView(effectOptionRow(
                icon = "✨",
                title = "Intestazione StreamCenter",
                summary = "Applica effetti a titolo, commit e build.",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_TITLE,
                optionAccent = COLOR_VISUAL_HEADER,
            ))
            addView(effectOptionRow(
                icon = "✦",
                title = "Particelle sullo sfondo",
                summary = "Particelle mobili nelle schermate delle impostazioni.",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_PARTICLES,
                optionAccent = COLOR_PARTICLES,
            ))
        }

        val title = titleText("Effetti visivi", 20).apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(22), dp(24), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(title)
            .setView(content)
            .setPositiveButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private enum class ApiCheckState {
        WAITING,
        RUNNING,
        SUCCESS,
        FAILURE,
        CONNECTED,
        DISCONNECTED,
    }

    private data class ApiCheckRowViews(
        val container: LinearLayout,
        val badge: TextView,
        val label: TextView,
        val name: String,
    )

    private fun checkApis() {
        val ctx = context ?: return
        val apiNames = listOf("AniList", "MyAnimeList (Jikan)", "Kitsu", "TMDB", "Mappe anime (Fribb)")
        val sourceNames = listOf("StreamingCommunity", "AnimeUnity", "AnimeWorld", "AnimeSaturn")
        val checkNames = apiNames + sourceNames
        val rows = mutableMapOf<String, ApiCheckRowViews>()
        val states = checkNames.associateWith { ApiCheckState.WAITING }.toMutableMap()
        var dialogVisible = true

        val summary = bodyText("0 disponibili  ·  0 non raggiungibili", 13).apply {
            setTextColor(Color.parseColor(COLOR_TEXT))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(4), dp(24), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = checkNames.size
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.parseColor(COLOR_API_CHECK))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(5),
            ).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
                bottomMargin = dp(12)
            }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
            background = cardBackground(COLOR_BACKGROUND, COLOR_STROKE, 20)
        }

        fun addSection(title: String, names: List<String>, accent: String) {
            content.addView(titleText(title, 14, true).apply {
                setTextColor(Color.parseColor(accent))
                layoutParams = verticalParams(top = 10)
                setPadding(dp(4), 0, 0, dp(2))
            })
            names.forEach { name ->
                createApiCheckRow(ctx, name).also { row ->
                    rows[name] = row
                    setApiCheckState(row, ApiCheckState.WAITING)
                    content.addView(row.container)
                }
            }
        }

        addSection("API", apiNames, COLOR_API_CHECK)
        addSection("Fonti streaming", sourceNames, COLOR_SOURCES)
        addSection("Servizi CloudStream", emptyList(), COLOR_CLOUDSTREAM_SERVICES)
        cloudstreamConnectionResults().forEach { (name, connected) ->
            createApiCheckRow(ctx, name).also { row ->
                setApiCheckState(
                    row,
                    if (connected) ApiCheckState.CONNECTED else ApiCheckState.DISCONNECTED,
                )
                content.addView(row.container)
            }
        }

        val dialogHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleText("Verifica API e Fonti", 20).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(22), dp(24), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
            addView(summary)
            addView(progress)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogHeader)
            .setView(ScrollView(ctx).apply { addView(content) })
            .setNeutralButton("Riprova", null)
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog) { dialogVisible = false }
        dialog.show()

        val retryButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL).apply {
            visibility = View.GONE
            setOnClickListener {
                dialog.dismiss()
                checkApis()
            }
        }

        fun renderSummary(completed: Int) {
            val online = states.count { it.value == ApiCheckState.SUCCESS }
            val unavailable = states.count { it.value == ApiCheckState.FAILURE }
            progress.progress = completed
            summary.text = "$online disponibili  ·  $unavailable non raggiungibili"
        }

        renderSummary(0)
        CoroutineScope(Dispatchers.IO).launch {
            val results = runCatching {
                StreamCenter.checkApisAvailability(sharedPref) { name, isRunning, result ->
                    withContext(Dispatchers.Main) {
                        if (!dialogVisible) return@withContext
                        val row = rows[name] ?: return@withContext
                        val state = if (isRunning) {
                            ApiCheckState.RUNNING
                        } else if (result == true) {
                            ApiCheckState.SUCCESS
                        } else {
                            ApiCheckState.FAILURE
                        }
                        states[name] = state
                        setApiCheckState(row, state)
                        renderSummary(states.count { it.value in setOf(ApiCheckState.SUCCESS, ApiCheckState.FAILURE) })
                    }
                }
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                if (!dialogVisible) return@withContext
                if (results.isEmpty()) {
                    states.forEach { (name, state) ->
                        if (state == ApiCheckState.WAITING || state == ApiCheckState.RUNNING) {
                            states[name] = ApiCheckState.FAILURE
                            rows[name]?.let { setApiCheckState(it, ApiCheckState.FAILURE) }
                        }
                    }
                }
                renderSummary(checkNames.size)
                retryButton.visibility = View.VISIBLE
            }
        }
    }

    private fun createApiCheckRow(ctx: Context, name: String): ApiCheckRowViews {
        val badge = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) }
        }
        val label = titleText(name, 13, true)
        val labels = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(label)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(10), dp(10), dp(12), dp(10))
            layoutParams = verticalParams(top = 6)
            addView(badge)
            addView(labels)
        }
        return ApiCheckRowViews(container, badge, label, name)
    }

    private fun setApiCheckState(row: ApiCheckRowViews, state: ApiCheckState) {
        val (symbol, label, color) = when (state) {
            ApiCheckState.WAITING -> Triple("\u2022", "In attesa", COLOR_MUTED)
            ApiCheckState.RUNNING -> Triple("\u2026", "Verifica in corso", COLOR_ACCENT)
            ApiCheckState.SUCCESS -> Triple("\u2713", "Raggiungibile", COLOR_SUCCESS)
            ApiCheckState.FAILURE -> Triple("\u00D7", "Non raggiungibile", COLOR_DANGER)
            ApiCheckState.CONNECTED -> Triple("\u2713", "Collegato", COLOR_SUCCESS)
            ApiCheckState.DISCONNECTED -> Triple("\u2022", "Non collegato", COLOR_MUTED)
        }
        row.badge.text = symbol
        row.badge.setTextColor(Color.parseColor(color))
        row.badge.background = cardBackground(tint(color, "22"), tint(color, "99"), 17)
        val text = "${row.name} - $label"
        row.label.text = SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(Color.parseColor(color)),
                row.name.length + 3,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        row.container.background = cardBackground(COLOR_CARD_ALT, tint(color, "66"), 16)
        row.container.alpha = if (state == ApiCheckState.DISCONNECTED) 0.64f else 1f
    }

    private fun cloudstreamConnectionResults(): List<Pair<String, Boolean>> {
        val accountApis = runCatching { AccountManager.allApis.toList() }.getOrDefault(emptyList())
        val syncApis = runCatching { AccountManager.syncApis.toList() }.getOrDefault(emptyList())
        fun connected(name: String): Boolean = accountApis
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.authData() != null
        fun syncConnected(syncIdName: SyncIdName): Boolean = syncApis
            .firstOrNull { it.syncIdName == syncIdName }
            ?.authData() != null
        return listOf(
            "MyAnimeList" to syncConnected(SyncIdName.MyAnimeList),
            "Kitsu" to syncConnected(SyncIdName.Kitsu),
            "AniList" to syncConnected(SyncIdName.Anilist),
            "Simkl" to syncConnected(SyncIdName.Simkl),
            "OpenSubtitles" to connected("OpenSubtitles"),
            "SubDL" to connected("SubDL"),
            "AnimeSkip" to connected("AnimeSkip"),
        )
    }

    private fun openFeedback(prefix: String) {
        val title = Uri.encode("StreamCenter $prefix")
        openUrl("${StreamCenterPlugin.FEEDBACK_ISSUES_URL}?title=$title")
    }
}
