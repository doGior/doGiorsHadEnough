package it.dogior.hadEnough.settings

import java.util.Locale
import android.os.Bundle
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

class StreamCenterBaseCatalogSettingsFragment : StreamCenterSectionEditingFragment() {
    override val screenTitle = "Ordine delle sezioni"
    override val screenIcon = "⇅"
    private var listContainer: LinearLayout? = null
    private var discoveryJob: Job? = null

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
            "Usa le frecce per riordinare. Tocca una sezione per cambiarne nome, quantità e posizione", 13,
        ))
        content.addView(settingsRow(
            title = "Aggiungi da un catalogo", summary = "TMDB, MyAnimeList, Kitsu, AniList, Simkl e altro",
            icon = "+", accent = COLOR_ACCENT,
        ) { chooseCatalog() }.view)
        content.addView(settingsRow(
            title = "Aggiungi da un'estensione", summary = "Sezioni delle altre estensioni CloudStream installate",
            icon = "+", accent = COLOR_ACCENT,
        ) { chooseExtension() }.view)
        content.addView(sectionHeading("Ordine delle sezioni", COLOR_ACCENT))
        listContainer = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)
        renderSections()
        return scroll(content, fixedSubmenuHeight = true)
    }

    override fun onSectionsEdited() = renderSections()

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
            }.joinToString(", ")
            lateinit var sectionRow: View
            sectionRow = settingsRow(
                title = "${index + 1}. $title", summary = summary, accent = COLOR_ACCENT,
                enabledAppearance = sectionOn && categoryOn && available,
                trailingViews = listOf(
                    reorderIconButton("↑", "Sposta $title in alto", COLOR_ACCENT, index > 0) { moveSection(section.key, index - 1) },
                    reorderIconButton("↓", "Sposta $title in basso", COLOR_ACCENT, index < sections.lastIndex) { moveSection(section.key, index + 1) },
                    styledSwitch(
                        sectionOn, COLOR_ACCENT,
                        defaultChecked = section.defaultEnabled, resetTitle = title,
                    ) { enabled ->
                        sharedPref?.edit()?.putBoolean(StreamCenterPlugin.sectionEnabledKey(section.key), enabled)?.apply()
                        animateRowEnabledAppearance(sectionRow, enabled && categoryOn && available)
                    },
                ),
                onReset = { resetSection(section); renderSections() },
            ) { editSection(section, countManagedBySource = imported != null) { confirmRemoveSection(section) } }.view
            container.addView(sectionRow)
        }
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

    private fun chooseCatalog() {
        val catalogs = StreamCenterCatalogs.allCatalogs(sharedPref)
        showSourcePicker(
            title = "Scegli un catalogo", items = catalogs, accent = COLOR_ACCENT,
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
            title = "Scegli un'estensione", items = extensions, accent = COLOR_ACCENT,
            name = { it.name }, badge = { installedExtensionBadge(it) }, summary = { it.summary },
        ) { extension ->
            val sources = extension.sources
            if (sources.size == 1) chooseExtensionRequest(sources.single().api)
            else showSourcePicker(
                title = extension.name, items = sources, accent = COLOR_ACCENT,
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
                fillColor = COLOR_CARD_ALT,
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

    override fun onDestroyView() {
        discoveryJob?.cancel()
        listContainer = null
        super.onDestroyView()
    }
}
