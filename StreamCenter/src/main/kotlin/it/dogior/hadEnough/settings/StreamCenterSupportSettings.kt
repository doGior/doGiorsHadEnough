package it.dogior.hadEnough.settings

import it.dogior.hadEnough.*

import android.animation.ArgbEvaluator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.core.graphics.ColorUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.sin
open class StreamCenterSupportSettingsFragment : StreamCenterBaseSettingsFragment() {
    private val supportSparkleTargets = mutableListOf<BorderSparkleTarget>()
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
        const val CHANGELOG_URL = "https://telegra.ph/StreamCenter-Changelog-07-18-2"
        const val CHANGELOG_TIMEOUT_MS = 10_000L
        const val API_CHECK_TIMEOUT_MS = 12_000L
        const val TELEGRAM_GROUP_URL = "https://t.me/cloudstream_italia"
        val versionHeaderPattern = Regex("^#{2,3}\\s*(.*?)(?:\\s+INIZIO)?\\s*$", RegexOption.IGNORE_CASE)
        val endMarkerPattern = Regex("^#{2,3}\\s*(?:FINE)?\\s*$", RegexOption.IGNORE_CASE)
        val primaryHeaderPattern = Regex(
            "^#\\s*Modific(?:a|he)\\s+Principal(?:e|i)\\b.*$",
            RegexOption.IGNORE_CASE,
        )
        val secondaryHeaderPattern = Regex(
            "^#\\s*Modific(?:a|he)\\s+Secondari(?:a|e)\\b.*$",
            RegexOption.IGNORE_CASE,
        )
    }

    private enum class ChangelogSection {
        NONE,
        PRIMARY,
        SECONDARY,
    }

    private data class ChangelogVersion(
        val title: String,
        val primaryChanges: MutableList<String> = mutableListOf(),
        val secondaryChanges: MutableList<String> = mutableListOf(),
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        supportSparkleTargets.clear()
        val content = rootContainer()
        content.minimumHeight = standardSubmenuMinimumHeight()
        content.addView(
            header(
                title = "Supporto",
                icon = "🛟",
                accent = COLOR_SUPPORT,
            ),
        )

        content.addView(supportCard(
            icon = "💬",
            title = "Invia feedback",
            summary = "Segnala un problema o proponi un miglioramento.",
            accent = COLOR_FEEDBACK,
        ) {
            showFeedbackChoiceDialog()
        })
        content.addView(supportCard(
            icon = "\uD83D\uDCDD",
            title = "Cambiamenti",
            summary = "Consulta le novità.",
            accent = COLOR_SUPPORT,
        ) {
            showChangelogDialog()
        })
        content.addView(supportCard(
            icon = "✨",
            title = "Effetti visivi",
            summary = "Animazioni, sfocature e bagliori.",
            accent = COLOR_VISUAL_EFFECTS,
        ) {
            showVisualEffectsDialog()
        })
        content.addView(supportCard(
            icon = "\uD83D\uDCBE",
            title = "Esporta/Importa",
            summary = "Crea un backup completo o ripristina una configurazione salvata.",
            accent = COLOR_BACKUP,
        ) {
            showBackupChoiceDialog()
        })
        content.addView(supportCard(
            icon = "♻️",
            title = "Ripristina tutte le impostazioni",
            summary = "Cancella preferenze, sessioni e dati salvati.",
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
                        refreshVisibleSettingsEffects()
                        resetRestartNeeded()
                        saveToast("StreamCenter ripristinato")
                    }.onFailure {
                        showBackupError("Ripristino non riuscito", it)
                    }
                }
                .setNegativeButton("Annulla", null)
                .create()
            applyDialogBackdrop(alertDialog)
            alertDialog.show()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18)
        })

        startBorderSparkleCycle(supportSparkleTargets)
        return scroll(content, fixedSubmenuHeight = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        supportSparkleTargets.clear()
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
        supportSparkleTargets += BorderSparkleTarget(card, accent)
        return card
    }

    private fun showChangelogDialog() {
        val ctx = context ?: return
        var loadingCancelled = false
        var loadingFinished = false
        var timeoutAction: Runnable? = null
        val loadingDialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Cambiamenti"))
            .setMessage("Recupero delle modifiche in corso…")
            .setNegativeButton("Annulla", null)
            .create()
        applyDialogBackdrop(loadingDialog) {
            if (!loadingFinished) loadingCancelled = true
            timeoutAction?.let { loadingDialog.window?.decorView?.removeCallbacks(it) }
        }
        loadingDialog.show()
        val changelogTimeoutAction = Runnable {
            if (!loadingDialog.isShowing || loadingFinished || loadingCancelled) return@Runnable
            loadingFinished = true
            loadingDialog.dismiss()
            Toast.makeText(
                ctx,
                "Impossibile recuperare i cambiamenti.",
                Toast.LENGTH_LONG,
            ).show()
        }
        timeoutAction = changelogTimeoutAction
        loadingDialog.window?.decorView?.postDelayed(changelogTimeoutAction, CHANGELOG_TIMEOUT_MS)

        CoroutineScope(Dispatchers.IO).launch {
            val versions = try {
                withTimeout(CHANGELOG_TIMEOUT_MS) { fetchChangelogVersions() }
            } catch (_: TimeoutCancellationException) {
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                if (!isAdded || loadingCancelled || loadingFinished) return@withContext
                loadingFinished = true
                loadingDialog.window?.decorView?.removeCallbacks(changelogTimeoutAction)
                loadingDialog.dismiss()
                if (versions.isEmpty()) {
                    Toast.makeText(
                        ctx,
                        "Impossibile recuperare i cambiamenti.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    showChangelogVersionPicker(versions)
                }
            }
        }
    }

    private suspend fun fetchChangelogVersions(): List<ChangelogVersion> {
        val html = app.get(
            CHANGELOG_URL,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Android) StreamCenter",
                "Accept-Language" to "it-IT,it;q=0.9",
            ),
            timeout = 8L,
        ).text
        val document = Jsoup.parse(html, CHANGELOG_URL)
        val article = document.selectFirst("#_tl_editor")
            ?: document.selectFirst(".tl_article_content")
            ?: return emptyList()
        val lines = article.children().flatMap { element ->
            val listItems = element.children().filter { it.tagName().equals("li", ignoreCase = true) }
            val texts = if (listItems.isEmpty()) {
                listOf(element.wholeText())
            } else {
                listItems.map { "- ${it.text()}" }
            }
            texts.flatMap { it.lineSequence().toList() }
        }.map(::normalizeChangelogLine).filter { it.isNotBlank() }

        val versions = mutableListOf<ChangelogVersion>()
        var currentVersion: ChangelogVersion? = null
        var currentSection = ChangelogSection.NONE
        lines.forEach { line ->
            if (endMarkerPattern.matches(line)) {
                currentVersion = null
                currentSection = ChangelogSection.NONE
                return@forEach
            }

            val versionTitle = versionHeaderPattern.matchEntire(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf(::isVersionTitle)
            if (versionTitle != null) {
                currentVersion = ChangelogVersion(versionTitle)
                versions += currentVersion
                currentSection = ChangelogSection.NONE
                return@forEach
            }

            when {
                primaryHeaderPattern.matches(line) -> currentSection = ChangelogSection.PRIMARY
                secondaryHeaderPattern.matches(line) -> currentSection = ChangelogSection.SECONDARY
                currentVersion != null && currentSection != ChangelogSection.NONE -> {
                    val change = line.replaceFirst(Regex("^[-–—]+\\s*"), "").trim()
                    if (change.isNotEmpty()) {
                        when (currentSection) {
                            ChangelogSection.PRIMARY -> currentVersion.primaryChanges += change
                            ChangelogSection.SECONDARY -> currentVersion.secondaryChanges += change
                            ChangelogSection.NONE -> Unit
                        }
                    }
                }
            }
        }
        return versions
    }

    private fun normalizeChangelogLine(value: String): String {
        return value.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun isVersionTitle(value: String): Boolean {
        return value.equals("Prima Versione", ignoreCase = true) ||
            value.startsWith("Dalla V", ignoreCase = true) ||
            value.matches(Regex("^V\\d+(?:\\.\\d+)?$", RegexOption.IGNORE_CASE))
    }

    private fun showChangelogVersionPicker(versions: List<ChangelogVersion>) {
        val ctx = context ?: return
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(14))
        }
        lateinit var dialog: AlertDialog
        versions.forEach { version ->
            val status = changelogVersionStatus(version)
            val accent = status?.second ?: COLOR_SUPPORT
            val icon = when {
                isInstalledPluginVersion(version.title) -> "\u2713"
                isUpcomingPluginVersion(version.title) -> "\u2726"
                else -> "V"
            }
            val statusView = status?.let { (label, color) ->
                chip(label, color)
            }
            val arrow = chevron(accent)
            list.addView(settingsRow(
                title = version.title,
                summary = changelogVersionSummary(version),
                accent = accent,
                fillColor = COLOR_CARD_ALT,
                strokeColor = tint(accent, "66"),
                leadingView = iconBadge(icon, accent, size = 40, marginEnd = 12),
                statusView = statusView,
                trailingViews = listOf(arrow),
                topMargin = 8,
                touchTarget = arrow,
            ) {
                dialog.dismiss()
                showChangelogVersion(version, versions)
            }.view)
        }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogBrandTitle(
                "Scegli una versione",
                iconBadge("V", COLOR_SUPPORT, size = 40, marginEnd = 10),
                COLOR_SUPPORT,
            ))
            .setView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(list)
            })
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun changelogVersionSummary(version: ChangelogVersion): String {
        val totalChanges = version.primaryChanges.size + version.secondaryChanges.size
        if (totalChanges == 0) return "Nessuna modifica indicata"
        return buildList {
            add("$totalChanges modifiche")
            if (version.primaryChanges.isNotEmpty()) add("${version.primaryChanges.size} principali")
            if (version.secondaryChanges.isNotEmpty()) add("${version.secondaryChanges.size} secondarie")
        }.joinToString(" · ")
    }

    private fun changelogVersionStatus(version: ChangelogVersion): Pair<String, String>? {
        return when {
            isInstalledPluginVersion(version.title) -> "Installata" to COLOR_SUCCESS
            isUpcomingPluginVersion(version.title) -> "Prossima" to COLOR_ACCENT
            else -> null
        }
    }

    private fun isInstalledPluginVersion(changelogVersion: String): Boolean {
        val pluginVersion = BuildConfig.PLUGIN_VERSION.trim()
        val title = changelogVersion.trim()
        return title.equals("V$pluginVersion", ignoreCase = true) ||
            (pluginVersion == "1" && title.equals("Prima Versione", ignoreCase = true)) ||
            title.endsWith("alla V$pluginVersion", ignoreCase = true)
    }

    private fun isUpcomingPluginVersion(changelogVersion: String): Boolean {
        val installedVersion = BuildConfig.PLUGIN_VERSION.trim().toIntOrNull() ?: return false
        return changelogMajorVersion(changelogVersion) == installedVersion + 1
    }

    private fun changelogMajorVersion(changelogVersion: String): Int? {
        val directVersion = Regex("^V(\\d+)(?:\\.\\d+)?$", RegexOption.IGNORE_CASE)
            .matchEntire(changelogVersion.trim())
            ?.groupValues
            ?.getOrNull(1)
        val targetVersion = Regex("\\balla\\s+V(\\d+)(?:\\.\\d+)?$", RegexOption.IGNORE_CASE)
            .find(changelogVersion.trim())
            ?.groupValues
            ?.getOrNull(1)
        return (directVersion ?: targetVersion)?.toIntOrNull()
    }

    private fun showChangelogVersion(
        version: ChangelogVersion,
        versions: List<ChangelogVersion>,
    ) {
        val ctx = context ?: return
        val accent = changelogVersionStatus(version)?.second ?: COLOR_SUPPORT
        val icon = when {
            isInstalledPluginVersion(version.title) -> "\u2713"
            isUpcomingPluginVersion(version.title) -> "\u2726"
            else -> "V"
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(12))
            addView(chip(changelogVersionSummary(version), accent).apply {
                layoutParams = verticalParams(top = 4)
            })
            if (version.primaryChanges.isNotEmpty()) {
                addChangelogSection(
                    this,
                    "Modifiche principali",
                    version.primaryChanges,
                    accent,
                    primary = true,
                )
            }
            if (version.secondaryChanges.isNotEmpty()) {
                addChangelogSection(
                    this,
                    "Modifiche secondarie",
                    version.secondaryChanges,
                    accent,
                    primary = false,
                )
            }
            if (version.primaryChanges.isEmpty() && version.secondaryChanges.isEmpty()) {
                addView(emptyStateCard("Nessuna modifica indicata", accent).apply {
                    layoutParams = verticalParams(top = 14)
                })
            }
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogBrandTitle(
                version.title,
                iconBadge(icon, accent, size = 40, marginEnd = 10),
                accent,
            ))
            .setView(ScrollView(ctx).apply { addView(content) })
            .setPositiveButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            dialog.dismiss()
            showChangelogVersionPicker(versions)
        }
    }

    private fun addChangelogSection(
        content: LinearLayout,
        title: String,
        changes: List<String>,
        accent: String,
        primary: Boolean,
    ) {
        content.addView(sectionLabel(title).apply {
            setTextColor(Color.parseColor(accent))
            layoutParams = verticalParams(top = 16)
        })
        changes.forEach { change ->
            val changeRow = settingsRow(
                title = change,
                accent = accent,
                fillColor = COLOR_CARD_ALT,
                strokeColor = tint(accent, "55"),
                leadingView = iconBadge(if (primary) "+" else "•", accent, size = 30, marginEnd = 10),
                topMargin = 8,
            )
            changeRow.title.apply {
                textSize = 13f
                typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            content.addView(changeRow.view)
        }
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
            .setNegativeButton("Annulla", null)
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
            .setNegativeButton("Annulla", null)
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
            .setNegativeButton("Annulla", null)
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
            .setNegativeButton("Annulla", null)
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
                            topMargin = 8,
                        ) {
                            confirmImport(backup) { showImportFilesDialog(files) }
                        }
                        row.title.maxLines = 2
                        row.view.contentDescription = "Importa ${backup.name}"
                        addView(row.view)
                    }
                }
                addView(ScrollView(ctx).apply {
                    isVerticalScrollBarEnabled = false
                    addView(rows)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp((files.size * 76).coerceIn(84, 390)),
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
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Rinomina backup"))
            .setView(content)
            .setPositiveButton("Rinomina", null)
            .setNegativeButton("Annulla", null)
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
            .setNegativeButton("Annulla", null)
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
            .setNegativeButton("Annulla", null)
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

    private fun showVisualEffectsDialog() {
        val ctx = context ?: return
        fun effectSelected(preferenceKey: String): Boolean {
            return sharedPref?.getBoolean(preferenceKey, true) ?: true
        }

        fun effectOptionRow(
            icon: String,
            title: String,
            preferenceKey: String,
            optionAccent: String,
        ): LinearLayout {
            return switchRow(
                title = title,
                summary = null,
                checked = effectSelected(preferenceKey),
                accent = optionAccent,
                icon = icon,
                strokeColor = tint(optionAccent, "55"),
                topMargin = 8,
            ) { enabled ->
                sharedPref?.edit { putBoolean(preferenceKey, enabled) }
                refreshVisibleSettingsEffects()
            }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
            addView(effectOptionRow(
                icon = "↔",
                title = "Animazioni",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_ANIMATIONS,
                optionAccent = COLOR_VISUAL_EFFECTS,
            ))
            addView(effectOptionRow(
                icon = "◌",
                title = "Sfocatura finestre",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_BLUR,
                optionAccent = COLOR_VISUAL_BLUR,
            ))
            addView(effectOptionRow(
                icon = "✨",
                title = "Intestazione StreamCenter",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_TITLE,
                optionAccent = COLOR_VISUAL_HEADER,
            ))
            addView(effectOptionRow(
                icon = "🌌",
                title = "Universo animato",
                preferenceKey = StreamCenterPlugin.PREF_VISUAL_EFFECTS_PARTICLES,
                optionAccent = COLOR_PARTICLES,
            ))
        }

        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Effetti visivi"))
            .setView(content)
            .setPositiveButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private enum class ApiCheckState {
        WAITING,
        RUNNING,
        SUCCESS,
        FAILURE,
        CONNECTED,
        DISCONNECTED,
    }

    private data class ApiCheckRowViews(
        val container: LinearLayout,
        val badge: TextView,
        val label: TextView,
        val name: String,
    )

    protected fun checkApis() {
        val ctx = context ?: return
        val apiNames = listOf("AniList", "MyAnimeList (Jikan)", "Kitsu", "AniZip", "TMDB")
        val sourceNames = listOf("StreamingCommunity", "VidxGo", "AnimeUnity", "AnimeWorld", "AnimeSaturn")
        val checkNames = apiNames + sourceNames
        val rows = mutableMapOf<String, ApiCheckRowViews>()
        val states = checkNames.associateWith { ApiCheckState.WAITING }.toMutableMap()
        var dialogVisible = true
        var checkFinished = false
        var timeoutAction: Runnable? = null
        val timeoutHandler = Handler(Looper.getMainLooper())

        val summary = bodyText("0 disponibili  ·  0 non raggiungibili", 13).apply {
            setTextColor(Color.parseColor(COLOR_TEXT))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(4), dp(24), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = checkNames.size
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.parseColor(COLOR_API_CHECK))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(5),
            ).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
                bottomMargin = dp(12)
            }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
            background = cardBackground(COLOR_BACKGROUND, COLOR_STROKE, 20)
        }

        fun addSection(title: String, names: List<String>, accent: String) {
            content.addView(titleText(title, 14, true).apply {
                setTextColor(Color.parseColor(accent))
                layoutParams = verticalParams(top = 10)
                setPadding(dp(4), 0, 0, dp(2))
            })
            names.forEach { name ->
                createApiCheckRow(ctx, name).also { row ->
                    rows[name] = row
                    setApiCheckState(row, ApiCheckState.WAITING)
                    content.addView(row.container)
                }
            }
        }

        addSection("API", apiNames, COLOR_API_CHECK)
        addSection("Fonti Streaming", sourceNames, COLOR_SOURCES)
        addSection("Servizi CloudStream", emptyList(), COLOR_CLOUDSTREAM_SERVICES)
        cloudstreamConnectionResults().forEach { (name, connected) ->
            createApiCheckRow(ctx, name).also { row ->
                setApiCheckState(
                    row,
                    if (connected) ApiCheckState.CONNECTED else ApiCheckState.DISCONNECTED,
                )
                content.addView(row.container)
            }
        }

        val dialogHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(dialogTitle("Verifica API e Fonti"))
            addView(summary)
            addView(progress)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogHeader)
            .setView(ScrollView(ctx).apply { addView(content) })
            .setNeutralButton("Riprova", null)
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog) {
            dialogVisible = false
            timeoutAction?.let(timeoutHandler::removeCallbacks)
        }
        dialog.show()

        val retryButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL).apply {
            visibility = View.GONE
            setOnClickListener {
                dialog.dismiss()
                checkApis()
            }
        }

        fun renderSummary(completed: Int) {
            val online = states.count { it.value == ApiCheckState.SUCCESS }
            val unavailable = states.count { it.value == ApiCheckState.FAILURE }
            progress.progress = completed
            summary.text = "$online disponibili  ·  $unavailable non raggiungibili"
        }

        checkNames.forEach { name ->
            states[name] = ApiCheckState.RUNNING
            rows[name]?.let { setApiCheckState(it, ApiCheckState.RUNNING) }
        }
        renderSummary(0)
        val apiTimeoutAction = Runnable {
            if (!dialogVisible || checkFinished) return@Runnable
            checkFinished = true
            states.forEach { (name, state) ->
                if (state == ApiCheckState.WAITING || state == ApiCheckState.RUNNING) {
                    states[name] = ApiCheckState.FAILURE
                    rows[name]?.let {
                        setApiCheckState(it, ApiCheckState.FAILURE, "Timeout della verifica")
                    }
                }
            }
            renderSummary(checkNames.size)
            retryButton.visibility = View.VISIBLE
        }
        timeoutAction = apiTimeoutAction
        timeoutHandler.postDelayed(apiTimeoutAction, API_CHECK_TIMEOUT_MS)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                StreamCenter.checkApisAvailability(sharedPref) { name, isRunning, result, detail ->
                    withContext(Dispatchers.Main) {
                        if (!dialogVisible || checkFinished) return@withContext
                        val row = rows[name] ?: return@withContext
                        val state = if (isRunning) {
                            ApiCheckState.RUNNING
                        } else if (result == true) {
                            ApiCheckState.SUCCESS
                        } else {
                            ApiCheckState.FAILURE
                        }
                        states[name] = state
                        setApiCheckState(row, state, detail)
                        renderSummary(states.count { it.value in setOf(ApiCheckState.SUCCESS, ApiCheckState.FAILURE) })
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (!dialogVisible || checkFinished) return@withContext
                checkFinished = true
                timeoutHandler.removeCallbacks(apiTimeoutAction)
                states.forEach { (name, state) ->
                    if (state == ApiCheckState.WAITING || state == ApiCheckState.RUNNING) {
                        states[name] = ApiCheckState.FAILURE
                        rows[name]?.let {
                            setApiCheckState(it, ApiCheckState.FAILURE, "Verifica interrotta")
                        }
                    }
                }
                renderSummary(checkNames.size)
                retryButton.visibility = View.VISIBLE
            }
        }
    }

    private fun createApiCheckRow(ctx: Context, name: String): ApiCheckRowViews {
        val badge = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) }
        }
        val label = titleText(name, 13, true).apply { maxLines = 2 }
        val labels = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(label)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(10), dp(10), dp(12), dp(10))
            layoutParams = verticalParams(top = 6)
            addView(badge)
            addView(labels)
        }
        return ApiCheckRowViews(container, badge, label, name)
    }

    private fun setApiCheckState(
        row: ApiCheckRowViews,
        state: ApiCheckState,
        detail: String? = null,
    ) {
        val (symbol, label, color) = when (state) {
            ApiCheckState.WAITING -> Triple("\u2022", "In attesa", COLOR_MUTED)
            ApiCheckState.RUNNING -> Triple("\u2026", "Verifica in corso", COLOR_ACCENT)
            ApiCheckState.SUCCESS -> Triple("\u2713", "Raggiungibile", COLOR_SUCCESS)
            ApiCheckState.FAILURE -> Triple("\u00D7", "Non raggiungibile", COLOR_DANGER)
            ApiCheckState.CONNECTED -> Triple("\u2713", "Collegato", COLOR_SUCCESS)
            ApiCheckState.DISCONNECTED -> Triple("\u2022", "Non collegato", COLOR_MUTED)
        }
        row.badge.text = symbol
        row.badge.setTextColor(Color.parseColor(color))
        row.badge.background = cardBackground(tint(color, "22"), tint(color, "99"), 17)
        val status = detail?.trim()?.takeIf(String::isNotBlank)
            ?.let { "$label\n$it" }
            ?: label
        val text = "${row.name} - $status"
        row.label.text = SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(Color.parseColor(color)),
                row.name.length + 3,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        row.container.background = cardBackground(COLOR_CARD_ALT, tint(color, "66"), 16)
        row.container.alpha = if (state == ApiCheckState.DISCONNECTED) 0.64f else 1f
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
