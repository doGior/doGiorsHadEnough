package it.dogior.hadEnough.settings

import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.StreamCenterLogger.LogFileInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class StreamCenterLogsSettingsFragment : StreamCenterBaseSettingsFragment() {
    private data class PendingLogExport(
        val fileName: String,
        val content: String,
    )

    private data class LogDisclosureSection(
        val title: String,
        val items: List<LogDisclosureItem>,
    )

    private data class LogDisclosureItem(
        val title: String,
        val content: String,
    )

    private var pendingLogExport: PendingLogExport? = null
    private val logExportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val export = pendingLogExport ?: return@registerForActivityResult
        pendingLogExport = null
        if (uri == null) {
            StreamCenterLogger.logMenu(
                action = "Esportazione log annullata",
                metadata = mapOf("file" to export.fileName),
            )
            return@registerForActivityResult
        }
        writeLogExport(uri, export)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val content = rootContainer().apply {
            setPadding(paddingLeft, dp(8), paddingRight, paddingBottom)
            minimumHeight = standardSubmenuMinimumHeight()
        }
        content.addView(
            header(
                title = "Log",
                icon = "\uD83D\uDCCB",
                accent = COLOR_LOG,
            ),
        )

        val enabledRow = switchRow(
            title = "Log",
            summary = "",
            checked = StreamCenterLogger.isEnabled(sharedPref),
            accent = COLOR_LOG,
            icon = "\uD83D\uDD0E",
            fixedHeight = true,
        ) { enabled ->
            val preferences = sharedPref
            if (preferences == null) {
                saveToast("Impossibile aggiornare l'impostazione dei log")
            } else {
                StreamCenterLogger.setEnabled(preferences, enabled)
                saveToast(
                    if (enabled) {
                        "Log attivati: è stata avviata una nuova sessione"
                    } else {
                        "Log disattivati"
                    },
                )
            }
        }
        val retentionLabel = bodyText(logRetentionLabel(), 12)
        val retentionRow = settingsRow(
            title = "Conservazione Log",
            icon = "\uD83E\uDDF9",
            accent = COLOR_LOG,
            fillColor = COLOR_CARD_ALT,
            summaryView = retentionLabel,
            trailingViews = listOf(chevron(COLOR_LOG)),
            fixedHeight = true,
        ) {
            showLogRetentionDialog(retentionLabel)
        }.view

        val archiveLogRow = settingsRow(
            title = "Archivio Log",
            summary = "",
            icon = "\uD83D\uDDC2",
            accent = COLOR_LOG,
            fillColor = COLOR_CARD_ALT,
            trailingViews = listOf(chevron(COLOR_LOG)),
            fixedHeight = true,
        ) {
            showLogArchive()
        }.view
        addAdaptiveCardGrid(content, listOf(enabledRow, retentionRow, archiveLogRow))

        return scroll(content, fixedSubmenuHeight = true)
    }

    private fun logRetentionLabel(): String {
        val policy = StreamCenterLogger.retentionPolicy(sharedPref)
        return listOfNotNull(
            policy.days?.let { days ->
                "Elimina dopo " + days + if (days == 1) " giorno" else " giorni"
            },
            policy.maximumLogCount?.let { maximumLogCount ->
                "Conserva gli ultimi $maximumLogCount log"
            },
        ).joinToString(" · ").ifBlank { "Non configurata" }
    }

    private fun showLogRetentionDialog(retentionLabel: TextView) {
        val ctx = context ?: return
        val currentPolicy = StreamCenterLogger.retentionPolicy(sharedPref)
        val daysInput = input(
            (currentPolicy.days ?: 30).toString(),
            widthDp = 86,
        ).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val logCountInput = input(
            (currentPolicy.maximumLogCount ?: 50).toString(),
            widthDp = 86,
        ).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }

        var daysEnabled = currentPolicy.days != null
        var logCountEnabled = currentPolicy.maximumLogCount != null
        lateinit var daysCard: LinearLayout
        lateinit var logCountCard: LinearLayout

        fun valueRow(input: View, suffix: String): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(50), dp(8), 0, 0)
                addView(input)
                addView(bodyText(suffix, 12).apply {
                    setTextColor(Color.parseColor(COLOR_MUTED))
                    setPadding(dp(10), 0, 0, 0)
                })
            }
        }

        val daysValueRow = valueRow(daysInput, "giorni")
        val logCountValueRow = valueRow(logCountInput, "log")

        fun refreshSelection() {
            fun refreshOption(card: LinearLayout, enabled: Boolean, valueRow: View) {
                card.background = interactiveBackground(
                    fill = if (enabled) tint(COLOR_LOG, "1A") else COLOR_CARD_ALT,
                    accent = COLOR_LOG,
                    radius = 16,
                    strokeColor = if (enabled) COLOR_LOG else tint(COLOR_LOG, "55"),
                )
                valueRow.visibility = if (enabled) View.VISIBLE else View.GONE
            }
            refreshOption(daysCard, daysEnabled, daysValueRow)
            refreshOption(logCountCard, logCountEnabled, logCountValueRow)
        }

        fun optionCard(
            icon: String,
            title: String,
            valueRow: View,
            checked: Boolean,
            onCheckedChanged: (Boolean) -> Unit,
        ): LinearLayout {
            val toggle = styledSwitch(checked, COLOR_LOG, onCheckedChanged)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                isFocusable = true
                contentDescription = title
                setOnClickListener { toggle.toggle() }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(iconBadge(icon, COLOR_LOG, size = 38, marginEnd = 12))
                    addView(titleText(title, 16, true).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        )
                    })
                    addView(toggle)
                })
                addView(valueRow)
                addCardTouchFeedback(this, COLOR_LOG)
            }
        }

        daysInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !daysEnabled) {
                daysEnabled = true
                refreshSelection()
            }
        }
        logCountInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !logCountEnabled) {
                logCountEnabled = true
                refreshSelection()
            }
        }
        daysCard = optionCard(
            icon = "\uD83D\uDCC5",
            title = "In base ai giorni",
            valueRow = daysValueRow,
            checked = daysEnabled,
        ) { enabled ->
            daysEnabled = enabled
            refreshSelection()
        }
        logCountCard = optionCard(
            icon = "\uD83D\uDCCB",
            title = "In base alla quantità",
            valueRow = logCountValueRow,
            checked = logCountEnabled,
        ) { enabled ->
            logCountEnabled = enabled
            refreshSelection()
        }
        content.addView(daysCard, verticalParams(top = 8))
        content.addView(logCountCard, verticalParams(top = 8))
        refreshSelection()

        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Conservazione Log"))
            .setView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = true
                addView(content)
            })
            .setPositiveButton("Applica", null)
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            fun valueOrError(input: TextView): Int? {
                val value = input.text.toString().trim().toIntOrNull()
                if (value == null || value <= 0) {
                    input.error = "Inserisci un numero maggiore di zero"
                    return null
                }
                return value
            }

            val days = if (daysEnabled) valueOrError(daysInput) ?: return@setOnClickListener else null
            val maximumLogCount = if (logCountEnabled) {
                valueOrError(logCountInput) ?: return@setOnClickListener
            } else {
                null
            }
            updateLogRetention(
                retentionLabel,
                StreamCenterLogger.RetentionPolicy(days, maximumLogCount),
            )
            dialog.dismiss()
        }
    }

    private fun updateLogRetention(
        retentionLabel: TextView,
        policy: StreamCenterLogger.RetentionPolicy,
    ) {
        val preferences = sharedPref ?: return
        StreamCenterLogger.setRetentionPolicy(preferences, policy)
        retentionLabel.text = logRetentionLabel()
        pruneLogs()
    }

    private fun pruneLogs() {
        val ctx = context ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val removedLogs = StreamCenterLogger.pruneLogs(ctx.applicationContext, sharedPref)
            withContext(Dispatchers.Main) {
                if (!isAdded || removedLogs == 0) return@withContext
                saveToast(
                    "$removedLogs ${if (removedLogs == 1) "log eliminato" else "log eliminati"}",
                )
            }
        }
    }

    private fun showLogArchive() {
        StreamCenterLogger.logMenu(
            action = "Consultazione archivio Log dalle impostazioni",
            metadata = mapOf("origine" to "impostazioni_log"),
        )
        val ctx = context ?: return
        val loadingDialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Archivio Log"))
            .setMessage("Caricamento delle sessioni in corso…")
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(loadingDialog)
        loadingDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { StreamCenterLogger.listLogs(ctx.applicationContext) }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                loadingDialog.dismiss()
                result.onSuccess(::showLogArchiveDialog).onFailure { error ->
                    showLogReadError("Archivio non disponibile", error)
                }
            }
        }
    }

    private fun showLogArchiveDialog(logs: List<LogFileInfo>) {
        val ctx = context ?: return
        if (logs.isEmpty()) {
            val dialog = AlertDialog.Builder(ctx)
                .setCustomTitle(dialogTitle("Archivio vuoto"))
                .setMessage("Non è stata ancora creata alcuna sessione di log.")
                .setNegativeButton("Chiudi", null)
                .create()
            applyDialogBackdrop(dialog)
            dialog.show()
            return
        }

        val entries = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(16))
        }
        lateinit var dialog: AlertDialog
        logs.forEach { log ->
            val arrow = chevron(COLOR_LOG)
            val deleteButton = deleteIconButton(
                description = "Elimina questa sessione di log",
                size = 34,
            ) {
                showDeleteLogConfirmation(log, dialog)
            }
            entries.addView(
                settingsRow(
                    title = if (log.isCurrent) "Sessione corrente" else "Sessione archiviata",
                    summary = "${log.createdAt} · ${Formatter.formatFileSize(ctx, log.sizeBytes)}",
                    icon = if (log.isCurrent) "\uD83D\uDFE2" else "\uD83D\uDCC4",
                    accent = COLOR_LOG,
                    fillColor = COLOR_CARD_ALT,
                    trailingViews = listOf(deleteButton, arrow),
                    touchTarget = arrow,
                    topMargin = 8,
                ) {
                    showStoredLog(log)
                }.view,
            )
        }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Archivio Log"))
            .setView(ScrollView(ctx).apply { addView(entries) })
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun showStoredLog(log: LogFileInfo) {
        StreamCenterLogger.logMenu(
            action = "Consultazione sessione di log archiviata",
            metadata = mapOf(
                "id_sessione" to log.id,
                "creata_il" to log.createdAt,
                "sessione_corrente" to log.isCurrent,
            ),
        )
        showLogLoading(
            title = if (log.isCurrent) "Log corrente" else "Log archiviato",
        ) { context ->
            StreamCenterLogger.readLog(context, log.id)
        }
    }

    private fun showLogLoading(
        title: String,
        operation: (Context) -> String?,
    ) {
        val ctx = context ?: return
        var readFinished = false
        var loadingCancelled = false
        var loadedLog: String? = null
        var readError: Throwable? = null
        val loadingDialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle(title))
            .setMessage("Lettura del log in corso…")
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(
            alertDialog = loadingDialog,
            onDismiss = {
                if (!readFinished) {
                    loadingCancelled = true
                } else if (isAdded) {
                    readError?.let { error ->
                        showLogReadError("Log non disponibile", error)
                    } ?: if (loadedLog.isNullOrBlank()) {
                        showNoLogDialog()
                    } else {
                        showLogContentDialog(title, loadedLog.orEmpty())
                    }
                }
            },
        )
        loadingDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { operation(ctx.applicationContext) }
            withContext(Dispatchers.Main) {
                if (!isAdded || loadingCancelled || !loadingDialog.isShowing) return@withContext
                result.onSuccess { log -> loadedLog = log }
                    .onFailure { error -> readError = error }
                readFinished = true
                loadingDialog.dismiss()
            }
        }
    }

    private fun showNoLogDialog() {
        val ctx = context ?: return
        val message = if (StreamCenterLogger.isEnabled(sharedPref)) {
            "Non è disponibile un log leggibile per questa sessione. Riprova dopo il prossimo avvio di StreamCenter."
        } else {
            "I log sono disattivati. Attivali per avviare una nuova sessione di registrazione."
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Nessun log disponibile"))
            .setMessage(message)
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun showLogReadError(title: String, error: Throwable) {
        StreamCenterLogger.logMenuError("Lettura log non riuscita", error)
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle(title))
            .setMessage(error.message ?: "Impossibile leggere il log della sessione corrente.")
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun showDeleteLogConfirmation(log: LogFileInfo, archiveDialog: AlertDialog) {
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Elimina log"))
            .setMessage("Questa sessione di log verrà eliminata definitivamente.")
            .setPositiveButton("Elimina", null)
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            StreamCenterLogger.logMenu(
                action = "Eliminazione sessione di log confermata",
                metadata = mapOf(
                    "id_sessione" to log.id,
                    "sessione_corrente" to log.isCurrent,
                ),
            )
            dialog.dismiss()
            archiveDialog.dismiss()
            deleteLog(log)
        }
    }

    private fun deleteLog(log: LogFileInfo) {
        val ctx = context ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                check(StreamCenterLogger.deleteLog(ctx.applicationContext, log.id)) {
                    "Il log selezionato non è più disponibile."
                }
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                result.onSuccess {
                    saveToast("Log eliminato")
                    showLogArchive()
                }.onFailure { error ->
                    showLogDeletionError(error)
                }
            }
        }
    }

    private fun showLogDeletionError(error: Throwable) {
        StreamCenterLogger.logMenuError("Eliminazione log non riuscita", error)
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Eliminazione non riuscita"))
            .setMessage(error.message ?: "Impossibile eliminare i log salvati.")
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun showLogContentDialog(title: String, log: String) {
        val ctx = context ?: return
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(16))
            val sections = parseLogDisclosureSections(log)
            if (sections.isEmpty()) {
                addView(bodyText("Il log non contiene informazioni consultabili.", 12).apply {
                    setPadding(dp(4), dp(12), dp(4), dp(12))
                })
            } else {
                sections.forEach { section ->
                    addView(
                        logNavigationRow(
                            title = formatLogSectionTitle(section.title),
                            summary = "${section.items.size} ${if (section.items.size == 1) "tipo di informazione" else "tipi di informazioni"}",
                        ) {
                            showLogSectionDialog(section)
                        },
                    )
                }
            }
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle(title))
            .setView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = true
                background = cardBackground(COLOR_INPUT_FILL, tint(COLOR_LOG, "44"), 12)
                addView(content)
            })
            .setPositiveButton("Esporta", null)
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            requestLogExport(title, log)
            dialog.dismiss()
        }
    }

    private fun showLogSectionDialog(section: LogDisclosureSection) {
        val ctx = context ?: return
        val sectionTitle = formatLogSectionTitle(section.title)
        StreamCenterLogger.logMenu(
            action = "Consultazione categoria log",
            metadata = mapOf(
                "categoria" to sectionTitle,
                "tipi_informazione" to section.items.size,
            ),
        )
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(16))
            section.items.forEach { item ->
                val lineCount = item.content.lineSequence().count { line -> line.isNotBlank() }
                addView(
                    logNavigationRow(
                        title = item.title,
                        summary = if (lineCount == 0) "Nessun dettaglio" else "$lineCount righe registrate",
                    ) {
                        showLogDetailDialog(sectionTitle, item)
                    },
                )
            }
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle(sectionTitle))
            .setView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = true
                background = cardBackground(COLOR_INPUT_FILL, tint(COLOR_LOG, "44"), 12)
                addView(content)
            })
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun showLogDetailDialog(sectionTitle: String, item: LogDisclosureItem) {
        val ctx = context ?: return
        StreamCenterLogger.logMenu(
            action = "Consultazione dettaglio log",
            metadata = mapOf(
                "categoria" to sectionTitle,
                "tipo_informazione" to item.title,
                "righe" to item.content.lineSequence().count { line -> line.isNotBlank() },
            ),
        )
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
            addView(bodyText(sectionTitle, 12).apply {
                setTextColor(Color.parseColor(COLOR_MUTED))
                setPadding(dp(4), dp(2), dp(4), dp(8))
            })
            addView(TextView(ctx).apply {
                text = item.content.ifBlank { "Nessun dettaglio registrato." }
                setTextColor(Color.parseColor(COLOR_TEXT))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            })
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle(item.title))
            .setView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = true
                background = cardBackground(COLOR_INPUT_FILL, tint(COLOR_LOG, "44"), 12)
                addView(content)
            })
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun logNavigationRow(
        title: String,
        summary: String,
        onClick: () -> Unit,
    ): View {
        val arrow = chevron(COLOR_LOG)
        return settingsRow(
            title = title,
            summary = summary,
            accent = COLOR_LOG,
            fillColor = COLOR_CARD_ALT,
            trailingViews = listOf(arrow),
            touchTarget = arrow,
            topMargin = 8,
            onClick = onClick,
        ).view
    }

    private fun parseLogDisclosureSections(log: String): List<LogDisclosureSection> {
        val sections = mutableListOf<LogDisclosureSection>()
        var currentSectionTitle: String? = null
        val currentItems = mutableListOf<LogDisclosureItem>()
        var currentItemTitle: String? = null
        val currentItemContent = StringBuilder()

        fun commitItem() {
            val itemTitle = currentItemTitle ?: return
            currentItems += LogDisclosureItem(itemTitle, currentItemContent.toString().trim())
            currentItemTitle = null
            currentItemContent.clear()
        }

        fun commitSection() {
            val sectionTitle = currentSectionTitle ?: return
            commitItem()
            sections += LogDisclosureSection(sectionTitle, currentItems.toList())
            currentSectionTitle = null
            currentItems.clear()
        }

        log.lineSequence().forEach { line ->
            when {
                line.startsWith("-- ") && !line.startsWith("--- ") -> {
                    commitSection()
                    currentSectionTitle = line.removePrefix("-- ").trim()
                }

                line.startsWith("--- ") && currentSectionTitle != null -> {
                    commitItem()
                    currentItemTitle = line.removePrefix("--- ").trim()
                }

                currentItemTitle != null -> currentItemContent.appendLine(line)
            }
        }
        commitSection()

        return sections
    }

    private fun formatLogSectionTitle(title: String): String {
        return when (title.uppercase(Locale.ITALY)) {
            "INFORMAZIONI MENU" -> "Informazioni menu"
            "INFORMAZIONI SCHEDE" -> "Informazioni schede"
            else -> title
        }
    }

    private fun requestLogExport(title: String, content: String) {
        val export = PendingLogExport(
            fileName = "streamcenter-log-${System.currentTimeMillis()}.txt",
            content = content,
        )
        pendingLogExport = export
        StreamCenterLogger.logMenu(
            action = "Esportazione log richiesta",
            metadata = mapOf(
                "titolo_log" to title,
                "file" to export.fileName,
                "dimensione_byte" to content.toByteArray(Charsets.UTF_8).size,
            ),
        )
        logExportPicker.launch(export.fileName)
    }

    private fun writeLogExport(destination: Uri, export: PendingLogExport) {
        val ctx = context ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                ctx.contentResolver.openOutputStream(destination, "w")
                    ?.bufferedWriter(Charsets.UTF_8)
                    ?.use { writer -> writer.write(export.content) }
                    ?: error("Impossibile aprire il file scelto.")
            }
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                result.onSuccess {
                    StreamCenterLogger.logMenu(
                        action = "Log esportato",
                        metadata = mapOf(
                            "file" to export.fileName,
                            "dimensione_byte" to export.content.toByteArray(Charsets.UTF_8).size,
                        ),
                    )
                    saveToast("Log esportato")
                }.onFailure { error ->
                    showLogExportError(error)
                }
            }
        }
    }

    private fun showLogExportError(error: Throwable) {
        StreamCenterLogger.logMenuError("Esportazione log non riuscita", error)
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Esportazione non riuscita"))
            .setMessage(error.message ?: "Impossibile esportare il log.")
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }
}
