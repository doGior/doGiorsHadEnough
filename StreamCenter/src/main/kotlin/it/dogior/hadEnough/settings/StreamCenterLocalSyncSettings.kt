package it.dogior.hadEnough.settings

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncEvent
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncListener
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncManager
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncOffer
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncPayloadType
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncResult
import it.dogior.hadEnough.localsync.StreamCenterLocalSyncState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class StreamCenterLocalSyncSettingsFragment : StreamCenterBaseSettingsFragment(), StreamCenterLocalSyncListener {
    private lateinit var manager: StreamCenterLocalSyncManager
    private lateinit var statusText: TextView
    private lateinit var pairingCodeText: TextView
    private lateinit var pairingCodeCard: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var logText: TextView
    private lateinit var logScroll: NestedScrollView
    private lateinit var sendButton: TextView
    private lateinit var receiveButton: TextView
    private lateinit var cancelButton: TextView
    private val eventLog = StringBuilder()
    private var activeOfferDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        manager = StreamCenterLocalSyncManager(requireContext(), this)
        val content = rootContainer().apply {
            minimumHeight = standardSubmenuMinimumHeight()
        }
        content.addView(
            header(
                title = "Sync locale",
                subtitle = "Trasferimento tra dispositivi sulla stessa rete",
                icon = "🔐",
                accent = COLOR_LOCAL_SYNC,
            ),
        )
        val actions = actionCard().also(content::addView)
        val session = sessionCard().also(content::addView)
        startBorderSparkleCycle(
            listOf(
                BorderSparkleTarget(actions, COLOR_LOCAL_SYNC),
                BorderSparkleTarget(session, COLOR_LOCAL_SYNC),
            ),
        )
        updateControls(StreamCenterLocalSyncState.IDLE)
        return scroll(content)
    }

    override fun onDestroyView() {
        activeOfferDialog?.dismiss()
        activeOfferDialog = null
        if (::manager.isInitialized) manager.close()
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (::manager.isInitialized) manager.cancel()
        super.onDismiss(dialog)
    }

    override fun onStateChanged(state: StreamCenterLocalSyncState, message: String) = onUi {
        statusText.text = message
        updateControls(state)
    }

    override fun onEvent(event: StreamCenterLocalSyncEvent) = onUi {
        appendEvent(event)
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
        showCompletion(result)
    }

    override fun onError(message: String, error: Throwable?) = onUi {
        appendEvent(StreamCenterLocalSyncEvent("Errore", message))
        AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Sync locale non completato", COLOR_DANGER))
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
            addView(titleText("Scegli il ruolo", 15, true))
            addView(
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(12), 0, 0)
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
                    addView(chip("LOCALE", COLOR_LOCAL_SYNC))
                },
            )
            statusText = bodyText("Nessuna sessione attiva", 12).apply {
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
            addView(sectionLabel("Registro completo").apply { setPadding(0, dp(12), 0, dp(6)) })
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
                contentDescription = "Registro completo della sincronizzazione"
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                addView(logText)
                layoutParams = verticalParams().apply { height = dp(220) }
            }
            addView(logScroll)
        }
    }

    private fun showSendChoice() {
        if (manager.isRunning) return
        val options = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(14))
        }
        lateinit var dialog: AlertDialog
        options.addView(
            settingsRow(
                title = "Tutto",
                icon = "🔄",
                accent = COLOR_LOCAL_SYNC,
                fillColor = COLOR_CARD_ALT,
                strokeColor = tint(COLOR_LOCAL_SYNC, "88"),
                trailingViews = listOf(chevron(COLOR_LOCAL_SYNC)),
                topMargin = 0,
            ) {
                dialog.dismiss()
                startSending(StreamCenterLocalSyncPayloadType.ALL)
            }.view,
        )
        options.addView(
            settingsRow(
                title = "Configurazione CloudStream",
                icon = "📦",
                accent = COLOR_LOCAL_SYNC,
                fillColor = COLOR_CARD_ALT,
                strokeColor = tint(COLOR_LOCAL_SYNC, "88"),
                trailingViews = listOf(chevron(COLOR_LOCAL_SYNC)),
                topMargin = 10,
            ) {
                dialog.dismiss()
                startSending(StreamCenterLocalSyncPayloadType.CLOUDSTREAM)
            }.view,
        )
        options.addView(
            settingsRow(
                title = "Libreria locale",
                icon = "📚",
                accent = COLOR_LOCAL_SYNC,
                fillColor = COLOR_CARD_ALT,
                strokeColor = tint(COLOR_LOCAL_SYNC, "88"),
                trailingViews = listOf(chevron(COLOR_LOCAL_SYNC)),
                topMargin = 10,
            ) {
                dialog.dismiss()
                startSending(StreamCenterLocalSyncPayloadType.LIBRARY)
            }.view,
        )
        options.addView(
            settingsRow(
                title = "Configurazione StreamCenter",
                icon = "⚙️",
                accent = COLOR_LOCAL_SYNC,
                fillColor = COLOR_CARD_ALT,
                strokeColor = tint(COLOR_LOCAL_SYNC, "88"),
                trailingViews = listOf(chevron(COLOR_LOCAL_SYNC)),
                topMargin = 10,
            ) {
                dialog.dismiss()
                startSending(StreamCenterLocalSyncPayloadType.STREAMCENTER)
            }.view,
        )
        val scroll = NestedScrollView(requireContext()).apply {
            isFillViewport = true
            addView(options)
        }
        dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Cosa vuoi inviare?", COLOR_LOCAL_SYNC))
            .setView(scroll)
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun startSending(type: StreamCenterLocalSyncPayloadType) {
        resetSessionView("Preparazione di ${type.title.lowercase(Locale.ITALIAN)}")
        manager.startSending(type)
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
        }
        val details = buildString {
            append(message)
            append("\n\n")
            append("Contenuto: ${offer.entryCount} voci")
            if (
                offer.type == StreamCenterLocalSyncPayloadType.ALL ||
                offer.type == StreamCenterLocalSyncPayloadType.LIBRARY
            ) {
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
            if (
                result.type == StreamCenterLocalSyncPayloadType.ALL ||
                result.type == StreamCenterLocalSyncPayloadType.LIBRARY
            ) {
                append(" · ${result.libraryItemCount} elementi · ${result.progressCount} progressi")
            }
            if (result.restartRequired) append(".\n\nRiavvia CloudStream.")
        }
        val builder = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Sync locale completato", COLOR_LOCAL_SYNC))
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
        statusText.text = message
        appendEvent(StreamCenterLocalSyncEvent("Sessione avviata", message))
    }

    private fun appendEvent(event: StreamCenterLocalSyncEvent) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.ITALIAN).format(Date())
        eventLog.append('[').append(timestamp).append("] ").append(event.message)
        event.detail?.takeIf(String::isNotBlank)?.let { detail -> eventLog.append("\n    ").append(detail) }
        event.progress?.let { progress -> eventLog.append(" · ").append(progress).append('%') }
        eventLog.append('\n')
        logText.text = eventLog.toString().trimEnd()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
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
