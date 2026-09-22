package it.dogior.hadEnough.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.ScrollView
import androidx.core.widget.doAfterTextChanged
import java.util.Calendar
import java.util.Locale
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.syncproviders.AccountManager
import it.dogior.hadEnough.StreamCenterHomeSectionDefinition
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.catalog.StreamCenterCatalogs
import it.dogior.hadEnough.catalog.StreamCenterHomeImport
import it.dogior.hadEnough.catalog.StreamCenterHomeImports
import it.dogior.hadEnough.extensions.InstalledExtensionSources
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import it.dogior.hadEnough.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StreamCenterBaseCatalogSettingsFragment : StreamCenterCardSettingsFragment() {
    override val screenTitle = "Ordinamento libero"
    override val screenIcon = "⇅"
    override val screenAccent = COLOR_HOME
    private var listContainer: LinearLayout? = null
    private var discoveryJob: Job? = null
    private var activeDialog: AlertDialog? = null
    private var titlePlaceholdersDialog: AlertDialog? = null

    override fun screenAction() = SettingsScreenAction(
        label = "Ripristina ordine",
        description = "Ripristina l'ordine delle sezioni, mantenendo quelle aggiunte",
        onInvoke = {
            sharedPref?.edit()?.remove(StreamCenterPlugin.PREF_HOME_ORDER)
                ?.remove(StreamCenterPlugin.PREF_HOME_FREE_ORDER)?.apply()
            renderSections()
            saveToast("Ordine ripristinato")
        },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = rootContainer()
        content.addView(bodyText(
            "Usa le frecce per ordinare • Tocca una sezione per scegliere la posizione, il nome e la quantità.", 13,
        ))
        content.addView(settingsRow(
            title = "Aggiungi da un catalogo", summary = "TMDB, MyAnimeList, Kitsu, AniList, Simkl e altro",
            icon = "+", accent = COLOR_HOME,
        ) { chooseCatalog() }.view)
        content.addView(settingsRow(
            title = "Aggiungi da un'estensione", summary = "Sezioni delle altre estensioni CloudStream installate",
            icon = "+", accent = COLOR_HOME,
        ) { chooseExtension() }.view)
        content.addView(sectionHeading("Ordine delle sezioni", COLOR_HOME))
        listContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)
        renderSections()
        return scroll(content, fixedSubmenuHeight = true)
    }

    private fun renderSections() {
        val container = listContainer ?: return
        container.removeAllViews()
        val sections = StreamCenterPlugin.getOrderedHomeSections(sharedPref)
        val imports = StreamCenterHomeImports.read(sharedPref).associateBy { it.key }
        val catalogs = StreamCenterCatalogs.allCatalogs(sharedPref).associateBy { it.key }
        val extensions = InstalledExtensionSources.available(forHome = true).flatMap { it.sources }.map { it.key }.toSet()
        sections.forEachIndexed { index, section ->
            val imported = imports[section.key]
            val categoryOn = StreamCenterPlugin.isHomeCategoryEnabled(sharedPref, StreamCenterPlugin.homeSectionCategoryKey(section))
            val sectionOn = StreamCenterPlugin.isHomeSectionEnabled(sharedPref, section)
            val available = when (imported?.kind) {
                StreamCenterHomeImports.CATALOG -> catalogs[imported.sourceKey]?.sections?.any { it.key == imported.sectionKey } == true
                StreamCenterHomeImports.EXTENSION -> imported.sourceKey in extensions
                else -> true
            }
            val title = StreamCenterPlugin.getHomeSectionTitleTemplate(sharedPref, section)
            val summary = if (section.key == "tv_catch_up") {
                "${StreamCenterPlugin.getHomeSectionCount(sharedPref, section)} elementi"
            } else buildList {
                add(imported?.sourceName ?: StreamCenterPlugin.homeSectionCategory(section))
                if (imported == null) add("${StreamCenterPlugin.getHomeSectionCount(sharedPref, section)} titoli")
                if (!categoryOn) add("Gruppo disattivato in Home")
                if (!available) add("Fonte non disponibile")
            }.joinToString(" · ")
            lateinit var sectionRow: View
            sectionRow = settingsRow(
                title = "${index + 1}. $title", summary = summary, accent = COLOR_HOME,
                enabledAppearance = sectionOn && categoryOn && available,
                trailingViews = listOf(
                    reorderIconButton("↑", "Sposta $title in alto", COLOR_HOME, index > 0) { move(section.key, index - 1) },
                    reorderIconButton("↓", "Sposta $title in basso", COLOR_HOME, index < sections.lastIndex) { move(section.key, index + 1) },
                    deleteIconButton("Elimina $title") { confirmRemoveSection(section) },
                    styledSwitch(
                        sectionOn, COLOR_HOME,
                        defaultChecked = section.defaultEnabled, resetTitle = title,
                    ) { enabled ->
                        sharedPref?.edit()?.putBoolean(StreamCenterPlugin.sectionEnabledKey(section.key), enabled)?.apply()
                        animateRowEnabledAppearance(sectionRow, enabled && categoryOn && available)
                    },
                ),
                onReset = { resetSection(section); renderSections() },
            ) { editSection(section, imported != null) }.view
            container.addView(sectionRow)
        }
    }

    private fun move(key: String, target: Int) {
        val keys = StreamCenterPlugin.getOrderedHomeSections(sharedPref).map { it.key }.toMutableList()
        val index = keys.indexOf(key)
        if (index < 0 || target !in keys.indices || index == target) return
        keys.add(target, keys.removeAt(index))
        StreamCenterPlugin.saveHomeSectionOrder(sharedPref, keys)
        renderSections()
    }

    private fun confirmRemoveSection(section: StreamCenterHomeSectionDefinition) {
        val title = StreamCenterPlugin.getHomeSectionTitleTemplate(sharedPref, section)
        val message = if (StreamCenterPlugin.homeSections.any { it.key == section.key }) {
            "Eliminare «$title» dalla Home?"
        } else {
            "Eliminare la sezione «$title» dalla Home?"
        }
        showDialog(AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Elimina sezione"))
            .setMessage(message)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina") { _, _ ->
                StreamCenterPlugin.removeHomeSection(sharedPref, section.key)
                renderSections()
                saveToast("Sezione eliminata")
            }.create())
    }

    private fun resetSection(section: StreamCenterHomeSectionDefinition) {
        sharedPref?.edit()?.remove(StreamCenterPlugin.sectionEnabledKey(section.key))
            ?.remove(StreamCenterPlugin.sectionTitleKey(section.key))
            ?.remove(StreamCenterPlugin.sectionCountKey(section.key))?.apply()
    }

    private fun editSection(section: StreamCenterHomeSectionDefinition, removable: Boolean) {
        val sections = StreamCenterPlugin.getOrderedHomeSections(sharedPref)
        val defaultTitle = StreamCenterPlugin.getDefaultHomeSectionTitle(section)
        val title = input(StreamCenterPlugin.getHomeSectionTitleTemplate(sharedPref, section)).apply {
            layoutParams = verticalParams(top = 4)
        }
        val count = input(StreamCenterPlugin.getHomeSectionCount(sharedPref, section).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = verticalParams(top = 4)
        }
        val position = input((sections.indexOfFirst { it.key == section.key } + 1).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = verticalParams(top = 4)
        }
        resetOnLongPress(title, "Nome della sezione") { title.setText(defaultTitle) }
        resetOnLongPress(count, "Numero di titoli") { count.setText(section.defaultCount.toString()) }
        resetOnLongPress(position, "Posizione della sezione") {
            val defaults = StreamCenterPlugin.getAllHomeSections(sharedPref)
                .sortedBy { StreamCenterPlugin.homeSectionCategoryRank(sharedPref, it) }
            position.setText((defaults.indexOfFirst { it.key == section.key } + 1).toString())
        }
        val content = rootContainer().apply {
            addView(bodyText("Nome della sezione", 13)); addView(title)
            if (!removable) {
                addView(bodyText("Numero massimo di titoli per pagina", 13)); addView(count)
            } else addView(bodyText("La quantità di titoli per pagina è gestita dalla fonte.", 13))
            addView(bodyText("Posizione (1–${sections.size})", 13)); addView(position)
        }
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(24), 0)
            addView(dialogTitle("Modifica sezione").apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(iconButton(
                symbol = "ⓘ",
                description = "Segnaposto disponibili per i titoli",
                accent = COLOR_HOME,
                size = 30,
            ) { showTitlePlaceholdersDialog() })
        }
        val builder = AlertDialog.Builder(requireContext()).setCustomTitle(header).setView(content)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva") { _, _ ->
                val maximum = if (section.key in setOf("tv_top10", "movie_top10")) 10 else StreamCenterPlugin.MAX_HOME_COUNT
                val amount = (count.text.toString().toIntOrNull() ?: section.defaultCount)
                    .coerceIn(StreamCenterPlugin.MIN_HOME_COUNT, maximum)
                sharedPref?.edit()?.apply {
                    putString(StreamCenterPlugin.sectionTitleKey(section.key), title.text.toString().trim().ifBlank { defaultTitle })
                    if (!removable) putInt(StreamCenterPlugin.sectionCountKey(section.key), amount)
                }?.apply()
                val target = (position.text.toString().toIntOrNull() ?: 1).coerceIn(1, sections.size) - 1
                move(section.key, target)
                renderSections()
            }
        builder.setNeutralButton("Elimina") { _, _ ->
            confirmRemoveSection(section)
        }
        showDialog(builder.create())
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
            Triple("%Totale%", "Numero degli elementi trovati: disponibile in tutte le sezioni.", ""),
        )
        fun resolvePreview(value: String): String {
            return StreamCenterPlugin.resolveHomeTitlePlaceholders(value, calendar)
        }
        val exampleTitle = "Anime: calendario (%Giorno%)"
        val previewText = titleText(resolvePreview(exampleTitle), 14, false).apply {
            setTextColor(Color.parseColor(COLOR_TEXT))
            setPadding(0, dp(4), 0, 0)
        }
        val titleInput = input("").apply {
            hint = "Es. Anime: calendario (%Giorno%)"
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
        titlePlaceholdersDialog = dialog
        dialog.setOnDismissListener { titlePlaceholdersDialog = null }
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun chooseCatalog() {
        val catalogs = StreamCenterCatalogs.allCatalogs(sharedPref)
        showSourcePicker(
            title = "Scegli un catalogo", items = catalogs, accent = COLOR_CATALOGS,
            name = { it.title }, badge = { catalogSiteBadge(it, marginEnd = 12) },
        ) { catalog ->
            val sections = StreamCenterCatalogs.orderedSections(sharedPref, catalog).filter { section ->
                val service = section.trackingServiceKey ?: return@filter true
                val syncId = StreamCenterPlugin.trackingServices.firstOrNull { it.key == service }?.syncIdName
                AccountManager.syncApis.any { it.syncIdName == syncId && it.authData() != null }
            }
            chooseImports(catalog.title, sections.map { StreamCenterHomeImports.catalog(catalog, it) })
        }
    }

    private fun chooseExtension() {
        val extensions = InstalledExtensionSources.available(forHome = true)
        if (extensions.isEmpty()) {
            saveToast("Nessuna estensione installata espone sezioni Home")
            return
        }
        showSourcePicker(
            title = "Scegli un'estensione", items = extensions, accent = COLOR_SOURCES,
            name = { it.name }, badge = { installedExtensionBadge(it) }, summary = { it.summary },
        ) { extension ->
            val sources = extension.sources
            if (sources.size == 1) chooseExtensionRequest(sources.single().api)
            else showSourcePicker(
                title = extension.name, items = sources, accent = COLOR_SOURCES,
                name = { it.api.name }, badge = { installedExtensionBadge(extension) },
                summary = { it.api.lang.uppercase(Locale.ROOT) },
            ) { source -> chooseExtensionRequest(source.api) }
        }
    }

    private fun <T> showSourcePicker(
        title: String,
        items: List<T>,
        accent: String,
        name: (T) -> String,
        badge: (T) -> View,
        summary: (T) -> String? = { null },
        onSelected: (T) -> Unit,
    ) {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        lateinit var dialog: AlertDialog
        items.forEach { item ->
            val arrow = chevron(accent)
            content.addView(settingsRow(
                title = name(item), summary = summary(item)?.takeIf(String::isNotBlank), accent = accent,
                fillColor = COLOR_CARD_ALT, strokeColor = tint(accent, "66"),
                leadingView = badge(item), trailingViews = listOf(arrow),
                topMargin = 8, touchTarget = arrow,
            ) {
                dialog.dismiss()
                onSelected(item)
            }.view)
        }
        dialog = AlertDialog.Builder(requireContext()).setCustomTitle(dialogTitle(title))
            .setView(scrollableDialogView(content)).setNegativeButton("Annulla", null).create()
        showDialog(dialog)
    }

    private fun chooseExtensionRequest(api: MainAPI) {
        val requests = runCatching { api.mainPage.map { MainPageRequest(it.name, it.data, it.horizontalImages) } }
            .getOrDefault(emptyList())
        if (requests.isEmpty()) {
            discoverSections(api, listOf(MainPageRequest("Home", "", false)), selectionConfirmed = false)
            return
        }
        val checked = BooleanArray(requests.size)
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(requireContext()).setCustomTitle(dialogTitle(api.name))
            .setMultiChoiceItems(requests.map { it.name }.toTypedArray(), checked) { _, index, selected ->
                checked[index] = selected
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked.any { it }
            }
            .setPositiveButton("Aggiungi") { _, _ ->
                val selected = requests.filterIndexed { index, _ -> checked[index] }
                if (selected.isNotEmpty()) discoverSections(api, selected, selectionConfirmed = true)
            }
            .setNegativeButton("Annulla", null).create()
        showDialog(dialog) { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false }
    }

    private fun discoverSections(api: MainAPI, requests: List<MainPageRequest>, selectionConfirmed: Boolean) {
        discoveryJob?.cancel()
        val progress = AlertDialog.Builder(requireContext()).setTitle(api.name)
            .setMessage("Caricamento delle sezioni…").setNegativeButton("Annulla") { _, _ -> discoveryJob?.cancel() }
            .create().apply { setOnCancelListener { discoveryJob?.cancel() } }
        showDialog(progress)
        discoveryJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    StreamCenterVpnGuard.requireInternetAccess(sharedPref)
                    StreamCenterHomeImports.discoverExtensionSections(api, requests)
                }
            }
            progress.dismiss()
            val discovery = result.getOrNull()
            if (discovery == null || discovery.sections.isEmpty()) saveToast("Sezioni non disponibili. Riprova più tardi.")
            else {
                if (selectionConfirmed) {
                    addImports(discovery.sections, partiallyUnavailable = discovery.unavailableRequests.isNotEmpty())
                } else {
                    chooseImports(api.name, discovery.sections)
                }
            }
        }
    }

    private fun addImports(sections: List<StreamCenterHomeImport>, partiallyUnavailable: Boolean = false) {
        val existing = StreamCenterHomeImports.read(sharedPref).map { it.key }.toSet()
        val available = sections.distinctBy { it.key }.filterNot { it.key in existing }
        if (available.isEmpty()) {
            saveToast("Nessuna nuova sezione disponibile da aggiungere")
            return
        }
        StreamCenterHomeImports.add(sharedPref, available)
        renderSections()
        saveToast(if (partiallyUnavailable) "Sezioni aggiunte; alcune non sono disponibili" else "Sezioni aggiunte al catalogo base")
    }

    private fun chooseImports(title: String, candidates: List<StreamCenterHomeImport>) {
        val existing = StreamCenterHomeImports.read(sharedPref).map { it.key }.toSet()
        val available = candidates.distinctBy { it.key }.filterNot { it.key in existing }
        if (available.isEmpty()) {
            saveToast("Nessuna nuova sezione disponibile da aggiungere")
            return
        }
        val checked = BooleanArray(available.size)
        showDialog(AlertDialog.Builder(requireContext()).setTitle(title)
            .setMultiChoiceItems(available.map { it.title }.toTypedArray(), checked) { _, index, selected -> checked[index] = selected }
            .setPositiveButton("Aggiungi") { _, _ ->
                val selected = available.filterIndexed { index, _ -> checked[index] }
                if (selected.isNotEmpty()) addImports(selected)
            }.setNegativeButton("Annulla", null).create())
    }

    private fun showDialog(dialog: AlertDialog, onShow: (() -> Unit)? = null) {
        activeDialog = dialog
        applyDialogBackdrop(dialog, onShow = onShow)
        dialog.show()
    }

    override fun onDestroyView() {
        discoveryJob?.cancel()
        titlePlaceholdersDialog?.dismiss()
        titlePlaceholdersDialog = null
        activeDialog?.dismiss()
        activeDialog = null
        listContainer = null
        super.onDestroyView()
    }
}
