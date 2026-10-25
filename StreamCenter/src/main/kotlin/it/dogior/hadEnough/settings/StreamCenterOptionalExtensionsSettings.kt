package it.dogior.hadEnough.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StreamCenterOptionalExtensionsSettingsFragment : StreamCenterBaseSettingsFragment() {
    override val screenTitle = "Estensioni opzionali"
    override val screenIcon = "🧩"
    private var entriesContainer: LinearLayout? = null
    private var updateStatus: TextView? = null
    private var checkingUpdates = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = rootContainer()
        content.addView(bodyText("Scegli cosa installare e gestisci le impostazioni qui, in StreamCenter."))
        updateStatus = bodyText("Controllo e aggiornamento delle estensioni…").also(content::addView)
        entriesContainer = groupColumn().also(content::addView)
        checkingUpdates = true
        renderEntries()
        return scroll(content, fixedSubmenuHeight = true)
    }

    private fun renderEntries() {
        val container = entriesContainer ?: return
        container.removeAllViews()
        StreamCenterOptionalExtensions.entries.forEach { entry ->
            val status = if (StreamCenterOptionalExtensions.isInstalled(entry)) {
                "Installato · ${StreamCenterOptionalExtensions.installedVersionLabel(entry)}"
            } else "Da installare"
            container.addView(navigationRow(entry.name, "${entry.credit} · $status\n${entry.summary}", entry.icon,
                leadingView = optionalExtensionBadge(entry)) {
                if (!checkingUpdates) {
                    openScreen(StreamCenterOptionalExtensionSettingsFragment.forExtension(entry.key), "StreamCenterOptional:${entry.key}")
                }
            }.apply { isEnabled = !checkingUpdates })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val hostActivity = requireActivity()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                runCatchingCancellable {
                    withContext(Dispatchers.IO) { StreamCenterOptionalExtensionUpdates.refresh(hostActivity, sharedPref) }
                }
            } finally {
                checkingUpdates = false
            }
            val report = result.getOrNull()
            updateStatus?.apply {
                visibility = if (report?.checked == 0) View.GONE else View.VISIBLE
                text = if (report == null) result.exceptionOrNull()?.message ?: "Controllo aggiornamenti non riuscito"
                else buildList {
                    if (report.updated.isNotEmpty()) add("Aggiornate: ${report.updated.joinToString()}")
                    if (report.failed.isNotEmpty()) add("Controllo o aggiornamento non riuscito: ${report.failed.joinToString()}. Riapri questa schermata per riprovare.")
                    if (isEmpty()) add("Estensioni già aggiornate")
                }.joinToString("\n")
            }
            renderEntries()
        }
    }

    override fun onDestroyView() {
        entriesContainer = null
        updateStatus = null
        super.onDestroyView()
    }
}

class StreamCenterOptionalExtensionSettingsFragment : StreamCenterBaseSettingsFragment() {
    companion object {
        private const val ARG_KEY = "optionalExtension"
        private const val PANEL_ID = "optionalPanelId"
        private const val PANEL_TAG = "optionalPanel"

        internal fun forExtension(key: String) = StreamCenterOptionalExtensionSettingsFragment().apply {
            arguments = Bundle().apply { putString(ARG_KEY, key) }
        }
    }

    private val entry get() = StreamCenterOptionalExtensions.entry(requireArguments().getString(ARG_KEY).orEmpty())
        ?: error("Estensione opzionale non disponibile")
    override val screenTitle get() = entry.name
    override val screenIcon get() = entry.icon
    private var panelId = 0
    private var content: LinearLayout? = null
    private var panelHost: FrameLayout? = null
    private var installing = false
    private var uninstalling = false
    private var uninstallDialog: AlertDialog? = null
    private var callbackManager: Any? = null
    private var callbackAccess: StreamCenterExtensionSettingsAccess? = null
    private var previousCallbacks = emptyMap<String, Any?>()
    private var ownedCallbacks = emptyMap<String, Any?>()
    private val callbackNames = listOf("OnStatusText", "OnPeerConnected", "OnConnectionStateChanged", "OnParticipantsChanged")

    private val panelCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, state: Bundle?) {
            if (fragment.tag != PANEL_TAG) return
            val plugin = StreamCenterOptionalExtensions.loaded(entry) ?: return
            pluginView(view, plugin, entry.closeViewId)?.visibility = View.GONE
            if (entry.name == "WatchParty") {
                pluginView(view, plugin, "wp_settings_card")?.setOnClickListener {
                    openScreen(forExtension(StreamCenterOptionalExtensions.WATCH_ADVANCED), "StreamCenterWatchPartyAdvanced")
                }
                val access = callbackAccess ?: StreamCenterExtensionSettingsAccess(plugin)
                val manager = callbackManager ?: access.watchPartyManager(plugin)
                ownedCallbacks = callbackNames.associateWith { access.call(manager, "get$it") }
                pluginView(view, plugin, "wp_leave")?.setOnClickListener {
                    runCatching { access.call(manager, "leaveRoom"); reloadPanel() }
                        .onFailure { saveToast("Impossibile lasciare la stanza WatchParty") }
                }
            }
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, fragment: Fragment) {
            if (fragment.tag == PANEL_TAG) restoreWatchPartyCallbacks()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        panelId = savedInstanceState?.getInt(PANEL_ID)?.takeIf { it != 0 } ?: View.generateViewId()
        childFragmentManager.fragmentFactory = object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
                if (className == entry.panelClass) {
                    return runCatching { createPanel() }.getOrNull() ?: Fragment()
                }
                return super.instantiate(classLoader, className)
            }
        }
        childFragmentManager.registerFragmentLifecycleCallbacks(panelCallbacks, false)
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(PANEL_ID, panelId)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        captureWatchPartyCallbacks()
        return rootContainer().apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            content = this
            render()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mountPanel()
    }

    private fun render() {
        val root = content ?: return
        root.removeAllViews()
        val installed = StreamCenterOptionalExtensions.isInstalled(entry)
        val version = if (installed) " · ${StreamCenterOptionalExtensions.installedVersionLabel(entry)}" else ""
        root.addView(bodyText("${entry.credit}$version\n${entry.summary}"))
        panelHost = FrameLayout(requireContext()).apply {
            id = panelId
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val plugin = StreamCenterOptionalExtensions.loaded(entry)
        val downloadRunning = installing || StreamCenterOptionalExtensions.isInstalling(entry)
        if (uninstalling) {
            root.addView(bodyText("Disinstallazione di ${entry.name} in corso…"))
            root.addView(panelHost)
            return
        }
        if (!installed) {
            root.addView(settingsRow(
                title = if (downloadRunning) "Download in corso…" else "Scarica ${entry.name}",
                summary = "Installazione facoltativa · ${entry.credit}", icon = "↓", accent = COLOR_ACCENT,
                leadingView = optionalExtensionBadge(entry),
            ) { install() }.view.apply { isEnabled = !downloadRunning })
        } else if (plugin == null) {
            root.addView(bodyText("Installato. Riavvia CloudStream per caricare le impostazioni in StreamCenter."))
        } else if (entry.key == StreamCenterOptionalExtensions.PRISYNX) {
            val extension = StreamCenterPriSynx.installed()!!
            root.addView(switchRow(
                title = "Usa le fonti di PriSynx", summary = "Fonti video aggiuntive per StreamCenter",
                checked = extension.isEnabled(InstalledExtensionSources.enabledKeys(sharedPref)),
                defaultChecked = false, accent = COLOR_ACCENT,
            ) { enabled -> InstalledExtensionSources.setEnabled(sharedPref, extension, enabled) })
            root.addView(navigationRow("Aggiungi cataloghi alla Home", "Scegli e riordina le sezioni in StreamCenter", "+") {
                val catalog = StreamCenterPriSynx.installed(forHome = true)
                if (catalog == null) saveToast("Cataloghi PriSynx non disponibili")
                else openScreen(StreamCenterBaseCatalogSettingsFragment.forExtension(catalog.key), "StreamCenterPriSynxCatalogs")
            })
            runCatching { StreamCenterPriSynxPanel.create(requireContext(), plugin) }
                .onSuccess { panel -> panelHost?.addView(panel, matchParent()) }
                .onFailure {
                    root.addView(navigationRow("Impostazioni PriSynx", "Provider, ordine, Home, TopX, Kitsu, RaiPlay e torrent", "⚙️") {
                        openScreen(StreamCenterPriSynxSettingsFragment(), "StreamCenterPriSynxSettings")
                    })
                }
        }
        if (installed && entry.key != StreamCenterOptionalExtensions.WATCH_ADVANCED) {
            root.addView(settingsRow(
                title = "Disinstalla ${entry.name}", summary = "Rimuovi l'estensione da CloudStream",
                icon = "🗑️", accent = COLOR_DANGER,
            ) { confirmUninstall() }.view.apply { isEnabled = !downloadRunning })
        }
        root.addView(panelHost)
    }

    private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun confirmUninstall() {
        if (installing || uninstalling || StreamCenterOptionalExtensions.isInstalling(entry)) return
        uninstallDialog?.dismiss()
        uninstallDialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Disinstalla ${entry.name}", COLOR_DANGER))
            .setMessage("Disinstallare ${entry.name} da CloudStream?")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Disinstalla") { _, _ -> uninstall() }
            .create().also { dialog -> applyDialogBackdrop(dialog); dialog.show() }
    }

    private fun uninstall() {
        if (installing || uninstalling || StreamCenterOptionalExtensions.isInstalling(entry) || childFragmentManager.isStateSaved) return
        childFragmentManager.findFragmentByTag(PANEL_TAG)?.let {
            childFragmentManager.beginTransaction().remove(it).commitNow()
        }
        uninstalling = true
        render()
        lifecycleScope.launch {
            val result = try {
                runCatchingCancellable {
                    withContext(NonCancellable) {
                        val removed = withContext(Dispatchers.IO) { StreamCenterOptionalExtensionUninstall.uninstall(entry) }
                        if (entry.name == "SleepGuard") markRestartNeeded()
                        removed
                    }
                }
            } finally {
                uninstalling = false
            }
            if (!isAdded || view == null) return@launch
            render()
            mountPanel()
            val needsRestart = result.isSuccess && entry.name == "SleepGuard"
            saveToast(result.exceptionOrNull()?.message ?: if (needsRestart) {
                "SleepGuard disinstallato. Riavvia CloudStream per arrestare il controllo di inattività."
            } else "${entry.name} disinstallato")
        }
    }

    private fun install() {
        val hostActivity = activity ?: return
        if (installing || uninstalling || StreamCenterOptionalExtensions.isInstalling(entry)) return
        installing = true
        render()
        lifecycleScope.launch {
            val result = try {
                runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        if (entry.key == StreamCenterOptionalExtensions.PRISYNX) StreamCenterPriSynx.install(hostActivity, sharedPref)
                        else StreamCenterUtilityExtensions.install(StreamCenterOptionalExtensions.utility(entry)!!, hostActivity, sharedPref)
                    }
                }
            } finally {
                installing = false
            }
            if (!isAdded || view == null) return@launch
            render()
            mountPanel()
            saveToast(result.exceptionOrNull()?.message ?: "${entry.name} installato · impostazioni disponibili qui")
        }
    }

    private fun mountPanel() {
        if (uninstalling || entry.panelClass == null || StreamCenterOptionalExtensions.loaded(entry) == null || childFragmentManager.isStateSaved) return
        childFragmentManager.findFragmentByTag(PANEL_TAG)?.takeIf { it.javaClass.name != entry.panelClass }?.let {
            childFragmentManager.beginTransaction().remove(it).commitNow()
        }
        if (childFragmentManager.findFragmentByTag(PANEL_TAG) == null) {
            runCatching {
                captureWatchPartyCallbacks()
                childFragmentManager.beginTransaction().replace(panelId, createPanel(), PANEL_TAG).commitNow()
            }.onFailure { showPanelError() }
        }
        panelHost?.post {
            if (isAdded && view != null && childFragmentManager.findFragmentByTag(PANEL_TAG)?.view == null) showPanelError()
        }
    }

    private fun reloadPanel() {
        childFragmentManager.findFragmentByTag(PANEL_TAG)?.let {
            childFragmentManager.beginTransaction().remove(it).commitNow()
        }
        mountPanel()
    }

    private fun createPanel(): DialogFragment {
        val plugin = StreamCenterOptionalExtensions.loaded(entry) ?: error("Estensione non caricata")
        if (entry.name == "WatchParty") {
            check(listOf("wp_settings_card", "wp_leave").all {
                (plugin.resources?.getIdentifier(it, "id", "it.dogior.hadEnough") ?: 0) != 0
            }) { "Pannello WatchParty non compatibile" }
        }
        val access = StreamCenterExtensionSettingsAccess(plugin)
        if (entry.name == "WatchParty") {
            val manager = access.watchPartyManager(plugin)
            callbackNames.forEach { access.call(manager, "get$it") }
        }
        val type = plugin.javaClass.classLoader!!.loadClass(entry.panelClass!!)
        val args: Array<Any?> = when {
            entry.key == StreamCenterOptionalExtensions.WATCH_ADVANCED -> arrayOf(plugin, null)
            entry.name == "WatchParty" -> arrayOf(plugin, access.watchPartyManager(plugin))
            else -> arrayOf(plugin)
        }
        val constructor = type.constructors.single { it.parameterTypes.size == args.size }
        return (constructor.newInstance(*args) as DialogFragment).apply { showsDialog = false }
    }

    private fun pluginView(root: View, plugin: Plugin, name: String?): View? {
        name ?: return null
        val id = plugin.resources?.getIdentifier(name, "id", "it.dogior.hadEnough") ?: return null
        return root.findViewById(id)
    }

    private fun captureWatchPartyCallbacks() {
        if (entry.name != "WatchParty" || previousCallbacks.isNotEmpty()) return
        runCatching {
            val plugin = StreamCenterOptionalExtensions.loaded(entry) ?: return
            val access = StreamCenterExtensionSettingsAccess(plugin)
            val manager = access.watchPartyManager(plugin)
            callbackAccess = access
            callbackManager = manager
            previousCallbacks = callbackNames.associateWith { access.call(manager, "get$it") }
        }
    }

    private fun restoreWatchPartyCallbacks() {
        val manager = callbackManager ?: return
        val access = callbackAccess ?: return
        ownedCallbacks.forEach { (name, callback) ->
            runCatching {
                if (access.call(manager, "get$name") === callback) access.call(manager, "set$name", previousCallbacks[name])
            }
        }
        ownedCallbacks = emptyMap()
        previousCallbacks = emptyMap()
        callbackManager = null
        callbackAccess = null
    }

    private fun showPanelError() {
        panelHost?.apply {
            removeAllViews()
            addView(bodyText("Il pannello di ${entry.name} non è compatibile con questa versione. Aggiorna l'estensione e riapri StreamCenter."))
        }
    }

    override fun onDestroyView() {
        uninstallDialog?.dismiss()
        uninstallDialog = null
        restoreWatchPartyCallbacks()
        content = null
        panelHost = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        childFragmentManager.unregisterFragmentLifecycleCallbacks(panelCallbacks)
        super.onDestroy()
    }
}
