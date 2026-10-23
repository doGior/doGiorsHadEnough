package it.dogior.hadEnough.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.utils.DataStoreHelper
import it.dogior.hadEnough.StreamCenter
import it.dogior.hadEnough.anime.metadata.AnimeSeasonRoutes
import it.dogior.hadEnough.tracking.ManualTrackingService
import it.dogior.hadEnough.tracking.StreamCenterTrackingOverrides
import it.dogior.hadEnough.tracking.TrackingIdOverride
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class TrackingChipFlowLayout(context: Context, private val spacing: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: Int.MAX_VALUE
        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = 0
        var widest = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            measureChild(child, MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), heightMeasureSpec)
            val childWidth = child.measuredWidth
            if (lineWidth > 0 && lineWidth + spacing + childWidth > maxWidth) {
                totalHeight += lineHeight + spacing
                widest = maxOf(widest, lineWidth)
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += if (lineWidth == 0) childWidth else spacing + childWidth
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        totalHeight += lineHeight
        widest = maxOf(widest, lineWidth)
        setMeasuredDimension(
            resolveSize(widest, widthMeasureSpec),
            resolveSize(totalHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l
        var x = 0
        var y = 0
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + spacing
                lineHeight = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + spacing
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }
}

class StreamCenterTrackingIdsSettingsFragment : StreamCenterBaseSettingsFragment() {
    override val screenTitle: String = "ID di tracciamento"

    override val screenIcon: String = "🆔"


    override fun screenAction(): SettingsScreenAction = SettingsScreenAction(
        label = "Svuota",
        description = "Svuota l'elenco delle schede aperte di recente, mantenendo gli ID manuali",
        onInvoke = { confirmClearHistory() },
    )

    private data class TrackingItem(
        val key: String,
        val apiName: String,
        val title: String,
        val posterUrl: String?,
        val type: TvType?,
        val year: Int?,
        val detected: Map<ManualTrackingService, String>,
        val override: TrackingIdOverride?,
        val groupedSeasons: Boolean,
        val seenAt: Long?,
        val listStatus: WatchType? = null,
        val listUpdatedAt: Long? = null,
    ) {
        val manual: Map<ManualTrackingService, String> get() = override?.values.orEmpty()
        val isAnime: Boolean
            get() = type == TvType.Anime || type == TvType.AnimeMovie || type == TvType.OVA ||
                (type == null && detected.keys.any { it.anime })
    }

    private data class LibraryEntry(
        val key: String,
        val apiName: String,
        val title: String,
        val posterUrl: String?,
        val type: TvType?,
        val year: Int?,
        val detected: Map<ManualTrackingService, String>,
        val groupedSeasons: Boolean,
        val status: WatchType,
        val updatedAt: Long,
    )

    private class ListStatus(val type: WatchType, val label: String, val badge: String, val glyph: String)

    private val listStatuses = listOf(
        ListStatus(WatchType.WATCHING, "Guardando", "Guardando", "▶"),
        ListStatus(WatchType.ONHOLD, "In pausa", "In pausa", "‖"),
        ListStatus(WatchType.PLANTOWATCH, "Da guardare", "Da guardare", "+"),
        ListStatus(WatchType.COMPLETED, "Completati", "Completato", "✓"),
        ListStatus(WatchType.DROPPED, "Interrotti", "Interrotto", "✕"),
    )

    private var listContainer: LinearLayout? = null
    private var summaryChips: TrackingChipFlowLayout? = null
    private var searchQuery: String = ""
    private var listFilter: WatchType? = null
    private var restoreFilterFocus = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer().apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
        }
        content.minimumHeight = standardSubmenuMinimumHeight()

        content.addView(introCard())
        content.addView(
            input(searchQuery).apply {
                hint = "Cerca una scheda"
                layoutParams = verticalParams(top = 12)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        searchQuery = s?.toString().orEmpty()
                        renderList()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            },
        )
        listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }.also(content::addView)
        renderList()
        return scroll(content, fixedSubmenuHeight = true)
    }

    override fun onDestroyView() {
        listContainer = null
        summaryChips = null
        super.onDestroyView()
    }

    private fun introCard(): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = cardBackground(COLOR_CARD, COLOR_CARD, 14)
            layoutParams = verticalParams(top = 6)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(iconBadge("🆔", COLOR_ACCENT, size = 36, marginEnd = 12))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(titleText("ID usati da CloudStream", 16, true, display = true))
                    addView(bodyText(
                        "Modifica gli ID dei titoli nella tua lista e delle schede aperte di recente",
                        12,
                    ).apply { setPadding(0, dp(4), 0, 0) })
                })
            })
            summaryChips = TrackingChipFlowLayout(ctx, dp(6)).apply {
                layoutParams = verticalParams(top = 12)
            }.also(::addView)
        }
    }

    private fun refreshSummary(items: List<TrackingItem>) {
        val chips = summaryChips ?: return
        chips.removeAllViews()
        val manualCount = items.count { it.manual.isNotEmpty() }
        val listCount = items.count { it.listStatus != null }
        chips.addView(idChip("${items.size} ${if (items.size == 1) "scheda" else "schede"}", COLOR_MUTED))
        if (listCount > 0) chips.addView(idChip("$listCount nella tua lista", COLOR_MUTED))
        chips.addView(idChip(
            "$manualCount con ID manuali",
            if (manualCount > 0) COLOR_ACCENT else COLOR_MUTED,
            filled = manualCount > 0,
        ))
    }

    private fun loadItems(): List<TrackingItem> {
        val seen = StreamCenterTrackingOverrides.seen().associateBy { it.key }
        val overrides = StreamCenterTrackingOverrides.overrides(sharedPref).associateBy { it.key }
        val library = libraryEntries().associateBy { it.key }
        return (overrides.keys + library.keys + seen.keys).distinct().mapNotNull { key ->
            val entry = seen[key]
            val override = overrides[key]
            val saved = library[key]
            when {
                entry != null -> TrackingItem(
                    key = key,
                    apiName = entry.apiName,
                    title = entry.title,
                    posterUrl = entry.posterUrl ?: override?.posterUrl,
                    type = entry.type ?: override?.type,
                    year = entry.year ?: override?.year,
                    detected = entry.detected,
                    override = override,
                    groupedSeasons = entry.groupedSeasons,
                    seenAt = entry.seenAt,
                )
                override != null -> TrackingItem(
                    key = key,
                    apiName = override.apiName,
                    title = override.title,
                    posterUrl = override.posterUrl,
                    type = override.type,
                    year = override.year,
                    detected = emptyMap(),
                    override = override,
                    groupedSeasons = saved?.groupedSeasons ?: false,
                    seenAt = null,
                )
                saved != null -> TrackingItem(
                    key = key,
                    apiName = saved.apiName,
                    title = saved.title,
                    posterUrl = saved.posterUrl,
                    type = saved.type,
                    year = saved.year,
                    detected = saved.detected,
                    override = null,
                    groupedSeasons = saved.groupedSeasons,
                    seenAt = null,
                )
                else -> null
            }?.copy(listStatus = saved?.status, listUpdatedAt = saved?.updatedAt)
        }
    }

    private fun libraryEntries(): List<LibraryEntry> = runCatching {
        DataStoreHelper.getAllBookmarkedData().mapNotNull { data ->
            val id = data.id ?: return@mapNotNull null
            val status = DataStoreHelper.getResultWatchState(id)
            if (listStatuses.none { it.type == status }) return@mapNotNull null
            if (getApiFromNameNull(data.apiName) !is StreamCenter) return@mapNotNull null
            if (data.type == TvType.Live) return@mapNotNull null
            LibraryEntry(
                key = AnimeSeasonRoutes.unwrap(data.url) ?: data.url,
                apiName = data.apiName,
                title = data.name,
                posterUrl = data.posterUrl,
                type = data.type,
                year = data.year,
                detected = StreamCenterTrackingOverrides.detectedIds(data.syncData),
                groupedSeasons = AnimeSeasonRoutes.isGrouped(data.url),
                status = status,
                updatedAt = maxOf(data.latestUpdatedTime, data.bookmarkedTime),
            )
        }.sortedByDescending { it.updatedAt }.distinctBy { it.key }
    }.getOrDefault(emptyList())

    private fun renderList() {
        val container = listContainer ?: return
        container.removeAllViews()
        val items = loadItems()
        refreshSummary(items)
        val query = normalize(searchQuery.trim())
        val filtered = if (query.isBlank()) items else items.filter { normalize(it.title).contains(query) }

        if (items.isEmpty()) {
            container.addView(emptyStateCard(
                "Aggiungi un titolo alla tua lista o apri una scheda: qui vedrai gli ID trovati da StreamCenter",
                COLOR_ACCENT,
                icon = "🔎",
            ).apply { (getChildAt(childCount - 1) as? TextView)?.applyWrap() })
            return
        }
        if (filtered.isEmpty()) {
            container.addView(emptyStateCard("Nessuna scheda trovata per \"$searchQuery\"", COLOR_ACCENT, icon = "🔎"))
            return
        }

        val inList = filtered.filter { it.listStatus != null }
        val others = filtered.filter { it.listStatus == null }
        val manual = others.filter { it.manual.isNotEmpty() }
            .sortedByDescending { it.override?.updatedAt ?: 0L }
        val automatic = others.filter { it.manual.isEmpty() }
            .sortedByDescending { it.seenAt ?: 0L }
        if (inList.isNotEmpty()) addLibrarySection(container, inList)
        if (manual.isNotEmpty()) {
            container.addView(sectionHeading("Con ID manuali"))
            addAdaptiveCardGrid(container, manual.map(::itemCard))
        }
        if (automatic.isNotEmpty()) {
            container.addView(sectionHeading("Aperte di recente"))
            addAdaptiveCardGrid(container, automatic.map(::itemCard))
        }
    }

    private fun addLibrarySection(container: LinearLayout, items: List<TrackingItem>) {
        container.addView(sectionHeading("Nella tua lista"))
        val counts = items.groupingBy { it.listStatus }.eachCount()
        container.addView(TrackingChipFlowLayout(requireContext(), dp(8)).apply {
            layoutParams = verticalParams(top = 0)
            addView(statusTile(null, "Tutti", items.size))
            listStatuses
                .filter { (counts[it.type] ?: 0) > 0 || it.type == listFilter }
                .forEach { addView(statusTile(it, it.label, counts[it.type] ?: 0)) }
        })
        val order = listStatuses.map { it.type }
        val shown = items.filter { listFilter == null || it.listStatus == listFilter }
            .sortedWith(
                compareBy<TrackingItem> { order.indexOf(it.listStatus) }
                    .thenByDescending { it.listUpdatedAt ?: 0L },
            )
        if (shown.isEmpty()) {
            val label = listStatuses.firstOrNull { it.type == listFilter }?.label.orEmpty()
            container.addView(counterText("Nessun titolo in «$label» per questa ricerca", 12).apply {
                setPadding(dp(6), dp(14), dp(6), dp(4))
            })
            return
        }
        addAdaptiveCardGrid(container, shown.map(::itemCard))
    }

    private fun statusTile(status: ListStatus?, label: String, count: Int): View {
        val ctx = requireContext()
        val type = status?.type
        val selected = listFilter == type
        val color = statusColor(type)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(78)
            setPadding(dp(12), dp(9), dp(14), dp(10))
            background = interactiveBackground(
                if (selected) tint(color, "2E") else COLOR_CARD_ALT,
                color,
                14,
                if (selected) tint(color, "B0") else COLOR_STROKE,
            )
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                status?.let {
                    addView(TextView(ctx).apply {
                        text = it.glyph
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        includeFontPadding = false
                        setTextColor(Color.parseColor(color))
                        setPadding(0, 0, dp(6), 0)
                    })
                }
                addView(titleText(count.toString(), 18, true, display = true).apply {
                    includeFontPadding = false
                    setTextColor(Color.parseColor(if (selected) COLOR_TEXT else color))
                })
            })
            addView(counterText(label, 11).apply {
                setPadding(0, dp(4), 0, 0)
                if (selected) {
                    alpha = 1f
                    setTextColor(Color.parseColor(COLOR_TEXT))
                }
            })
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = "$label, $count ${if (count == 1) "titolo" else "titoli"}" +
                if (selected) ", filtro attivo" else ""
            setOnClickListener {
                if (listFilter == type) return@setOnClickListener
                listFilter = type
                restoreFilterFocus = isTvLikeDevice()
                renderList()
            }
            if (selected && restoreFilterFocus) {
                restoreFilterFocus = false
                post { requestFocus() }
            }
            addCardTouchFeedback(this, color)
        }
    }

    private fun statusBadge(type: WatchType): TextView? {
        val status = listStatuses.firstOrNull { it.type == type } ?: return null
        return chip("${status.glyph}  ${status.badge}", statusColor(type)).apply {
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }
    }

    private fun statusColor(type: WatchType?): String = when (type) {
        WatchType.COMPLETED -> COLOR_SUCCESS
        WatchType.DROPPED -> COLOR_DANGER
        WatchType.ONHOLD, WatchType.PLANTOWATCH -> COLOR_MUTED
        else -> COLOR_ACCENT
    }

    private fun itemCard(item: TrackingItem): View {
        val ctx = requireContext()
        val accent = COLOR_ACCENT
        val hasManual = item.manual.isNotEmpty()
        val arrow = chevron(accent)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(8), dp(12))
            background = interactiveBackground(
                COLOR_CARD_ALT,
                accent,
                16,
                if (hasManual) tint(accent, "88") else COLOR_STROKE,
            )
            layoutParams = verticalParams(top = 8)
            isClickable = true
            isFocusable = true
            contentDescription = listOfNotNull(
                item.title,
                listStatuses.firstOrNull { it.type == item.listStatus }?.badge,
                "Modifica ID di tracciamento",
            ).joinToString(". ")
            setOnClickListener { showEditor(item) }
        }
        val poster = posterThumb(item.posterUrl, typeIcon(item.type), item.title, widthDp = 48, heightDp = 70)
        card.addView(poster)
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
                marginEnd = dp(4)
            }
            item.listStatus?.let(::statusBadge)?.let(::addView)
            addView(titleText(item.title, 15, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(counterText(metaLine(item), 11).apply { setPadding(0, dp(2), 0, 0) })
            addView(TrackingChipFlowLayout(ctx, dp(5)).apply {
                layoutParams = verticalParams(top = 8)
                val services = orderedServices(item)
                var shown = 0
                services.forEach { service ->
                    val manual = item.manual[service]
                    val detected = item.detected[service]
                    when {
                        manual == StreamCenterTrackingOverrides.DISABLED -> addView(
                            idChip("⊘ ${service.shortLabel}", COLOR_DANGER),
                        )
                        manual != null -> addView(idChip("✎ ${service.shortLabel} $manual", accent, filled = true))
                        detected != null -> addView(idChip("${service.shortLabel} $detected", COLOR_MUTED))
                        else -> return@forEach
                    }
                    shown++
                }
                if (shown == 0) addView(idChip("Nessun ID trovato", COLOR_DANGER))
            })
            if (item.groupedSeasons) {
                addView(counterText("Stagioni unificate: CloudStream non traccia questa scheda", 11).apply {
                    setTextColor(Color.parseColor(tint(COLOR_DANGER, "D0")))
                    setPadding(0, dp(6), 0, 0)
                })
            }
        })
        card.addView(arrow)
        addCardTouchFeedback(card, accent, poster, arrow)
        return card
    }

    private fun metaLine(item: TrackingItem): String = listOfNotNull(
        typeLabel(item.type),
        item.year?.toString(),
        item.seenAt?.let { "aperta ${relativeTime(it)}" }
            ?: "non aperta di recente".takeIf { item.listStatus == null },
    ).joinToString(", ")

    private fun showEditor(item: TrackingItem) {
        val ctx = context ?: return
        val accent = COLOR_ACCENT
        val inputs = linkedMapOf<ManualTrackingService, android.widget.EditText>()
        val refreshers = mutableListOf<() -> Unit>()

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(6))
        }

        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBackground(COLOR_CARD, tint(accent, "55"), 16)
            addView(posterThumb(item.posterUrl, typeIcon(item.type), item.title, widthDp = 54, heightDp = 78))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                }
                addView(titleText(item.title, 16, true).apply {
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(counterText(metaLine(item), 11).apply { setPadding(0, dp(3), 0, 0) })
                addView(bodyText(
                    "Lascia vuoto per usare l'ID trovato da StreamCenter. Puoi incollare anche il link " +
                        "della pagina. Usa ⊘ per non tracciare la scheda su quel servizio",
                    11,
                ).apply { setPadding(0, dp(6), 0, 0) })
            })
        })
        if (item.groupedSeasons) {
            content.addView(bodyText(
                "📚 «Unifica stagioni Anime» è attivo: gli ID manuali servono a trovare le stagioni " +
                    "giuste, ma CloudStream non traccia le schede unificate",
                11,
            ).apply {
                setTextColor(Color.parseColor(tint(COLOR_DANGER, "E0")))
                setPadding(dp(4), dp(10), dp(4), 0)
            })
        }

        val animeServices = ManualTrackingService.entries.filter { it.anime }
        val generalServices = ManualTrackingService.entries.filterNot { it.anime }
        val groups = listOf("Anime" to animeServices, "Serie TV / Film" to generalServices)
            .let { if (item.isAnime) it else it.reversed() }
        groups.forEach { (label, services) ->
            content.addView(sectionHeading(label, accent, topMargin = 14))
            val fields = services.map { service ->
                serviceField(item, service, inputs, refreshers)
            }
            addAdaptiveCardGrid(content, fields)
        }

        val builder = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("ID di tracciamento"))
            .setView(scrollableDialogView(content))
            .setPositiveButton("Salva", null)
            .setNegativeButton("Chiudi", null)
        if (item.override != null) builder.setNeutralButton("Automatici", null)
        val dialog = builder.create()
        applyDialogBackdrop(dialog)
        dialog.show()
        refreshers.forEach { it() }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val values = linkedMapOf<ManualTrackingService, String>()
            var firstInvalid: android.widget.EditText? = null
            inputs.forEach { (service, input) ->
                val raw = input.text?.toString().orEmpty().trim()
                if (raw.isEmpty()) return@forEach
                val normalized = service.normalize(raw)
                if (normalized == null) {
                    input.error = "ID ${service.label} non valido"
                    if (firstInvalid == null) firstInvalid = input
                } else {
                    values[service] = normalized
                }
            }
            firstInvalid?.let {
                it.requestFocus()
                saveToast("Controlla gli ID evidenziati")
                return@setOnClickListener
            }
            if (values.isEmpty()) {
                StreamCenterTrackingOverrides.removeOverride(sharedPref, item.key)
                saveToast("Verranno usati gli ID trovati da StreamCenter")
            } else {
                StreamCenterTrackingOverrides.saveOverride(
                    sharedPref,
                    TrackingIdOverride(
                        key = item.key,
                        apiName = item.apiName,
                        title = item.title,
                        posterUrl = item.posterUrl,
                        type = item.type,
                        year = item.year,
                        values = values,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                saveToast("ID salvati: riapri la scheda per applicarli")
            }
            dialog.dismiss()
            renderList()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            StreamCenterTrackingOverrides.removeOverride(sharedPref, item.key)
            saveToast("Ripristinati gli ID trovati da StreamCenter")
            dialog.dismiss()
            renderList()
        }
    }

    private fun serviceField(
        item: TrackingItem,
        service: ManualTrackingService,
        inputs: MutableMap<ManualTrackingService, android.widget.EditText>,
        refreshers: MutableList<() -> Unit>,
    ): View {
        val ctx = requireContext()
        val accent = COLOR_ACCENT
        val detected = item.detected[service]
        val status = counterText("", 11)
        lateinit var openButton: TextView
        lateinit var disableButton: TextView

        val input = input(item.manual[service].orEmpty()).apply {
            hint = detected?.let { "Automatico, $it" } ?: service.placeholder
            inputType = if (service == ManualTrackingService.IMDB) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            filters = arrayOf(InputFilter.LengthFilter(300))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputs[service] = input

        fun effectiveId(): String? {
            val raw = input.text?.toString().orEmpty().trim()
            if (raw.isEmpty()) return detected
            return service.normalize(raw)
        }

        fun refresh() {
            val raw = input.text?.toString().orEmpty().trim()
            val normalized = raw.takeIf(String::isNotEmpty)?.let(service::normalize)
            val (text, color) = when {
                raw.isEmpty() && detected != null -> "Trovato da StreamCenter: $detected" to COLOR_MUTED
                raw.isEmpty() -> "Nessun ID trovato da StreamCenter" to COLOR_MUTED
                normalized == null -> "ID non valido" to COLOR_DANGER
                normalized == StreamCenterTrackingOverrides.DISABLED ->
                    "⊘ Disattivato: CloudStream non userà ${service.label}" to COLOR_DANGER
                normalized == detected -> "✓ Uguale a quello trovato" to COLOR_SUCCESS
                else -> "✎ Manuale: $normalized" +
                    (detected?.let { " (trovato: $it)" } ?: "") to COLOR_ACCENT
            }
            status.text = text
            status.setTextColor(Color.parseColor(color))
            val pageAvailable = effectiveId()?.let { service.pageUrl(it, item.type) } != null
            openButton.isEnabled = pageAvailable
            openButton.alpha = if (pageAvailable) 1f else 0.35f
            val disabled = normalized == StreamCenterTrackingOverrides.DISABLED
            disableButton.text = if (disabled) "↺" else "⊘"
            disableButton.contentDescription = if (disabled) {
                "Riattiva ${service.label}"
            } else {
                "Non tracciare su ${service.label}"
            }
        }

        openButton = iconButton("↗", "Apri la pagina su ${service.label}", accent) {
            val url = effectiveId()?.let { service.pageUrl(it, item.type) }
            if (url == null) saveToast("Inserisci un ID valido per aprire ${service.label}") else openUrl(url)
        }
        disableButton = iconButton("⊘", "Non tracciare su ${service.label}", COLOR_DANGER) {
            val current = input.text?.toString().orEmpty().trim()
            input.setText(if (current == StreamCenterTrackingOverrides.DISABLED) "" else StreamCenterTrackingOverrides.DISABLED)
            input.setSelection(input.text?.length ?: 0)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                input.error = null
                refresh()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshers += ::refresh

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            background = cardBackground(COLOR_CARD_ALT, tint(accent, "40"), 14)
            layoutParams = verticalParams(top = 8)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(siteIconBadge(
                    fallback = service.fallbackIcon,
                    accent = accent,
                    contentDescription = service.label,
                    websiteUrl = service.websiteUrl,
                    size = 34,
                    marginEnd = 10,
                ))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(titleText(service.label, 14, true))
                    addView(status.apply {
                        setPadding(0, dp(2), 0, 0)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                })
                addView(disableButton)
                addView(openButton)
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = verticalParams(top = 8)
                addView(input)
            })
        }
    }

    private fun confirmClearHistory() {
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Svuota elenco"))
            .setMessage(
                "Vuoi rimuovere le schede aperte di recente? Gli ID inseriti a mano non verranno toccati.",
            )
            .setPositiveButton("Rimuovi") { _, _ ->
                StreamCenterTrackingOverrides.clearSeen()
                renderList()
                saveToast("Elenco svuotato")
            }
            .setNegativeButton("Chiudi", null)
            .create()
        showCompactActionDialog(dialog)
    }

    private fun idChip(value: String, color: String, filled: Boolean = false): TextView {
        return TextView(requireContext()).apply {
            text = value
            textSize = 10.5f
            typeface = if (filled) Typeface.DEFAULT_BOLD else Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor(if (filled) COLOR_TEXT else tint(color, "E0")))
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = outlined(
                tint(color, if (filled) "AA" else "4D"),
                tint(color, if (filled) "38" else "12"),
                999,
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun orderedServices(item: TrackingItem): List<ManualTrackingService> {
        val anime = ManualTrackingService.entries.filter { it.anime }
        val general = ManualTrackingService.entries.filterNot { it.anime }
        return if (item.isAnime) anime + general else general + anime
    }

    private fun typeLabel(type: TvType?): String? = when (type) {
        TvType.Anime, TvType.OVA -> "Anime"
        TvType.AnimeMovie -> "Film Anime"
        TvType.TvSeries, TvType.AsianDrama, TvType.Cartoon, TvType.Documentary -> "Serie TV"
        TvType.Movie -> "Film"
        null -> null
        else -> type.name
    }

    private fun typeIcon(type: TvType?): String = when (type) {
        TvType.Anime, TvType.OVA, TvType.AnimeMovie -> "🌸"
        TvType.Movie -> "🎬"
        else -> "📺"
    }

    private fun relativeTime(timestamp: Long): String {
        val elapsed = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
        val minutes = elapsed / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            minutes < 1L -> "adesso"
            minutes < 60L -> "$minutes min fa"
            hours < 24L -> if (hours == 1L) "1 ora fa" else "$hours ore fa"
            days == 1L -> "ieri"
            days < 7L -> "$days giorni fa"
            else -> "il " + SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(Date(timestamp))
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ITALY), Normalizer.Form.NFD).replace("\\p{Mn}+".toRegex(), "")

    private fun TextView.applyWrap() {
        maxLines = 4
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
}
