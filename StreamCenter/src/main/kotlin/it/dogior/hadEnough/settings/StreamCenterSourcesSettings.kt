package it.dogior.hadEnough.settings

import it.dogior.hadEnough.*
import it.dogior.hadEnough.catalog.StreamCenterCatalogDefinition
import it.dogior.hadEnough.catalog.StreamCenterCatalogSection
import it.dogior.hadEnough.catalog.StreamCenterCatalogs
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.StreamCenterExtAvailability
import it.dogior.hadEnough.torrent.StreamCenterExtCloudflareSession
import it.dogior.hadEnough.torrent.StreamCenterExtContainLocation
import it.dogior.hadEnough.torrent.StreamCenterExtDomain
import it.dogior.hadEnough.torrent.StreamCenterExtDomainStatus
import it.dogior.hadEnough.torrent.StreamCenterExtTorrentClient
import it.dogior.hadEnough.torrent.StreamCenterTorrentKeywordGroup
import it.dogior.hadEnough.torrent.StreamCenterTorrentLanguageFilter
import it.dogior.hadEnough.torrent.StreamCenterTorrentMetadata
import it.dogior.hadEnough.torrent.StreamCenterTorrentPreferences
import it.dogior.hadEnough.torrent.StreamCenterTorrentVideoCodec
import it.dogior.hadEnough.util.StreamCenterVpnGuard

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.utils.ImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.Locale

private const val STREMIO_CATEGORY_TITLE = "Add-on Stremio"
private const val STREMIO_MANIFEST_REFRESH_NOTICE_MS = 3_000L
private const val STREMIO_MANIFEST_REFRESH_FADE_MS = 450L
private const val STREMIO_MANIFEST_REFRESH_NOTICE_ALPHA = 0.72f
private const val CLOUDFLARE_CLEARANCE_POLL_INTERVAL_MS = 700L
private const val CLOUDFLARE_CLEARANCE_CLOSE_DELAY_MS = 900L

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

private data class TorrentChoice<T>(
    val title: String,
    val description: String,
    val value: T,
)

private data class TorrentToggleOption(
    val title: String,
    val enabled: Boolean,
    val onChanged: (Boolean) -> Unit,
)

private enum class TorrentSizeUnit(
    val label: String,
    val bytes: Long,
) {
    MB("MB", 1_048_576L),
    GB("GB", 1_073_741_824L),
}

class StreamCenterSourcesSettingsFragment : StreamCenterSupportSettingsFragment() {
    private val rows = mutableListOf<SourceRowState>()
    private val preloadedSourceIconUrls = mutableSetOf<String>()
    private val sourceCategories = listOf(
        SourceCategory("anime", "Anime", "🎌", COLOR_SOURCE_ANIME),
        SourceCategory("tv", "Serie TV / Film", "📺", COLOR_SOURCE_TV),
    )
    private val categoryStatusViews = mutableMapOf<String, TextView>()
    private var expandedCategoryKey: String? = null
    private var pendingCategoryExpansionKey: String? = null
    private var pendingCategoryFocusKey: String? = null
    private var categoryTransitionRunning = false
    private var rowsContainer: LinearLayout? = null
    private var sourceIconPreloadContainer: FrameLayout? = null
    private var sourceIconPreloadGeneration = 0
    private var stremioManifestRefreshNoticeView: TextView? = null
    private var stremioCategoryStatusView: TextView? = null
    private var torrentCategoryStatusView: TextView? = null
    private var isStreamingCommunityLinkCheckRunning = false
    private var isStremioAddonInstallRunning = false
    private var isExtAvailabilityCheckRunning = false
    private val extDomainStatuses = mutableMapOf<StreamCenterExtDomain, StreamCenterExtDomainStatus>()
    private var extAvailabilityStatusViews: Map<StreamCenterExtDomain, TextView> = emptyMap()
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
                title = "Fonti",
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

        addAdaptiveCardGrid(content, listOf(sourceDomainUpdateCard(), apiCheckCard()))

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
            title = "Verifica API e Fonti",
            summary = "",
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
        return categoryContainer(COLOR_STREMIO).apply {
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
            val headerRow = categoryHeaderRow(
                title = STREMIO_CATEGORY_TITLE,
                summaryView = status,
                icon = "\uD83D\uDD0C",
                accent = COLOR_STREMIO,
                trailingViews = listOf(expandButton),
                titleCompanion = notice,
                leadingBadge = siteIconBadge(
                    fallback = "\uD83D\uDD0C",
                    accent = COLOR_STREMIO,
                    contentDescription = "Icona di Stremio",
                    websiteUrl = STREMIO_WEBSITE_URL,
                    size = 40,
                    marginEnd = 10,
                ),
            ) { expandButton.callOnClick() }
            headerRow.view.tag = "source-category-header:$categoryKey"
            addView(headerRow.view)

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
            setOnLongClickListener {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("Manifest Stremio", addon.manifestUrl))
                saveToast("Manifest copiato")
                true
            }
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
                input.error = "Inserisci manifest"
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
                    if (result.isFailure) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        input.error = result.exceptionOrNull()?.message ?: "Impossibile leggere il manifest"
                        return@withContext
                    }
                    val addon = result.getOrThrow()
                    val catalog = StreamCenterCatalogs.stremioCatalogDefinition(addon)
                    when {
                        addon.hasStreamingResources && catalog != null -> {
                            dialog.dismiss()
                            promptStremioSourceAndCatalog(addon, catalog)
                        }
                        addon.hasStreamingResources -> {
                            StreamCenterPlugin.saveStremioAddon(sharedPref, addon)
                            renderRows()
                            dialog.dismiss()
                            saveToast("Add-on aggiunto")
                        }
                        catalog != null -> {
                            dialog.dismiss()
                            promptStremioCatalogInstead(addon, catalog)
                        }
                        else -> {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            input.error = "L'add-on non offre Cataloghi o Fonti Streaming"
                        }
                    }
                }
            }
        }
    }

    private fun promptStremioCatalogInstead(
        addon: StreamCenterStremioAddon,
        catalog: StreamCenterCatalogDefinition,
    ) {
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Non è una Fonte Stremio"))
            .setMessage(
                "${addon.name} non contiene Fonti Streaming, ma offre Cataloghi. Vuoi aggiungerlo come Catalogo?",
            )
            .setPositiveButton("Aggiungi Catalogo") { _, _ ->
                if (addStremioCatalogToHome(catalog)) {
                    saveToast("Catalogo aggiunto in Home")
                } else {
                    saveToast("Impossibile aggiungere il Catalogo")
                }
            }
            .setNegativeButton("Annulla", null)
            .create()
        showCompactActionDialog(dialog)
    }

    private fun promptStremioSourceAndCatalog(
        addon: StreamCenterStremioAddon,
        catalog: StreamCenterCatalogDefinition,
    ) {
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Add-on Stremio completo"))
            .setMessage(
                "${addon.name} offre sia Fonti Streaming sia Cataloghi. Puoi aggiungerlo sia come Fonte sia come Catalogo.",
            )
            .setPositiveButton("Fonte e Catalogo") { _, _ ->
                StreamCenterPlugin.saveStremioAddon(sharedPref, addon)
                renderRows()
                saveToast(
                    if (addStremioCatalogToHome(catalog)) {
                        "Add-on aggiunto in Fonti e Cataloghi"
                    } else {
                        "Add-on aggiunto in Fonti, ma non il Catalogo"
                    },
                )
            }
            .setNeutralButton("Solo Fonte") { _, _ ->
                StreamCenterPlugin.saveStremioAddon(sharedPref, addon)
                renderRows()
                saveToast("Add-on aggiunto in Fonti")
            }
            .setNegativeButton("Annulla", null)
            .create()
        showCompactActionDialog(dialog)
    }

    private fun addStremioCatalogToHome(catalog: StreamCenterCatalogDefinition): Boolean {
        if (StreamCenterCatalogs.isConfigured(sharedPref, catalog)) return true
        val selectedSections = catalog.sections
            .filter(StreamCenterCatalogSection::defaultEnabled)
            .map(StreamCenterCatalogSection::key)
        if (!StreamCenterCatalogs.saveCatalog(sharedPref, catalog, selectedSections)) return false
        sharedPref?.edit {
            putBoolean(StreamCenterPlugin.homeCategoryEnabledKey(StreamCenterCatalogs.CATEGORY_KEY), true)
        }
        StreamCenterPlugin.refreshCatalogs()
        return true
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
                input.error = "Inserisci manifest"
                return@setOnClickListener
            }
            isStremioAddonInstallRunning = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            CoroutineScope(Dispatchers.IO).launch {
                val result = runCatching { StreamCenterStremioAddonClient.readStreamingAddon(manifestUrl) }
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
            val result = runCatching { StreamCenterStremioAddonClient.readStreamingAddon(addon.manifestUrl) }
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
        stremioManifestRefreshNoticeView?.removeCallbacks(fadeStremioManifestRefreshNotice)
        stremioManifestRefreshNoticeView?.animate()?.cancel()
        container.removeAllViews()
        categoryStatusViews.clear()
        stremioManifestRefreshNoticeView = null
        stremioCategoryStatusView = null
        torrentCategoryStatusView = null
        container.addView(torrentCategoryCard())
        sourceCategories.forEach { category ->
            val categoryRows = rows.filter { it.source.category == category.key }
            if (categoryRows.isNotEmpty()) {
                container.addView(sourceCategoryCard(category, categoryRows))
            }
        }
        container.addView(stremioAddonsCategoryCard())
        restorePendingCategoryFocus(container)
    }

    private fun torrentCategoryCard(): LinearLayout {
        val categoryKey = "torrent"
        val enabled = StreamCenterPlugin.isTorrentEnabled(sharedPref)
        val expanded = enabled && expandedCategoryKey == categoryKey
        return categoryContainer(COLOR_TORRENT, topMargin = 0).apply {
            val status = counterText(torrentCategoryStatus()).apply {
                torrentCategoryStatusView = this
            }
            val expandButton = categoryExpandButton(
                expanded = expanded,
                description = if (expanded) "Chiudi Torrent" else "Apri Torrent",
                accent = COLOR_TORRENT,
                size = 34,
            ) {
                if (StreamCenterPlugin.isTorrentEnabled(sharedPref)) {
                    toggleCategory(categoryKey)
                }
            }.apply {
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.38f
            }
            val masterSwitch = styledSwitch(enabled, COLOR_TORRENT) { isEnabled ->
                StreamCenterPlugin.setTorrentEnabled(sharedPref, isEnabled)
                refreshTorrentCategoryStatus()
                expandButton.isEnabled = isEnabled
                expandButton.animate().cancel()
                if (reduceMotion) {
                    expandButton.alpha = if (isEnabled) 1f else 0.38f
                } else {
                    expandButton.animate()
                        .alpha(if (isEnabled) 1f else 0.38f)
                        .setDuration(190L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }

                val expandedContent = rowsContainer
                    ?.findViewWithTag<View>("source-category-content:$categoryKey")
                if (!isEnabled && expandedContent != null) {
                    rememberCategoryFocus(categoryKey)
                    categoryTransitionRunning = true
                    animateCategoryCollapse(expandedContent) {
                        expandedCategoryKey = null
                        pendingCategoryExpansionKey = null
                        renderRows()
                        categoryTransitionRunning = false
                    }
                }
                saveToast(if (isEnabled) "Torrent attivati" else "Torrent disattivati")
            }.apply {
                id = View.generateViewId()
                isFocusable = true
                contentDescription = "Attiva o disattiva tutte le fonti Torrent"
            }
            val headerRow = categoryHeaderRow(
                title = "Torrent",
                summaryView = status,
                icon = "🧲",
                accent = COLOR_TORRENT,
                trailingViews = listOf(masterSwitch, expandButton),
            ) {
                if (StreamCenterPlugin.isTorrentEnabled(sharedPref)) {
                    expandButton.callOnClick()
                }
            }
            headerRow.view.id = View.generateViewId()
            headerRow.view.tag = "source-category-header:$categoryKey"
            headerRow.view.nextFocusRightId = masterSwitch.id
            headerRow.view.nextFocusForwardId = masterSwitch.id
            masterSwitch.nextFocusLeftId = headerRow.view.id
            addView(headerRow.view)

            if (expanded) {
                val expandedContent = LinearLayout(requireContext()).apply {
                    tag = "source-category-content:$categoryKey"
                    orientation = LinearLayout.VERTICAL
                }
                expandedContent.addView(torrentSectionLabel("Collegamento", top = 10))
                expandedContent.addView(torrentSettingsRow(
                    title = "Endpoint EXT",
                ) { showExtDomainsDialog() })
                if (StreamCenterPlugin.isPerformanceModeEnabled(sharedPref)) {
                    expandedContent.addView(bodyText(
                        "Modalità Prestazioni attiva: usa un budget più breve e rispetta il numero di torrent scelto.",
                        12,
                    ).apply {
                        setPadding(dp(4), 0, dp(4), 0)
                        layoutParams = verticalParams(top = 9)
                    })
                }
                expandedContent.addView(torrentSectionLabel("Ricerca"))
                expandedContent.addView(torrentSettingsRow(
                    title = "Dove cercare",
                    summary = torrentSearchLocationSummary(),
                ) {
                    showTorrentChoiceDialog(
                        title = "Dove cercare",
                        options = StreamCenterExtContainLocation.entries.map { location ->
                            TorrentChoice(location.title, location.description, location)
                        },
                        selected = StreamCenterTorrentPreferences.read(sharedPref).containLocation,
                        dismissLabel = "Chiudi",
                    ) { selected ->
                        StreamCenterTorrentPreferences.setContainLocation(sharedPref, selected.value)
                        renderRows()
                    }
                })
                expandedContent.addView(torrentSettingsRow(
                    title = "Lingua",
                    summary = StreamCenterTorrentPreferences.read(sharedPref).language.title,
                ) { showTorrentLanguageDialog() })
                expandedContent.addView(torrentSectionLabel("Filtri dei risultati"))
                expandedContent.addView(torrentSettingsRow(
                    title = "Filtri e Risultati",
                    summary = torrentFiltersSummary(),
                ) { showTorrentFiltersDialog() })
                expandedContent.addView(torrentSettingsRow(
                    title = "Codec video",
                    summary = torrentVideoCodecsSummary(),
                ) { showTorrentVideoCodecsDialog() })
                expandedContent.addView(actionButton("Ripristina impostazioni Torrent", COLOR_TORRENT) {
                    confirmTorrentReset()
                }.apply {
                    layoutParams = verticalParams(top = 10)
                })
                addView(expandedContent)
                if (pendingCategoryExpansionKey == categoryKey) {
                    animateCategoryExpansion(expandedContent)
                }
            }
        }
    }

    private fun torrentCategoryStatus(): String {
        return if (StreamCenterPlugin.isTorrentEnabled(sharedPref)) "Attivo" else "Disattivato"
    }

    private fun refreshTorrentCategoryStatus() {
        torrentCategoryStatusView?.text = torrentCategoryStatus()
    }

    private fun torrentSettingsRow(
        title: String,
        summary: String? = null,
        onClick: () -> Unit,
    ): LinearLayout {
        val arrow = chevron(COLOR_TORRENT)
        return settingsRow(
            title = title,
            summary = summary,
            accent = COLOR_TORRENT,
            fillColor = COLOR_CARD,
            strokeColor = tint(COLOR_TORRENT, "55"),
            trailingViews = listOf(arrow),
            touchTarget = arrow,
            topMargin = 7,
            onClick = onClick,
        ).view
    }

    private fun compactTorrentSelector(
        label: String,
        contentDescription: String,
        onClick: () -> Unit,
    ): TextView = TextView(requireContext()).apply {
        text = "$label  ▾"
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor(COLOR_TEXT))
        this.contentDescription = contentDescription
        isClickable = true
        isFocusable = true
        minimumHeight = dp(44)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = interactiveBackground(
            fill = COLOR_CARD_ALT,
            accent = COLOR_TORRENT,
            radius = 10,
            strokeColor = COLOR_STROKE,
        )
        setOnClickListener { onClick() }
    }

    private fun torrentToggleGrid(options: List<TorrentToggleOption>): GridLayout {
        return GridLayout(requireContext()).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_MARGINS
            useDefaultMargins = false
            options.forEach { option ->
                var enabled = option.enabled
                val item = TextView(requireContext()).apply {
                    text = option.title
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 2
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    isClickable = true
                    isFocusable = true
                    minimumHeight = dp(48)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                }
                fun refreshAppearance() {
                    item.isSelected = enabled
                    item.alpha = if (enabled) 1f else 0.48f
                    item.setTextColor(Color.parseColor(if (enabled) COLOR_TEXT else COLOR_MUTED))
                    item.background = interactiveBackground(
                        fill = if (enabled) tint(COLOR_TORRENT, "24") else COLOR_CARD_ALT,
                        accent = COLOR_TORRENT,
                        radius = 12,
                        strokeColor = if (enabled) tint(COLOR_TORRENT, "99") else COLOR_STROKE,
                    )
                    item.contentDescription = "${option.title}, ${if (enabled) "attivo" else "disattivato"}"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        item.stateDescription = if (enabled) "Attivo" else "Disattivato"
                    }
                }
                item.setOnClickListener {
                    enabled = !enabled
                    refreshAppearance()
                    option.onChanged(enabled)
                }
                refreshAppearance()
                addView(item)
            }
        }
    }

    private fun torrentSectionLabel(value: String, top: Int = 16): TextView = sectionLabel(value).apply {
        setPadding(dp(4), 0, dp(4), 0)
        layoutParams = verticalParams(top = top)
    }

    private fun confirmTorrentReset() {
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Ripristina Torrent"))
            .setMessage(
                "Ripristina la configurazione dei torrent ai valori predefiniti.",
            )
            .setPositiveButton("Ripristina") { _, _ ->
                StreamCenterPlugin.resetTorrentConfiguration(sharedPref)
                extDomainStatuses.clear()
                renderRows()
                saveToast("Impostazioni Torrent ripristinate")
            }
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun torrentDomainShortLabel(domain: StreamCenterExtDomain): String {
        val role = when (domain) {
            StreamCenterExtDomain.PRIMARY -> "Principale"
            StreamCenterExtDomain.SECONDARY -> "Secondario"
            StreamCenterExtDomain.PROXY -> "Proxy"
        }
        val host = domain.baseUrl.substringAfter("://").removePrefix("www.").trimEnd('/')
        return "$role · $host"
    }

    private fun moveExtDomain(domain: StreamCenterExtDomain, direction: Int) {
        val order = StreamCenterTorrentPreferences.domainOrder(sharedPref).toMutableList()
        val index = order.indexOf(domain)
        val target = index + direction
        if (index < 0 || target !in order.indices) return
        order[index] = order[target].also { order[target] = order[index] }
        StreamCenterTorrentPreferences.setDomainOrder(sharedPref, order)
    }

    private fun torrentSearchLocationSummary(): String =
        StreamCenterTorrentPreferences.read(sharedPref).containLocation.title

    private fun torrentFiltersSummary(): String {
        val filters = StreamCenterTorrentPreferences.read(sharedPref)
        val seed = when {
            filters.minimumSeeders != null && filters.maximumSeeders != null ->
                "Seeds ${filters.minimumSeeders}–${filters.maximumSeeders}"
            filters.minimumSeeders != null -> "Seeds ≥ ${filters.minimumSeeders}"
            filters.maximumSeeders != null -> "Seeds ≤ ${filters.maximumSeeders}"
            else -> "Seeds qualsiasi"
        }
        val quality = filters.minimumResolution.takeIf { value -> value > 0 }
            ?.let { value -> "Qualità ≥ ${value}p" }
            ?: "Qualità qualsiasi"
        return listOf("${filters.resultLimit} risultati", seed, quality).joinToString(" · ")
    }

    private fun showTorrentFiltersDialog() {
        val ctx = context ?: return
        val filters = StreamCenterTorrentPreferences.read(sharedPref)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        var persistIfValid: () -> Unit = {}
        var sizeUnit = torrentSizeUnitFor(filters.minimumSizeBytes ?: filters.maximumSizeBytes)
        val minimumSizeInput = input(formatTorrentSizeInput(filters.minimumSizeBytes, sizeUnit), widthDp = 92).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            gravity = Gravity.CENTER
            hint = "Minima"
            contentDescription = "Dimensione minima del torrent, ${sizeUnit.label}"
        }
        val maximumSizeInput = input(formatTorrentSizeInput(filters.maximumSizeBytes, sizeUnit), widthDp = 92).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            gravity = Gravity.CENTER
            hint = "Massima"
            contentDescription = "Dimensione massima del torrent, ${sizeUnit.label}"
        }
        lateinit var sizeUnitButton: TextView
        sizeUnitButton = compactTorrentSelector(
            label = sizeUnit.label,
            contentDescription = "Unità di misura: ${sizeUnit.label}",
        ) {
            PopupMenu(ctx, sizeUnitButton).apply {
                TorrentSizeUnit.entries.forEachIndexed { index, unit ->
                    menu.add(0, index, index, unit.label).apply {
                        isCheckable = true
                        isChecked = unit == sizeUnit
                    }
                }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener { item ->
                    val unit = TorrentSizeUnit.entries.getOrNull(item.itemId)
                        ?: return@setOnMenuItemClickListener false
                    val previousUnit = sizeUnit
                    sizeUnit = unit
                    minimumSizeInput.setText(
                        convertTorrentSizeInput(minimumSizeInput.text?.toString(), previousUnit, sizeUnit),
                    )
                    maximumSizeInput.setText(
                        convertTorrentSizeInput(maximumSizeInput.text?.toString(), previousUnit, sizeUnit),
                    )
                    minimumSizeInput.contentDescription = "Dimensione minima del torrent, ${sizeUnit.label}"
                    maximumSizeInput.contentDescription = "Dimensione massima del torrent, ${sizeUnit.label}"
                    sizeUnitButton.text = "${sizeUnit.label}  ▾"
                    sizeUnitButton.contentDescription = "Unità di misura: ${sizeUnit.label}"
                    persistIfValid()
                    true
                }
                show()
            }
        }
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleText("Dimensione", 16, true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
            addView(sizeUnitButton.apply {
                layoutParams = LinearLayout.LayoutParams(dp(88), dp(44)).apply {
                    marginStart = dp(8)
                }
            })
        })
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = verticalParams(top = 8)
            addView(bodyText("Da", 12).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(minimumSizeInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(bodyText("a", 12).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(maximumSizeInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        content.addView(titleText("Seeders", 16, true).apply { layoutParams = verticalParams(top = 14) })
        val minimumSeedInput = input(filters.minimumSeeders?.toString().orEmpty(), widthDp = 92).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            hint = "Minimi"
            contentDescription = "Numero minimo di seeders"
        }
        val maximumSeedInput = input(filters.maximumSeeders?.toString().orEmpty(), widthDp = 92).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            hint = "Massimi"
            contentDescription = "Numero massimo di seeders"
        }
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = verticalParams(top = 8)
            addView(bodyText("Da", 12).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(minimumSeedInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(bodyText("a", 12).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(maximumSeedInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        var selectedMinimumResolution = filters.minimumResolution
        fun resolutionLabel(value: Int): String = value.takeIf { it > 0 }?.let { "$it p" } ?: "Qualsiasi"
        lateinit var resolutionButton: TextView
        resolutionButton = compactTorrentSelector(
            label = resolutionLabel(selectedMinimumResolution),
            contentDescription = "Risoluzione minima: ${resolutionLabel(selectedMinimumResolution)}",
        ) {
            val options = StreamCenterTorrentPreferences.minimumResolutionOptions
            PopupMenu(ctx, resolutionButton).apply {
                options.forEachIndexed { index, resolution ->
                    menu.add(
                        0,
                        index,
                        index,
                        resolution.takeIf { it > 0 }?.let { "$it p" } ?: "Qualsiasi",
                    ).apply {
                        isCheckable = true
                        isChecked = resolution == selectedMinimumResolution
                    }
                }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener { item ->
                    val resolution = options.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
                    selectedMinimumResolution = resolution
                    resolutionButton.text = "${resolutionLabel(resolution)}  ▾"
                    resolutionButton.contentDescription = "Risoluzione minima: ${resolutionLabel(resolution)}"
                    StreamCenterTorrentPreferences.setMinimumResolution(sharedPref, resolution)
                    true
                }
                show()
            }
        }
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = verticalParams(top = 16)
            addView(titleText("Risoluzione minima", 16, true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
            addView(resolutionButton.apply {
                layoutParams = LinearLayout.LayoutParams(dp(104), dp(44)).apply {
                    marginStart = dp(8)
                }
            })
        })
        val resultLimitInput = input(filters.resultLimit.toString(), widthDp = 64).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            hint = "Da 1 a 100"
            this.filters = arrayOf(InputFilter.LengthFilter(3))
            contentDescription = "Numero di torrent da trovare, da 1 a 100"
        }
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = verticalParams(top = 14)
            addView(titleText("Torrent da trovare", 16, true))
            addView(resultLimitInput.apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(44)).apply {
                    marginStart = dp(8)
                }
            })
        })
        content.addView(titleText("Parole da escludere", 16, true).apply {
            layoutParams = verticalParams(top = 14)
        })
        val termsInput = input(filters.excludedTerms).apply {
            hint = "sample, trailer, cam"
            this.filters = arrayOf(InputFilter.LengthFilter(500))
            contentDescription = "Parole o frasi da escludere dai risultati torrent"
            layoutParams = verticalParams(top = 8)
        }
        content.addView(termsInput)
        val termsCounter = counterText("0 esclusioni", 12).apply {
            layoutParams = verticalParams(top = 6)
        }
        fun updateTermsCounter() {
            val count = termsInput.text?.toString().orEmpty()
                .split(',', ';', '\n')
                .map(String::trim)
                .count(String::isNotBlank)
            termsCounter.text = if (count == 1) "1 esclusione" else "$count esclusioni"
        }
        termsInput.doAfterTextChanged {
            StreamCenterTorrentPreferences.setExcludedTerms(sharedPref, termsInput.text?.toString().orEmpty())
            updateTermsCounter()
        }
        updateTermsCounter()
        content.addView(termsCounter)
        content.addView(switchRow(
            title = "Escludi copie cinema",
            checked = filters.excludeCinemaCopies,
            accent = COLOR_TORRENT,
            strokeColor = tint(COLOR_TORRENT, "55"),
            topMargin = 12,
        ) { enabled ->
            StreamCenterTorrentPreferences.setExcludeCinemaCopies(sharedPref, enabled)
        })
        persistIfValid = persist@ {
            val minimumSize = parseTorrentSize(minimumSizeInput.text?.toString(), sizeUnit)
            val maximumSize = parseTorrentSize(maximumSizeInput.text?.toString(), sizeUnit)
            val minimumSeeders = parseOptionalNonNegativeInt(minimumSeedInput.text?.toString())
            val maximumSeeders = parseOptionalNonNegativeInt(maximumSeedInput.text?.toString())
            val resultLimit = resultLimitInput.text?.toString()?.trim()?.toIntOrNull()
            val invalidMinimumSize = minimumSizeInput.text?.toString().orEmpty().trim().isNotBlank() &&
                minimumSize == null
            val invalidMaximumSize = maximumSizeInput.text?.toString().orEmpty().trim().isNotBlank() &&
                maximumSize == null
            val invalidMinimumSeeders = minimumSeedInput.text?.toString().orEmpty().trim().isNotBlank() &&
                minimumSeeders == null
            val invalidMaximumSeeders = maximumSeedInput.text?.toString().orEmpty().trim().isNotBlank() &&
                maximumSeeders == null
            val invalidResultLimit = resultLimit !in 1..100
            val invalidSizeBounds = minimumSize != null && maximumSize != null && minimumSize > maximumSize
            val invalidSeederBounds =
                minimumSeeders != null && maximumSeeders != null && minimumSeeders > maximumSeeders

            minimumSizeInput.error = when {
                invalidMinimumSize -> "Inserisci una dimensione positiva."
                invalidSizeBounds -> "Non può superare la dimensione massima."
                else -> null
            }
            maximumSizeInput.error = if (invalidMaximumSize) "Inserisci una dimensione positiva." else null
            minimumSeedInput.error = when {
                invalidMinimumSeeders -> "Inserisci un numero non negativo."
                invalidSeederBounds -> "Non può superare il numero massimo."
                else -> null
            }
            maximumSeedInput.error = if (invalidMaximumSeeders) "Inserisci un numero non negativo." else null
            resultLimitInput.error = if (invalidResultLimit) "Inserisci un valore da 1 a 100." else null

            if (
                invalidMinimumSize ||
                invalidMaximumSize ||
                invalidMinimumSeeders ||
                invalidMaximumSeeders ||
                invalidResultLimit ||
                invalidSizeBounds ||
                invalidSeederBounds
            ) return@persist
            StreamCenterTorrentPreferences.setSizeBounds(sharedPref, minimumSize, maximumSize)
            StreamCenterTorrentPreferences.setSeederBounds(sharedPref, minimumSeeders, maximumSeeders)
            StreamCenterTorrentPreferences.setResultLimit(sharedPref, resultLimit ?: return@persist)
        }
        listOf(
            minimumSizeInput,
            maximumSizeInput,
            minimumSeedInput,
            maximumSeedInput,
            resultLimitInput,
        ).forEach { input ->
            input.doAfterTextChanged { persistIfValid() }
        }
        val scroll = ScrollView(ctx).apply { addView(content) }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Filtri e Risultati"))
            .setView(scroll)
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog, onDismiss = { renderRows() })
        dialog.show()
    }

    private fun showExtDomainsDialog() {
        val ctx = context ?: return
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        val statusViews = mutableMapOf<StreamCenterExtDomain, TextView>()
        fun rebuildDomainRows() {
            content.removeAllViews()
            statusViews.clear()
            val orderedDomains = StreamCenterTorrentPreferences.domainOrder(sharedPref)
            orderedDomains.forEachIndexed { index, domain ->
                val status = bodyText(torrentDomainStatusText(domain, extDomainStatuses[domain]), 12)
                statusViews[domain] = status
                val trailing = buildList<View> {
                    add(styledSwitch(
                        StreamCenterTorrentPreferences.isDomainEnabled(sharedPref, domain),
                        COLOR_TORRENT,
                    ) { enabled ->
                        StreamCenterTorrentPreferences.setDomainEnabled(sharedPref, domain, enabled)
                        rebuildDomainRows()
                        renderRows()
                    }.apply {
                        contentDescription = "Attiva o disattiva ${torrentDomainShortLabel(domain)}"
                    })
                    if (domain.requiresCloudflare) {
                        add(iconButton(
                            symbol = "🛡",
                            description = "Verifica Cloudflare per ${torrentDomainShortLabel(domain)}",
                            accent = COLOR_TORRENT,
                            size = 30,
                        ) {
                            showExtCloudflareVerification(domain)
                        })
                    } else {
                        add(View(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                                marginStart = dp(8)
                            }
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        })
                    }
                    add(reorderIconButton(
                        symbol = "↑",
                        description = "Alza la priorità di ${torrentDomainShortLabel(domain)}",
                        accent = COLOR_TORRENT,
                        enabled = index > 0,
                        size = 30,
                    ) {
                        moveExtDomain(domain, -1)
                        rebuildDomainRows()
                    })
                    add(reorderIconButton(
                        symbol = "↓",
                        description = "Abbassa la priorità di ${torrentDomainShortLabel(domain)}",
                        accent = COLOR_TORRENT,
                        enabled = index < orderedDomains.size - 1,
                        size = 30,
                    ) {
                        moveExtDomain(domain, 1)
                        rebuildDomainRows()
                    })
                }
                content.addView(endpointDomainCard(domain, index + 1, status, trailing))
            }
        }
        rebuildDomainRows()
        extAvailabilityStatusViews = statusViews
        refreshExtAvailabilityDialogState()
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Endpoint EXT"))
            .setView(ScrollView(ctx).apply { addView(content) })
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog, onDismiss = {
            extAvailabilityStatusViews = emptyMap()
        })
        dialog.show()
        startExtAvailabilityCheck()
    }

    private fun endpointDomainCard(
        domain: StreamCenterExtDomain,
        priority: Int,
        status: TextView,
        controls: List<View>,
    ): LinearLayout {
        val isEnabled = StreamCenterTorrentPreferences.isDomainEnabled(sharedPref, domain)
        val host = domain.baseUrl
            .substringAfter("://")
            .removePrefix("www.")
            .trimEnd('/')
        updateEndpointStatusChip(status, domain, extDomainStatuses[domain])
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(
                fillColor = if (isEnabled) COLOR_CARD_ALT else COLOR_CARD,
                strokeColor = tint(COLOR_TORRENT, if (isEnabled) "65" else "36"),
                radius = 16,
            )
            setPadding(dp(14), dp(13), dp(12), dp(12))
            layoutParams = verticalParams(top = 10)
            contentDescription = "${domain.title}, priorità $priority"

            addView(LinearLayout(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(chip(priority.toString(), COLOR_TORRENT).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginEnd = dp(10)
                    }
                })
                addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        marginEnd = dp(8)
                    }
                    addView(titleText(domain.title, 15, true))
                    addView(bodyText(host, 11).apply {
                        setPadding(0, dp(3), 0, 0)
                    })
                })
                addView(status)
            })

            addView(LinearLayout(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
                addView(chip("PRIORITÀ $priority", COLOR_TORRENT))
                addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
                controls.forEach(::addView)
            })
        }
    }

    private fun updateEndpointStatusChip(
        target: TextView,
        domain: StreamCenterExtDomain,
        status: StreamCenterExtDomainStatus?,
        checking: Boolean = false,
    ) {
        val accent = when {
            checking -> COLOR_TORRENT
            !StreamCenterTorrentPreferences.isDomainEnabled(sharedPref, domain) -> COLOR_MUTED
            status?.availability == StreamCenterExtAvailability.AVAILABLE -> COLOR_VPN_ON
            status == null -> COLOR_TORRENT
            else -> COLOR_DANGER
        }
        target.text = if (checking) "Verifica in corso…" else torrentDomainStatusText(domain, status)
        target.visibility = if (target.text.isBlank()) View.GONE else View.VISIBLE
        target.maxLines = 1
        target.setTextColor(Color.parseColor(tint(accent, "C7")))
        target.background = outlined(tint(accent, "4D"), tint(accent, "14"), 999)
        target.setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private fun startExtAvailabilityCheck() {
        if (isExtAvailabilityCheckRunning) return
        if (!StreamCenterVpnGuard.canUseInternet(sharedPref)) {
            return
        }
        isExtAvailabilityCheckRunning = true
        refreshExtAvailabilityDialogState()
        viewLifecycleOwner.lifecycleScope.launch {
            val statuses = supervisorScope {
                StreamCenterExtDomain.entries.map { domain ->
                    async(Dispatchers.IO) {
                        val result = try {
                            Result.success(StreamCenterExtTorrentClient.checkAvailability(domain))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                        domain to result
                    }
                }.awaitAll()
            }
            statuses.forEach { (domain, result) ->
                extDomainStatuses[domain] = result.getOrElse { error ->
                    StreamCenterExtDomainStatus(
                        domain = domain,
                        availability = StreamCenterExtAvailability.UNAVAILABLE,
                        detail = error.message,
                    )
                }
            }
            isExtAvailabilityCheckRunning = false
            if (!isAdded) return@launch
            refreshExtAvailabilityDialogState()
            refreshTorrentCategoryStatus()
        }
    }

    private fun refreshExtAvailabilityDialogState() {
        extAvailabilityStatusViews.forEach { (domain, view) ->
            updateEndpointStatusChip(
                target = view,
                domain = domain,
                status = extDomainStatuses[domain],
                checking = isExtAvailabilityCheckRunning,
            )
        }
    }

    private fun torrentDomainStatusText(
        domain: StreamCenterExtDomain,
        status: StreamCenterExtDomainStatus?,
    ): String {
        if (
            !StreamCenterTorrentPreferences.isDomainEnabled(sharedPref, domain)
        ) {
            return "Disattivato"
        }
        if (!domain.requiresCloudflare) {
            return when (status?.availability) {
                null -> "Non verificato"
                StreamCenterExtAvailability.AVAILABLE -> ""
                else -> "Non disponibile"
            }
        }
        val hasClearance = StreamCenterExtCloudflareSession.hasVerifiedClearance(domain.baseUrl)
        return when {
            status == null -> if (hasClearance) "Cloudflare verificato ✓" else "Non verificato"
            status.availability == StreamCenterExtAvailability.AVAILABLE ->
                if (hasClearance) "Cloudflare ✓" else ""
            status.availability == StreamCenterExtAvailability.VERIFICATION_REQUIRED ->
                if (hasClearance) "Cloudflare verificato ✓" else "Verifica Cloudflare richiesta"
            status.availability == StreamCenterExtAvailability.RATE_LIMITED ->
                "Temporaneamente limitato"
            else -> "Non disponibile"
        }
    }

    private fun showTorrentLanguageDialog() {
        val ctx = context ?: return
        val options = StreamCenterTorrentLanguageFilter.entries.map { language ->
            TorrentChoice(language.title, language.description, language)
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        val optionsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(optionsContainer)
        val keywordsView = bodyText("", 12).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(COLOR_INPUT_FILL, COLOR_STROKE, 12)
            layoutParams = verticalParams(top = 12)
        }
        content.addView(keywordsView)
        content.addView(titleText("Personalizzate", 15, true).apply {
            layoutParams = verticalParams(top = 16)
        })
        val customInput = input(StreamCenterTorrentPreferences.read(sharedPref).customItalianTerms).apply {
            hint = "es. italiansubs, itasa, nostro"
            this.filters = arrayOf(InputFilter.LengthFilter(500))
            contentDescription = "Personalizzate considerate come italiano"
            layoutParams = verticalParams(top = 8)
        }
        content.addView(customInput)
        lateinit var dialog: AlertDialog
        var current = StreamCenterTorrentPreferences.read(sharedPref).language
        fun renderKeywords() {
            keywordsView.text = torrentLanguageKeywords(current)
        }
        fun renderOptions() {
            optionsContainer.removeAllViews()
            options.forEach { option ->
                optionsContainer.addView(torrentChoiceRow(option, option.value == current) {
                    StreamCenterTorrentPreferences.setLanguage(sharedPref, option.value)
                    current = option.value
                    renderOptions()
                    renderKeywords()
                    renderRows()
                })
            }
        }
        customInput.doAfterTextChanged {
            StreamCenterTorrentPreferences.setCustomItalianTerms(
                sharedPref,
                customInput.text?.toString().orEmpty(),
            )
            renderKeywords()
        }
        renderOptions()
        renderKeywords()
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Lingua"))
            .setView(ScrollView(ctx).apply { addView(content) })
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun torrentLanguageKeywords(language: StreamCenterTorrentLanguageFilter): CharSequence {
        val groups = StreamCenterTorrentMetadata.discriminationKeywords(language).toMutableList()
        val custom = StreamCenterTorrentPreferences.read(sharedPref).customItalianTermSet()
        if (language != StreamCenterTorrentLanguageFilter.ANY && custom.isNotEmpty()) {
            groups.add(StreamCenterTorrentKeywordGroup("Personalizzate", custom.toList()))
        }
        if (groups.isEmpty()) {
            return "Nessun filtro di lingua: vengono mostrati tutti i risultati e non viene cercata nessuna parola."
        }
        val intro = if (language == StreamCenterTorrentLanguageFilter.ONLY_ITALIAN) {
            "Tiene solo le release che contengono queste parole (audio o sottotitoli italiani):"
        } else {
            "Porta in cima le release che contengono queste parole:"
        }
        return buildString {
            append(intro)
            groups.forEach { group ->
                append("\n\n")
                append(group.label)
                append(": ")
                append(group.words.joinToString(", "))
            }
        }
    }

    private fun torrentVideoCodecsSummary(): String {
        val blocked = StreamCenterTorrentPreferences.read(sharedPref).blockedVideoCodecs
        return when {
            blocked.isEmpty() -> "Tutti i codec consentiti"
            blocked.size >= StreamCenterTorrentVideoCodec.entries.size -> "Nessun codec consentito"
            else -> "Esclusi: " + blocked.joinToString(", ") { codec -> codec.displayName }
        }
    }

    private fun showTorrentVideoCodecsDialog() {
        val ctx = context ?: return
        val filters = StreamCenterTorrentPreferences.read(sharedPref)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(16))
        }
        content.addView(torrentToggleGrid(
            StreamCenterTorrentVideoCodec.entries.map { codec ->
                TorrentToggleOption(
                    title = codec.displayName,
                    enabled = codec !in filters.blockedVideoCodecs,
                    onChanged = { allowed ->
                        StreamCenterTorrentPreferences.setVideoCodecAllowed(sharedPref, codec, allowed)
                        renderRows()
                    },
                )
            },
        ))
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Codec video"))
            .setView(ScrollView(ctx).apply { addView(content) })
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun <T> torrentChoiceRow(
        option: TorrentChoice<T>,
        isSelected: Boolean,
        onClick: () -> Unit,
    ): View {
        val selectedBadge = iconBadge("✓", COLOR_TORRENT, size = 30, marginEnd = 0).apply {
            visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        }
        val row = settingsRow(
            title = option.title,
            summary = option.description,
            accent = COLOR_TORRENT,
            fillColor = COLOR_CARD_ALT,
            strokeColor = if (isSelected) COLOR_TORRENT else tint(COLOR_TORRENT, "55"),
            trailingViews = listOf(selectedBadge),
            touchTarget = selectedBadge,
            topMargin = 8,
            accessibilityState = { if (isSelected) "Selezionata" else "Non selezionata" },
        ) { onClick() }
        row.view.isSelected = isSelected
        return row.view
    }

    private fun <T> showTorrentChoiceDialog(
        title: String,
        options: List<TorrentChoice<T>>,
        selected: T,
        dismissLabel: String = "Annulla",
        onSelected: (TorrentChoice<T>) -> Unit,
    ) {
        val ctx = context ?: return
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        lateinit var dialog: AlertDialog
        options.forEach { option ->
            content.addView(torrentChoiceRow(option, option.value == selected) {
                onSelected(option)
                dialog.dismiss()
            })
        }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle(title))
            .setView(ScrollView(ctx).apply { addView(content) })
            .setNegativeButton(dismissLabel, null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun torrentSizeUnitFor(bytes: Long?): TorrentSizeUnit =
        if ((bytes ?: 0L) >= TorrentSizeUnit.GB.bytes) TorrentSizeUnit.GB else TorrentSizeUnit.MB

    private fun formatTorrentSizeInput(bytes: Long?, unit: TorrentSizeUnit): String {
        val value = bytes ?: return ""
        return "%.3f".format(Locale.ROOT, value.toDouble() / unit.bytes)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun convertTorrentSizeInput(
        value: String?,
        from: TorrentSizeUnit,
        to: TorrentSizeUnit,
    ): String {
        val number = value?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return ""
        return "%.3f".format(Locale.ROOT, number * from.bytes / to.bytes)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun parseTorrentSize(value: String?, unit: TorrentSizeUnit): Long? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        val amount = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
        return (amount * unit.bytes)
            .takeIf { bytes -> bytes <= Long.MAX_VALUE.toDouble() }
            ?.toLong()
            ?.takeIf { bytes -> bytes > 0L }
    }

    private fun parseOptionalNonNegativeInt(value: String?): Int? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        return text.toIntOrNull()?.takeIf { number -> number >= 0 }
    }

    private fun formatTorrentSize(bytes: Long): String {
        val unit = torrentSizeUnitFor(bytes)
        return "${formatTorrentSizeInput(bytes, unit)} ${unit.label}"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showExtCloudflareVerification(domain: StreamCenterExtDomain) {
        if (!StreamCenterVpnGuard.canUseInternet(sharedPref)) {
            Toast.makeText(requireContext(), "Attiva una VPN per usare la verifica Cloudflare", Toast.LENGTH_SHORT).show()
            return
        }
        val normalizedUrl = domain.baseUrl.trim().let { value ->
            when {
                value.startsWith("https://", ignoreCase = true) -> value
                value.startsWith("http://", ignoreCase = true) -> value
                else -> "https://$value"
            }
        }.trimEnd('/')
        val parsedUrl = Uri.parse(normalizedUrl)
        if (
            parsedUrl.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            parsedUrl.host.isNullOrBlank()
        ) {
            Toast.makeText(requireContext(), "Link EXT non valido", Toast.LENGTH_SHORT).show()
            return
        }
        val baseUrl = parsedUrl.toString()

        val status = bodyText("Completa la verifica nella pagina qui sotto.", 12).apply {
            setPadding(dp(18), dp(12), dp(18), dp(8))
        }
        val cursorHint = bodyText(
            "Se col telecomando non riesci a cliccare la casella, premi «Cursore TV».",
            11,
        ).apply {
            setPadding(dp(18), 0, dp(18), dp(8))
        }
        val webView = WebView(requireContext()).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val webHost = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(520),
            )
            addView(webView)
        }
        val tvCursor = StreamCenterTvTouchCursor(webHost, webView)
        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        StreamCenterExtCloudflareSession.updateUserAgent(webView.settings.userAgentString)

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(cursorHint)
            addView(webHost)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Verifica Cloudflare · EXT"))
            .setView(content)
            .setNeutralButton("Cursore TV", null)
            .setNegativeButton("Chiudi", null)
            .create()
        tvCursor.onStateChanged = { active ->
            cursorHint.text = if (active) {
                "Cursore TV attivo · muovi il pallino col D-pad e premi OK per cliccare la casella."
            } else {
                "Se col telecomando non riesci a cliccare la casella, premi «Cursore TV» (o il tasto MENU)."
            }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.text =
                if (active) "Cursore: ON" else "Cursore TV"
        }
        var clearanceHandled = false
        fun handleClearance() {
            if (clearanceHandled) return
            clearanceHandled = true
            status.text = "Verifica completata. Chiudo…"
            viewLifecycleOwner.lifecycleScope.launch {
                delay(CLOUDFLARE_CLEARANCE_CLOSE_DELAY_MS)
                if (isAdded && dialog.isShowing) dialog.dismiss()
            }
        }
        val clearancePollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && !clearanceHandled) {
                CookieManager.getInstance().flush()
                if (StreamCenterExtCloudflareSession.isReady(baseUrl)) {
                    handleClearance()
                    break
                }
                delay(CLOUDFLARE_CLEARANCE_POLL_INTERVAL_MS)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return true
                if (clearanceHandled) return true
                val blocked = !isAllowedExtNavigation(baseUrl, target)
                if (blocked) status.text = "Navigazione esterna bloccata per sicurezza."
                return blocked
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url ?: return true
                if (clearanceHandled) return true
                val blocked = !isAllowedExtNavigation(baseUrl, target)
                if (blocked) status.text = "Navigazione esterna bloccata per sicurezza."
                return blocked
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                cookieManager.flush()
                if (StreamCenterExtCloudflareSession.isReady(baseUrl)) {
                    handleClearance()
                } else if (!clearanceHandled) {
                    status.text = "Completa la verifica nella pagina qui sotto."
                }
            }
        }
        applyDialogBackdrop(dialog, onDismiss = {
            clearancePollJob.cancel()
            cookieManager.flush()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
            if (isAdded) startExtAvailabilityCheck()
        })
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) tvCursor.toggle()
                true
            } else {
                tvCursor.onKeyEvent(keyCode, event)
            }
        }
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener { tvCursor.toggle() }
        tvCursor.activate()
        webView.loadUrl(baseUrl)
    }

    private fun isAllowedExtNavigation(baseUrl: String, targetUrl: String): Boolean {
        val base = Uri.parse(baseUrl)
        val target = Uri.parse(targetUrl)
        if (target.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return false
        val baseHost = base.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return false
        val targetHost = target.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return false
        return baseHost == targetHost
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
            val headerRow = categoryHeaderRow(
                title = category.title,
                summaryView = status,
                icon = category.icon,
                accent = category.accent,
                trailingViews = listOf(expandButton),
            ) { expandButton.callOnClick() }
            headerRow.view.tag = "source-category-header:${category.key}"
            addView(headerRow.view)

            if (expanded) {
                val expandedContent = LinearLayout(requireContext()).apply {
                    tag = "source-category-content:${category.key}"
                    orientation = LinearLayout.VERTICAL
                }
                val firstMovableIndex = categoryRows.indexOfFirst { !it.source.isPinned }
                val movableCount = categoryRows.count { !it.source.isPinned }
                addAdaptiveCardGrid(
                    expandedContent,
                    categoryRows.mapIndexed { index, row ->
                        sourceRow(
                            index = index,
                            row = row,
                            accent = category.accent,
                            firstMovableIndex = firstMovableIndex.coerceAtLeast(0),
                            movableCount = movableCount,
                        )
                    },
                )
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
        rememberCategoryFocus(categoryKey)
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

    private fun rememberCategoryFocus(categoryKey: String) {
        val focused = rowsContainer?.findFocus() ?: return
        if (!focused.isInTouchMode) pendingCategoryFocusKey = categoryKey
    }

    private fun restorePendingCategoryFocus(container: View) {
        val categoryKey = pendingCategoryFocusKey ?: return
        val header = container.findViewWithTag<View>("source-category-header:$categoryKey") ?: return
        pendingCategoryFocusKey = null
        header.post {
            if (isAdded && header.isAttachedToWindow) header.requestFocus()
        }
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
        if (!StreamCenterVpnGuard.canUseInternet(sharedPref)) return
        val container = sourceIconPreloadContainer ?: return
        val generation = ++sourceIconPreloadGeneration

        StreamCenterPlugin.getStremioAddons(sharedPref)
            .mapNotNull(StreamCenterStremioAddon::logoUrl)
            .distinct()
            .forEach { iconUrl -> preloadSourceIcon(container, iconUrl) }

        val sourceUrls = (listOf(STREMIO_WEBSITE_URL) + rows.map(SourceRowState::url))
            .filter(String::isNotBlank)
            .distinct()
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
        if (!StreamCenterVpnGuard.canUseInternet(sharedPref)) return
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
        firstMovableIndex: Int,
        movableCount: Int,
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            val rowView = this
            val isPinned = row.source.isPinned
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
                if (isPinned) setSingleLine(true)
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
                this.filters = arrayOf(InputFilter.LengthFilter(120))
                fun saveLink(value: String) {
                    val previousUrl = row.url
                    StreamCenterPlugin.setSourceBaseUrl(sharedPref, row.source.key, value)
                    row.url = StreamCenterPlugin.getSourceBaseUrl(sharedPref, row.source.key)
                    if (row.url != previousUrl) {
                        StreamCenter.resetSourceDomainChecks()
                    }
                }
                doAfterTextChanged { editable ->
                    saveLink(editable?.toString().orEmpty())
                }
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        saveLink(text?.toString().orEmpty())
                        if (text?.toString() != row.url) {
                            setText(row.url)
                        }
                    }
                }
            }
            linkRow.addView(linkInput)
            if (!isPinned) {
                linkRow.addView(iconButton(
                    symbol = "↶",
                    description = "Ripristina il link predefinito di ${row.source.title}",
                    accent = accent,
                    size = 30,
                ) {
                    resetSourceLink(row, linkInput)
                })
            }
            if (isPinned) {
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
            } else if (movableCount > 1) {
                linkRow.addView(reorderIconButton(
                    symbol = "↑",
                    description = "Alza la priorità di ${row.source.title}",
                    accent = accent,
                    enabled = index > firstMovableIndex,
                    size = 30,
                ) {
                    moveRow(row.source.category, index - firstMovableIndex, -1)
                })
                linkRow.addView(reorderIconButton(
                    symbol = "↓",
                    description = "Abbassa la priorità di ${row.source.title}",
                    accent = accent,
                    enabled = index < firstMovableIndex + movableCount - 1,
                    size = 30,
                ) {
                    moveRow(row.source.category, index - firstMovableIndex, 1)
                })
            }
            addView(linkRow)

        }
    }

    private fun moveRow(categoryKey: String, index: Int, direction: Int) {
        val categoryIndices = rows.indices.filter {
            rows[it].source.category == categoryKey && !rows[it].source.isPinned
        }
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
        loadStreamingCommunityPublishedUrl(button, "↻") { publishedUrl ->
            val configuredUrl = StreamCenterPlugin.getSourceBaseUrl(
                sharedPref,
                StreamCenterPlugin.PREF_SOURCE_STREAMINGCOMMUNITY,
            )
            if (sameSourceHost(configuredUrl, publishedUrl)) {
                saveToast("Il link di StreamingCommunity è già aggiornato")
                return@loadStreamingCommunityPublishedUrl
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

    private fun resetSourceLink(
        row: SourceRowState,
        linkInput: EditText,
    ) {
        rowsContainer?.clearFocus()
        StreamCenterPlugin.setSourceBaseUrl(sharedPref, row.source.key, "")
        row.url = StreamCenterPlugin.getSourceBaseUrl(sharedPref, row.source.key)
        if (linkInput.text?.toString() != row.url) {
            linkInput.setText(row.url)
        }
        StreamCenter.resetSourceDomainChecks()
        saveToast("Link di ${row.source.title} ripristinato")
    }

    private fun loadStreamingCommunityPublishedUrl(
        button: TextView,
        idleSymbol: String,
        onLoaded: (String) -> Unit,
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
                button.text = idleSymbol
                if (publishedUrl == null) {
                    saveToast("Impossibile leggere il link aggiornato di StreamingCommunity")
                } else {
                    onLoaded(publishedUrl)
                }
            }
        }
    }

    private fun streamingCommunityPublishedUrl(): String? {
        StreamCenterVpnGuard.requireInternetAccess(sharedPref)
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
        StreamCenterPlugin.resetSourcesConfiguration(sharedPref)
        StreamCenter.resetSourceDomainChecks()
        expandedCategoryKey = null
        pendingCategoryExpansionKey = null
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
        torrentCategoryStatusView = null
        extDomainStatuses.clear()
        extAvailabilityStatusViews = emptyMap()
        isExtAvailabilityCheckRunning = false
        super.onDestroyView()
    }
}
