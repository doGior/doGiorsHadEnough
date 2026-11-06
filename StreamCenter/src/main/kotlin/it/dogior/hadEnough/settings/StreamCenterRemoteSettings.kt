package it.dogior.hadEnough.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.SearchResponse
import it.dogior.hadEnough.StreamCenter
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.activeMainProvider
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncPresence
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncStorage
import it.dogior.hadEnough.remote.RemoteCard
import it.dogior.hadEnough.remote.RemoteCommand
import it.dogior.hadEnough.remote.RemoteHandoff
import it.dogior.hadEnough.remote.RemotePlayerState
import it.dogior.hadEnough.remote.RemoteQueueItem
import it.dogior.hadEnough.remote.RemoteResumeItem
import it.dogior.hadEnough.remote.StreamCenterRemote
import it.dogior.hadEnough.remote.StreamCenterRemoteClient
import it.dogior.hadEnough.remote.StreamCenterRemoteClientListener
import it.dogior.hadEnough.remote.StreamCenterRemoteHistory
import it.dogior.hadEnough.remote.StreamCenterRemoteNavigator
import it.dogior.hadEnough.remote.StreamCenterRemoteQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

internal class StreamCenterRemoteSettingsFragment : StreamCenterBaseSettingsFragment(), StreamCenterRemoteClientListener {
    override val screenTitle: String = "Telecomando"

    override val screenIcon: String = "🧪"

    override val screenAccent: String get() = COLOR_EXPERIMENTAL

    private val mainHandler = Handler(Looper.getMainLooper())
    private var client: StreamCenterRemoteClient? = null
    private var deviceRow: SettingsRowViews? = null
    private var receivers: List<StreamCenterLocalSyncPresence> = emptyList()
    private var playerContainer: LinearLayout? = null
    private var historyContainer: LinearLayout? = null
    private var searchResults: LinearLayout? = null
    private var queueContainer: LinearLayout? = null
    private var playerViews: PlayerViews? = null
    private var playerMode: PlayerMode? = null
    private var pendingSeek: Runnable? = null
    private var lastState: RemotePlayerState? = null
    private var connected = false
    private var screenContent: LinearLayout? = null
    private var remoteSwitch: View? = null
    private var scanJob: Job? = null
    private var scanGeneration = 0
    private val rescan = Runnable { if (!connected) scan() }
    private val queueListener: () -> Unit = { mainHandler.post { renderQueue() } }

    private enum class PlayerMode { PLAYING, PARKED, IDLE, OFFLINE }

    private class PlayerViews(
        val title: TextView,
        val detail: TextView,
        val time: TextView,
        val seekBar: SeekBar,
        val playPause: TextView,
        val subtitles: TextView,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer().apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
            minimumHeight = standardSubmenuMinimumHeight()
        }
        screenContent = content
        populate(content)
        return scroll(content, fixedSubmenuHeight = true)
    }

    private fun rebuild() {
        val content = screenContent ?: return
        if (!isAdded) return
        releaseLiveViews()
        content.removeAllViews()
        populate(content)
        if (isTvLikeDevice()) remoteSwitch?.requestFocus()
    }

    private fun populate(content: LinearLayout) {
        content.addView(experimentalBanner())
        content.addView(remoteSwitchRow().also { remoteSwitch = it })
        if (!StreamCenterRemote.isEnabled()) {
            content.addView(setupSteps(paired = false))
            return
        }
        if (!StreamCenterRemote.hasTrustedPeers(requireContext())) {
            content.addView(setupSteps(paired = false))
            content.addView(navigationRow(
                title = "Associa un dispositivo",
                summary = "Apri «Sync tra dispositivi» e collega telefono e TV",
                icon = "🔗",
                accent = COLOR_EXPERIMENTAL,
            ) { openScreen(StreamCenterLocalSyncSettingsFragment(), "StreamCenterLocalSyncSettings") })
            return
        }
        if (isTvLikeDevice()) return tvContent(content)

        content.addView(sectionHeading("TV"))
        deviceRow = settingsRow(
            title = "🟡 Cerco la TV…",
            summary = SEARCH_HINT,
            icon = "📺",
            accent = COLOR_EXPERIMENTAL,
            trailingViews = listOf(iconButton("🔄", "Cerca di nuovo", COLOR_EXPERIMENTAL) { scan() }),
            touchTarget = null,
        ) { if (receivers.size > 1) chooseDevice() else scan() }.also { content.addView(it.view) }
        playerContainer = verticalSection().also(content::addView)

        content.addView(sectionHeading("Cerca e apri sulla TV"))
        content.addView(sectionHint("Tocca un titolo per aprirlo sulla TV, ➕ per metterlo in coda"))
        content.addView(searchBar())
        searchResults = verticalSection().also(content::addView)

        content.addView(sectionHeading("Continua sulla TV"))
        content.addView(sectionHint("Riparte dal punto in cui eri arrivato sul telefono"))
        historyContainer = verticalSection().also(content::addView)

        content.addView(sectionHeading("Prossimi da guardare"))
        queueContainer = verticalSection().also(content::addView)

        client = StreamCenterRemoteClient(requireContext(), this)
        StreamCenterRemoteQueue.addListener(queueListener)
        renderPlayer(null, parked = false)
        renderHistory()
        renderQueue()
        scan()
    }

    private fun tvContent(content: LinearLayout) {
        content.addView(sectionHeading("Questa TV"))
        content.addView(settingsRow(
            title = "🟢 Pronta a ricevere",
            summary = "Sul telefono apri Telecomando e scegli «${StreamCenterLocalSyncStorage.deviceName()}». " +
                "Tieni StreamCenter aperto qui mentre lo usi",
            icon = "📺",
            accent = COLOR_EXPERIMENTAL,
        ).view)
        content.addView(sectionHeading("Prossimi da guardare"))
        queueContainer = verticalSection().also(content::addView)
        StreamCenterRemoteQueue.addListener(queueListener)
        renderQueue()
    }

    override fun onDestroyView() {
        releaseLiveViews()
        screenContent = null
        remoteSwitch = null
        super.onDestroyView()
    }

    private fun releaseLiveViews() {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        mainHandler.removeCallbacks(rescan)
        StreamCenterRemoteQueue.removeListener(queueListener)
        pendingSeek?.let(mainHandler::removeCallbacks)
        pendingSeek = null
        client?.close()
        client = null
        connected = false
        receivers = emptyList()
        lastState = null
        deviceRow = null
        playerContainer = null
        historyContainer = null
        searchResults = null
        queueContainer = null
        playerViews = null
        playerMode = null
    }

    override fun onStatus(message: String, connected: Boolean) {
        mainHandler.post {
            if (client == null) return@post
            this.connected = connected
            showDevice("${if (connected) "🟢" else "🟡"} ${client?.target?.name ?: "TV"}", message)
            if (!connected) renderPlayer(null, parked = false)
        }
    }

    override fun onState(state: RemotePlayerState?, parked: Boolean) {
        mainHandler.post { if (client != null) renderPlayer(state, parked) }
    }

    override fun onHandoff(handoff: RemoteHandoff?) {
        mainHandler.post {
            if (handoff == null) {
                saveToast("Sulla TV non c'è niente da riprendere")
                return@post
            }
            closeSettingsForNavigation() ?: return@post
            StreamCenterRemoteNavigator.play(handoff)
        }
    }

    private fun verticalSection(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = verticalParams(top = 2)
    }

    private fun remoteSwitchRow(): View = switchRow(
        title = "Telecomando e continuità",
        summary = "Usa il telefono come telecomando della TV",
        checked = StreamCenterRemote.isEnabled(),
        defaultChecked = false,
        accent = COLOR_EXPERIMENTAL,
        icon = "🧪",
    ) { enabled ->
        sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_REMOTE_CONTROL, enabled) }
        StreamCenterRemote.refresh()
        saveToast(if (enabled) "Telecomando attivo" else "Telecomando disattivato")
        mainHandler.post { rebuild() }
    }

    private fun sectionHint(text: String): View = bodyText(text, 12).apply {
        alpha = 0.75f
        layoutParams = verticalParams(top = 2)
    }

    private fun setupSteps(paired: Boolean): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = cardBackground(COLOR_CARD, tint(COLOR_EXPERIMENTAL, "55"), 14)
        layoutParams = verticalParams(top = 8)
        isFocusable = true
        addView(titleText("Come si usa", 15))
        listOf(
            "1. Attiva «Telecomando e continuità» sia sul telefono sia sulla TV",
            if (paired) "2. Telefono e TV sono già associati ✓" else "2. Associa i due dispositivi in «Sync tra dispositivi»",
            "3. Apri StreamCenter sulla TV: da qui la comandi e le mandi titoli e fonti",
        ).forEach { step -> addView(bodyText(step, 13).apply { layoutParams = verticalParams(top = 6) }) }
    }

    private fun showDevice(title: String, summary: String) {
        val row = deviceRow ?: return
        row.title.text = title
        row.summary?.text = if (receivers.size > 1) "$summary · tocca per cambiare" else summary
    }

    private fun scan() {
        val remote = client ?: return
        scanJob?.cancel()
        val generation = ++scanGeneration
        showDevice("Cerco la TV…", SEARCH_HINT)
        scanJob = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                try { remote.scan() } catch (error: CancellationException) { throw error }
                catch (_: Exception) { emptyList() }
            }
            if (generation != scanGeneration || client !== remote || screenContent == null) return@launch
            receivers = found
            val current = remote.target
            when {
                found.isEmpty() -> {
                    showDevice("🔴 Nessuna TV trovata", "Apri StreamCenter sulla TV: riprovo da solo ogni ${RESCAN_INTERVAL_MS / 1_000} s")
                    mainHandler.removeCallbacks(rescan)
                    mainHandler.postDelayed(rescan, RESCAN_INTERVAL_MS)
                }
                current == null && found.size == 1 -> remote.connect(found.first())
                current == null -> showDevice("${found.size} dispositivi trovati", "Tocca per scegliere quale comandare")
                found.none { it.identityKey == current.identityKey } -> remote.connect(found.first())
                else -> showDevice("🟢 ${current.name}", "Collegato")
            }
        }
    }

    private fun chooseDevice() {
        val selected = receivers.indexOfFirst { it.identityKey == client?.target?.identityKey }
        singleChoice("Scegli la TV", receivers.map { it.name }, selected) { client?.connect(receivers[it]) }
    }

    private fun renderPlayer(state: RemotePlayerState?, parked: Boolean) {
        val container = playerContainer ?: return
        lastState = state
        val mode = when {
            state != null -> PlayerMode.PLAYING
            parked -> PlayerMode.PARKED
            connected -> PlayerMode.IDLE
            else -> PlayerMode.OFFLINE
        }
        if (mode != playerMode) {
            playerMode = mode
            playerViews = null
            container.removeAllViews()
            when (mode) {
                PlayerMode.PLAYING -> playerViews = createPlayerViews(container)
                PlayerMode.PARKED -> container.addView(navigationRow(
                    title = "Riprendi qui",
                    summary = "Continua sul telefono ciò che hai lasciato sulla TV",
                    icon = "📱",
                    accent = COLOR_EXPERIMENTAL,
                ) { takeOver() })
                PlayerMode.IDLE -> container.addView(emptyStateCard(
                    "Sulla TV non c'è niente in riproduzione: cerca un titolo qui sotto",
                    COLOR_EXPERIMENTAL,
                    "⏸",
                ))
                PlayerMode.OFFLINE -> Unit
            }
        }
        val views = playerViews ?: return
        state ?: return
        views.title.text = state.title
        views.detail.text = listOfNotNull(
            state.season?.let { "Stagione $it" },
            state.episode?.let { "Episodio $it" },
        ).joinToString(" · ").ifBlank { "Film" }
        views.time.text = "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}"
        views.playPause.text = if (state.playing) "⏸ Pausa" else "▶ Play"
        if (pendingSeek == null && !views.seekBar.isPressed) {
            views.seekBar.max = (state.durationMs / 1_000L).toInt().coerceAtLeast(1)
            views.seekBar.progress = (state.positionMs / 1_000L).toInt()
        }
        views.subtitles.text = "💬 " + (state.subtitles.firstOrNull { it.id == state.selectedSubtitle }?.label ?: "Sottotitoli: no")
    }

    private fun createPlayerViews(container: LinearLayout): PlayerViews {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(COLOR_CARD, tint(COLOR_EXPERIMENTAL, "55"), 14)
            layoutParams = verticalParams(top = 8)
        }.also(container::addView)
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }.also(root::addView)
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.also(header::addView)
        val title = titleText("", 16).also(texts::addView)
        val detail = bodyText("", 12).also(texts::addView)
        val time = bodyText("", 12).also(header::addView)
        val seekBar = SeekBar(ctx).apply {
            layoutParams = verticalParams(top = 6)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) scheduleSeek(progress * 1_000L)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }.also(root::addView)
        val controls = buttonRow(root)
        controls.addWeighted(actionButton("⏪ Indietro", COLOR_EXPERIMENTAL) { client?.command(RemoteCommand.SeekBack) })
        val playPause = actionButton("⏯ Play", COLOR_EXPERIMENTAL) { client?.command(RemoteCommand.Toggle) }
            .also { controls.addWeighted(it) }
        controls.addWeighted(actionButton("⏩ Avanti", COLOR_EXPERIMENTAL) { client?.command(RemoteCommand.SeekForward) })
        val volume = buttonRow(root)
        volume.addWeighted(actionButton("🔉 Volume −", COLOR_EXPERIMENTAL) { client?.command(RemoteCommand.VolumeDown) })
        volume.addWeighted(actionButton("🔊 Volume +", COLOR_EXPERIMENTAL) { client?.command(RemoteCommand.VolumeUp) })
        val extras = buttonRow(root)
        val subtitles = actionButton("CC", COLOR_EXPERIMENTAL) { chooseSubtitles() }.also { extras.addWeighted(it) }
        extras.addWeighted(actionButton("📱 Continua qui", COLOR_EXPERIMENTAL) { takeOver() })
        return PlayerViews(title, detail, time, seekBar, playPause, subtitles)
    }

    private fun buttonRow(parent: LinearLayout): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = verticalParams(top = 8)
    }.also(parent::addView)

    private fun LinearLayout.addWeighted(view: View) {
        view.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (childCount > 0) marginStart = dp(8)
        }
        addView(view)
    }

    private fun scheduleSeek(positionMs: Long) {
        pendingSeek?.let(mainHandler::removeCallbacks)
        val action = Runnable {
            pendingSeek = null
            client?.command(RemoteCommand.SeekTo(positionMs))
        }
        pendingSeek = action
        mainHandler.postDelayed(action, SEEK_DEBOUNCE_MS)
    }

    private fun singleChoice(title: String, labels: List<String>, selected: Int, onChosen: (Int) -> Unit) {
        val alertDialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle(title, COLOR_EXPERIMENTAL))
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                onChosen(which)
                dialog.dismiss()
            }
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(alertDialog)
        alertDialog.show()
    }

    private fun chooseSubtitles() {
        val state = lastState ?: return
        val labels = listOf("Nessuno") + state.subtitles.map { it.label }
        val selected = state.subtitles.indexOfFirst { it.id == state.selectedSubtitle } + 1
        singleChoice("Sottotitoli", labels, selected) { which ->
            client?.command(RemoteCommand.Subtitle(if (which == 0) null else state.subtitles[which - 1].id))
        }
    }

    private fun takeOver() {
        if (client?.send(JSONObject().put("type", "takeOver")) != true) saveToast(NOT_CONNECTED)
    }

    private fun renderHistory() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { StreamCenterRemoteHistory.recent(MAX_HISTORY_ITEMS) }
            val container = historyContainer ?: return@launch
            container.removeAllViews()
            if (items.isEmpty()) {
                container.addView(emptyStateCard("Quello che guardi sul telefono compare qui", COLOR_EXPERIMENTAL, "▶"))
                return@launch
            }
            items.forEach { container.addView(historyRow(it)) }
        }
    }

    private fun historyRow(item: RemoteResumeItem): View {
        val handoff = item.handoff
        val episode = listOfNotNull(handoff.season?.let { "S$it" }, handoff.episode?.let { "E$it" }).joinToString(" ")
        val position = if (handoff.positionMs > 0) "da ${formatTime(handoff.positionMs)}" else "dall'inizio"
        val arrow = chevron(COLOR_EXPERIMENTAL)
        return settingsRow(
            title = handoff.name,
            summary = listOf(episode, position).filter(String::isNotBlank).joinToString(" · "),
            accent = COLOR_EXPERIMENTAL,
            leadingView = rowPoster(item.poster, "🎬", handoff.name),
            trailingViews = listOf(arrow),
            touchTarget = arrow,
        ) { playOnReceiver(handoff) }.view
    }

    private fun rowPoster(url: String?, fallbackIcon: String, description: String): View =
        posterThumb(url, fallbackIcon, description, widthDp = 40, heightDp = 58).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(12)
        }

    private fun playOnReceiver(handoff: RemoteHandoff) {
        val message = JSONObject().put("type", "play").put("handoff", handoff.toJson())
        if (client?.send(message) == true) saveToast("Riproduco «${handoff.name}» sulla TV") else saveToast(NOT_CONNECTED)
    }

    private fun searchBar(): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = verticalParams(top = 4)
        }
        val field = input("").apply {
            hint = "Titolo da cercare"
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) search(field.text.toString())
            actionId == EditorInfo.IME_ACTION_SEARCH
        }
        row.addView(field)
        row.addView(iconButton("🔎", "Cerca", COLOR_EXPERIMENTAL, size = 44) { search(field.text.toString()) })
        return row
    }

    private fun search(query: String) {
        val container = searchResults ?: return
        val text = query.trim().takeIf { it.length >= 2 } ?: return
        container.removeAllViews()
        container.addView(bodyText("Ricerca in corso…").apply { layoutParams = verticalParams(top = 8) })
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                runCatching { mainProvider()?.search(text).orEmpty() }.getOrDefault(emptyList())
            }
            container.removeAllViews()
            if (results.isEmpty()) {
                container.addView(emptyStateCard("Nessun risultato", COLOR_EXPERIMENTAL, "🔎"))
                return@launch
            }
            results.take(MAX_SEARCH_RESULTS).forEach { container.addView(searchRow(it)) }
        }
    }

    private fun searchRow(result: SearchResponse): View = settingsRow(
        title = result.name,
        summary = "Apri la scheda sulla TV",
        accent = COLOR_EXPERIMENTAL,
        leadingView = rowPoster(result.posterUrl, "🎬", result.name),
        trailingViews = listOf(iconButton("➕", "Aggiungi a Prossimi da guardare", COLOR_EXPERIMENTAL) {
            val added = StreamCenterRemoteQueue.add(
                requireContext(), result.url, result.apiName, result.name, result.posterUrl,
                StreamCenterLocalSyncStorage.deviceName(),
            )
            if (added != null) {
                client?.send(JSONObject().put("type", "queueAdd").put("item", added.toJson()))
                saveToast("Aggiunto a Prossimi da guardare")
            } else {
                saveToast("È già in Prossimi da guardare")
            }
        }),
        touchTarget = null,
    ) { openOnReceiver(result.url, result.apiName, result.name) }.view

    private fun renderQueue() {
        val container = queueContainer ?: return
        val items = StreamCenterRemoteQueue.items(requireContext())
        container.removeAllViews()
        if (items.isEmpty()) {
            val hint = if (isTvLikeDevice()) "Vuota: aggiungi titoli dal telefono" else "Vuota: aggiungi titoli con ➕ dalla ricerca"
            container.addView(emptyStateCard(hint, COLOR_EXPERIMENTAL, "📋"))
            return
        }
        items.forEachIndexed { index, item -> container.addView(queueRow(item, index, items.size)) }
    }

    private fun queueRow(item: RemoteQueueItem, index: Int, total: Int): View = settingsRow(
        title = item.name,
        summary = "Aggiunto da ${item.addedBy}",
        accent = COLOR_EXPERIMENTAL,
        leadingView = rowPoster(item.poster, "🎬", item.name),
        trailingViews = listOf(
            reorderIconButton("↑", "Sposta su", COLOR_EXPERIMENTAL, index > 0) {
                StreamCenterRemoteQueue.move(requireContext(), item.id, -1)
            },
            reorderIconButton("↓", "Sposta giù", COLOR_EXPERIMENTAL, index < total - 1) {
                StreamCenterRemoteQueue.move(requireContext(), item.id, 1)
            },
            deleteIconButton("Rimuovi da Prossimi da guardare") {
                StreamCenterRemoteQueue.remove(requireContext(), item.id)
                client?.send(JSONObject().put("type", "queueRemove").put("id", item.id))
            },
        ),
        touchTarget = null,
    ) { openQueueItem(item) }.view

    private fun openQueueItem(item: RemoteQueueItem) {
        if (!isTvLikeDevice()) return openOnReceiver(item.url, item.apiName, item.name)
        closeSettingsForNavigation() ?: return
        StreamCenterRemoteNavigator.openCard(RemoteCard(item.url, item.apiName, item.name))
    }

    private fun openOnReceiver(url: String, apiName: String, name: String) {
        val message = JSONObject().put("type", "open").put("url", url).put("apiName", apiName).put("name", name)
        if (client?.send(message) == true) saveToast("Apro «$name» sulla TV") else saveToast(NOT_CONNECTED)
    }

    private fun mainProvider(): StreamCenter? = activeMainProvider()

    private fun formatTime(ms: Long): String {
        val total = (ms / 1_000L).coerceAtLeast(0L)
        val hours = total / 3_600
        val minutes = (total % 3_600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.ITALIAN, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.ITALIAN, "%d:%02d", minutes, seconds)
    }

    private companion object {
        const val SEEK_DEBOUNCE_MS = 600L
        const val RESCAN_INTERVAL_MS = 10_000L
        const val MAX_SEARCH_RESULTS = 20
        const val MAX_HISTORY_ITEMS = 8
        const val SEARCH_HINT = "La TV deve avere StreamCenter aperto"
        const val NOT_CONNECTED = "Nessuna TV collegata"
    }
}
