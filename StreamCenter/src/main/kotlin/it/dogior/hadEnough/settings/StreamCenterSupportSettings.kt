package it.dogior.hadEnough.settings

import it.dogior.hadEnough.*
import it.dogior.hadEnough.availability.*
import it.dogior.hadEnough.util.StreamCenterVpnGuard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
open class StreamCenterSupportSettingsFragment : StreamCenterBaseSettingsFragment() {
    override val screenTitle: String = "Sistema"

    override val screenIcon: String = "⚙️"

    override val screenAccent: String = COLOR_SUPPORT

    private var activeBackupDialog: AlertDialog? = null
    private var backupLocationText: TextView? = null
    private val backupFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val ctx = context ?: return@registerForActivityResult
        uri ?: return@registerForActivityResult
        runCatching { StreamCenterBackupManager.selectDirectory(ctx, uri) }
            .onSuccess {
                backupLocationText?.text = StreamCenterBackupManager.locationLabel(ctx)
                saveToast("Percorso dei backup aggiornato")
            }
            .onFailure { saveToast(it.message ?: "Impossibile usare la cartella scelta") }
    }

    private companion object {
        const val TELEGRAM_GROUP_URL = "https://t.me/cloudstream_italia"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer().apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
        }
        content.minimumHeight = standardSubmenuMinimumHeight()

        fun addSection(label: String, accent: String, topMargin: Int, rows: List<View>) {
            content.addView(sectionHeading(label, accent, topMargin))
            addAdaptiveCardGrid(content, rows)
        }
        addSection("Rete", COLOR_VPN_GUARD, 4, listOf(vpnGuardRow()))
        addSection("Dati", COLOR_BACKUP, 16, listOf(backupCard(), syncLocaleCard(), cacheCard()))
        addSection("Diagnostica", COLOR_LOG, 16, listOf(logCard(), feedbackCard(), resetCard()))
        return scroll(content, fixedSubmenuHeight = true)
    }

    private var setVpnGuardChecked: ((Boolean) -> Unit)? = null

    private fun vpnGuardRow(): View = switchRow(
        title = "Protezione VPN",
        summary = "Blocca Internet finché la VPN non è attiva.",
        checked = StreamCenterPlugin.isVpnRequired(sharedPref),
        defaultChecked = StreamCenterPlugin.isVpnRequired(null),
        accent = COLOR_VPN_GUARD,
        icon = "🛡️",
        fixedHeight = true,
        bindChecked = { setter -> setVpnGuardChecked = setter },
    ) { enabled ->
        sharedPref?.edit { putBoolean(StreamCenterPlugin.PREF_REQUIRE_VPN, enabled) }
    }

    private fun logCard(): View = supportCard(
        icon = "📋",
        title = "Log",
        summary = "Ricerche, fonti ed errori.",
        accent = COLOR_LOG,
    ) {
        openScreen(StreamCenterLogsSettingsFragment(), "StreamCenterLogsSettings")
    }

    private fun cacheCard(): View = supportCard(
        icon = "⚡",
        title = "Cache",
        summary = "Riapre subito le schede già viste.",
        accent = COLOR_CACHE,
    ) {
        openScreen(StreamCenterCacheSettingsFragment(), "StreamCenterCacheSettings")
    }

    private fun feedbackCard(): View = supportCard(
        icon = "💬",
        title = "Invia feedback",
        summary = "Segnala un problema.",
        accent = COLOR_FEEDBACK,
    ) { showFeedbackChoiceDialog() }

    private fun backupCard(): View = supportCard(
        icon = "💾",
        title = "Esporta e importa",
        summary = "Salva la configurazione in un file.",
        accent = COLOR_BACKUP,
    ) { showBackupChoiceDialog() }

    private fun syncLocaleCard(): View = supportCard(
        icon = "🔐",
        title = "Sync locale",
        summary = "Allinea i dispositivi sulla stessa rete.",
        accent = COLOR_LOCAL_SYNC,
    ) { showLocalSyncWarningDialog() }

    private fun resetCard(): View = supportCard(
        icon = "♻️",
        title = "Ripristina tutte le impostazioni",
        summary = "Torna alla configurazione iniziale.",
        accent = COLOR_RESET,
    ) {
        val alertDialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Ripristina impostazioni"))
            .setMessage("Vuoi riportare StreamCenter alle impostazioni iniziali?")
            .setPositiveButton("Ripristina") { _, _ ->
                runCatching {
                    StreamCenterPlugin.resetAllConfiguration(
                        requireContext().applicationContext,
                        sharedPref,
                    )
                }.onSuccess {
                    setVpnGuardChecked?.invoke(StreamCenterPlugin.isVpnRequired(sharedPref))
                    refreshVisibleSettingsEffects()
                    resetRestartNeeded()
                    saveToast("StreamCenter ripristinato")
                }.onFailure {
                    showBackupError("Ripristino non riuscito", it)
                }
            }
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(alertDialog)
        alertDialog.show()
    }

    private fun supportCard(
        icon: String,
        title: String,
        summary: String,
        accent: String,
        onClick: () -> Unit,
    ): LinearLayout {
        val arrow = chevron(accent)
        val card = settingsRow(
            title = title,
            summary = summary,
            icon = icon,
            accent = accent,
            fillColor = COLOR_CARD,
            trailingViews = listOf(arrow),
            touchTarget = arrow,
            onClick = onClick,
        ).view
        return card
    }

    private fun showLocalSyncWarningDialog() {
        val alertDialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Funzionalità sperimentale", COLOR_LOCAL_SYNC))
            .setMessage(localSyncWarningMessage())
            .setPositiveButton("Continua") { _, _ -> openLocalSync() }
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(alertDialog)
        alertDialog.show()
    }

    private fun localSyncWarningMessage(): SpannableString {
        val backupLabel = "Back up data"
        val warning = "ATTENZIONE: potresti perdere l’intera lista locale."
        val message =
            "Questa funzionalità è ancora sperimentale.\n\n" +
                "Prima di continuare, è consigliato creare un $backupLabel " +
                "tramite le impostazioni di CloudStream.\n\n" +
                warning
        return SpannableString(message).apply {
            val backupStart = message.indexOf(backupLabel)
            setSpan(
                StyleSpan(Typeface.BOLD),
                backupStart,
                backupStart + backupLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val warningStart = message.indexOf(warning)
            setSpan(
                ForegroundColorSpan(Color.parseColor(COLOR_DANGER)),
                warningStart,
                warningStart + warning.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                warningStart,
                warningStart + warning.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun openLocalSync() {
        openScreen(StreamCenterLocalSyncSettingsFragment(), "StreamCenterLocalSyncSettings")
    }

    private fun showFeedbackChoiceDialog() {
        val ctx = requireContext()
        var dialog: AlertDialog? = null

        val choices = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    dialogActionTile(
                        icon = "💡",
                        label = "Proponi un\nmiglioramento",
                        accent = COLOR_SUCCESS,
                    ) {
                        dialog?.dismiss()
                        openFeedback("[suggerimento]: ")
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(6)
                    },
                )
                addView(
                    dialogActionTile(
                        icon = "🐞",
                        label = "Segnala un\nproblema",
                        accent = COLOR_DANGER,
                    ) {
                        dialog?.dismiss()
                        openFeedback("[problema]: ")
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(6)
                    },
                )
            })
            addView(
                dialogActionTile(
                    badge = siteIconBadge(
                        fallback = "✈",
                        accent = COLOR_TELEGRAM,
                        contentDescription = "Icona di Telegram",
                        iconUrl = TELEGRAM_ICON_URL,
                        size = 38,
                        marginEnd = 0,
                    ),
                    label = "CloudStream Italia 🇮🇹",
                    accent = COLOR_TELEGRAM,
                ) {
                    dialog?.dismiss()
                    openUrl(TELEGRAM_GROUP_URL)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                },
            )
        }
        val alertDialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Invia feedback"))
            .setView(choices)
            .setNegativeButton("Chiudi", null)
            .create()
        dialog = alertDialog
        applyDialogBackdrop(alertDialog)
        alertDialog.show()
    }

    private fun presentBackupDialog(
        dialog: AlertDialog,
        onDismiss: (() -> Unit)? = null,
    ) {
        val outgoing = activeBackupDialog?.takeIf { it !== dialog && it.isShowing }
        outgoing?.window?.decorView?.animate()?.cancel()
        outgoing?.dismiss()
        applyDialogBackdrop(dialog) {
            if (activeBackupDialog === dialog) activeBackupDialog = null
            onDismiss?.invoke()
        }
        dialog.show()
        activeBackupDialog = dialog
        if (reduceMotion) return
        dialog.window?.decorView?.apply {
            animate().cancel()
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            translationY = dp(14).toFloat()
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(210L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun setBackupBackNavigation(
        dialog: AlertDialog,
        destination: () -> Unit,
    ) {
        var navigating = false
        val navigate = {
            if (!navigating && dialog.isShowing) {
                navigating = true
                destination()
            }
        }
        dialog.setOnCancelListener { navigate() }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener { navigate() }
    }

    private fun showBackupChoiceDialog() {
        val ctx = requireContext()

        val choices = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(10), dp(14), 0)
            addView(
                dialogActionTile("\uD83D\uDCE4", "Esporta", COLOR_SUCCESS) { showExportNameDialog() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(5)
                },
            )
            addView(
                dialogActionTile("\uD83D\uDCE5", "Importa", COLOR_ACCENT) { showImportPicker() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(5)
                    marginEnd = dp(5)
                },
            )
            addView(
                dialogActionTile("\uD83D\uDCC1", "Percorso", COLOR_SOURCES) { chooseBackupDirectory() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(5)
                },
            )
        }
        val locationText = bodyText(StreamCenterBackupManager.locationLabel(ctx), 11).apply {
            setPadding(0, dp(2), 0, 0)
        }
        backupLocationText = locationText
        val resetLocation = iconButton("⌂", "Usa il percorso predefinito", COLOR_BACKUP, size = 34) {
                runCatching { StreamCenterBackupManager.resetDirectory(ctx.applicationContext) }
                    .onSuccess {
                        locationText.text = StreamCenterBackupManager.locationLabel(ctx)
                        saveToast("Percorso ripristinato")
                    }
                    .onFailure {
                        showBackupError("Percorso non aggiornato", it)
                    }
            }
        val location = settingsRow(
            title = "Cartella dei backup",
            icon = "\uD83D\uDCCD",
            accent = COLOR_BACKUP,
            fillColor = COLOR_CARD_ALT,
            strokeColor = tint(COLOR_BACKUP, "55"),
            summaryView = locationText,
            trailingViews = listOf(resetLocation),
        ).view
        StreamCenterSettingReset.bind(location) { resetLocation.performClick() }
        StreamCenterSettingReset.bind(resetLocation) { resetLocation.performClick() }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(4))
            addView(choices)
            addView(location, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(20)
                marginEnd = dp(20)
                topMargin = dp(10)
            })
        }
        val alertDialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Esporta/Importa"))
            .setView(content)
            .setNegativeButton("Chiudi", null)
            .create()
        presentBackupDialog(alertDialog) {
            if (backupLocationText === locationText) backupLocationText = null
        }
    }

    private fun chooseBackupDirectory() {
        runCatching { backupFolderPicker.launch(null) }
            .onFailure { saveToast("Impossibile aprire la scelta del percorso") }
    }

    private fun showExportNameDialog() {
        val ctx = context ?: return
        val defaultName = StreamCenterBackupManager.defaultFileName()
        val nameInput = input(defaultName).apply {
            hint = "Nome del backup"
            filters = arrayOf(InputFilter.LengthFilter(120))
            layoutParams = verticalParams(top = 6)
        }
        val preview = bodyText(StreamCenterBackupManager.normalizedFileName(defaultName), 12).apply {
            setTextColor(Color.parseColor(COLOR_TEXT))
            setPadding(0, dp(3), 0, 0)
        }
        nameInput.doAfterTextChanged {
            preview.text = StreamCenterBackupManager.normalizedFileName(it?.toString().orEmpty())
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(sectionLabel("Nome del file"))
            addView(nameInput)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = cardBackground(COLOR_CARD_ALT, tint(COLOR_BACKUP, "55"), 12)
                layoutParams = verticalParams(top = 10)
                addView(bodyText("Anteprima", 11).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor(COLOR_BACKUP))
                })
                addView(preview)
            })
            addView(bodyText(
                "Segnaposto: %data%, %giorno%, %dd%, %mm%, %yyyy%, %ora%, %minuti%, %secondi%, %versione%.",
                11,
            ).apply {
                setPadding(dp(2), dp(10), dp(2), 0)
            })
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Nome del backup"))
            .setView(content)
            .setPositiveButton("Esporta", null)
            .setNegativeButton("Chiudi", null)
            .create()
        presentBackupDialog(dialog)
        setBackupBackNavigation(dialog, ::showBackupChoiceDialog)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val requestedName = nameInput.text?.toString().orEmpty()
            if (requestedName.isBlank()) {
                nameInput.error = "Inserisci un nome"
                return@setOnClickListener
            }
            exportBackup(requestedName)
        }
    }

    private fun exportBackup(requestedName: String) {
        val ctx = context ?: return
        val preferences = sharedPref ?: run {
            saveToast("Configurazione non disponibile")
            return
        }
        val loadingDialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Esporta configurazione"))
            .setMessage("Creazione del backup in corso…")
            .create()
            .apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
        presentBackupDialog(loadingDialog)
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                StreamCenterBackupManager.export(ctx.applicationContext, preferences, requestedName)
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                result.onSuccess { backup ->
                    val completedDialog = AlertDialog.Builder(ctx)
                        .setCustomTitle(dialogTitle("Backup esportato"))
                        .setMessage(
                            "${backup.name}\n\nSalvato in ${StreamCenterBackupManager.locationLabel(ctx)}.",
                        )
                        .setPositiveButton("Chiudi", null)
                        .create()
                    presentBackupDialog(completedDialog)
                    completedDialog.setOnCancelListener { showBackupChoiceDialog() }
                }.onFailure {
                    showBackupError("Esportazione non riuscita", it)
                }
            }
        }
    }

    private fun showImportPicker() {
        val ctx = context ?: return
        var cancelled = false
        val loadingDialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Importa configurazione"))
            .setMessage("Ricerca dei backup nella cartella…")
            .setNegativeButton("Chiudi", null)
            .create()
        presentBackupDialog(loadingDialog) { cancelled = true }
        setBackupBackNavigation(loadingDialog, ::showBackupChoiceDialog)
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { StreamCenterBackupManager.list(ctx.applicationContext) }
            withContext(Dispatchers.Main) {
                if (!isAdded || cancelled) return@withContext
                result.onSuccess(::showImportFilesDialog).onFailure {
                    showBackupError("Cartella non disponibile", it)
                }
            }
        }
    }

    private fun showImportFilesDialog(files: List<StreamCenterBackupFile>) {
        val ctx = context ?: return
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(settingsRow(
                title = if (files.size == 1) "1 backup disponibile" else "${files.size} backup disponibili",
                summary = StreamCenterBackupManager.locationLabel(ctx),
                icon = "\uD83D\uDCC1",
                accent = COLOR_BACKUP,
                fillColor = COLOR_CARD_ALT,
                strokeColor = tint(COLOR_BACKUP, "55"),
                topMargin = 0,
            ).view)
            if (files.isEmpty()) {
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(28), dp(20), dp(24))
                    addView(iconBadge("\uD83D\uDCED", COLOR_MUTED, size = 48))
                    addView(titleText("Nessun backup trovato", 15, true).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(12), 0, 0)
                    })
                    addView(bodyText("Esporta una configurazione oppure scegli un'altra cartella.", 12).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(5), 0, 0)
                    })
                })
            } else {
                val rows = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    files.forEach { backup ->
                        val renameButton = iconButton("✎", "Rinomina ${backup.name}", COLOR_BACKUP) {
                                showRenameBackupDialog(backup) { showImportFilesDialog(files) }
                            }
                        val deleteButton = deleteIconButton("Elimina ${backup.name}") {
                                confirmDeleteBackup(backup) { showImportFilesDialog(files) }
                            }
                        val row = settingsRow(
                            title = StreamCenterBackupManager.fileNameWithoutExtension(backup.name),
                            summary = backupMetadata(ctx, backup),
                            icon = "\uD83D\uDCE6",
                            accent = COLOR_BACKUP,
                            fillColor = COLOR_CARD_ALT,
                            strokeColor = tint(COLOR_BACKUP, "55"),
                            trailingViews = listOf(renameButton, deleteButton),
                            touchTarget = null,
                            topMargin = 8,
                        ) {
                            confirmImport(backup) { showImportFilesDialog(files) }
                        }
                        row.title.maxLines = 2
                        row.view.contentDescription = "Importa ${backup.name}"
                        row.view.setOnLongClickListener {
                            showBackupContent(backup) { showImportFilesDialog(files) }
                            true
                        }
                        addView(row.view)
                    }
                }
                addView(ScrollView(ctx).apply {
                    isVerticalScrollBarEnabled = false
                    addView(rows)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp((files.size * 108).coerceIn(108, 432)),
                ))
            }
        }
        val builder = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Scegli un backup"))
            .setView(content)
            .setNegativeButton("Chiudi", null)
        if (files.isEmpty()) {
            builder.setPositiveButton("Percorso", null)
        }
        val alertDialog = builder.create()
        presentBackupDialog(alertDialog)
        setBackupBackNavigation(alertDialog, ::showBackupChoiceDialog)
        if (files.isEmpty()) {
            alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                showBackupChoiceDialog()
                chooseBackupDirectory()
            }
        }
    }

    private fun showRenameBackupDialog(
        backup: StreamCenterBackupFile,
        onBack: () -> Unit,
    ) {
        val ctx = context ?: return
        val currentName = StreamCenterBackupManager.fileNameWithoutExtension(backup.name)
        val nameInput = input(currentName).apply {
            hint = "Nome del backup"
            filters = arrayOf(InputFilter.LengthFilter(120))
            layoutParams = verticalParams(top = 6)
        }
        val preview = bodyText(StreamCenterBackupManager.normalizedFileName(currentName), 12).apply {
            setTextColor(Color.parseColor(COLOR_TEXT))
            setPadding(0, dp(3), 0, 0)
        }
        nameInput.doAfterTextChanged {
            preview.text = StreamCenterBackupManager.normalizedFileName(it?.toString().orEmpty())
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(sectionLabel("Nuovo nome"))
            addView(nameInput)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = cardBackground(COLOR_CARD_ALT, tint(COLOR_BACKUP, "55"), 12)
                layoutParams = verticalParams(top = 10)
                addView(bodyText("Anteprima", 11).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor(COLOR_BACKUP))
                })
                addView(preview)
            })
            addView(bodyText(
                "Segnaposto: %data%, %giorno%, %dd%, %mm%, %yyyy%, %ora%, %minuti%, %secondi%, %versione%.",
                11,
            ).apply {
                setPadding(dp(2), dp(10), dp(2), 0)
            })
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Rinomina backup"))
            .setView(content)
            .setPositiveButton("Rinomina", null)
            .setNegativeButton("Chiudi", null)
            .create()
        presentBackupDialog(dialog)
        setBackupBackNavigation(dialog, onBack)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val requestedName = nameInput.text?.toString().orEmpty()
            if (requestedName.isBlank()) {
                nameInput.error = "Inserisci un nome"
                return@setOnClickListener
            }
            performBackupFileAction(
                title = "Rinomina backup",
                message = "Rinomina del backup in corso…",
                operation = { appContext ->
                    StreamCenterBackupManager.renameBackup(appContext, backup, requestedName)
                },
            ) { renamed ->
                saveToast("Backup rinominato: ${renamed.name}")
                showImportPicker()
            }
        }
    }

    private fun confirmDeleteBackup(
        backup: StreamCenterBackupFile,
        onBack: () -> Unit,
    ) {
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Elimina backup"))
            .setMessage("Vuoi eliminare definitivamente ${backup.name}?")
            .setPositiveButton("Elimina", null)
            .setNegativeButton("Chiudi", null)
            .create()
        presentBackupDialog(dialog)
        setBackupBackNavigation(dialog, onBack)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            performBackupFileAction(
                title = "Elimina backup",
                message = "Eliminazione del backup in corso…",
                operation = { appContext ->
                    StreamCenterBackupManager.deleteBackup(appContext, backup)
                },
            ) {
                saveToast("Backup eliminato")
                showImportPicker()
            }
        }
    }

    private fun showBackupContent(
        backup: StreamCenterBackupFile,
        onBack: () -> Unit,
    ) {
        val ctx = context ?: return
        val loadingDialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Contenuto backup"))
            .setMessage("Lettura del backup in corso…")
            .create()
            .apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
        presentBackupDialog(loadingDialog)
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                StreamCenterBackupManager.readContent(ctx.applicationContext, backup)
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                result.onSuccess { content ->
                    showBackupContentDialog(backup, content, onBack)
                }.onFailure { error ->
                    val dialog = AlertDialog.Builder(ctx)
                        .setCustomTitle(dialogTitle("Contenuto non disponibile"))
                        .setMessage(error.message ?: "Impossibile leggere il backup.")
                        .setNegativeButton("Indietro", null)
                        .create()
                    presentBackupDialog(dialog)
                    setBackupBackNavigation(dialog, onBack)
                }
            }
        }
    }

    private fun showBackupContentDialog(
        backup: StreamCenterBackupFile,
        rawContent: String,
        onBack: () -> Unit,
    ) {
        val ctx = context ?: return
        val formattedContent = runCatching { JSONObject(rawContent).toString(2) }.getOrDefault(rawContent)
        val contentText = TextView(ctx).apply {
            text = formattedContent
            setTextColor(Color.parseColor(COLOR_TEXT))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
            addView(bodyText(backup.name, 12).apply {
                setTextColor(Color.parseColor(COLOR_MUTED))
                maxLines = 2
            })
            addView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = true
                background = cardBackground(COLOR_INPUT_FILL, tint(COLOR_BACKUP, "44"), 12)
                addView(contentText)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(400),
            ).apply {
                topMargin = dp(8)
            })
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Contenuto backup"))
            .setView(content)
            .setNegativeButton("Indietro", null)
            .create()
        presentBackupDialog(dialog)
        setBackupBackNavigation(dialog, onBack)
    }

    private fun <T> performBackupFileAction(
        title: String,
        message: String,
        operation: (Context) -> T,
        onSuccess: (T) -> Unit,
    ) {
        val ctx = context ?: return
        val loadingDialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle(title))
            .setMessage(message)
            .create()
            .apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
        presentBackupDialog(loadingDialog)
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { operation(ctx.applicationContext) }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                result.onSuccess(onSuccess).onFailure {
                    showBackupError(title, it)
                }
            }
        }
    }

    private fun confirmImport(
        backup: StreamCenterBackupFile,
        onBack: () -> Unit,
    ) {
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Importa configurazione"))
            .setMessage("Vuoi importare ${backup.name}?")
            .setPositiveButton("Importa", null)
            .setNegativeButton("Chiudi", null)
            .create()
        presentBackupDialog(dialog)
        setBackupBackNavigation(dialog, onBack)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            importBackup(backup)
        }
    }

    private fun importBackup(backup: StreamCenterBackupFile) {
        val ctx = context ?: return
        val preferences = sharedPref ?: run {
            saveToast("Configurazione non disponibile")
            return
        }
        val loadingDialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Importa configurazione"))
            .setMessage("Ripristino del backup in corso…")
            .create()
            .apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
        presentBackupDialog(loadingDialog)
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                StreamCenterBackupManager.import(ctx.applicationContext, preferences, backup)
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                result.onSuccess { restored ->
                    StreamCenter.resetSourceDomainChecks()
                    StreamCenterPlugin.refreshCatalogs()
                    resetRestartNeeded()
                    refreshVisibleSettingsEffects()
                    val completedDialog = AlertDialog.Builder(ctx)
                        .setCustomTitle(dialogTitle("Importazione completata"))
                        .setMessage(
                            "Ripristinate ${restored.preferenceCount} impostazioni dal backup della " +
                                "versione ${restored.sourceVersion}. La configurazione è già attiva.",
                        )
                        .setPositiveButton("Chiudi", null)
                        .create()
                    presentBackupDialog(completedDialog)
                    completedDialog.setOnCancelListener { showBackupChoiceDialog() }
                }.onFailure {
                    showBackupError("Importazione non riuscita", it)
                }
            }
        }
    }

    private fun backupMetadata(ctx: Context, backup: StreamCenterBackupFile): String {
        val date = if (backup.lastModified > 0L) {
            SimpleDateFormat("dd/MM/yyyy · HH:mm", Locale.ITALY).format(Date(backup.lastModified))
        } else {
            "Data non disponibile"
        }
        val size = Formatter.formatShortFileSize(ctx, backup.size.coerceAtLeast(0L))
        return "$date · $size"
    }

    private fun showBackupError(title: String, error: Throwable) {
        val ctx = context ?: return
        val returnToBackup = activeBackupDialog?.isShowing == true
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle(title))
            .setMessage(error.message ?: "Si è verificato un errore imprevisto.")
            .setPositiveButton("Chiudi", null)
            .create()
        presentBackupDialog(dialog)
        if (returnToBackup) {
            dialog.setOnCancelListener { showBackupChoiceDialog() }
        }
    }

    private var activeApiDialog: AlertDialog? = null

    private data class ApiCheckRowViews(
        val container: LinearLayout,
        val badge: TextView,
        val detail: TextView,
    )

    protected fun checkApis(includeDisabled: Boolean = false) {
        val ctx = context ?: return
        activeApiDialog?.dismiss()
        val preferences = sharedPref
        val checks = StreamCenterAvailabilityChecker.checks(preferences)
        val rows = mutableMapOf<String, ApiCheckRowViews>()
        val results = checks.associate { it.id to AvailabilityResult(AvailabilityState.WAITING, "") }.toMutableMap()
        val accounts = cloudstreamConnectionResults()
        var checkJob: Job? = null
        val checkedAt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).format(Date())

        val summary = bodyText(availabilitySummary(results.values, checks.size), 12).apply {
            setTextColor(Color.parseColor(COLOR_TEXT))
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = checks.size
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.parseColor(COLOR_API_CHECK))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
                bottomMargin = dp(12)
            }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
            background = cardBackground(COLOR_BACKGROUND, COLOR_STROKE, 20)
            addView(bodyText(
                "Controlla accesso e risposte delle fonti; la riproduzione non è garantita. ", 12,
            ))
        }
        val includeDisabledToggle = CheckBox(ctx).apply {
            text = "Verifica anche le fonti disattivate"
            textSize = 13f
            setTextColor(Color.parseColor(COLOR_TEXT))
            buttonTintList = ColorStateList.valueOf(Color.parseColor(COLOR_API_CHECK))
            isChecked = includeDisabled
            minimumHeight = dp(48)
        }
        content.addView(includeDisabledToggle)

        fun addHeading(title: String, accent: String) {
            content.addView(titleText(title, 14, true).apply {
                setTextColor(Color.parseColor(accent))
                layoutParams = verticalParams(top = 12)
                setPadding(dp(4), 0, 0, dp(2))
            })
        }
        AvailabilityGroup.entries.forEach { group ->
            addHeading(group.title, when (group) {
                AvailabilityGroup.TORRENT -> COLOR_TORRENT
                AvailabilityGroup.SITES -> COLOR_SOURCES
                else -> COLOR_API_CHECK
            })
            val entries = checks.filter { it.group == group }
            if (entries.isEmpty()) content.addView(bodyText(
                when (group) {
                    AvailabilityGroup.STREMIO -> "Nessun add-on Stremio configurato"
                    AvailabilityGroup.SECTIONS -> "Nessuna sezione attiva"
                    else -> "Nessuna fonte installata"
                }, 12,
            ))
            entries.forEach { check ->
                val row = createApiCheckRow(ctx, check.name)
                rows[check.id] = row
                setApiCheckState(row, results.getValue(check.id))
                content.addView(row.container)
            }
        }
        addHeading("Account CloudStream", COLOR_CLOUDSTREAM_SERVICES)
        accounts.forEach { (name, connected) ->
            val row = createApiCheckRow(ctx, name)
            setApiCheckState(row, AvailabilityResult(
                if (connected) AvailabilityState.SUCCESS else AvailabilityState.DISABLED, "",
            ), if (connected) "Collegato" else "Non collegato")
            content.addView(row.container)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(dialogTitle("Verifica API e Fonti"))
                addView(summary)
                addView(progress)
            })
            .setView(ScrollView(ctx).apply { addView(content) })
            .setPositiveButton("Copia report", null)
            .setNeutralButton("Riprova", null)
            .setNegativeButton("Chiudi", null)
            .create()
        activeApiDialog = dialog
        applyDialogBackdrop(dialog) {
            checkJob?.cancel()
            if (activeApiDialog === dialog) activeApiDialog = null
        }
        dialog.show()
        includeDisabledToggle.setOnCheckedChangeListener { _, checked ->
            dialog.dismiss()
            checkApis(checked)
        }
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val report = buildString {
                appendLine("Data: $checkedAt · Versione: ${BuildConfig.PLUGIN_VERSION}")
                append(availabilityReport(checks, results))
                appendLine()
                appendLine("Account CloudStream (collegamenti locali; accesso remoto non verificato)")
                accounts.forEach { (name, connected) ->
                    appendLine("$name: ${if (connected) "collegato" else "non collegato"}")
                }
            }
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText("Verifica API e Fonti", report))
            saveToast("Report copiato")
        }
        val retryButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL).apply {
            isEnabled = false
            setOnClickListener {
                dialog.dismiss()
                checkApis(includeDisabled)
            }
        }
        fun renderSummary() {
            progress.progress = results.values.count { it.state != AvailabilityState.WAITING && it.state != AvailabilityState.RUNNING }
            summary.text = availabilitySummary(results.values, checks.size)
        }
        checkJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                AvailabilityRunner.run(checks, includeDisabled,
                    canUseInternet = { StreamCenterVpnGuard.canUseInternet(preferences) },
                ) { check, result ->
                    withContext(Dispatchers.Main) {
                        if (dialog.isShowing) {
                            results[check.id] = result
                            rows[check.id]?.let { setApiCheckState(it, result) }
                            renderSummary()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (dialog.isShowing) {
                    checks.filter { results[it.id]?.state in setOf(AvailabilityState.WAITING, AvailabilityState.RUNNING) }
                        .forEach { check ->
                            val result = AvailabilityResult(AvailabilityState.FAILURE, "Verifica interrotta: riprova")
                            results[check.id] = result
                            rows[check.id]?.let { setApiCheckState(it, result) }
                        }
                }
            } finally {
                if (dialog.isShowing) {
                    renderSummary()
                    retryButton.isEnabled = true
                }
            }
        }
    }

    override fun onDestroyView() {
        activeApiDialog?.dismiss()
        activeApiDialog = null
        super.onDestroyView()
    }

    private fun createApiCheckRow(ctx: Context, name: String): ApiCheckRowViews {
        val badge = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) }
        }
        val label = titleText(name, 13, true)
        val detail = bodyText("", 12).apply { setPadding(0, dp(3), 0, 0) }
        val labels = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(label)
            addView(detail)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(10), dp(10), dp(12), dp(10))
            layoutParams = verticalParams(top = 6)
            addView(badge)
            addView(labels)
        }
        return ApiCheckRowViews(container, badge, detail)
    }

    private fun setApiCheckState(row: ApiCheckRowViews, result: AvailabilityResult, labelOverride: String? = null) {
        val (symbol, color) = when (result.state) {
            AvailabilityState.WAITING -> "•" to COLOR_MUTED
            AvailabilityState.RUNNING -> "…" to COLOR_ACCENT
            AvailabilityState.SUCCESS -> "✓" to COLOR_SUCCESS
            AvailabilityState.WARNING -> "!" to COLOR_ACCENT
            AvailabilityState.FAILURE -> "×" to COLOR_DANGER
            AvailabilityState.DISABLED -> "•" to COLOR_MUTED
            AvailabilityState.BLOCKED -> "!" to COLOR_ACCENT
        }
        row.badge.text = symbol
        row.badge.setTextColor(Color.parseColor(color))
        row.badge.background = cardBackground(tint(color, "22"), tint(color, "99"), 17)
        row.detail.text = buildString {
            append(labelOverride ?: result.state.label)
            result.elapsedMs?.let { append(" · $it ms") }
            if (result.detail.isNotBlank()) append("\n${result.detail}")
        }
        row.detail.setTextColor(Color.parseColor(color))
        row.container.background = cardBackground(COLOR_CARD_ALT, tint(color, "66"), 16)
        row.container.alpha = if (result.state == AvailabilityState.DISABLED) 0.64f else 1f
    }

    private fun cloudstreamConnectionResults(): List<Pair<String, Boolean>> {
        val accountApis = runCatching { AccountManager.allApis.toList() }.getOrDefault(emptyList())
        val syncApis = runCatching { AccountManager.syncApis.toList() }.getOrDefault(emptyList())
        fun connected(name: String): Boolean = accountApis
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.authData() != null
        fun syncConnected(syncIdName: SyncIdName): Boolean = syncApis
            .firstOrNull { it.syncIdName == syncIdName }
            ?.authData() != null
        return listOf(
            "MyAnimeList" to syncConnected(SyncIdName.MyAnimeList),
            "Kitsu" to syncConnected(SyncIdName.Kitsu),
            "AniList" to syncConnected(SyncIdName.Anilist),
            "Simkl" to syncConnected(SyncIdName.Simkl),
            "OpenSubtitles" to connected("OpenSubtitles"),
            "SubDL" to connected("SubDL"),
            "AnimeSkip" to connected("AnimeSkip"),
        )
    }

    private fun openFeedback(prefix: String) {
        val title = Uri.encode("StreamCenter $prefix")
        openUrl("${StreamCenterPlugin.FEEDBACK_ISSUES_URL}?title=$title")
    }

}
