package it.dogior.hadEnough.settings

import it.dogior.hadEnough.*
import it.dogior.hadEnough.catalog.StreamCenterCatalogDefinition
import it.dogior.hadEnough.catalog.StreamCenterCatalogSection
import it.dogior.hadEnough.catalog.StreamCenterCatalogs
import it.dogior.hadEnough.iptv.StreamCenterIptv

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
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
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
import com.lagradost.cloudstream3.TvType
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

private const val CATALOG_ICON_SIZE_DP = 42

private data class HomeRowState(
    val section: StreamCenterHomeSectionDefinition,
    var title: String,
    var enabled: Boolean,
    var count: Int,
)

class StreamCenterHomeSettingsFragment : StreamCenterBaseSettingsFragment() {
    private val rows = mutableListOf<HomeRowState>()
    private val categoryOrder = mutableListOf<String>()
    private val categoryEnabled = mutableMapOf<String, Boolean>()
    private var expandedCategoryKey: String? = null
    private var pendingCategoryExpansionKey: String? = null
    private var categoryTransitionRunning = false
    private var rowsContainer: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        loadRows()

        val content = rootContainer()
        content.minimumHeight = standardSubmenuMinimumHeight()
        val homeHeader = header(
            title = "Home",
            icon = "🏠",
            accent = COLOR_HOME,
            actionText = "Ripristina",
            onAction = { resetHome() },
            actionWidthDp = 104,
            actionHeightDp = 44,
            actionGravity = Gravity.TOP,
            actionTopMarginDp = 4,
        )
        val placeholdersInfoButton = iconButton(
            symbol = "ⓘ",
            description = "Segnaposto disponibili per i titoli",
            accent = COLOR_HOME,
            size = 30,
        ) { showTitlePlaceholdersDialog() }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = dp(4)
                topMargin = dp(11)
                gravity = Gravity.TOP
            }
        }
        homeHeader.addView(
            placeholdersInfoButton,
            (homeHeader.childCount - 1).coerceAtLeast(0),
        )
        content.addView(homeHeader)

        val rowsView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 8)
        }
        rowsContainer = rowsView
        content.addView(rowsView)
        renderRows()

        return scroll(content, fixedSubmenuHeight = true)
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
                setPadding(dp(14), dp(12), dp(14), dp(13))
                background = cardBackground(COLOR_CARD_ALT, tint(accent, "66"), 14)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(iconBadge("✎", accent, size = 30, marginEnd = 9))
                    addView(titleText("Prova un nome", 13, true).apply {
                        setTextColor(Color.parseColor(accent))
                    })
                })
                addView(titleInput)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(11), dp(8), dp(11), dp(9))
                    background = cardBackground(COLOR_INPUT_FILL, tint(accent, "44"), 10)
                    layoutParams = verticalParams(top = 10)
                    addView(bodyText("Anteprima", 10).apply {
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.parseColor(accent))
                    })
                    addView(previewText)
                })
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(8), dp(11), dp(8))
                background = cardBackground(tint(accent, "12"), tint(accent, "44"), 11)
                layoutParams = verticalParams(top = 10)
                addView(iconBadge("ⓘ", accent, size = 28, marginEnd = 9))
                addView(bodyText("Tocca per copiare • Tieni premuto per inserire.", 11).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
            placeholders.forEach { (token, description, example) ->
                val summary = bodyText(description, 11).apply {
                    if (example.isNotBlank()) {
                        text = SpannableString("$description  •  $example").apply {
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
                }
                val copyBadge = iconBadge("⧉", accent, size = 32, marginEnd = 0)
                val placeholderRow = settingsRow(
                    title = token,
                    accent = accent,
                    fillColor = COLOR_INPUT_FILL,
                    strokeColor = tint(accent, "55"),
                    summaryView = summary,
                    trailingViews = listOf(copyBadge),
                    topMargin = 6,
                ) {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText("Segnaposto", token))
                    saveToast("$token copiato")
                }.apply {
                    title.typeface = Typeface.MONOSPACE
                    title.setTextColor(Color.parseColor(accent))
                    view.contentDescription = "Copia $token. Tieni premuto per inserirlo nel nome"
                    view.setOnLongClickListener {
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
                }
                addView(placeholderRow.view)
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
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Segnaposto disponibili"))
            .setView(dialogContent)
            .setPositiveButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
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
            if (
                categoryRows.isEmpty() &&
                categoryKey !in setOf("live", "tracking", StreamCenterCatalogs.CATEGORY_KEY)
            ) return@forEachIndexed
            val accent = categoryAccent(categoryKey)
            val enabled = categoryEnabled[categoryKey] ?: true
            val categoryContainer = categoryContainer(
                accent = accent,
                fillColor = if (enabled) COLOR_CARD_ALT else "#151920",
                strokeColor = if (enabled) tint(accent, "88") else COLOR_STROKE,
                topMargin = if (categoryIndex == 0) 0 else 10,
            ).apply {
                tag = "home-category-container:$categoryKey"
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
                if (categoryKey == StreamCenterCatalogs.CATEGORY_KEY) {
                    expandedContent.addView(catalogsContent())
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
                if (categoryKey == StreamCenterCatalogs.CATEGORY_KEY) {
                    expandedContent.addView(catalogsCategoryFooter())
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
        val accent = categoryAccent(categoryKey)
        val enabled = categoryEnabled[categoryKey] ?: true
        val expanded = expandedCategoryKey == categoryKey
        lateinit var header: SettingsRowViews
        val toggle = styledSwitch(enabled, accent) { checked ->
            categoryEnabled[categoryKey] = checked
            saveRows()
            if (categoryKey == StreamCenterCatalogs.CATEGORY_KEY && checked) {
                StreamCenterPlugin.refreshCatalogs()
            }
            playToggleFeedback(header.view, header.badge, accent, checked)
            updateCategoryAppearance(categoryContainer, categoryKey, checked)
        }
        val categoryIndex = categoryOrder.indexOf(categoryKey)
        val moveUp = reorderIconButton(
            "↑",
            "Sposta ${categoryTitle(categoryKey)} in alto",
            accent,
            enabled = categoryIndex > 0,
        ) {
            moveCategory(categoryKey, -1)
        }
        val moveDown = reorderIconButton(
            "↓",
            "Sposta ${categoryTitle(categoryKey)} in basso",
            accent,
            enabled = categoryIndex in 0 until categoryOrder.lastIndex,
        ) {
            moveCategory(categoryKey, 1)
        }
        val expandButton = categoryExpandButton(
            expanded = expanded,
            description = if (expanded) "Chiudi ${categoryTitle(categoryKey)}" else "Apri ${categoryTitle(categoryKey)}",
            accent = accent,
        ) { toggleHomeCategory(categoryKey) }
        val status = counterText(categorySectionsLabel(categoryKey)).apply {
            tag = "home-category-count:$categoryKey"
        }
        header = categoryHeaderRow(
            title = categoryTitle(categoryKey),
            summaryView = status,
            icon = categoryEmoji(categoryKey),
            accent = accent,
            trailingViews = listOf(toggle, moveUp, moveDown, expandButton),
            strokeColor = tint(accent, "88"),
            enabledAppearance = enabled,
        ) { expandButton.callOnClick() }
        header.view.tag = "home-category:$categoryKey"
        return header.view
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
        if (categoryKey == StreamCenterCatalogs.CATEGORY_KEY) {
            val configured = StreamCenterCatalogs.configuredCatalogs(sharedPref).size
            val available = StreamCenterCatalogs.catalogs.size
            return if (configured == 0) "Nessun catalogo" else "$configured/$available Cataloghi"
        }
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
            StreamCenterCatalogs.CATEGORY_KEY -> "Cataloghi"
            else -> "Altro"
        }
    }

    private fun categoryEmoji(categoryKey: String): String {
        return when (categoryKey) {
            "anime" -> "🎌"
            "tv" -> "📺"
            "movie" -> "🎬"
            "tracking" -> "\uD83D\uDCDA"
            StreamCenterCatalogs.CATEGORY_KEY -> "\uD83D\uDCC7"
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
            StreamCenterCatalogs.CATEGORY_KEY -> COLOR_CATALOGS
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
            radius = 18,
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
            val canMoveUp = index > 0 &&
                StreamCenterPlugin.homeSectionCategoryKey(rows[index - 1].section) == categoryKey
            val canMoveDown = index < rows.lastIndex &&
                StreamCenterPlugin.homeSectionCategoryKey(rows[index + 1].section) == categoryKey
            topLine.addView(reorderIconButton(
                "↑",
                "Sposta la sezione in alto",
                accent,
                enabled = canMoveUp,
            ) {
                row.title = titleInput.text?.toString()?.trim().orEmpty()
                persistCount()
                moveRow(index, -1)
            })
            topLine.addView(reorderIconButton(
                "↓",
                "Sposta la sezione in basso",
                accent,
                enabled = canMoveDown,
            ) {
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
                controls.addView(deleteIconButton("Elimina la sezione TV") {
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
                    controls.addView(iconButton("M", "Modifica i filtri della sezione", accent) {
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
                    controls.addView(deleteIconButton("Elimina la sezione") {
                        confirmDeleteAnimeSection(row.section.key)
                    }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8) })
                }
                if (isTrackingCustomRow) {
                    controls.addView(deleteIconButton("Elimina la lista") {
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
        StreamCenterCatalogs.reset(sharedPref)
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
        val filterRowHeight = dp(60)
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

        fun filterChoiceBadge(label: String, option: String): String {
            if (option == "Qualsiasi") return "•"
            return when (label) {
                "Ordine" -> when (option) {
                    "A-Z" -> "A"
                    "Z-A" -> "Z"
                    "Popolarità" -> "P"
                    else -> "★"
                }
                "Stato" -> when (option) {
                    "In Corso" -> "▶"
                    "Terminato" -> "✓"
                    "In Uscita" -> "◷"
                    else -> "×"
                }
                "Tipo" -> when (option) {
                    "TV Short" -> "TS"
                    "Special" -> "SP"
                    "Movie" -> "M"
                    else -> option
                }
                "Stagione" -> when (option) {
                    "Inverno" -> "❄"
                    "Primavera" -> "✿"
                    "Estate" -> "☀"
                    else -> "🍂"
                }
                else -> option.take(2).uppercase(Locale.ITALY)
            }
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
                        val choices = labels.map { option ->
                            SettingsChoiceOption(
                                label = option,
                                value = option.takeUnless { it == any },
                                badge = filterChoiceBadge(label, option),
                            )
                        }
                        showSettingsChoiceDialog(
                            title = label,
                            options = choices,
                            selectedValue = currentValue(),
                            accent = accent,
                        ) { selected ->
                            text = filterText(label, selected.value ?: any)
                            onSelected(selected.value)
                        }
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
                    dp(360),
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
                    val row = settingsRow(
                        title = choice?.second ?: "Qualsiasi",
                        accent = accent,
                        fillColor = COLOR_CARD_ALT,
                        strokeColor = if (selected) accent else tint(accent, "55"),
                        topMargin = if (index == 0) 0 else 8,
                    ) { selectGenre(choice) }
                    if (selected) row.title.setTextColor(Color.parseColor(accent))
                    genreList.addView(row.view)
                }
            }
            val pickerContent = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
                addView(searchInput)
                addView(genreScroll)
            }
            picker = AlertDialog.Builder(ctx)
                .setCustomTitle(dialogTitle("Genere"))
                .setView(pickerContent)
                .create()
            searchInput.doAfterTextChanged { renderGenres(it?.toString().orEmpty()) }
            renderGenres()
            applyDialogBackdrop(picker)
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
        val dubSwitch = styledSwitch(existing?.dubbed == true, categoryAccent("anime")) { }
        val dubRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(12), dp(6))
            background = cardBackground(COLOR_INPUT_FILL, COLOR_STROKE, 12)
            layoutParams = verticalParams(top = 8).apply { height = filterRowHeight }
            addView(titleText("DUB ITA", 13, true).apply {
                setTextColor(Color.parseColor(COLOR_TEXT))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(12), dp(6))
            background = cardBackground(COLOR_INPUT_FILL, COLOR_STROKE, 12)
            layoutParams = verticalParams(top = 8).apply { height = filterRowHeight }
            addView(titleText("Elementi da mostrare", 13, true).apply {
                setTextColor(Color.parseColor(COLOR_TEXT))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(countInput.apply {
                layoutParams = LinearLayout.LayoutParams(dp(70), dp(40))
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
        val animeDialogTitle = dialogTitle(
            if (sectionKey == null) "Nuova sezione Anime" else "Modifica sezione Anime",
        )

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
            .setCustomTitle(animeDialogTitle)
            .setView(scroll)
            .setPositiveButton(if (sectionKey == null) "Crea" else "Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
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
            .setCustomTitle(dialogTitle("Elimina sezione Anime"))
            .setMessage("Vuoi eliminare la sezione \"$name\"?")
            .setPositiveButton("Elimina") { _, _ ->
                StreamCenterPlugin.deleteAnimeCustomSection(sharedPref, sectionKey)
                loadRows()
                renderRows()
                saveToast("Sezione eliminata")
            }
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun trackingEmptyState(): LinearLayout {
        return emptyStateCard("Nessuna sezione Tracciamento", categoryAccent("tracking"))
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

    private fun catalogTrackingService(
        section: StreamCenterCatalogSection,
    ): StreamCenterTrackingService? {
        val serviceKey = section.trackingServiceKey ?: return null
        return StreamCenterPlugin.trackingServices.firstOrNull { it.key == serviceKey }
    }

    private fun isCatalogSectionAvailable(section: StreamCenterCatalogSection): Boolean {
        if (section.trackingServiceKey == null) return true
        return catalogTrackingService(section)?.let(::isTrackingServiceConnected) == true
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
            val arrow = chevron(accent)
            val row = settingsRow(
                title = service.title,
                summary = if (connected) "Collegato" else "Non collegato nelle impostazioni di CloudStream",
                accent = accent,
                fillColor = if (connected) COLOR_CARD_ALT else COLOR_CARD_DISABLED,
                strokeColor = if (connected) tint(accent, "66") else COLOR_STROKE,
                leadingView = trackingServiceSiteBadge(service),
                trailingViews = if (connected) listOf(arrow) else emptyList(),
                topMargin = 8,
                enabledAppearance = connected,
                disabledAlpha = 0.48f,
                touchTarget = arrow,
                onClick = if (connected) {
                    {
                        dialog.dismiss()
                        promptTrackingListType(service)
                    }
                } else {
                    null
                },
            )
            if (!connected) row.title.setTextColor(Color.parseColor(COLOR_MUTED))
            list.addView(row.view)
        }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Servizio di tracciamento"))
            .setView(list)
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun trackingServiceSiteBadge(service: StreamCenterTrackingService): FrameLayout {
        val catalog = StreamCenterCatalogs.catalogs.firstOrNull { it.key == service.key }
        return catalog?.let { catalogSiteBadge(it, marginEnd = 12) } ?: siteIconBadge(
            fallback = service.title.take(1),
            accent = COLOR_CATALOGS,
            contentDescription = "Icona di ${service.title}",
            size = CATALOG_ICON_SIZE_DP,
            marginEnd = 12,
        )
    }

    private fun promptTrackingListType(service: StreamCenterTrackingService) {
        val ctx = context ?: return
        val accent = categoryAccent("tracking")
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        lateinit var dialog: AlertDialog
        service.statuses.forEach { status ->
            val arrow = chevron(accent)
            val row = settingsRow(
                title = status.title,
                accent = accent,
                fillColor = COLOR_CARD_ALT,
                strokeColor = tint(accent, "66"),
                leadingView = iconBadge(trackingListBadge(status), accent, size = 42, marginEnd = 12),
                trailingViews = listOf(arrow),
                topMargin = 8,
                touchTarget = arrow,
            ) {
                    dialog.dismiss()
                    promptTrackingSectionName(service, status)
                }
            content.addView(row.view)
        }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle(service.title, accent))
            .setView(content)
            .setNegativeButton("Indietro", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun trackingListBadge(status: StreamCenterTrackingListStatus): String {
        return when (status.key) {
            "watching" -> "▶"
            "completed" -> "✓"
            "on_hold" -> "Ⅱ"
            "dropped" -> "■"
            "plan_to_watch" -> "+"
            "rewatching" -> "↻"
            else -> status.title.take(1).uppercase(Locale.ITALY)
        }
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
            .setCustomTitle(dialogTitle("Nome della sezione"))
            .setView(content)
            .setPositiveButton("Crea", null)
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
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
            .setCustomTitle(dialogTitle("Elimina lista di tracciamento"))
            .setMessage("Vuoi eliminare la sezione \"$name\"?")
            .setPositiveButton("Elimina") { _, _ ->
                StreamCenterPlugin.deleteTrackingCustomSection(sharedPref, sectionKey)
                loadRows()
                renderRows()
                saveToast("Lista eliminata")
            }
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun catalogsContent(): LinearLayout {
        val catalogs = StreamCenterCatalogs.configuredCatalogs(sharedPref)
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            if (catalogs.isEmpty()) {
                addView(emptyStateCard("Nessun Catalogo", COLOR_CATALOGS))
            } else {
                catalogs.forEach { catalog -> addView(catalogCard(catalog)) }
            }
        }
    }

    private fun catalogCard(catalog: StreamCenterCatalogDefinition): LinearLayout {
        val accent = COLOR_CATALOGS
        val modifyButton = iconButton("M", "Modifica ${catalog.title}", accent) {
            promptCatalogSections(catalog)
        }
        val deleteButton = deleteIconButton("Elimina ${catalog.title}") {
            confirmDeleteCatalog(catalog)
        }
        return settingsRow(
            title = catalog.title,
            accent = accent,
            fillColor = COLOR_CARD,
            strokeColor = tint(accent, "66"),
            leadingView = catalogSiteBadge(catalog, marginEnd = 12),
            trailingViews = listOf(modifyButton, deleteButton),
            topMargin = 8,
        ).view
    }

    private fun catalogsCategoryFooter(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 8)
            addView(actionButton("+ Aggiungi Catalogo", COLOR_CATALOGS) {
                promptCreateCatalog()
            })
        }
    }

    private fun catalogSiteBadge(
        catalog: StreamCenterCatalogDefinition,
        marginEnd: Int = 11,
    ): FrameLayout {
        return siteIconBadge(
            fallback = catalog.title.take(1),
            accent = COLOR_CATALOGS,
            contentDescription = "Icona di ${catalog.title}",
            iconUrl = catalog.iconUrl,
            websiteUrl = catalog.websiteUrl,
            size = CATALOG_ICON_SIZE_DP,
            marginEnd = marginEnd,
        )
    }

    private fun promptCreateCatalog() {
        val ctx = context ?: return
        val accent = COLOR_CATALOGS
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        lateinit var dialog: AlertDialog
        StreamCenterCatalogs.catalogs.forEach { catalog ->
            val arrow = chevron(accent)
            list.addView(settingsRow(
                title = catalog.title,
                accent = accent,
                fillColor = COLOR_CARD_ALT,
                strokeColor = tint(accent, "66"),
                leadingView = catalogSiteBadge(catalog, marginEnd = 12),
                trailingViews = listOf(arrow),
                topMargin = 8,
                touchTarget = arrow,
            ) {
                    dialog.dismiss()
                    promptCatalogSections(catalog)
                }.view)
        }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Fonte del Catalogo"))
            .setView(list)
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun promptCatalogSections(catalog: StreamCenterCatalogDefinition) {
        val ctx = context ?: return
        val accent = COLOR_CATALOGS
        val configured = StreamCenterCatalogs.isConfigured(sharedPref, catalog)
        val orderedSections = StreamCenterCatalogs.orderedSections(sharedPref, catalog).toMutableList()
        val selectedKeys = if (configured) {
            StreamCenterCatalogs.selectedSections(sharedPref, catalog).mapTo(mutableSetOf()) { it.key }
        } else {
            catalog.sections.filter(StreamCenterCatalogSection::defaultEnabled)
                .mapTo(mutableSetOf()) { it.key }
        }
        val sectionList = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        fun renderSectionList() {
            sectionList.removeAllViews()
            orderedSections.forEachIndexed { index, section ->
                val available = isCatalogSectionAvailable(section)
                val trackingService = catalogTrackingService(section)
                if (!available) selectedKeys -= section.key
                val selected = available && section.key in selectedKeys
                lateinit var sectionRow: SettingsRowViews
                lateinit var typeView: TextView
                val sectionSwitch = styledSwitch(selected, accent) { enabled ->
                    if (available) {
                        if (enabled) selectedKeys += section.key else selectedKeys -= section.key
                        val targetAlpha = if (enabled) 1f else 0.55f
                        if (reduceMotion) {
                            sectionRow.view.alpha = targetAlpha
                        } else {
                            sectionRow.view.animate().cancel()
                            sectionRow.view.animate().alpha(targetAlpha).setDuration(220L).start()
                        }
                        animateCardFill(
                            sectionRow.view,
                            fromColor = if (enabled) COLOR_CARD_DISABLED else COLOR_CARD_ALT,
                            toColor = if (enabled) COLOR_CARD_ALT else COLOR_CARD_DISABLED,
                            strokeColor = tint(accent, "55"),
                        )
                        typeView.setTextColor(Color.parseColor(if (enabled) accent else COLOR_MUTED))
                    }
                }.apply {
                    isEnabled = available
                    alpha = if (available) 1f else 0.42f
                }
                val moveUp = reorderIconButton(
                    "↑",
                    "Sposta ${section.title} in alto",
                    accent,
                    enabled = index > 0,
                ) {
                        val movedSection = orderedSections.removeAt(index)
                        orderedSections.add(index - 1, movedSection)
                        renderSectionList()
                }
                val moveDown = reorderIconButton(
                    "↓",
                    "Sposta ${section.title} in basso",
                    accent,
                    enabled = index < orderedSections.lastIndex,
                ) {
                        val movedSection = orderedSections.removeAt(index)
                        orderedSections.add(index + 1, movedSection)
                        renderSectionList()
                }
                val sectionType = when {
                    trackingService != null -> "Tracciamento"
                    section.type == TvType.Movie -> "Film"
                    section.type in setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA) -> "Anime"
                    else -> "Serie TV"
                }
                typeView = bodyText(sectionType, 11).apply {
                    setTextColor(Color.parseColor(if (selected) accent else COLOR_MUTED))
                }
                sectionRow = settingsRow(
                    title = section.title,
                    accent = if (available) accent else COLOR_STROKE,
                    fillColor = if (selected) COLOR_CARD_ALT else COLOR_CARD_DISABLED,
                    strokeColor = if (available) tint(accent, "55") else COLOR_STROKE,
                    summaryView = typeView,
                    trailingViews = listOf(moveUp, moveDown, sectionSwitch),
                    topMargin = 8,
                    enabledAppearance = selected,
                    disabledAlpha = if (available) 0.55f else 0.58f,
                ) {
                        if (available) {
                            sectionSwitch.isChecked = !sectionSwitch.isChecked
                        } else {
                            saveToast("Collega l'account ${trackingService?.title ?: catalog.title} in CloudStream")
                        }
                    }
                if (!available) sectionRow.title.setTextColor(Color.parseColor(COLOR_MUTED))
                sectionList.addView(sectionRow.view)
            }
        }
        renderSectionList()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(sectionList)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(410)))
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogBrandTitle(
                catalog.title,
                catalogSiteBadge(catalog, marginEnd = 10),
                accent,
            ))
            .setView(content)
            .setPositiveButton(if (configured) "Salva" else "Crea", null)
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            selectedKeys.removeAll { sectionKey ->
                catalog.sections.firstOrNull { it.key == sectionKey }
                    ?.let(::isCatalogSectionAvailable) == false
            }
            if (selectedKeys.isEmpty()) {
                saveToast("Seleziona almeno una sezione")
                return@setOnClickListener
            }
            if (!StreamCenterCatalogs.saveCatalog(
                    sharedPref,
                    catalog,
                    selectedKeys,
                    orderedSections.map { it.key },
                )
            ) {
                saveToast("Impossibile salvare il Catalogo")
                return@setOnClickListener
            }
            sharedPref?.edit {
                putBoolean(StreamCenterPlugin.homeCategoryEnabledKey(StreamCenterCatalogs.CATEGORY_KEY), true)
            }
            categoryEnabled[StreamCenterCatalogs.CATEGORY_KEY] = true
            StreamCenterPlugin.refreshCatalogs()
            loadRows()
            renderRows()
            saveToast(if (configured) "Catalogo aggiornato" else "Catalogo ${catalog.title} aggiunto")
            dialog.dismiss()
        }
    }

    private fun confirmDeleteCatalog(catalog: StreamCenterCatalogDefinition) {
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Elimina Catalogo"))
            .setMessage("Vuoi rimuovere ${catalog.title}?")
            .setPositiveButton("Elimina") { _, _ ->
                StreamCenterCatalogs.removeCatalog(sharedPref, catalog)
                loadRows()
                renderRows()
                saveToast("Catalogo ${catalog.title} eliminato")
            }
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun tvEmptyState(): LinearLayout {
        return emptyStateCard("Nessuna sezione TV", categoryAccent("live"))
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
            val arrow = chevron(presetAccent)
            presetList.addView(settingsRow(
                title = preset.title,
                icon = preset.icon,
                accent = presetAccent,
                fillColor = COLOR_CARD_ALT,
                strokeColor = tint(presetAccent, "72"),
                trailingViews = listOf(arrow),
                topMargin = 8,
                touchTarget = arrow,
            ) {
                    dialog.dismiss()
                    createTvSectionFromPreset(preset)
                }.view)
        }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Preset TV"))
            .setView(ScrollView(ctx).apply { addView(presetList) })
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
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
            .setCustomTitle(dialogTitle("Nuova sezione TV"))
            .setView(container)
            .setPositiveButton("Crea", null)
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
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
            .setCustomTitle(dialogTitle("Elimina sezione TV"))
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
        applyDialogBackdrop(dialog)
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
        val testTitle = dialogTitle("Test TV (0/${selected.size})")
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(testTitle)
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
                    testTitle.text = "Test TV ($completed/${selected.size})"
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
                testTitle.text = "Risultato test TV"
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
            .setCustomTitle(dialogTitle("Regione · ${tvSectionTitle(sectionKey)}"))
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
                        .setCustomTitle(dialogTitle("TV · ${region.name}"))
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
        val regionChannelIds = allChannels.mapTo(linkedSetOf(), StreamCenterIptv.Channel::id)
        lateinit var selectAllButton: TextView

        fun updateCounter() {
            val inRegion = selected.count { it.startsWith("${region.key}:") }
            counter.text = when {
                visible.isEmpty() -> "Nessun canale trovato · $inRegion selezionati"
                selected.isEmpty() -> "${visible.size} canali disponibili"
                else -> "${visible.size} canali · $inRegion selezionati qui · ${selected.size} in totale"
            }
            val allRegionSelected = regionChannelIds.isNotEmpty() && regionChannelIds.all(selected::contains)
            selectAllButton.text = if (allRegionSelected) {
                "Rimuovi tutti i canali della regione"
            } else {
                "Aggiungi tutti i ${regionChannelIds.size} canali"
            }
        }

        fun syncVisibleChoices() {
            listView.clearChoices()
            visible.forEachIndexed { index, channel ->
                listView.setItemChecked(index, channel.id in selected)
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
            syncVisibleChoices()
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
        selectAllButton = actionButton(
            "Aggiungi tutti i ${regionChannelIds.size} canali",
            categoryAccent("live"),
        ) {
            val allRegionSelected = regionChannelIds.isNotEmpty() && regionChannelIds.all(selected::contains)
            if (allRegionSelected) {
                selected.removeAll(regionChannelIds)
            } else {
                selected.addAll(regionChannelIds)
            }
            syncVisibleChoices()
            updateCounter()
        }.apply {
            layoutParams = verticalParams(top = 8)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(searchInput)
            addView(counter)
            addView(selectAllButton)
            addView(listView)
        }
        renderList("")

        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("${tvSectionTitle(sectionKey)} · ${region.name}"))
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
                    addView(reorderIconButton(
                        "↑",
                        "Sposta ${entry.name} in alto",
                        accent,
                        enabled = index > 0,
                        size = 30,
                    ) {
                        if (index > 0) {
                            entries[index] = entries[index - 1].also { entries[index - 1] = entries[index] }
                            renderEntries()
                        }
                    })
                    addView(reorderIconButton(
                        "↓",
                        "Sposta ${entry.name} in basso",
                        accent,
                        enabled = index < entries.lastIndex,
                        size = 30,
                    ) {
                        if (index < entries.lastIndex) {
                            entries[index] = entries[index + 1].also { entries[index + 1] = entries[index] }
                            renderEntries()
                        }
                    })
                    val entryActionButton = if (entry.removed) {
                        iconButton("↩", "Ripristina ${entry.name}", COLOR_SUCCESS, size = 30) {
                            entry.removed = false
                            renderEntries()
                        }
                    } else {
                        deleteIconButton("Rimuovi ${entry.name}", size = 30) {
                            entry.removed = true
                            renderEntries()
                        }
                    }
                    addView(entryActionButton)
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
            .setCustomTitle(dialogTitle("Selezionati · ${tvSectionTitle(sectionKey)}"))
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
            .setCustomTitle(dialogTitle("Selezione salvata"))
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
        applyDialogBackdrop(
            alertDialog = dialog,
            onShow = {
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
            },
            onDismiss = onDismiss,
        )
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
        super.onDestroyView()
    }

}
