package it.dogior.hadEnough.settings

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.InputFilter
import android.text.InputType
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncAutoConfig
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncAutoRunner
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncCategory
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncEvent
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncListener
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncManager
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncOffer
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncPayloadType
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncPeerMode
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncResult
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncState
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncTrust
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncTrustedPeer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val AUTO_SYNC_ROW_HEIGHT_DP = 80
private const val IDLE_SESSION_STATUS = "Nessuna sessione attiva al momento"

internal class StreamCenterLocalSyncSettingsFragment : StreamCenterBaseSettingsFragment(), StreamCenterLocalSyncListener {
    private lateinit var manager: StreamCenterLocalSyncManager
    private var autoCard: LinearLayout? = null
    private var autoExpanded = false
    private val autoDateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    private lateinit var statusText: TextView
    private lateinit var sessionClockText: TextView
    private lateinit var scheduleText: TextView
    private lateinit var pairingCodeText: TextView
    private lateinit var pairingCodeCard: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var logText: TextView
    private lateinit var logScroll: NestedScrollView
    private lateinit var sendButton: TextView
    private lateinit var receiveButton: TextView
    private lateinit var cancelButton: TextView
    private val eventLog = SpannableStringBuilder()
    private var activeOfferDialog: AlertDialog? = null
    private val sessionClockHandler = Handler(Looper.getMainLooper())
    private val sessionClockRunnable = object : Runnable {
        override fun run() {
            if (::sessionClockText.isInitialized) {
                sessionClockText.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val activeContext = context
                if (::scheduleText.isInitialized && activeContext != null) {
                    scheduleText.text = automaticSyncScheduleSummary(
                        StreamCenterLocalSyncAutoConfig.intervalMinutes(activeContext),
                    )
                }
                sessionClockHandler.postDelayed(this, 1_000L)
            }
        }
    }

    private data class IntervalRowViews(
        val view: View,
        val field: EditText,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        manager = StreamCenterLocalSyncManager(requireContext(), this)
        StreamCenterLocalSyncAutoRunner.attach(requireContext())
        StreamCenterLocalSyncAutoRunner.addListener(this)
        val content = rootContainer().apply {
            minimumHeight = standardSubmenuMinimumHeight()
        }
        content.addView(
            header(
                title = "Sync Locale",
                subtitle = "Trasferimento tra dispositivi sulla stessa rete",
                icon = "🔐",
                accent = COLOR_LOCAL_SYNC,
            ),
        )
        actionCard().also(content::addView)
        autoSyncCard().also(content::addView)
        sessionCard().also(content::addView)
        startSessionClock()
        updateControls(StreamCenterLocalSyncState.IDLE)
        StreamCenterLocalSyncAutoRunner.refresh()
        return scroll(content, fixedSubmenuHeight = true)
    }

    override fun onDestroyView() {
        sessionClockHandler.removeCallbacks(sessionClockRunnable)
        activeOfferDialog?.dismiss()
        activeOfferDialog = null
        StreamCenterLocalSyncAutoRunner.removeListener(this)
        autoCard = null
        if (::manager.isInitialized) manager.close()
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (::manager.isInitialized) manager.cancel()
        super.onDismiss(dialog)
    }

    override fun onStateChanged(state: StreamCenterLocalSyncState, message: String) = onUi {
        setSessionStatus(
            message.takeUnless { state == StreamCenterLocalSyncState.IDLE }.orEmpty(),
            statusColorFor(state),
        )
        updateControls(state)
    }

    override fun onEvent(event: StreamCenterLocalSyncEvent) = onUi {
        appendEvent(event)
        if (event.message == "Sincronizzazione automatica riuscita") {
            rebuildAutoSyncCard()
        }
        event.progress?.let { progress ->
            progressBar.visibility = View.VISIBLE
            progressBar.progress = progress.coerceIn(0, 100)
        }
    }

    override fun onOfferFound(offer: StreamCenterLocalSyncOffer) = onUi {
        if (activeOfferDialog?.isShowing == true) return@onUi
        showOfferConfirmation(offer)
    }

    override fun onPairingCodeReady(code: String) = onUi {
        pairingCodeText.text = code.chunked(3).joinToString(" ")
        pairingCodeCard.visibility = View.VISIBLE
    }

    override fun onCompleted(result: StreamCenterLocalSyncResult) = onUi {
        progressBar.progress = 100
        if (result.restartRequired) markRestartNeeded()
        rebuildAutoSyncCard()
        StreamCenterLocalSyncAutoRunner.refresh()
        showCompletion(result)
    }

    override fun onError(message: String, error: Throwable?) = onUi {
        appendEvent(StreamCenterLocalSyncEvent("Errore", message))
        AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Sync Locale non completato", COLOR_DANGER))
            .setMessage(message)
            .setNegativeButton("Chiudi", null)
            .create()
            .also {
                applyDialogBackdrop(it)
                it.show()
            }
    }

    private fun actionCard(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardBackground(COLOR_CARD, tint(COLOR_LOCAL_SYNC, "78"), 16)
            layoutParams = verticalParams(10)
            addView(
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, 0)
                    sendButton = actionButton("Invia", COLOR_LOCAL_SYNC, ::showSendChoice).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = dp(5)
                        }
                    }
                    receiveButton = actionButton("Ricevi", COLOR_LOCAL_SYNC, ::startReceiving).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(5)
                        }
                    }
                    addView(sendButton)
                    addView(receiveButton)
                },
            )
            cancelButton = actionButton("Annulla sessione", COLOR_DANGER, ::cancelSession).apply {
                layoutParams = verticalParams(10)
            }
            addView(cancelButton)
        }
    }

    private fun sessionCard(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardBackground(COLOR_CARD, tint(COLOR_LOCAL_SYNC, "78"), 16)
            layoutParams = verticalParams(10)
            addView(
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(titleText("Attività della sessione", 15, true).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    sessionClockText = bodyText("--:--:--", 9).apply {
                        typeface = Typeface.MONOSPACE
                        setTextColor(Color.parseColor(COLOR_MUTED))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            marginEnd = dp(8)
                        }
                    }
                    addView(sessionClockText)
                    addView(chip("LOCALE", COLOR_LOCAL_SYNC))
                },
            )
            statusText = bodyText(IDLE_SESSION_STATUS, 12).apply {
                setPadding(0, dp(7), 0, dp(8))
                setTextColor(Color.parseColor(COLOR_TEXT))
            }
            addView(statusText)
            pairingCodeCard = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = cardBackground(COLOR_CARD_ALT, tint(COLOR_LOCAL_SYNC, "88"), 12)
                addView(bodyText("Codice temporaneo", 12).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                pairingCodeText = titleText("--- ---", 20, true).apply {
                    typeface = Typeface.MONOSPACE
                    letterSpacing = 0.08f
                    setTextColor(Color.parseColor(COLOR_LOCAL_SYNC))
                }
                addView(pairingCodeText)
            }
            addView(pairingCodeCard)
            progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                max = 100
                progress = 0
                visibility = View.GONE
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_LOCAL_SYNC))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_STROKE))
                layoutParams = verticalParams(10).apply { height = dp(5) }
            }
            addView(progressBar)
            addView(sectionLabel("Registro").apply { setPadding(0, dp(12), 0, dp(6)) })
            logText = bodyText("In attesa di un'azione…", 11).apply {
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor(tint(COLOR_TEXT, "D6")))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            logScroll = NestedScrollView(requireContext()).apply {
                background = cardBackground(COLOR_INPUT_FILL, tint(COLOR_LOCAL_SYNC, "55"), 12)
                isVerticalScrollBarEnabled = true
                isNestedScrollingEnabled = true
                isFillViewport = true
                isFocusable = true
                isFocusableInTouchMode = true
                contentDescription = "Registro della sincronizzazione"
                addView(logText)
                layoutParams = verticalParams().apply {
                    height = dp(
                        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                            118
                        } else {
                            220
                        },
                    )
                }
            }
            addView(logScroll)
        }
    }

    private fun autoSyncCard(): LinearLayout {
        val card = categoryContainer(COLOR_LOCAL_SYNC, topMargin = 10)
        autoCard = card
        rebuildAutoSyncCard()
        return card
    }

    private fun rebuildAutoSyncCard() {
        val card = autoCard ?: return
        val context = context ?: return
        card.removeAllViews()

        val peers = StreamCenterLocalSyncTrust.trustedPeers(context)

        val expandButton = categoryExpandButton(
            expanded = autoExpanded,
            description = if (autoExpanded) "Chiudi Sync automatico" else "Apri Sync automatico",
            accent = COLOR_LOCAL_SYNC,
            size = 34,
        ) { toggleAutoExpanded() }
        val masterSwitch = styledSwitch(
            StreamCenterLocalSyncAutoConfig.isEnabled(context),
            COLOR_LOCAL_SYNC,
        ) { isOn ->
            StreamCenterLocalSyncAutoConfig.setEnabled(context, isOn)
            StreamCenterLocalSyncAutoRunner.refresh()
        }.apply {
            id = View.generateViewId()
            isFocusable = true
            contentDescription = "Attiva o disattiva la sincronizzazione automatica"
        }

        val headerSummary = counterText(trustedPeersSummary(peers.size)).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val headerRow = categoryHeaderRow(
            title = "Sync automatico",
            summaryView = headerSummary,
            icon = "🔁",
            accent = COLOR_LOCAL_SYNC,
            trailingViews = listOf(masterSwitch, expandButton),
        ) { expandButton.callOnClick() }
        headerRow.title.maxLines = 1
        headerRow.title.ellipsize = android.text.TextUtils.TruncateAt.END
        headerRow.view.id = View.generateViewId()
        applyAutoSyncRowHeight(headerRow.view)
        headerRow.view.nextFocusRightId = masterSwitch.id
        headerRow.view.nextFocusForwardId = masterSwitch.id
        masterSwitch.nextFocusLeftId = headerRow.view.id
        card.addView(headerRow.view)

        if (!autoExpanded) return

        val categories = StreamCenterLocalSyncAutoConfig.categories(context)
        val categoriesRowViews = settingsRow(
            title = "Cosa sincronizzare",
            summary = categories.joinToString(", ") { it.title }.ifBlank { "Niente selezionato" },
            icon = "📂",
            accent = COLOR_LOCAL_SYNC,
            fillColor = COLOR_CARD_ALT,
            strokeColor = tint(COLOR_LOCAL_SYNC, "88"),
            trailingViews = listOf(chevron(COLOR_LOCAL_SYNC)),
            topMargin = 10,
        ) { showAutoCategoriesDialog() }
        categoriesRowViews.title.maxLines = 1
        categoriesRowViews.title.ellipsize = android.text.TextUtils.TruncateAt.END
        categoriesRowViews.summary?.maxLines = 1
        categoriesRowViews.summary?.ellipsize = android.text.TextUtils.TruncateAt.END
        val categoriesRow = categoriesRowViews.view.apply {
            id = View.generateViewId()
            applyAutoSyncRowHeight(this, topMargin = 10)
        }
        val interval = intervalRow(context)
        categoriesRow.nextFocusDownId = interval.field.id
        categoriesRow.nextFocusForwardId = interval.field.id
        interval.field.nextFocusUpId = categoriesRow.id
        card.addView(categoriesRow)
        card.addView(interval.view)
        card.addView(sectionLabel("Dispositivi fidati").apply { setPadding(dp(4), dp(12), dp(4), dp(6)) })
        if (peers.isEmpty()) {
            card.addView(
                bodyText(
                    "Nessuno ancora. Fai un primo trasferimento con Invia/Ricevi: i due dispositivi si ricorderanno a vicenda.",
                    12,
                ).apply { setPadding(dp(4), 0, dp(4), 0) },
            )
        } else {
            var previousPeerRow: View? = null
            peers.forEach { peer ->
                val mode = StreamCenterLocalSyncAutoConfig.peerMode(context, peer.id)
                val peerRowViews = settingsRow(
                    title = peer.name,
                    summary = "${mode.title}\nUltima sync: ${formatLastSync(peer)}",
                    icon = "📱",
                    accent = COLOR_LOCAL_SYNC,
                    fillColor = COLOR_CARD_ALT,
                    strokeColor = tint(COLOR_LOCAL_SYNC, "88"),
                    trailingViews = listOf(chevron(COLOR_LOCAL_SYNC)),
                    topMargin = 8,
                ) { showPeerSettings(peer) }
                peerRowViews.title.maxLines = 1
                peerRowViews.title.ellipsize = android.text.TextUtils.TruncateAt.END
                peerRowViews.summary?.maxLines = 2
                peerRowViews.summary?.ellipsize = android.text.TextUtils.TruncateAt.END
                val peerRow = peerRowViews.view.apply {
                    id = View.generateViewId()
                    applyAutoSyncRowHeight(this, topMargin = 8)
                    setOnLongClickListener {
                        forcePeerSync(peer)
                        true
                    }
                    contentDescription = "$peer.name. ${mode.title}. Tieni premuto su entrambi i dispositivi per sincronizzare subito"
                }
                val previous = previousPeerRow
                if (previous == null) {
                    interval.field.nextFocusDownId = peerRow.id
                    peerRow.nextFocusUpId = interval.field.id
                } else {
                    previous.nextFocusDownId = peerRow.id
                    peerRow.nextFocusUpId = previous.id
                }
                card.addView(peerRow)
                previousPeerRow = peerRow
            }
        }
    }

    private fun toggleAutoExpanded() {
        autoExpanded = !autoExpanded
        rebuildAutoSyncCard()
    }

    private fun forcePeerSync(peer: StreamCenterLocalSyncTrustedPeer) {
        val started = StreamCenterLocalSyncAutoRunner.forcePeerSync(peer.id)
        if (started) {
            saveToast("Finestra aperta: tieni premuto anche sull'altro dispositivo")
        } else {
            saveToast("Impossibile avviare la sync forzata")
        }
    }

    private fun trustedPeersSummary(count: Int): String = when (count) {
        0 -> "Nessun dispositivo fidato · accoppia con Invia/Ricevi"
        1 -> "1 dispositivo fidato sulla rete locale"
        else -> "$count dispositivi fidati sulla rete locale"
    }

    private fun formatLastSync(peer: StreamCenterLocalSyncTrustedPeer): String =
        if (peer.lastSyncAtMs > 0L) autoDateFormat.format(Date(peer.lastSyncAtMs)) else "mai"

    private fun applyAutoSyncRowHeight(view: View, topMargin: Int = 0) {
        view.minimumHeight = dp(AUTO_SYNC_ROW_HEIGHT_DP)
        view.layoutParams = verticalParams(top = topMargin).apply {
            height = dp(AUTO_SYNC_ROW_HEIGHT_DP)
        }
    }

    private fun intervalRow(context: android.content.Context): IntervalRowViews {
        scheduleText = bodyText(
            automaticSyncScheduleSummary(StreamCenterLocalSyncAutoConfig.intervalMinutes(context)),
            9,
        ).apply {
            maxLines = 1
            setTextColor(Color.parseColor(COLOR_MUTED))
            setPadding(0, dp(2), 0, 0)
        }
        val field = EditText(context).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(3))
            setSingleLine(true)
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setSelectAllOnFocus(true)
            setTextColor(Color.parseColor(COLOR_TEXT))
            setText(StreamCenterLocalSyncAutoConfig.intervalMinutes(context).toString())
            background = interactiveBackground(COLOR_INPUT_FILL, COLOR_LOCAL_SYNC, 10, tint(COLOR_LOCAL_SYNC, "88"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
            contentDescription = "Frequenza in minuti, da 1 a 240"
            doAfterTextChanged { editable ->
                val value = editable?.toString()?.trim()?.toIntOrNull() ?: return@doAfterTextChanged
                StreamCenterLocalSyncAutoConfig.setIntervalMinutes(context, value)
                scheduleText.text = automaticSyncScheduleSummary(
                    StreamCenterLocalSyncAutoConfig.intervalMinutes(context),
                )
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) setText(StreamCenterLocalSyncAutoConfig.intervalMinutes(context).toString())
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            minimumHeight = dp(AUTO_SYNC_ROW_HEIGHT_DP)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = cardBackground(COLOR_CARD_ALT, tint(COLOR_LOCAL_SYNC, "88"), 12)
            layoutParams = verticalParams(top = 8).apply {
                height = dp(AUTO_SYNC_ROW_HEIGHT_DP)
            }
            addView(iconBadge("🕒", COLOR_LOCAL_SYNC, size = 36, marginEnd = 10))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(titleText("Frequenza (minuti)", 14, true).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(scheduleText)
                },
            )
            addView(field)
        }
        return IntervalRowViews(row, field)
    }

    private fun automaticSyncScheduleSummary(intervalMinutes: Int): String {
        val intervalMs = intervalMinutes.coerceIn(1, 240) * 60_000L
        val now = System.currentTimeMillis()
        val nextCycle = ((now / intervalMs) + 1) * intervalMs
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextCycle))
        return "Prossimo ciclo alle $time"
    }

    private fun startSessionClock() {
        sessionClockHandler.removeCallbacks(sessionClockRunnable)
        sessionClockRunnable.run()
    }

    private fun showPeerSettings(peer: StreamCenterLocalSyncTrustedPeer) {
        val context = requireContext()
        val selectedMode = StreamCenterLocalSyncAutoConfig.peerMode(context, peer.id)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(14))
            addView(
                bodyText(
                    "La modalità bidirezionale confronta ogni singola voce e conserva quella modificata più di recente. Anche scegliendo un solo verso, una lista vecchia non sovrascrive dati più recenti.",
                    12,
                ).apply { setPadding(dp(2), 0, dp(2), dp(4)) },
            )
        }
        lateinit var dialog: AlertDialog
        StreamCenterLocalSyncPeerMode.entries.forEachIndexed { index, mode ->
            val selected = mode == selectedMode
            val selectedBadge = iconBadge("✓", COLOR_LOCAL_SYNC, size = 30, marginEnd = 0).apply {
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
            }
            content.addView(
                settingsRow(
                    title = mode.title,
                    summary = mode.summary,
                    icon = peerModeIcon(mode),
                    accent = COLOR_LOCAL_SYNC,
                    fillColor = COLOR_CARD_ALT,
                    strokeColor = if (selected) COLOR_LOCAL_SYNC else tint(COLOR_LOCAL_SYNC, "55"),
                    trailingViews = listOf(selectedBadge),
                    topMargin = if (index == 0) 10 else 8,
                    accessibilityState = { if (selected) "Selezionata" else "Non selezionata" },
                ) {
                    StreamCenterLocalSyncAutoConfig.setPeerMode(context, peer.id, mode)
                    rebuildAutoSyncCard()
                    dialog.dismiss()
                }.view,
            )
        }
        dialog = AlertDialog.Builder(context)
            .setCustomTitle(dialogTitle("Direzione con ${peer.name}", COLOR_LOCAL_SYNC))
            .setView(content)
            .setPositiveButton("Chiudi", null)
            .setNegativeButton("Dimentica", null)
            .create()
        applyDialogBackdrop(
            alertDialog = dialog,
            onShow = {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                    setTextColor(Color.parseColor(COLOR_DANGER))
                    setOnClickListener {
                        dialog.dismiss()
                        confirmForgetPeer(peer)
                    }
                }
            },
        )
        dialog.show()
    }

    private fun peerModeIcon(mode: StreamCenterLocalSyncPeerMode): String = when (mode) {
        StreamCenterLocalSyncPeerMode.BIDIRECTIONAL -> "↔️"
        StreamCenterLocalSyncPeerMode.SEND_ONLY -> "⬆️"
        StreamCenterLocalSyncPeerMode.RECEIVE_ONLY -> "⬇️"
    }

    private fun confirmForgetPeer(peer: StreamCenterLocalSyncTrustedPeer) {
        AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Dimenticare ${peer.name}?", COLOR_DANGER))
            .setMessage("Per sincronizzare di nuovo dovrai accoppiarlo un'altra volta con il codice.")
            .setPositiveButton("Dimentica") { _, _ ->
                StreamCenterLocalSyncAutoConfig.removePeerMode(requireContext(), peer.id)
                StreamCenterLocalSyncTrust.forgetPeer(requireContext(), peer.id)
                StreamCenterLocalSyncAutoRunner.refresh()
                rebuildAutoSyncCard()
            }
            .setNegativeButton("Annulla", null)
            .create()
            .also {
                applyDialogBackdrop(it)
                it.show()
            }
    }

    private fun autoCategoryIcon(category: StreamCenterLocalSyncCategory): String = when (category) {
        StreamCenterLocalSyncCategory.LIBRARY -> "📚"
        StreamCenterLocalSyncCategory.CLOUDSTREAM_CONFIG -> "📦"
        StreamCenterLocalSyncCategory.STREAMCENTER_CONFIG -> "⚙️"
    }

    private fun showAutoCategoriesDialog() {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(14))
        }
        StreamCenterLocalSyncCategory.entries.forEachIndexed { index, category ->
            container.addView(
                switchRow(
                    title = category.title,
                    checked = StreamCenterLocalSyncAutoConfig.isCategoryEnabled(context, category),
                    accent = COLOR_LOCAL_SYNC,
                    icon = autoCategoryIcon(category),
                    topMargin = if (index == 0) 0 else 10,
                ) { on ->
                    StreamCenterLocalSyncAutoConfig.setCategoryEnabled(context, category, on)
                },
            )
        }
        AlertDialog.Builder(context)
            .setCustomTitle(dialogTitle("Cosa sincronizzare", COLOR_LOCAL_SYNC))
            .setView(container)
            .setPositiveButton("Fatto") { _, _ -> rebuildAutoSyncCard() }
            .create()
            .also {
                applyDialogBackdrop(it)
                it.show()
            }
    }

    private fun showSendChoice() {
        if (manager.isRunning) return
        val context = requireContext()
        val selected = StreamCenterLocalSyncCategory.entries.toMutableSet()
        val options = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(14))
        }
        StreamCenterLocalSyncCategory.entries.forEachIndexed { index, category ->
            options.addView(
                switchRow(
                    title = category.title,
                    checked = category in selected,
                    accent = COLOR_LOCAL_SYNC,
                    icon = autoCategoryIcon(category),
                    topMargin = if (index == 0) 0 else 10,
                ) { on ->
                    if (on) selected.add(category) else selected.remove(category)
                },
            )
        }
        AlertDialog.Builder(context)
            .setCustomTitle(dialogTitle("Cosa vuoi inviare", COLOR_LOCAL_SYNC))
            .setView(options)
            .setPositiveButton("Invia") { _, _ ->
                if (selected.isNotEmpty()) startSending(selected.toSet())
            }
            .setNegativeButton("Annulla", null)
            .create()
            .also {
                applyDialogBackdrop(it)
                it.show()
            }
    }

    private fun startSending(categories: Set<StreamCenterLocalSyncCategory>) {
        resetSessionView(
            "Preparazione di ${categories.joinToString(", ") { it.title.lowercase(Locale.ITALIAN) }}",
        )
        manager.startSending(categories)
    }

    private fun startReceiving() {
        if (manager.isRunning) return
        resetSessionView("Avvio ricerca locale")
        manager.startReceiving()
    }

    private fun cancelSession() {
        activeOfferDialog?.dismiss()
        activeOfferDialog = null
        manager.cancel()
    }

    private fun showOfferConfirmation(offer: StreamCenterLocalSyncOffer) {
        val message = when (offer.type) {
            StreamCenterLocalSyncPayloadType.ALL ->
                "È stato trovato un trasferimento completo da ${offer.senderName}. Se procedi, verranno sostituite la configurazione CloudStream, la libreria locale del profilo corrente e la configurazione StreamCenter. Token, percorsi locali, download e file dei plugin resteranno sul dispositivo. Vuoi scaricarlo e applicarlo?"
            StreamCenterLocalSyncPayloadType.CLOUDSTREAM ->
                "È stata trovata una configurazione CloudStream da ${offer.senderName}. Se procedi, verranno sostituite soltanto le impostazioni e i dati di configurazione CloudStream. La libreria locale e la configurazione StreamCenter resteranno invariate. Token, percorsi locali, download e file dei plugin resteranno sul dispositivo. Vuoi scaricarla e applicarla?"
            StreamCenterLocalSyncPayloadType.LIBRARY ->
                "È stata trovata una libreria locale di CloudStream da ${offer.senderName}. Se procedi, libreria, episodi e progressi del profilo CloudStream corrente verranno completamente sostituiti. Vuoi scaricarla e applicarla?"
            StreamCenterLocalSyncPayloadType.STREAMCENTER ->
                "È stata trovata una configurazione StreamCenter da ${offer.senderName}. Se procedi, tutte le preferenze, le fonti, i filtri e le personalizzazioni di StreamCenter verranno sostituiti. Le impostazioni e la libreria di CloudStream resteranno invariate. Vuoi scaricarla e applicarla?"
            StreamCenterLocalSyncPayloadType.SELECTIVE ->
                "È stata trovata una selezione di dati da ${offer.senderName}. Verranno sostituite soltanto le categorie incluse nella selezione (libreria/progressi e/o configurazioni); le categorie non incluse resteranno invariate. Vuoi scaricarla e applicarla?"
        }
        val details = buildString {
            append(message)
            append("\n\n")
            append("Contenuto: ${offer.entryCount} voci")
            if (offer.libraryItemCount > 0 || offer.progressCount > 0) {
                append(" · ${offer.libraryItemCount} elementi · ${offer.progressCount} progressi")
            }
        }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Trasferimento locale trovato", COLOR_LOCAL_SYNC))
            .setMessage(details)
            .setPositiveButton("Continua") { _, _ -> showPairingCodeDialog(offer) }
            .setNegativeButton("No", null)
            .create()
        activeOfferDialog = dialog
        applyDialogBackdrop(dialog, onDismiss = {
            if (activeOfferDialog === dialog) activeOfferDialog = null
        })
        dialog.show()
    }

    private fun showPairingCodeDialog(offer: StreamCenterLocalSyncOffer) {
        val field = EditText(requireContext()).apply {
            hint = "000000"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            textSize = 20f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(COLOR_TEXT))
            setHintTextColor(Color.parseColor(COLOR_MUTED))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(COLOR_INPUT_FILL, COLOR_LOCAL_SYNC, 12)
        }
        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(bodyText("Inserisci il codice di sei cifre mostrato sul dispositivo mittente.", 12))
            addView(field, verticalParams(10))
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Verifica il mittente", COLOR_LOCAL_SYNC))
            .setView(wrapper)
            .setPositiveButton("Connetti", null)
            .setNegativeButton("Annulla", null)
            .create()
        activeOfferDialog = dialog
        applyDialogBackdrop(
            alertDialog = dialog,
            onShow = {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val code = field.text?.toString().orEmpty()
                    if (!code.matches(Regex("\\d{6}"))) {
                        field.error = "Servono sei cifre"
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    manager.acceptOffer(offer, code)
                }
                field.requestFocus()
            },
            onDismiss = {
                if (activeOfferDialog === dialog) activeOfferDialog = null
            },
        )
        dialog.show()
    }

    private fun showCompletion(result: StreamCenterLocalSyncResult) {
        val direction = if (result.sent) {
            "inviato e applicato da ${result.peerName}"
        } else {
            "ricevuto da ${result.peerName} e applicato"
        }
        val statistics = buildString {
            append("${result.type.title}: contenuto $direction.")
            append("\n\n${result.entryCount} voci elaborate")
            if (result.libraryItemCount > 0 || result.progressCount > 0) {
                append(" · ${result.libraryItemCount} elementi · ${result.progressCount} progressi")
            }
            if (result.restartRequired) append(".\n\nRiavvia CloudStream.")
        }
        val builder = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Sync Locale completato", COLOR_LOCAL_SYNC))
            .setMessage(statistics)
        if (result.restartRequired) {
            builder
                .setPositiveButton("Riavvia ora") { _, _ ->
                    if (consumeRestartNeeded()) restartApplication()
                }
                .setNegativeButton("Più tardi", null)
        } else {
            builder.setNegativeButton("Chiudi", null)
        }
        builder.create().also {
            applyDialogBackdrop(it)
            it.show()
        }
    }

    private fun resetSessionView(message: String) {
        activeOfferDialog?.dismiss()
        activeOfferDialog = null
        eventLog.clear()
        logText.text = ""
        pairingCodeText.text = "--- ---"
        pairingCodeCard.visibility = View.GONE
        progressBar.progress = 0
        progressBar.visibility = View.GONE
        setSessionStatus(message)
        appendEvent(StreamCenterLocalSyncEvent("Sessione avviata", message))
    }

    private fun setSessionStatus(message: String, color: String = COLOR_TEXT) {
        val blank = message.isBlank()
        statusText.text = if (blank) IDLE_SESSION_STATUS else message
        statusText.setTextColor(Color.parseColor(if (blank) COLOR_MUTED else color))
        statusText.visibility = View.VISIBLE
    }

    private fun statusColorFor(state: StreamCenterLocalSyncState): String = when (state) {
        StreamCenterLocalSyncState.COMPLETED -> COLOR_SUCCESS
        StreamCenterLocalSyncState.ERROR -> COLOR_DANGER
        StreamCenterLocalSyncState.IDLE, StreamCenterLocalSyncState.CANCELLED -> COLOR_MUTED
        else -> COLOR_LOCAL_SYNC
    }

    private fun appendEvent(event: StreamCenterLocalSyncEvent) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.ITALIAN).format(Date())
        val timestampStart = eventLog.length
        eventLog.append('[').append(timestamp).append("] ")
        eventLog.setSpan(
            ForegroundColorSpan(Color.parseColor(COLOR_MUTED)),
            timestampStart,
            eventLog.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val messageStart = eventLog.length
        eventLog.append(event.message)
        eventLog.setSpan(
            ForegroundColorSpan(Color.parseColor(eventColor(event))),
            messageStart,
            eventLog.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        eventLog.setSpan(
            StyleSpan(Typeface.BOLD),
            messageStart,
            eventLog.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        event.detail?.takeIf(String::isNotBlank)?.let { detail ->
            val detailStart = eventLog.length
            eventLog.append("\n    ").append(detail)
            eventLog.setSpan(
                ForegroundColorSpan(Color.parseColor(tint(COLOR_TEXT, "D6"))),
                detailStart,
                eventLog.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        event.progress?.let { progress ->
            val progressStart = eventLog.length
            eventLog.append(" · ").append(progress.coerceIn(0, 100).toString()).append('%')
            eventLog.setSpan(
                ForegroundColorSpan(Color.parseColor(COLOR_LOCAL_SYNC)),
                progressStart,
                eventLog.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        eventLog.append('\n')
        logText.text = eventLog
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun eventColor(event: StreamCenterLocalSyncEvent): String = when {
        event.message.contains("errore", ignoreCase = true) ||
            event.message.contains("non riuscita", ignoreCase = true) ||
            event.message.contains("non raggiungibile", ignoreCase = true) -> COLOR_DANGER
        event.message.contains("riuscita", ignoreCase = true) ||
            event.message.contains("completato", ignoreCase = true) -> COLOR_SUCCESS
        event.message.contains("finestra", ignoreCase = true) -> COLOR_LOCAL_SYNC
        else -> COLOR_TEXT
    }

    private fun updateControls(state: StreamCenterLocalSyncState) {
        val running = state in setOf(
            StreamCenterLocalSyncState.PREPARING,
            StreamCenterLocalSyncState.ADVERTISING,
            StreamCenterLocalSyncState.DISCOVERING,
            StreamCenterLocalSyncState.AUTHENTICATING,
            StreamCenterLocalSyncState.TRANSFERRING,
            StreamCenterLocalSyncState.APPLYING,
        )
        setActionEnabled(sendButton, !running)
        setActionEnabled(receiveButton, !running)
        setActionEnabled(cancelButton, running)
        if (!running) {
            pairingCodeText.text = "--- ---"
            pairingCodeCard.visibility = View.GONE
        }
        if (!running && state != StreamCenterLocalSyncState.COMPLETED) progressBar.visibility = View.GONE
    }

    private fun setActionEnabled(view: TextView, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.38f
    }

    private inline fun onUi(crossinline action: () -> Unit) {
        activity?.runOnUiThread {
            if (!isAdded || view == null) return@runOnUiThread
            action()
        }
    }
}
