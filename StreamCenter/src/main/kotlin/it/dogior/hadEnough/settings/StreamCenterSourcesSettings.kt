package it.dogior.hadEnough.settings

import it.dogior.hadEnough.*
import it.dogior.hadEnough.stremio.*

import android.animation.ArgbEvaluator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import kotlin.math.sin

private const val STREMIO_CATEGORY_TITLE = "Add-on Stremio"
private const val STREMIO_MANIFEST_REFRESH_NOTICE_MS = 3_000L
private const val STREMIO_MANIFEST_REFRESH_FADE_MS = 450L
private const val STREMIO_MANIFEST_REFRESH_NOTICE_ALPHA = 0.72f

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

private data class SourceActionCard(
    val view: LinearLayout,
    val badge: View,
)

class StreamCenterSourcesSettingsFragment : StreamCenterSupportSettingsFragment() {
    private val rows = mutableListOf<SourceRowState>()
    private val preloadedSourceIconUrls = mutableSetOf<String>()
    private val sourceCategories = listOf(
        SourceCategory("anime", "Anime", "🎌", COLOR_SOURCE_ANIME),
        SourceCategory("tv", "Serie TV", "📺", COLOR_SOURCE_TV),
    )
    private val categoryStatusViews = mutableMapOf<String, TextView>()
    private var expandedCategoryKey: String? = null
    private var pendingCategoryExpansionKey: String? = null
    private var categoryTransitionRunning = false
    private var rowsContainer: LinearLayout? = null
    private var sourceIconPreloadContainer: FrameLayout? = null
    private var sourceIconPreloadGeneration = 0
    private var stremioManifestRefreshNoticeView: TextView? = null
    private var stremioCategoryStatusView: TextView? = null
    private var isStreamingCommunityLinkCheckRunning = false
    private var isStremioAddonInstallRunning = false
    private var manifestRefreshObserverToken: Int? = null
    private var stremioManifestRefreshNoticeUntil = 0L
    private val fadeStremioManifestRefreshNotice = Runnable {
        val noticeView = stremioManifestRefreshNoticeView ?: return@Runnable
        val remainingMs = stremioManifestRefreshNoticeUntil - System.currentTimeMillis()
        if (remainingMs > 0L) {
            updateStremioManifestRefreshNotice()
            return@Runnable
        }
        noticeView.animate()
            .alpha(0f)
            .setDuration(STREMIO_MANIFEST_REFRESH_FADE_MS)
            .withEndAction {
                if (stremioManifestRefreshNoticeView === noticeView) {
                    stremioManifestRefreshNoticeUntil = 0L
                    noticeView.visibility = View.GONE
                    noticeView.alpha = STREMIO_MANIFEST_REFRESH_NOTICE_ALPHA
                }
            }
            .start()
    }

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
                title = "Fonti Streaming",
                icon = "📡",
                accent = COLOR_SOURCES,
                actionText = "Ripristina",
                onAction = { resetSources() },
                actionWidthDp = 104,
                actionHeightDp = 44,
                actionGravity = Gravity.TOP,
                actionTopMarginDp = 4,
            ),
        )

        content.addView(sourceDomainUpdateCard())

        content.addView(apiCheckCard())

        val rowsView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 10)
        }
        rowsContainer = rowsView
        content.addView(rowsView)
        renderRows()

        sourceIconPreloadContainer = FrameLayout(requireContext()).apply {
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(1, 1)
        }.also(content::addView)
        preloadStreamingSourceIcons()

        return scroll(content, fixedSubmenuHeight = true)
    }

    override fun onStart() {
        super.onStart()
        if (manifestRefreshObserverToken != null) return
        manifestRefreshObserverToken = StreamCenterStremioManifestRefreshNotice.observe { result ->
            if (!isAdded || view == null) return@observe
            stremioManifestRefreshNoticeUntil = if (result.updated > 0) {
                System.currentTimeMillis() + STREMIO_MANIFEST_REFRESH_NOTICE_MS
            } else {
                0L
            }
            renderRows()
            preloadStreamingSourceIcons()
        }
        updateStremioManifestRefreshNotice()
    }

    override fun onStop() {
        StreamCenterStremioManifestRefreshNotice.removeObserver(manifestRefreshObserverToken)
        manifestRefreshObserverToken = null
        stremioManifestRefreshNoticeView?.removeCallbacks(fadeStremioManifestRefreshNotice)
        stremioManifestRefreshNoticeView?.animate()?.cancel()
        super.onStop()
    }

    private fun sourceActionCard(
        title: String,
        summary: String,
        accent: String,
        icon: String,
        trailing: View,
        motionTarget: View? = null,
        onClick: () -> Unit,
    ): SourceActionCard {
        val row = settingsRow(
            title = title,
            summary = summary,
            icon = icon,
            accent = accent,
            fillColor = COLOR_CARD_ALT,
            strokeColor = tint(accent, "70"),
            trailingViews = listOf(trailing),
            touchTarget = motionTarget,
            onClick = onClick,
        )
        row.summary?.alpha = 0.82f
        return SourceActionCard(row.view, row.badge ?: error("Icona mancante"))
    }

    private fun sourceDomainUpdateCard(): LinearLayout {
        lateinit var actionCard: SourceActionCard
        lateinit var toggle: SwitchCompat
        toggle = styledSwitch(
            StreamCenterPlugin.isSourceUrlAutoUpdateEnabled(sharedPref),
            COLOR_SOURCE_UPDATE,
        ) { enabled ->
            playToggleFeedback(actionCard.view, actionCard.badge, COLOR_SOURCE_UPDATE, enabled)
            sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_AUTO_UPDATE_SOURCE_URLS, enabled) }
        }
        actionCard = sourceActionCard(
            title = "Aggiornamento automatico",
            summary = "Rileva automaticamente il nuovo indirizzo delle fonti e lo salva.",
            accent = COLOR_SOURCE_UPDATE,
            icon = "🔄",
            trailing = toggle,
            onClick = { toggle.toggle() },
        )
        return actionCard.view
    }

    private fun apiCheckCard(): LinearLayout {
        val arrow = chevron(COLOR_API_CHECK)
        return sourceActionCard(
            title = "Verifica API e fonti",
            summary = "Controlla la raggiungibilità di servizi e fonti streaming.",
            accent = COLOR_API_CHECK,
            icon = "📡",
            trailing = arrow,
            motionTarget = arrow,
            onClick = { checkApis() },
        ).view
    }

    private fun stremioAddonsCategoryCard(): LinearLayout {
        val categoryKey = "stremio"
        val addons = StreamCenterPlugin.getStremioAddons(sharedPref)
        val expanded = expandedCategoryKey == categoryKey
        return categoryContainer(COLOR_STREMIO, topMargin = 0).apply {
            val notice = counterText("(manifest aggiornati)", 11).apply {
                setPadding(dp(5), dp(2), 0, 0)
                stremioManifestRefreshNoticeView = this
                updateStremioManifestRefreshNotice()
            }
            val status = counterText(stremioCategoryStatus()).apply {
                stremioCategoryStatusView = this
            }
            val expandButton = categoryExpandButton(
                expanded = expanded,
                description = if (expanded) "Chiudi Add-on Stremio" else "Apri Add-on Stremio",
                accent = COLOR_STREMIO,
                size = 34,
            ) { toggleCategory(categoryKey) }
            addView(categoryHeaderRow(
                title = STREMIO_CATEGORY_TITLE,
                summaryView = status,
                icon = "\uD83D\uDD0C",
                accent = COLOR_STREMIO,
                trailingViews = listOf(expandButton),
                titleCompanion = notice,
            ) { expandButton.callOnClick() }.view)

            if (expanded) {
                val expandedContent = LinearLayout(requireContext()).apply {
                    tag = "source-category-content:$categoryKey"
                    orientation = LinearLayout.VERTICAL
                }
                if (addons.isEmpty()) {
                    expandedContent.addView(stremioEmptyState())
                }
                if (addons.isNotEmpty()) {
                    addons.forEach { addon -> expandedContent.addView(stremioAddonRow(addon)) }
                }
                expandedContent.addView(stremioCategoryFooter())
                addView(expandedContent)
                if (pendingCategoryExpansionKey == categoryKey) {
                    animateCategoryExpansion(expandedContent)
                }
            }
        }
    }

    private fun stremioCategoryStatus(): String {
        val addons = StreamCenterPlugin.getStremioAddons(sharedPref)
        if (addons.isEmpty()) return "Nessun add-on"
        val enabledAddons = addons.count { addon ->
            StreamCenterPlugin.isStremioAddonEnabled(sharedPref, addon.key)
        }
        return "$enabledAddons/${addons.size} add-on attivi"
    }

    private fun refreshStremioCategoryStatus() {
        stremioCategoryStatusView?.text = stremioCategoryStatus()
    }

    private fun updateStremioManifestRefreshNotice() {
        val noticeView = stremioManifestRefreshNoticeView ?: return
        noticeView.removeCallbacks(fadeStremioManifestRefreshNotice)
        noticeView.animate().cancel()
        val remainingMs = stremioManifestRefreshNoticeUntil - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            noticeView.visibility = View.GONE
            noticeView.alpha = STREMIO_MANIFEST_REFRESH_NOTICE_ALPHA
            return
        }
        noticeView.visibility = View.VISIBLE
        noticeView.alpha = STREMIO_MANIFEST_REFRESH_NOTICE_ALPHA
        noticeView.postDelayed(fadeStremioManifestRefreshNotice, remainingMs)
    }

    private fun stremioEmptyState(): LinearLayout {
        return emptyStateCard("Nessun add-on", COLOR_STREMIO)
    }

    private fun stremioCategoryFooter(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 8)
            addView(actionButton("+ Aggiungi add-on", COLOR_STREMIO) {
                showAddStremioAddonDialog()
            })
        }
    }

    private fun stremioAddonBadge(addon: StreamCenterStremioAddon): FrameLayout {
        return siteIconBadge(
            fallback = "🔌",
            accent = COLOR_STREMIO,
            contentDescription = "Logo di ${addon.name}",
            iconUrl = addon.logoUrl,
        )
    }

    private fun stremioAddonRow(addon: StreamCenterStremioAddon): LinearLayout {
        val enabled = StreamCenterPlugin.isStremioAddonEnabled(sharedPref, addon.key)
        val supportsStreams = addon.resources.any { it.name.equals("stream", ignoreCase = true) }
        val supportsSubtitles = addon.resources.any { it.name.equals("subtitles", ignoreCase = true) }
        val capabilities = listOfNotNull(
            "stream".takeIf { supportsStreams },
            "sottotitoli".takeIf { supportsSubtitles },
        ).joinToString(" · ")
        return LinearLayout(requireContext()).apply {
            val rowView = this
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(9))
            background = cardBackground(
                if (enabled) COLOR_CARD else COLOR_CARD_DISABLED,
                if (enabled) tint(COLOR_STREMIO, "66") else COLOR_STROKE,
                14,
            )
            layoutParams = verticalParams(top = 7)
            val badge = stremioAddonBadge(addon).apply {
                alpha = if (enabled) 1f else 0.5f
            }
            val topLine = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topLine.addView(badge)
            topLine.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(titleText(addon.name, 14, true))
                addView(bodyText(listOfNotNull(capabilities.takeIf(String::isNotBlank), addon.version?.let { "v$it" }).joinToString(" · "), 11).apply {
                    setPadding(0, dp(2), 0, 0)
                    alpha = 0.75f
                })
            })
            topLine.addView(styledSwitch(
                enabled,
                COLOR_STREMIO,
            ) { isEnabled ->
                animateCardFill(
                    rowView,
                    fromColor = if (isEnabled) COLOR_CARD_DISABLED else COLOR_CARD,
                    toColor = if (isEnabled) COLOR_CARD else COLOR_CARD_DISABLED,
                    strokeColor = if (isEnabled) tint(COLOR_STREMIO, "66") else COLOR_STROKE,
                )
                badge.alpha = if (isEnabled) 1f else 0.5f
                StreamCenterPlugin.setStremioAddonEnabled(sharedPref, addon.key, isEnabled)
                refreshStremioCategoryStatus()
                saveToast(if (isEnabled) "Add-on attivato" else "Add-on disattivato")
            }.apply {
                contentDescription = "Attiva o disattiva stream e sottotitoli di ${addon.name}"
            })
            lateinit var refreshButton: TextView
            refreshButton = iconButton(
                symbol = "↻",
                description = "Aggiorna il manifest di ${addon.name}",
                accent = COLOR_STREMIO,
                size = 30,
            ) { refreshStremioAddon(addon, refreshButton) }
            topLine.addView(refreshButton)
            topLine.addView(iconButton(
                symbol = "\u270E",
                description = "Modifica il link del manifest di ${addon.name}",
                accent = COLOR_STREMIO,
                size = 30,
            ) { editStremioManifestUrl(addon) })
            topLine.addView(deleteIconButton(
                description = "Rimuovi ${addon.name}",
                accent = COLOR_SUPPORT,
                size = 30,
            ) { confirmStremioAddonRemoval(addon) })
            addView(topLine)
            addCardTouchFeedback(this, COLOR_STREMIO, badge)
        }
    }

    private fun showAddStremioAddonDialog() {
        if (isStremioAddonInstallRunning) return
        val ctx = context ?: return
        val input = input("").apply {
            hint = "https://esempio.it/manifest.json"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            filters = arrayOf(InputFilter.LengthFilter(500))
            layoutParams = verticalParams()
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Aggiungi add-on Stremio"))
            .setView(container)
            .setPositiveButton("Aggiungi", null)
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val manifestUrl = input.text?.toString().orEmpty().trim()
            if (manifestUrl.isBlank()) {
                input.error = "Inserisci il link al manifest"
                return@setOnClickListener
            }
            isStremioAddonInstallRunning = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            CoroutineScope(Dispatchers.IO).launch {
                val result = runCatching { StreamCenterStremioAddonClient.readManifest(manifestUrl) }
                withContext(Dispatchers.Main) {
                    isStremioAddonInstallRunning = false
                    if (!isAdded) return@withContext
                    if (result.isSuccess) {
                        StreamCenterPlugin.saveStremioAddon(sharedPref, result.getOrThrow())
                        renderRows()
                        dialog.dismiss()
                        saveToast("Add-on aggiunto")
                    } else {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        input.error = result.exceptionOrNull()?.message ?: "Impossibile leggere il manifest"
                    }
                }
            }
        }
    }

    private fun editStremioManifestUrl(addon: StreamCenterStremioAddon) {
        if (isStremioAddonInstallRunning) return
        val ctx = context ?: return
        val input = input(addon.manifestUrl).apply {
            hint = "https://esempio.it/manifest.json"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            filters = arrayOf(InputFilter.LengthFilter(500))
            setSelection(text.length)
            layoutParams = verticalParams()
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Modifica manifest"))
            .setView(container)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val manifestUrl = input.text?.toString().orEmpty().trim()
            if (manifestUrl.isBlank()) {
                input.error = "Inserisci il link al manifest"
                return@setOnClickListener
            }
            isStremioAddonInstallRunning = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            CoroutineScope(Dispatchers.IO).launch {
                val result = runCatching { StreamCenterStremioAddonClient.readManifest(manifestUrl) }
                withContext(Dispatchers.Main) {
                    isStremioAddonInstallRunning = false
                    if (!isAdded) return@withContext
                    if (result.isSuccess) {
                        val replacement = result.getOrThrow()
                        if (!StreamCenterPlugin.replaceStremioAddon(sharedPref, addon, replacement)) {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            input.error = "Esiste gia un add-on con questo ID"
                            return@withContext
                        }
                        renderRows()
                        dialog.dismiss()
                        saveToast("Link del manifest aggiornato")
                    } else {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        input.error = result.exceptionOrNull()?.message ?: "Impossibile leggere il manifest"
                    }
                }
            }
        }
    }

    private fun refreshStremioAddon(addon: StreamCenterStremioAddon, button: TextView) {
        if (isStremioAddonInstallRunning) return
        isStremioAddonInstallRunning = true
        button.isEnabled = false
        button.text = "…"
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { StreamCenterStremioAddonClient.readManifest(addon.manifestUrl) }
            withContext(Dispatchers.Main) {
                isStremioAddonInstallRunning = false
                if (!isAdded) return@withContext
                if (result.isSuccess) {
                    val replacement = result.getOrThrow()
                    if (!StreamCenterPlugin.replaceStremioAddon(sharedPref, addon, replacement)) {
                        button.isEnabled = true
                        button.text = "↻"
                        saveToast("Il manifest aggiornato usa un ID gia configurato")
                        return@withContext
                    }
                    renderRows()
                    saveToast("Manifest aggiornato")
                } else {
                    button.isEnabled = true
                    button.text = "↻"
                    saveToast(result.exceptionOrNull()?.message ?: "Impossibile aggiornare il manifest")
                }
            }
        }
    }

    private fun confirmStremioAddonRemoval(addon: StreamCenterStremioAddon) {
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Elimina add-on Stremio"))
            .setMessage("Vuoi eliminare l'add-on \"${addon.name}\"?")
            .setPositiveButton("Elimina") { _, _ ->
                StreamCenterPlugin.removeStremioAddon(sharedPref, addon.key)
                renderRows()
                saveToast("Add-on eliminato")
            }
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
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
        stremioManifestRefreshNoticeView?.removeCallbacks(fadeStremioManifestRefreshNotice)
        stremioManifestRefreshNoticeView?.animate()?.cancel()
        container.removeAllViews()
        categoryStatusViews.clear()
        stremioManifestRefreshNoticeView = null
        stremioCategoryStatusView = null
        val sparkleTargets = mutableListOf<BorderSparkleTarget>()
        val stremioCategoryCard = stremioAddonsCategoryCard()
        sparkleTargets += BorderSparkleTarget(stremioCategoryCard, COLOR_STREMIO)
        container.addView(stremioCategoryCard)
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
        return categoryContainer(category.accent).apply {
            val status = counterText(categoryStatus(category.key)).apply {
                categoryStatusViews[category.key] = this
            }
            val expandButton = categoryExpandButton(
                expanded = expanded,
                description = if (expanded) "Chiudi ${category.title}" else "Apri ${category.title}",
                accent = category.accent,
                size = 34,
            ) { toggleCategory(category.key) }
            addView(categoryHeaderRow(
                title = category.title,
                summaryView = status,
                icon = category.icon,
                accent = category.accent,
                trailingViews = listOf(expandButton),
            ) { expandButton.callOnClick() }.view)

            if (expanded) {
                val expandedContent = LinearLayout(requireContext()).apply {
                    tag = "source-category-content:${category.key}"
                    orientation = LinearLayout.VERTICAL
                }
                categoryRows.forEachIndexed { index, row ->
                    expandedContent.addView(sourceRow(index, row, category.accent, categoryRows.size))
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

    private fun nativeSourceBadge(
        row: SourceRowState,
        accent: String,
    ): FrameLayout {
        return siteIconBadge(
            fallback = "▶",
            accent = accent,
            contentDescription = "Icona di ${row.source.title}",
            websiteUrl = row.url,
        )
    }

    private fun preloadStreamingSourceIcons() {
        val container = sourceIconPreloadContainer ?: return
        val generation = ++sourceIconPreloadGeneration

        StreamCenterPlugin.getStremioAddons(sharedPref)
            .mapNotNull(StreamCenterStremioAddon::logoUrl)
            .distinct()
            .forEach { iconUrl -> preloadSourceIcon(container, iconUrl) }

        val sourceUrls = rows.map(SourceRowState::url).filter(String::isNotBlank).distinct()
        CoroutineScope(Dispatchers.IO).launch {
            sourceUrls.forEach { sourceUrl ->
                launch {
                    val iconUrl = StreamCenterSiteIcons.resolve(sourceUrl) ?: return@launch
                    withContext(Dispatchers.Main) {
                        if (
                            !isAdded ||
                            generation != sourceIconPreloadGeneration ||
                            sourceIconPreloadContainer !== container
                        ) {
                            return@withContext
                        }
                        preloadSourceIcon(container, iconUrl)
                    }
                }
            }
        }
    }

    private fun preloadSourceIcon(container: FrameLayout, iconUrl: String) {
        if (!preloadedSourceIconUrls.add(iconUrl)) return
        val preloadView = ImageView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(dp(42), dp(42))
        }
        container.addView(preloadView)
        ImageLoader.run { preloadView.loadImage(iconUrl) }
    }

    private fun sourceRow(
        index: Int,
        row: SourceRowState,
        accent: String,
        categorySize: Int,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            val rowView = this
            val isStreamingCommunity = row.source.key == StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY
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
            val sourceBadge = nativeSourceBadge(row, accent).apply {
                alpha = if (row.enabled) 1f else 0.5f
            }
            topLine.addView(sourceBadge)
            val texts = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(titleText(row.source.title, 15, true).apply {
                if (isStreamingCommunity) setSingleLine(true)
            })
            topLine.addView(texts)
            topLine.addView(styledSwitch(row.enabled, accent) { checked ->
                row.enabled = checked
                animateCardFill(
                    rowView,
                    fromColor = if (checked) COLOR_CARD_DISABLED else COLOR_CARD,
                    toColor = if (checked) COLOR_CARD else COLOR_CARD_DISABLED,
                )
                sourceBadge.alpha = if (checked) 1f else 0.5f
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
            if (isStreamingCommunity) {
                linkRow.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                        marginStart = dp(8)
                    }
                })
                lateinit var checkLinkButton: TextView
                checkLinkButton = iconButton(
                    symbol = "↻",
                    description = "Verifica il link aggiornato di StreamingCommunity",
                    accent = accent,
                    size = 30,
                ) {
                    checkStreamingCommunityLink(row, linkInput, checkLinkButton)
                }
                linkRow.addView(checkLinkButton)
            } else if (categorySize > 1) {
                linkRow.addView(reorderIconButton(
                    symbol = "↑",
                    description = "Alza la priorità di ${row.source.title}",
                    accent = accent,
                    enabled = index > 0,
                    size = 30,
                ) {
                    moveRow(row.source.category, index, -1)
                })
                linkRow.addView(reorderIconButton(
                    symbol = "↓",
                    description = "Abbassa la priorità di ${row.source.title}",
                    accent = accent,
                    enabled = index < categorySize - 1,
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
                    .setCustomTitle(dialogTitle("Nuovo link StreamingCommunity"))
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
        sourceIconPreloadGeneration += 1
        sourceIconPreloadContainer?.removeAllViews()
        stremioManifestRefreshNoticeView?.removeCallbacks(fadeStremioManifestRefreshNotice)
        stremioManifestRefreshNoticeView?.animate()?.cancel()
        categoryStatusViews.clear()
        rowsContainer = null
        sourceIconPreloadContainer = null
        preloadedSourceIconUrls.clear()
        stremioManifestRefreshNoticeView = null
        stremioCategoryStatusView = null
        super.onDestroyView()
    }
}
