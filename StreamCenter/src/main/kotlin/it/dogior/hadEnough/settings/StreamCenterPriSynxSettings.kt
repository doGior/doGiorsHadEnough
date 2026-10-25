package it.dogior.hadEnough.settings

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import it.dogior.hadEnough.extensions.StreamCenterExtensionSettingsAccess
import it.dogior.hadEnough.extensions.StreamCenterPriSynx
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import it.dogior.hadEnough.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StreamCenterPriSynxSettingsFragment : StreamCenterBaseSettingsFragment() {
    override val screenTitle = "Impostazioni PriSynx"
    override val screenIcon = "🧩"
    private var access: StreamCenterExtensionSettingsAccess? = null
    private var hub: Any? = null
    private var content: LinearLayout? = null
    private var scroller: ScrollView? = null
    private var activeDialog: AlertDialog? = null
    private var discoveryJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = rootContainer()
        content = root
        runCatching {
            val plugin = StreamCenterPriSynx.loaded() ?: error("PriSynx non è caricato")
            access = StreamCenterExtensionSettingsAccess(plugin)
            hub = access!!.singleton("prisynx.hub.HubSettings")
            render()
        }.onFailure {
            root.removeAllViews()
            root.addView(bodyText("Carica o aggiorna PriSynx per gestire le sue impostazioni in StreamCenter."))
        }
        return scroll(root, fixedSubmenuHeight = true).also { scroller = root.parent as? ScrollView }
    }

    private fun call(target: Any = hub!!, name: String, vararg arguments: Any?): Any? = access!!.call(target, name, *arguments)
    private fun list(name: String): List<Any> = (call(name = name) as List<*>).filterNotNull()
    private fun text(target: Any, name: String): String = call(target, name).toString()

    private fun render() {
        val root = content ?: return
        val position = scroller?.scrollY ?: 0
        root.removeAllViews()
        root.addView(bodyText("Autore: ${StreamCenterPriSynx.AUTHOR} · modifiche salvate direttamente in PriSynx"))
        root.addView(sectionHeading("Provider"))
        root.addView(settingsRow("Attiva tutti i provider", icon = "✓") {
            change { list("getSources").forEach { call(name = "setSourceEnabled", arguments = arrayOf<Any?>(text(it, "getId"), true)) }; render() }
        }.view)
        root.addView(settingsRow("Disattiva tutti i provider", icon = "−") {
            change { list("getSources").forEach { call(name = "setSourceEnabled", arguments = arrayOf<Any?>(text(it, "getId"), false)) }; render() }
        }.view)
        val providers = list("orderedSources")
        providers.forEachIndexed { index, provider ->
            val id = text(provider, "getId")
            val title = text(provider, "getLabel")
            val toggle = styledSwitch(call(name = "isSourceEnabled", arguments = arrayOf<Any?>(id)) as Boolean, COLOR_ACCENT) {
                enabled -> change { call(name = "setSourceEnabled", arguments = arrayOf<Any?>(id, enabled)) }
            }
            root.addView(settingsRow(title, "Priorità ${index + 1}", trailingViews = listOf(toggle) +
                orderButtons(title, index, providers.size) { direction ->
                    val updated = providers.toMutableList().apply { add(index + direction, removeAt(index)) }
                    change { call(name = "saveOrder", arguments = arrayOf<Any?>(updated)); render() }
                },
            ).view)
        }

        root.addView(sectionHeading("Ordine dei cataloghi PriSynx"))
        val order = list("homeOrder").map(Any::toString)
        val groups = list("getHomeGroups").associate { (it as Pair<*, *>).first.toString() to it.second.toString() }
        order.forEachIndexed { index, id ->
            val title = groups[id] ?: id
            root.addView(settingsRow(title, trailingViews = orderButtons(title, index, order.size) { direction ->
                val updated = order.toMutableList().apply { add(index + direction, removeAt(index)) }
                change { call(name = "saveHomeOrder", arguments = arrayOf<Any?>(updated)); render() }
            }).view)
        }
        root.addView(sectionHeading("Cataloghi TMDB"))
        list("getTmdbRows").forEach { row ->
            val title = text(call(row, "getRow")!!, "getName")
            booleanRow(root, title, call(name = "isTmdbRowEnabled", arguments = arrayOf<Any?>(row)) as Boolean) {
                call(name = "setTmdbRowEnabled", arguments = arrayOf<Any?>(row, it))
            }
        }

        root.addView(sectionHeading("TopX"))
        propertyToggle(root, "Mostra cataloghi TopX", "TopxEnabled")
        runCatching { call(name = "getTopxLandscape") as Boolean }.onSuccess { landscape ->
            root.addView(switchRow("Copertine orizzontali", summary = "Righe TopX in formato landscape",
                checked = landscape, accent = COLOR_ACCENT) { enabled ->
                change { call(name = "setTopxLandscape", arguments = arrayOf<Any?>(enabled)); clearTopxCache(); render() }
            })
        }
        root.addView(navigationRow("URL manifest TopX", "Modifica il catalogo di PriSynx", "🔗") { editTopxUrl() })
        root.addView(navigationRow("Scegli cataloghi TopX", "Attiva o disattiva i singoli cataloghi", "☑") { discoverRows(topx = true) })
        root.addView(settingsRow("Ripristina URL TopX", icon = "↺") {
            change {
                call(name = "setTopxUrl", arguments = arrayOf<Any?>(defaultTopxUrl()))
                clearTopxCache()
                saveToast("URL TopX ripristinato")
            }
        }.view)
        root.addView(settingsRow("Ricarica cataloghi TopX", icon = "↻") {
            change { clearTopxCache(); discoverRows(topx = true) }
        }.view)

        root.addView(sectionHeading("Anime · Kitsu"))
        propertyToggle(root, "Righe Kitsu nella Home", "KitsuHome")
        propertyToggle(root, "Anime Kitsu nella ricerca", "KitsuSearch")
        propertyToggle(root, "Nascondi anime da TMDB", "HideTmdbAnime")
        val kitsu = access!!.singleton("prisynx.providers.core.Kitsu")
        (call(kitsu, "getRows") as List<*>).filterNotNull().forEach { row ->
            val id = text(row, "getId")
            booleanRow(root, text(row, "getName"), call(name = "isKitsuRowEnabled", arguments = arrayOf<Any?>(id)) as Boolean) {
                call(name = "setKitsuRowEnabled", arguments = arrayOf<Any?>(id, it))
            }
        }

        root.addView(sectionHeading("RaiPlay"))
        propertyToggle(root, "Righe RaiPlay nella Home", "RaiHome")
        propertyToggle(root, "RaiPlay nella ricerca", "RaiSearch")
        root.addView(navigationRow("Scegli righe RaiPlay", "Film, serie, documentari e dirette", "☑") { discoverRows(topx = false) })

        root.addView(sectionHeading("Torrent"))
        val count = (call(name = "getTorrentMax") as Number).toInt()
        root.addView(settingsRow("Risultati IlCorsaroViola", "$count torrent al massimo", icon = "🧲") {
            val options = (1..20).map { SettingsChoiceOption(label = it.toString(), value = it, badge = "🧲") }
            showSettingsChoiceDialog("Risultati torrent", options, count, COLOR_ACCENT) { option ->
                change { call(name = "setTorrentMax", arguments = arrayOf<Any?>(option.value)); render() }
            }
        }.view)
        runCatching { call(name = "getSyncedOnly") as Boolean }.onSuccess { synced ->
            root.addView(sectionHeading("MovyITA e DualSync"))
            booleanRow(root, "Solo link sincronizzati", synced) { call(name = "setSyncedOnly", arguments = arrayOf<Any?>(it)) }
        }
        root.addView(settingsRow("Ripristina impostazioni PriSynx", "Provider, ordine e cataloghi", icon = "↺") {
            showDialog(AlertDialog.Builder(requireContext()).setTitle("Ripristina PriSynx")
                .setMessage("Ripristinare tutte le impostazioni di PriSynx?")
                .setNegativeButton("Annulla", null).setPositiveButton("Ripristina") { _, _ ->
                    change { call(name = "resetAll"); clearTopxCache(); render() }
                }.create())
        }.view)
        scroller?.post { scroller?.scrollTo(0, position) }
    }

    private fun orderButtons(title: String, index: Int, size: Int, move: (Int) -> Unit): List<View> = listOf(
        reorderIconButton("↑", "Sposta $title in alto", COLOR_ACCENT, index > 0) { move(-1) },
        reorderIconButton("↓", "Sposta $title in basso", COLOR_ACCENT, index < size - 1) { move(1) },
    )

    private fun booleanRow(root: LinearLayout, title: String, checked: Boolean, update: (Boolean) -> Unit) {
        root.addView(switchRow(title, checked = checked, accent = COLOR_ACCENT) { enabled -> change { update(enabled) } })
    }

    private fun propertyToggle(root: LinearLayout, title: String, property: String) =
        booleanRow(root, title, call(name = "get$property") as Boolean) { call(name = "set$property", arguments = arrayOf<Any?>(it)) }

    private fun defaultTopxUrl(): String = runCatching {
        call(name = "defaultTopxUrl", arguments = arrayOf<Any?>(call(name = "getTopxLandscape")))
    }.getOrElse { hub!!.javaClass.getField("DEFAULT_TOPX_URL").get(null) }.toString()

    private fun clearTopxCache() {
        call(access!!.singleton("prisynx.hub.TopX"), "clearCache")
    }

    private fun editTopxUrl() {
        val field = input(call(name = "getTopxUrl").toString()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        showDialog(AlertDialog.Builder(requireContext()).setTitle("URL manifest TopX")
            .setView(field).setNegativeButton("Annulla", null).setPositiveButton("Salva") { _, _ ->
                change {
                    val value = field.text.toString().trim().ifBlank { defaultTopxUrl() }
                    call(name = "setTopxUrl", arguments = arrayOf<Any?>(value)); clearTopxCache()
                    saveToast("URL TopX salvato")
                }
            }.create())
    }

    private fun discoverRows(topx: Boolean) {
        discoveryJob?.cancel()
        val progress = AlertDialog.Builder(requireContext()).setTitle(if (topx) "Cataloghi TopX" else "Righe RaiPlay")
            .setMessage("Caricamento…").setNegativeButton("Annulla") { _, _ -> discoveryJob?.cancel() }.create()
            .apply { setOnCancelListener { discoveryJob?.cancel() } }
        showDialog(progress)
        discoveryJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    StreamCenterVpnGuard.requireInternetAccess(sharedPref)
                    val owner = access!!.singleton(if (topx) "prisynx.hub.TopX" else "prisynx.hub.RaiHub")
                    val rows = if (topx) access!!.callSuspending(owner, "catalogs", call(name = "getTopxUrl"), false)
                        else access!!.callSuspending(owner, "rows", false)
                    (rows as List<*>).filterNotNull().map { row ->
                        if (topx) text(row, "getKey") to text(row, "getLabel")
                        else call(owner, "key", row).toString() to text(row, "getName")
                    }
                }
            }
            progress.dismiss()
            val rows = result.getOrNull()
            if (rows.isNullOrEmpty()) {
                saveToast("Elenco non disponibile. Controlla la connessione e riprova.")
                return@launch
            }
            val getter = if (topx) "isTopxCatalogEnabled" else "isRaiRowEnabled"
            val setter = if (topx) "setTopxCatalogEnabled" else "setRaiRowEnabled"
            change {
                val checked = BooleanArray(rows.size) { call(name = getter, arguments = arrayOf<Any?>(rows[it].first)) as Boolean }
                showDialog(AlertDialog.Builder(requireContext()).setTitle(if (topx) "Cataloghi TopX" else "Righe RaiPlay")
                    .setMultiChoiceItems(rows.map { it.second }.toTypedArray(), checked) { _, index, enabled -> checked[index] = enabled }
                    .setNegativeButton("Annulla", null).setPositiveButton("Salva") { _, _ ->
                        change { rows.forEachIndexed { index, row -> call(name = setter, arguments = arrayOf<Any?>(row.first, checked[index])) } }
                    }.create())
            }
        }
    }

    private fun showDialog(dialog: AlertDialog) {
        activeDialog?.dismiss()
        activeDialog = dialog
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun change(block: () -> Unit) {
        runCatching(block).onFailure { saveToast("Impostazione PriSynx non disponibile: aggiorna l'estensione e riprova.") }
    }

    override fun onDestroyView() {
        discoveryJob?.cancel()
        activeDialog?.dismiss()
        activeDialog = null
        content = null
        scroller = null
        hub = null
        access = null
        super.onDestroyView()
    }
}
