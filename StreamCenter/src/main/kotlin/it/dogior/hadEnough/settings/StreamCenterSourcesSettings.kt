package it.dogior.hadEnough.settings

import it.dogior.hadEnough.*
import it.dogior.hadEnough.catalog.StreamCenterCatalogDefinition
import it.dogior.hadEnough.catalog.StreamCenterCatalogSection
import it.dogior.hadEnough.catalog.StreamCenterCatalogs
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.tmdb.StreamCenterTmdbApiClient
import it.dogior.hadEnough.tmdb.StreamCenterTmdbCredential
import it.dogior.hadEnough.tmdb.StreamCenterTmdbUserAgent
import it.dogior.hadEnough.tmdb.TmdbAttribution
import it.dogior.hadEnough.tmdb.TmdbLogoDrawable
import it.dogior.hadEnough.tmdb.TmdbVerifyResult
import it.dogior.hadEnough.extensions.InstalledExtensionSources
import it.dogior.hadEnough.torrent.StreamCenterTorrentPreferences
import it.dogior.hadEnough.util.StreamCenterVpnGuard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import com.lagradost.cloudstream3.utils.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

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
    private companion object {
        const val STREMIO_CATEGORY_KEY = "stremio"
        const val EXTENSIONS_CATEGORY_KEY = "installed-extensions"
        const val TMDB_LOGO_HEIGHT_DP = 20
    }

    override val screenTitle: String = "Fonti"

    override val screenIcon: String = "📡"


    override fun screenAction(): SettingsScreenAction = SettingsScreenAction(
        label = "Ripristina",
        description = "Ripristina le fonti",
        onInvoke = { resetSources() },
    )

    private val rows = mutableListOf<SourceRowState>()
    private val preloadedSourceIconUrls = mutableSetOf<String>()
    private val sourceCategories = listOf(
        SourceCategory("anime", "Anime", "🎌", COLOR_ACCENT),
        SourceCategory("tv", "Serie TV / Film", "📺", COLOR_ACCENT),
    )
    private val categoryStatusViews = mutableMapOf<String, TextView>()
    private var expandedCategoryKey: String? = null
    private var pendingCategoryExpansionKey: String? = null
    private var pendingCategoryFocusKey: String? = null
    private var rowsContainer: LinearLayout? = null
    private var tmdbUserAgentSummaryView: TextView? = null
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

        val content = rootContainer().apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
        }
        content.minimumHeight = standardSubmenuMinimumHeight()

        val rowsView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 2)
        }
        rowsContainer = rowsView
        content.addView(rowsView)
        renderRows()

        content.addView(sectionHeading("TMDB"))
        content.addView(tmdbCredentialRow())
        content.addView(tmdbUserAgentRow())

        content.addView(sectionHeading("Ricerca"))
        content.addView(switchRow(
            title = "Cerca su TMDB se non ci sono risultati",
            summary = "Estende la ricerca generale",
            checked = StreamCenterPlugin.isTmdbSearchFallbackEnabled(sharedPref),
            defaultChecked = StreamCenterPlugin.isTmdbSearchFallbackEnabled(null),
            accent = COLOR_ACCENT,
            icon = "🔎",
        ) { enabled ->
            sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_TMDB_SEARCH_FALLBACK, enabled) }
        })

        content.addView(sectionHeading("Manutenzione"))
        addAdaptiveCardGrid(content, listOf(sourceDomainUpdateCard(), apiCheckCard()))

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
        val autoUpdateEnabled = StreamCenterPlugin.isSourceUrlAutoUpdateEnabled(sharedPref)
        toggle = styledSwitch(
            autoUpdateEnabled,
            COLOR_ACCENT,
            defaultChecked = StreamCenterPlugin.isSourceUrlAutoUpdateEnabled(null),
            resetTitle = "Aggiornamento automatico",
        ) { enabled ->
            playToggleFeedback(actionCard.view, actionCard.badge, COLOR_ACCENT, enabled)
            animateRowEnabledAppearance(actionCard.view, enabled)
            sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_AUTO_UPDATE_SOURCE_URLS, enabled) }
        }.drivenByRow()
        actionCard = sourceActionCard(
            title = "Aggiornamento automatico",
            summary = "Rileva automaticamente il nuovo indirizzo delle fonti e lo salva",
            accent = COLOR_ACCENT,
            icon = "🔄",
            trailing = toggle,
            onClick = { toggle.toggle() },
        )
        actionCard.view.alpha = if (autoUpdateEnabled) 1f else settingsRowDisabledAlpha
        return actionCard.view
    }

    private fun apiCheckCard(): LinearLayout {
        val arrow = chevron(COLOR_ACCENT)
        return sourceActionCard(
            title = "Verifica fonti",
            summary = "Controlla quali siti e servizi rispondono adesso",
            accent = COLOR_ACCENT,
            icon = "📡",
            trailing = arrow,
            motionTarget = arrow,
            onClick = { checkApis() },
        ).view
    }

    private fun stremioAddonsCategoryCard(): LinearLayout {
        val categoryKey = STREMIO_CATEGORY_KEY
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
                    size = 36,
                    marginEnd = 10,
                ),
            ) { expandButton.callOnClick() }
            headerRow.view.tag = "source-category-header:$categoryKey"
            addView(headerRow.view)

            if (expanded) {
                val expandedContent = groupColumn().apply {
                    tag = "source-category-content:$categoryKey"
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
            addView(actionButton("Aggiungi add-on", COLOR_STREMIO) {
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

    private fun stremioAddonRow(addon: StreamCenterStremioAddon): View {
        val enabled = StreamCenterPlugin.isStremioAddonEnabled(sharedPref, addon.key)
        val capabilities = listOfNotNull(
            "Stream".takeIf { addon.resources.any { it.name.equals("stream", ignoreCase = true) } },
            "sottotitoli".takeIf { addon.resources.any { it.name.equals("subtitles", ignoreCase = true) } },
        ).joinToString(" e ")
        val summary = listOfNotNull(capabilities.takeIf(String::isNotBlank), addon.version?.let { "v$it" })
            .joinToString(", ")
        lateinit var views: SettingsRowViews
        lateinit var overflow: TextView
        val toggle = styledSwitch(
            enabled,
            COLOR_STREMIO,
            defaultChecked = StreamCenterPlugin.isStremioAddonEnabled(null, addon.key),
            resetTitle = addon.name,
        ) { isEnabled ->
            animateRowEnabledAppearance(views.view, isEnabled)
            StreamCenterPlugin.setStremioAddonEnabled(sharedPref, addon.key, isEnabled)
            refreshStremioCategoryStatus()
            saveToast(if (isEnabled) "Add-on attivato" else "Add-on disattivato")
        }.apply {
            contentDescription = "Attiva o disattiva stream e sottotitoli di ${addon.name}"
        }.drivenByRow()
        overflow = overflowButton("Altre azioni per ${addon.name}") {
            listOf(
                OverflowAction("Aggiorna manifest") { refreshStremioAddon(addon, overflow) },
                OverflowAction("Modifica link del manifest") { editStremioManifestUrl(addon) },
                OverflowAction("Copia link del manifest") {
                    (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText("Manifest Stremio", addon.manifestUrl))
                    saveToast("Link del manifest copiato")
                },
                OverflowAction("Rimuovi") { confirmStremioAddonRemoval(addon) },
            )
        }
        views = settingsRow(
            title = addon.name,
            summary = summary.takeIf(String::isNotBlank),
            leadingView = stremioAddonBadge(addon),
            accent = COLOR_STREMIO,
            trailingViews = listOf(toggle, overflow),
            touchTarget = toggle,
            enabledAppearance = enabled,
            logAction = false,
        ) { toggle.toggle() }
        return views.view
    }

    private fun tmdbCredentialSummary(): String {
        val credential = StreamCenterTmdbCredential.read(sharedPref)
        return when {
            credential == null -> "Non impostata · uso il sito"
            StreamCenterTmdbCredential.isInvalid(sharedPref) -> "Non valida · uso il sito"
            else -> "Attiva (${credential.kind.label}) · uso l'API ufficiale"
        }
    }

    private fun tmdbCredentialRow(): View {
        lateinit var views: SettingsRowViews
        views = settingsRow(
            title = "Chiave API TMDB",
            summary = tmdbCredentialSummary(),
            icon = "🔑",
            accent = COLOR_ACCENT,
        ) { showTmdbCredentialDialog { views.summary?.text = tmdbCredentialSummary() } }
        return views.view
    }

    private fun tmdbUserAgentRow(): View {
        fun summary() = StreamCenterTmdbUserAgent.read(sharedPref) ?: "Predefinito"
        lateinit var views: SettingsRowViews
        views = settingsRow(
            title = "User-Agent TMDB",
            summary = summary(),
            icon = "🌐",
            accent = COLOR_ACCENT,
        ) {
            val ctx = context ?: return@settingsRow
            val input = input(StreamCenterTmdbUserAgent.read(sharedPref).orEmpty()).apply {
                hint = "User-Agent personalizzato"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine(true)
                layoutParams = verticalParams(top = 10)
            }
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(10), dp(20), 0)
                addView(bodyText("Usato per le richieste al sito e all'API TMDB. Lascia vuoto per usare i valori predefiniti."))
                addView(input)
            }
            fun save(value: String) {
                sharedPref?.edit {
                    if (value.isEmpty()) remove(StreamCenterTmdbUserAgent.PREF_KEY)
                    else putString(StreamCenterTmdbUserAgent.PREF_KEY, value)
                }
                StreamCenterTmdbApiClient.clearCache()
                views.summary?.text = summary()
                saveToast("User-Agent TMDB salvato")
            }
            val dialog = AlertDialog.Builder(ctx)
                .setCustomTitle(dialogTitle("User-Agent TMDB"))
                .setView(scrollableDialogView(container))
                .setPositiveButton("Salva", null)
                .setNegativeButton("Annulla", null)
                .setNeutralButton("Ripristina") { _, _ -> save("") }
                .create()
            applyDialogBackdrop(dialog)
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString().orEmpty().trim()
                if (!StreamCenterTmdbUserAgent.isValid(value)) {
                    input.error = "Usa solo caratteri ASCII stampabili, senza ritorni a capo"
                    return@setOnClickListener
                }
                save(value)
                dialog.dismiss()
            }
        }
        tmdbUserAgentSummaryView = views.summary
        return views.view
    }

    private fun tmdbAttributionView(): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = verticalParams(top = 8)
            setPadding(0, dp(8), 0, dp(4))
            addView(ImageView(ctx).apply {
                setImageDrawable(TmdbLogoDrawable())
                contentDescription = "TMDB"
                layoutParams = LinearLayout.LayoutParams(
                    dp((TMDB_LOGO_HEIGHT_DP * TmdbLogoDrawable.VIEWBOX_WIDTH / TmdbLogoDrawable.VIEWBOX_HEIGHT).toInt()),
                    dp(TMDB_LOGO_HEIGHT_DP),
                )
            })
            addView(bodyText(TmdbAttribution.NOTICE_EN, 12).apply { layoutParams = verticalParams(top = 8) })
            addView(bodyText(TmdbAttribution.NOTICE_IT, 12).apply { layoutParams = verticalParams(top = 4) })
        }
    }

    private fun showTmdbCredentialDialog(onChanged: () -> Unit) {
        val ctx = context ?: return
        val hasCredential = StreamCenterTmdbCredential.read(sharedPref) != null
        val input = input("").apply {
            hint = "Chiave API (v3) o token di lettura (v4)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(1_000))
            layoutParams = verticalParams()
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(bodyText("Usa la tua chiave personale: resta solo su questo dispositivo e non viene inclusa in backup o sincronizzazioni."))
            addView(input.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(10) })
            addView(bodyText("Come ottenerla: ${TmdbAttribution.API_SETTINGS_URL}").apply {
                layoutParams = verticalParams(top = 10)
                setTextColor(android.graphics.Color.parseColor(COLOR_ACCENT))
                isFocusable = true
                isClickable = true
                background = interactiveBackground(COLOR_TRANSPARENT, COLOR_ACCENT, 8, COLOR_TRANSPARENT)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setOnClickListener { openUrl(TmdbAttribution.API_SETTINGS_URL) }
            })
            addView(tmdbAttributionView())
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Chiave API TMDB"))
            .setView(scrollableDialogView(container))
            .setPositiveButton("Salva", null)
            .setNegativeButton("Chiudi", null)
            .apply { if (hasCredential) setNeutralButton("Rimuovi", null) }
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            StreamCenterTmdbCredential.clear(sharedPref)
            StreamCenterTmdbApiClient.clearCache()
            onChanged()
            dialog.dismiss()
            saveToast("Chiave TMDB rimossa")
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val credential = StreamCenterTmdbCredential.parse(input.text?.toString())
            if (credential == null) {
                input.error = "Formato non valido: incolla la chiave v3 (32 caratteri) o il token v4"
                return@setOnClickListener
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            CoroutineScope(Dispatchers.IO).launch {
                val result = StreamCenterTmdbApiClient.verify(credential, sharedPref)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    when (result) {
                        TmdbVerifyResult.VALID -> {
                            StreamCenterTmdbCredential.save(sharedPref, credential)
                            StreamCenterTmdbApiClient.clearCache()
                            onChanged()
                            dialog.dismiss()
                            saveToast("Chiave TMDB attiva")
                        }
                        TmdbVerifyResult.INVALID -> input.error = "TMDB ha rifiutato la chiave"
                        TmdbVerifyResult.NETWORK_ERROR -> input.error = "Impossibile contattare TMDB, riprova"
                    }
                }
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
            .setNegativeButton("Chiudi", null)
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
            .setNegativeButton("Chiudi", null)
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
            .setNegativeButton("Chiudi", null)
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
            .setNegativeButton("Chiudi", null)
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
                        button.text = OVERFLOW_SYMBOL
                        saveToast("Il manifest aggiornato usa un ID gia configurato")
                        return@withContext
                    }
                    renderRows()
                    saveToast("Manifest aggiornato")
                } else {
                    button.isEnabled = true
                    button.text = OVERFLOW_SYMBOL
                    saveToast(result.exceptionOrNull()?.message ?: "Impossibile aggiornare il manifest")
                }
            }
        }
    }

    private fun confirmStremioAddonRemoval(addon: StreamCenterStremioAddon) {
        val linkedCatalog = StreamCenterCatalogs.configuredStremioCatalog(sharedPref, addon.key)
        fun removeSource() {
            StreamCenterPlugin.removeStremioAddon(sharedPref, addon.key)
            StreamCenterPlugin.refreshCatalogs()
            renderRows()
        }
        val builder = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Elimina add-on Stremio"))
            .setNegativeButton("Chiudi", null)
        if (linkedCatalog == null) {
            builder
                .setMessage("Vuoi eliminare l'add-on \"${addon.name}\"?")
                .setPositiveButton("Elimina") { _, _ ->
                    removeSource()
                    saveToast("Add-on eliminato")
                }
        } else {
            builder
                .setMessage(
                    "\"${addon.name}\" è presente anche come Catalogo nella Home. " +
                        "Vuoi eliminare anche il Catalogo e le sue sezioni?",
                )
                .setPositiveButton("Elimina entrambi") { _, _ ->
                    StreamCenterCatalogs.removeCatalog(sharedPref, linkedCatalog)
                    removeSource()
                    saveToast("Add-on eliminato da Fonti e Cataloghi")
                }
                .setNeutralButton("Solo Fonte") { _, _ ->
                    removeSource()
                    saveToast("Add-on eliminato da Fonti")
                }
        }
        showCompactActionDialog(builder.create())
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
        container.addView(sectionHeading("Siti", topMargin = 0))
        sourceCategories.forEach { category -> categoryCard(category.key)?.let(container::addView) }
        container.addView(sectionHeading("Altre fonti"))
        listOf(STREMIO_CATEGORY_KEY, EXTENSIONS_CATEGORY_KEY).forEach { key ->
            categoryCard(key)?.let(container::addView)
        }
        container.addView(torrentCard())
        restorePendingCategoryFocus(container)
    }

    private fun torrentCard(): View = categoryContainer(COLOR_ACCENT).apply {
        val enabled = StreamCenterPlugin.isTorrentEnabled(sharedPref)
        val status = counterText(torrentStatus())
        lateinit var header: SettingsRowViews
        val toggle = styledSwitch(
            enabled,
            COLOR_ACCENT,
            defaultChecked = StreamCenterPlugin.isTorrentEnabled(null),
            resetTitle = "Torrent",
        ) { isEnabled ->
            StreamCenterPlugin.setTorrentEnabled(sharedPref, isEnabled)
            status.text = torrentStatus()
            animateRowEnabledAppearance(header.view, isEnabled)
            saveToast(if (isEnabled) "Torrent attivati" else "Torrent disattivati")
        }.apply { contentDescription = "Attiva o disattiva i torrent" }
        val arrow = chevron(COLOR_ACCENT)
        header = categoryHeaderRow(
            title = "Torrent",
            summaryView = status,
            icon = "🧲",
            accent = COLOR_ACCENT,
            trailingViews = listOf(toggle, arrow),
            enabledAppearance = enabled,
        ) { openScreen(StreamCenterTorrentSettingsFragment(), "StreamCenterTorrentSettings") }
        addView(header.view)
    }

    private fun torrentStatus(): String {
        if (!StreamCenterPlugin.isTorrentEnabled(sharedPref)) return "Disattivati"
        val active = StreamCenterTorrentPreferences.enabledDomains(sharedPref).size
        val language = StreamCenterTorrentPreferences.read(sharedPref).language.title
        return "Attivi, $active endpoint, lingua $language"
    }

    private fun categoryCard(key: String): View? {
        val card = when (key) {
            STREMIO_CATEGORY_KEY -> stremioAddonsCategoryCard()
            EXTENSIONS_CATEGORY_KEY -> installedExtensionsCategoryCard()
            else -> {
                val category = sourceCategories.firstOrNull { it.key == key } ?: return null
                val categoryRows = rows.filter { it.source.category == key }
                if (categoryRows.isEmpty()) return null
                sourceCategoryCard(category, categoryRows)
            }
        }
        return card.apply { tag = "source-category-card:$key" }
    }

    private fun rerenderCategory(key: String) {
        val container = rowsContainer ?: return
        val current = container.findViewWithTag<View>("source-category-card:$key")
        if (current == null) {
            renderRows()
            return
        }
        val index = container.indexOfChild(current)
        container.removeViewAt(index)
        categoryCard(key)?.let { container.addView(it, index) }
    }

    private fun installedExtensionsCategoryCard(): LinearLayout {
        val categoryKey = EXTENSIONS_CATEGORY_KEY
        val sources = InstalledExtensionSources.available()
        val expanded = expandedCategoryKey == categoryKey
        fun statusText(): String {
            val selected = InstalledExtensionSources.enabledKeys(sharedPref)
            return if (sources.isEmpty()) "Nessuna fonte disponibile"
            else "${sources.count { it.isEnabled(selected) }}/${sources.size} estensioni attive"
        }
        return categoryContainer(COLOR_ACCENT).apply {
            val status = counterText(statusText())
            val expandButton = categoryExpandButton(
                expanded = expanded,
                description = if (expanded) "Chiudi Estensioni installate" else "Apri Estensioni installate",
                accent = COLOR_ACCENT,
                size = 34,
            ) { toggleCategory(categoryKey) }
            val header = categoryHeaderRow(
                title = "Estensioni installate",
                summaryView = status,
                icon = "\uD83E\uDDE9",
                accent = COLOR_ACCENT,
                trailingViews = listOf(expandButton),
            ) { expandButton.callOnClick() }
            header.view.tag = "source-category-header:$categoryKey"
            addView(header.view)
            if (expanded) {
                val content = groupColumn().apply {
                    tag = "source-category-content:$categoryKey"
                    addView(bodyText(
                        if (sources.isEmpty()) {
                            "Installa un'estensione"
                        } else {
                            "Le estensioni installate offrono fonti aggiuntive"
                        },
                    ).apply { setPadding(dp(12), dp(12), dp(12), dp(8)) })
                    val selected = InstalledExtensionSources.enabledKeys(sharedPref)
                    sources.forEach { source ->
                        addView(switchRow(
                            title = source.name,
                            summary = source.summary,
                            checked = source.isEnabled(selected),
                            defaultChecked = false,
                            accent = COLOR_ACCENT,
                            leadingView = installedExtensionBadge(source),
                            fixedHeight = false,
                        ) { enabled ->
                            InstalledExtensionSources.setEnabled(sharedPref, source, enabled)
                            status.text = statusText()
                        })
                    }
                }
                addView(content)
                if (pendingCategoryExpansionKey == categoryKey) animateCategoryExpansion(content)
            }
        }
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
                val expandedContent = groupColumn().apply {
                    tag = "source-category-content:${category.key}"
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
        rememberCategoryFocus(categoryKey)
        val previous = expandedCategoryKey
        val opening = previous != categoryKey
        expandedCategoryKey = categoryKey.takeIf { opening }
        previous?.let(::rerenderCategory)
        if (opening) {
            pendingCategoryExpansionKey = categoryKey
            rerenderCategory(categoryKey)
            pendingCategoryExpansionKey = null
        }
        rowsContainer?.let(::restorePendingCategoryFocus)
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
            .filterNot(StreamCenterBundledLogos::covers)
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
    ): View {
        lateinit var views: SettingsRowViews
        lateinit var overflow: TextView
        val toggle = styledSwitch(
            row.enabled,
            accent,
            defaultChecked = row.source.defaultEnabled,
            resetTitle = row.source.title,
        ) { checked ->
            row.enabled = checked
            animateRowEnabledAppearance(views.view, checked)
            sharedPref?.edit { putBoolean(row.source.key, checked) }
            refreshCategoryStatus(row.source.category)
        }.drivenByRow()
        fun refreshSummary() {
            views.summary?.text = sourceSummary(index, row)
        }
        overflow = overflowButton("Altre azioni per ${row.source.title}") {
            buildList {
                add(OverflowAction("Modifica link") { showSourceLinkDialog(row, ::refreshSummary) })
                if (row.source.isPinned) {
                    add(OverflowAction("Cerca link aggiornato") {
                        checkStreamingCommunityLink(row, overflow, ::refreshSummary)
                    })
                } else {
                    add(OverflowAction("Ripristina link") {
                        resetSourceLink(row)
                        refreshSummary()
                    })
                }
                if (!row.source.isPinned && movableCount > 1) {
                    add(OverflowAction("Sposta su", enabled = index > firstMovableIndex) {
                        moveRow(row.source.category, index - firstMovableIndex, -1)
                    })
                    add(OverflowAction("Sposta giù", enabled = index < firstMovableIndex + movableCount - 1) {
                        moveRow(row.source.category, index - firstMovableIndex, 1)
                    })
                }
            }
        }
        views = settingsRow(
            title = row.source.title,
            summary = sourceSummary(index, row),
            leadingView = nativeSourceBadge(row, accent),
            accent = accent,
            trailingViews = listOf(toggle, overflow),
            touchTarget = toggle,
            enabledAppearance = row.enabled,
            logAction = false,
        ) { toggle.toggle() }
        return views.view
    }

    private fun sourceSummary(index: Int, row: SourceRowState): String {
        val host = Uri.parse(row.url).host?.removePrefix("www.") ?: row.url
        return "Priorità ${index + 1}, $host"
    }

    private fun showSourceLinkDialog(row: SourceRowState, onSaved: () -> Unit) {
        val field = input(row.url).apply {
            filters = arrayOf(InputFilter.LengthFilter(120))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = verticalParams(top = 6)
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(sectionLabel("Link del sito"))
            addView(field)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle(row.source.title))
            .setView(content)
            .setPositiveButton("Salva") { _, _ ->
                val previousUrl = row.url
                StreamCenterPlugin.setSourceBaseUrl(sharedPref, row.source.key, field.text?.toString().orEmpty())
                row.url = StreamCenterPlugin.getSourceBaseUrl(sharedPref, row.source.key)
                if (row.url != previousUrl) StreamCenter.resetSourceDomainChecks()
                onSaved()
                saveToast("Link di ${row.source.title} salvato")
            }
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
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
        button: TextView,
        onUpdated: () -> Unit,
    ) {
        loadStreamingCommunityPublishedUrl(button, OVERFLOW_SYMBOL) { publishedUrl ->
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
                    onUpdated()
                    StreamCenter.resetSourceDomainChecks()
                    saveToast("Link di StreamingCommunity aggiornato")
                }
                .setNegativeButton("Ok", null)
                .create()
            applyDialogBackdrop(alertDialog)
            alertDialog.show()
        }
    }

    private fun resetSourceLink(row: SourceRowState) {
        StreamCenterPlugin.setSourceBaseUrl(sharedPref, row.source.key, "")
        row.url = StreamCenterPlugin.getSourceBaseUrl(sharedPref, row.source.key)
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
        StreamCenterTmdbApiClient.clearCache()
        tmdbUserAgentSummaryView?.text = "Predefinito"
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
        tmdbUserAgentSummaryView = null
        sourceIconPreloadContainer = null
        preloadedSourceIconUrls.clear()
        stremioManifestRefreshNoticeView = null
        stremioCategoryStatusView = null
        super.onDestroyView()
    }
}
