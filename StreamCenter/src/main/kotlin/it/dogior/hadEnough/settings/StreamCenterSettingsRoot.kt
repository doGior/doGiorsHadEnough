package it.dogior.hadEnough.settings

import it.dogior.hadEnough.*
import it.dogior.hadEnough.catalog.StreamCenterCatalogDefinition
import it.dogior.hadEnough.catalog.StreamCenterCatalogs
import it.dogior.hadEnough.stremio.StreamCenterStremioAddon

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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.util.Calendar
import java.util.Locale
import kotlin.math.sin

private const val MAIN_MENU_SUBMENU_REVEAL_DP = 116

private class SettingsAuroraDecoration(context: Context) : View(context) {
    private data class Star(
        val x: Float,
        val y: Float,
        val radius: Float,
        val alpha: Int,
    )

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val horizonPath = Path()
    private val auroraColors = intArrayOf(
        Color.parseColor("#60DFF5"),
        Color.parseColor("#A78BFA"),
        Color.parseColor(COLOR_SUPPORT),
    )
    private val stars = listOf(
        Star(0.08f, 0.28f, 0.9f, 96),
        Star(0.18f, 0.58f, 1.3f, 82),
        Star(0.28f, 0.18f, 0.7f, 76),
        Star(0.36f, 0.76f, 1.1f, 94),
        Star(0.48f, 0.42f, 0.8f, 72),
        Star(0.61f, 0.22f, 1.0f, 88),
        Star(0.72f, 0.66f, 1.4f, 92),
        Star(0.84f, 0.34f, 0.8f, 78),
        Star(0.93f, 0.82f, 1.2f, 86),
    )
    private var active = false
    private var phase = 0f
    private val animationFrame = object : Runnable {
        override fun run() {
            if (!active || !isAttachedToWindow) return
            phase += 0.009f
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setActive(enabled: Boolean) {
        active = enabled
        visibility = if (enabled) VISIBLE else INVISIBLE
        if (enabled) {
            removeCallbacks(animationFrame)
            postOnAnimation(animationFrame)
        } else {
            removeCallbacks(animationFrame)
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) {
            removeCallbacks(animationFrame)
            postOnAnimation(animationFrame)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animationFrame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active || width == 0 || height == 0) return
        drawGalaxyGlow(canvas)
        drawAuroraHorizon(canvas)
        stars.forEachIndexed { index, star ->
            val twinkle = (sin(phase * (0.75f + index * 0.09f) + index) + 1f) / 2f
            paint.shader = null
            paint.color = auroraColors[index % auroraColors.size]
            paint.alpha = (star.alpha * (0.36f + twinkle * 0.64f)).toInt()
            canvas.drawCircle(
                width * star.x,
                height * star.y,
                star.radius * density * (0.78f + twinkle * 0.42f),
                paint,
            )
        }
    }

    private fun drawAuroraHorizon(canvas: Canvas) {
        val motion = height * 0.075f
        val leftMotion = sin(phase * 1.24f) * motion
        val firstCrestMotion = sin(phase * 1.08f + 0.65f) * motion * 0.8f
        val firstValleyMotion = sin(phase * 1.32f + 1.5f) * motion * 0.9f
        val centerMotion = sin(phase * 1.16f + 2.15f) * motion
        val secondCrestMotion = sin(phase * 1.28f + 2.75f) * motion * 0.85f
        val rightMotion = sin(phase * 1.04f + 3.45f) * motion * 0.9f
        paint.alpha = 255
        horizonPath.reset()
        horizonPath.moveTo(-width * 0.18f, height * 0.72f + leftMotion)
        horizonPath.cubicTo(
            width * 0.17f,
            height * 0.56f + firstCrestMotion,
            width * 0.39f,
            height * 0.82f + firstValleyMotion,
            width * 0.61f,
            height * 0.63f + centerMotion,
        )
        horizonPath.cubicTo(
            width * 0.81f,
            height * 0.47f + secondCrestMotion,
            width * 0.97f,
            height * 0.7f + rightMotion,
            width * 1.18f,
            height * 0.62f + rightMotion,
        )
        paint.shader = LinearGradient(
            0f,
            height * 0.62f,
            width.toFloat(),
            height * 0.62f,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(auroraColors[0], 24),
                ColorUtils.setAlphaComponent(auroraColors[1], 28),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.2f, 0.76f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = density * 18f
        canvas.drawPath(horizonPath, paint)
        paint.shader = LinearGradient(
            0f,
            height * 0.7f,
            width.toFloat(),
            height * 0.7f,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(auroraColors[0], 184),
                ColorUtils.setAlphaComponent(auroraColors[1], 164),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.25f, 0.74f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.strokeWidth = density * 2.1f
        canvas.drawPath(horizonPath, paint)
        paint.shader = null
        paint.color = ColorUtils.setAlphaComponent(Color.WHITE, 58)
        paint.strokeWidth = density * 0.45f
        canvas.drawPath(horizonPath, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawGalaxyGlow(canvas: Canvas) {
        val radius = minOf(width, height).toFloat() * 0.72f
        paint.alpha = 255
        paint.shader = RadialGradient(
            width * 0.77f,
            height * 0.66f,
            radius,
            intArrayOf(
                Color.argb(42, 165, 130, 255),
                Color.argb(14, 98, 83, 198),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(width * 0.77f, height * 0.66f, radius, paint)
        paint.shader = null
    }
}

internal object StreamCenterStremioManifestRefreshNotice {
    private val lock = Any()
    private var generation = 0L
    private var runningGeneration: Long? = null
    private var pendingResult: StreamCenterStremioManifestRefreshResult? = null
    private var nextObserverToken = 1
    private val observers = linkedMapOf<Int, (StreamCenterStremioManifestRefreshResult) -> Unit>()

    fun begin(): Long = synchronized(lock) {
        generation += 1
        runningGeneration = generation
        pendingResult = null
        generation
    }

    fun complete(
        refreshGeneration: Long,
        result: StreamCenterStremioManifestRefreshResult,
    ) {
        val callbacks = synchronized(lock) {
            if (runningGeneration != refreshGeneration) return
            runningGeneration = null
            observers.values.toList().also { activeObservers ->
                pendingResult = result.takeIf { activeObservers.isEmpty() }
            }
        }
        callbacks.forEach { callback -> runCatching { callback(result) } }
    }

    fun observe(
        callback: (StreamCenterStremioManifestRefreshResult) -> Unit,
    ): Int {
        var immediateResult: StreamCenterStremioManifestRefreshResult? = null
        val token = synchronized(lock) {
            val observerToken = nextObserverToken++
            observers[observerToken] = callback
            immediateResult = pendingResult
            pendingResult = null
            observerToken
        }
        immediateResult?.let(callback)
        return token
    }

    fun removeObserver(token: Int?) {
        token ?: return
        synchronized(lock) { observers.remove(token) }
    }

    fun reset() {
        synchronized(lock) {
            generation += 1
            runningGeneration = null
            pendingResult = null
        }
    }
}

class StreamCenterSettings : StreamCenterBaseSettingsFragment() {
    private var sourcesStatus: TextView? = null
    private var mainContent: View? = null
    private var openSubmenus = 0
    private var stremioManifestRefreshStarted = false
    private var iconPreloadContainer: FrameLayout? = null
    private var iconPreloadGeneration = 0
    private val preloadedIconUrls = mutableSetOf<String>()
    private val menuSparkleTargets = mutableListOf<BorderSparkleTarget>()
    private var supportAurora: SettingsAuroraDecoration? = null
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
        refreshStremioManifestsOnSettingsOpen()
        menuSparkleTargets.clear()
        val content = rootContainer().apply {
            clipToPadding = false
            minimumHeight = minOf(
                standardSubmenuMinimumHeight() + dp(MAIN_MENU_SUBMENU_REVEAL_DP),
                (resources.displayMetrics.heightPixels * 0.9f).toInt(),
            )
        }
        mainContent = content
        content.addView(
            header(
                title = "StreamCenter",
                metadata = buildInfoBadges(),
                centered = true,
                titleEffect = true,
            ),
        )
        content.addView(headerConnector())

        val performanceCard = switchRow(
            title = "Modalità Prestazioni",
            checked = StreamCenterPlugin.isPerformanceModeEnabled(sharedPref),
            accent = COLOR_PERFORMANCE,
            icon = "⚡",
        ) { enabled ->
            sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_PERFORMANCE_MODE, enabled) }
            refreshVisibleSettingsEffects()
            saveToast(if (enabled) "Modalità Prestazioni ON" else "Modalità Prestazioni OFF")
        }
        (performanceCard.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
        menuSparkleTargets += BorderSparkleTarget(performanceCard, COLOR_PERFORMANCE)
        content.addView(performanceCard)
        content.addView(
            settingsMenuCard(
                title = "Preferenze",
                icon = "🖼️",
                accent = COLOR_DISPLAY,
            ) {
                showSubmenu(StreamCenterDisplaySettingsFragment(), "StreamCenterDisplaySettings")
            },
        )
        content.addView(
            settingsMenuCard(
                title = "Home",
                icon = "🏠",
                accent = COLOR_HOME,
            ) {
                showSubmenu(StreamCenterHomeSettingsFragment(), "StreamCenterHomeSettings")
            },
        )
        content.addView(
            settingsMenuCard(
                title = "Fonti Streaming",
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
                icon = "🛟",
                accent = COLOR_SUPPORT,
            ) {
                showSubmenu(StreamCenterSupportSettingsFragment(), "StreamCenterSupportSettings")
            },
        )
        supportAurora = SettingsAuroraDecoration(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(126),
            ).apply {
                topMargin = dp(4)
                leftMargin = -dp(16)
                rightMargin = -dp(16)
            }
            setActive(visualParticlesEnabled)
        }.also(content::addView)

        iconPreloadContainer = FrameLayout(requireContext()).apply {
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(1, 1)
        }.also(content::addView)
        preloadSettingsIcons()

        startBorderSparkleCycle(menuSparkleTargets)
        return scroll(content)
    }

    private fun refreshStremioManifestsOnSettingsOpen() {
        if (stremioManifestRefreshStarted) return
        stremioManifestRefreshStarted = true
        val refreshGeneration = StreamCenterStremioManifestRefreshNotice.begin()
        val prefs = sharedPref
        val configuredCount = StreamCenterPlugin.getStremioAddons(prefs).size
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                StreamCenterPlugin.refreshStremioAddonManifests(prefs)
            }.getOrElse {
                StreamCenterStremioManifestRefreshResult(
                    total = configuredCount,
                    updated = 0,
                )
            }
            withContext(Dispatchers.Main) {
                StreamCenterStremioManifestRefreshNotice.complete(refreshGeneration, result)
            }
        }
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
        iconPreloadGeneration += 1
        iconPreloadContainer?.removeAllViews()
        sourcesStatus = null
        mainContent = null
        iconPreloadContainer = null
        supportAurora = null
        preloadedIconUrls.clear()
        super.onDestroyView()
        menuSparkleTargets.clear()
    }

    private fun preloadSettingsIcons() {
        val container = iconPreloadContainer ?: return
        val generation = ++iconPreloadGeneration
        val directIconUrls = (
            StreamCenterPlugin.getStremioAddons(sharedPref).mapNotNull(StreamCenterStremioAddon::logoUrl) +
                StreamCenterCatalogs.catalogs.mapNotNull(StreamCenterCatalogDefinition::iconUrl) +
                TELEGRAM_ICON_URL
            )
            .distinct()
        directIconUrls.forEach { preloadIcon(container, it) }
        val siteUrls = (
            StreamCenterCatalogs.catalogs.filter { it.iconUrl == null }
                .map(StreamCenterCatalogDefinition::websiteUrl) +
                StreamCenterPlugin.streamingSources.map { source ->
                    StreamCenterPlugin.getSourceBaseUrl(sharedPref, source.key)
                }
            ).filter(String::isNotBlank).distinct()
        CoroutineScope(Dispatchers.IO).launch {
            val resolvedIconUrls = siteUrls.map { siteUrl ->
                async { StreamCenterSiteIcons.resolve(siteUrl) }
            }.awaitAll().filterNotNull().distinct()
            withContext(Dispatchers.Main) {
                if (
                    !isAdded ||
                    generation != iconPreloadGeneration ||
                    iconPreloadContainer !== container
                ) {
                    return@withContext
                }
                resolvedIconUrls.forEach { preloadIcon(container, it) }
            }
        }
    }

    private fun preloadIcon(container: FrameLayout, iconUrl: String) {
        if (!preloadedIconUrls.add(iconUrl)) return
        val imageView = ImageView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(dp(42), dp(42))
        }
        container.addView(imageView)
        ImageLoader.run { imageView.loadImage(iconUrl) }
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
                value = formatBuildValue(line.removePrefix("Build ")),
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
            badges += bodyText(infoLines.firstOrNull() ?: "???", 12).apply {
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

    private fun formatBuildValue(value: String): String {
        val match = Regex("(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2})").find(value) ?: return value
        return "${match.groupValues[1]} · ${match.groupValues[2]}"
    }

    private fun settingsMenuCard(
        title: String,
        summary: String? = null,
        icon: String,
        accent: String,
        status: String? = null,
        onStatusReady: ((TextView) -> Unit)? = null,
        onClick: () -> Unit,
    ): LinearLayout {
        val statusView = status?.let { chip(it, accent) }
        val arrow = chevron(accent)
        val card = settingsRow(
            title = title,
            summary = summary,
            icon = icon,
            accent = accent,
            fillColor = COLOR_CARD,
            statusView = statusView,
            trailingViews = listOf(arrow),
            touchTarget = arrow,
            onClick = onClick,
        ).view
        statusView?.let { onStatusReady?.invoke(it) }
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
        supportAurora?.setActive(visualParticlesEnabled)
        updateMainBackdrop()
    }
}
