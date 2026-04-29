package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.Editable
import android.text.style.ForegroundColorSpan
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class SpinnerItem<T>(
    val label: String,
    val value: T,
) {
    override fun toString(): String = label
}

abstract class AnimeUnityBaseSettingsFragment : BottomSheetDialogFragment() {

    protected val plugin: AnimeUnityPlugin
        get() = AnimeUnityPlugin.activePlugin
            ?: error("AnimeUnity plugin not available")

    protected val sharedPref: SharedPreferences?
        get() = AnimeUnityPlugin.activeSharedPref

    protected abstract val layoutName: String

    protected fun View.makeTvCompatible() {
        this.setPadding(
            this.paddingLeft + 10,
            this.paddingTop + 10,
            this.paddingRight + 10,
            this.paddingBottom + 10
        )
        this.background = getDrawable("outline")
    }

    protected fun View.applyOutlineBackground() {
        this.background = getDrawable("outline")
    }

    @SuppressLint("DiscouragedApi")
    protected fun getDrawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    @SuppressLint("DiscouragedApi")
    protected fun getString(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
    }

    @SuppressLint("DiscouragedApi")
    protected fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id ?: return null)
    }

    protected fun setupSaveButton(view: View, onClick: () -> Unit) {
        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))
        saveBtn?.setOnClickListener { onClick() }
    }

    protected fun promptRestartAfterSave(message: String) {
        val context = context ?: return

        AlertDialog.Builder(context)
            .setTitle(getString("settings_restart_title") ?: "Riavvia applicazione")
            .setMessage(message)
            .setPositiveButton(getString("restart_now") ?: "Riavvia") { _, _ ->
                dismiss()
                restartApp()
            }
            .setNegativeButton(getString("restart_later") ?: "Piu tardi", null)
            .show()
    }

    private fun restartApp() {
        val context = context?.applicationContext ?: return
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component

        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        } else {
            showToast(
                getString("restart_unavailable")
                    ?: "Impossibile riavviare automaticamente l'app. Chiudila e riaprila manualmente."
            )
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val layoutId =
            plugin.resources?.getIdentifier(layoutName, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return layoutId?.let {
            inflater.inflate(plugin.resources?.getLayout(it), container, false)
        }
    }
}

class AnimeUnitySettings : AnimeUnityBaseSettingsFragment() {

    override val layoutName: String = "settings"

    private fun buildFeedbackDialogMessage(): SpannableStringBuilder {
        val message = SpannableStringBuilder(
            getString("settings_feedback_message")
                ?: "Vuoi segnalare un Problema o vuoi suggerire un Miglioramento?"
        )

        val problemText = getString("settings_feedback_problem_highlight") ?: "Problema"
        val suggestionText =
            getString("settings_feedback_suggestion_highlight") ?: "Miglioramento"

        val problemIndex = message.indexOf(problemText)
        if (problemIndex >= 0) {
            message.setSpan(
                ForegroundColorSpan(Color.parseColor("#FFFF7F7F")),
                problemIndex,
                problemIndex + problemText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val suggestionIndex = message.indexOf(suggestionText)
        if (suggestionIndex >= 0) {
            message.setSpan(
                ForegroundColorSpan(Color.parseColor("#FF7CFF9D")),
                suggestionIndex,
                suggestionIndex + suggestionText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return message
    }

    private fun openFeedbackPage(titlePrefix: String) {
        val context = context ?: return
        val issuesUrl =
            "https://github.com/doGior/doGiorsHadEnough/issues/new?title=${Uri.encode(titlePrefix)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(issuesUrl))

        runCatching { startActivity(intent) }
            .onFailure {
                showToast(
                    getString("settings_feedback_unavailable")
                        ?: "Impossibile aprire GitHub in questo momento."
                )
            }
    }

    private fun showFeedbackDialog() {
        val context = context ?: return
        val layoutId = plugin.resources?.getIdentifier(
            "settings_feedback_dialog",
            "layout",
            BuildConfig.LIBRARY_PACKAGE_NAME
        ) ?: return
        val dialogView = layoutInflater.inflate(plugin.resources?.getLayout(layoutId), null, false)

        dialogView.findViewByName<TextView>("feedback_dialog_title")?.text =
            getString("settings_feedback_title") ?: "Segnalazioni e Suggerimenti"
        dialogView.findViewByName<TextView>("feedback_dialog_message")?.text =
            buildFeedbackDialogMessage()

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        dialogView.findViewByName<TextView>("feedback_problem_btn")?.apply {
            makeTvCompatible()
            background = getDrawable("outline_danger")
            setTextColor(Color.parseColor("#FFFF7F7F"))
            text = getString("settings_feedback_problem_action") ?: "Segnala un Problema"
            setOnClickListener {
                dialog.dismiss()
                openFeedbackPage("AnimeUnity [problema]: ")
            }
        }

        dialogView.findViewByName<TextView>("feedback_suggestion_btn")?.apply {
            makeTvCompatible()
            background = getDrawable("outline_success")
            setTextColor(Color.parseColor("#FF7CFF9D"))
            text = getString("settings_feedback_suggestion_action")
                ?: "Suggerisci un Miglioramento"
            setOnClickListener {
                dialog.dismiss()
                openFeedbackPage("AnimeUnity [suggerimento]: ")
            }
        }

        dialogView.findViewByName<TextView>("feedback_cancel_btn")?.apply {
            makeTvCompatible()
            background = getDrawable("outline")
            setTextColor(Color.parseColor("#FFE6E6E6"))
            text = getString("settings_feedback_cancel") ?: "Annulla"
            setOnClickListener { dialog.dismiss() }
        }

        dialog.show()
    }

    private fun getBuildInfoText(): String? {
        val rawCommit = BuildConfig.BUILD_COMMIT_SHA.trim()
        val rawBuildCompletedAt = BuildConfig.BUILD_COMPLETED_AT_ROME.trim()
        val shortCommit = rawCommit.takeIf { it.isNotEmpty() && it != "unknown" }?.take(7)
            ?: return null

        return if (rawBuildCompletedAt.isNotEmpty()) {
            (getString("settings_build_info") ?: "Commit %1\$s | Build %2\$s").format(
                shortCommit,
                rawBuildCompletedAt
            )
        } else {
            (getString("settings_build_info_commit_only") ?: "Commit %1\$s").format(shortCommit)
        }
    }

    private fun setupSiteUrlResetButton(
        button: ImageButton?,
        input: EditText?,
    ) {
        button ?: return
        button.setImageDrawable(getDrawable("clear_icon"))
        button.setOnClickListener {
            input?.let { siteUrlInput ->
                siteUrlInput.error = null
                siteUrlInput.setText(AnimeUnityPlugin.DEFAULT_SITE_URL)
                siteUrlInput.setSelection(siteUrlInput.text.length)
            }
        }
    }

    private fun updateAdvancedSearchActionState(
        actionView: TextView?,
        enabledColor: Int,
        isEnabled: Boolean,
    ) {
        actionView?.setTextColor(if (isEnabled) enabledColor else Color.parseColor("#7A7A7A"))
        actionView?.alpha = if (isEnabled) 1f else 0.65f
    }

    private fun resetAllSettings(
        siteUrlInput: EditText?,
        advancedSearchSwitch: Switch?,
        advancedSearchAction: TextView?,
        advancedSearchActionEnabledColor: Int,
    ) {
        sharedPref?.edit {
            clear()
        }
        context?.applicationContext?.let { appContext ->
            AnimeUnityCache.init(appContext, sharedPref)
        }

        siteUrlInput?.error = null
        siteUrlInput?.setText(AnimeUnityPlugin.DEFAULT_SITE_URL)
        advancedSearchSwitch?.isChecked = false
        updateAdvancedSearchActionState(advancedSearchAction, advancedSearchActionEnabledColor, false)

        promptRestartAfterSave(
            getString("settings_reset_restart_message")
                ?: "Impostazioni ripristinate. Vuoi riavviare l'applicazione ora per applicare subito i valori predefiniti?"
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewByName<TextView>("header_tw")?.text =
            getString("settings_menu_title")
        view.findViewByName<TextView>("header_build_info")?.apply {
            val buildInfoText = getBuildInfoText()
            text = buildInfoText
            visibility = if (buildInfoText.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        view.findViewByName<TextView>("home_settings_title")?.text =
            getString("settings_menu_home_title")
        view.findViewByName<TextView>("home_settings_summary")?.text =
            getString("settings_menu_home_summary")
        view.findViewByName<TextView>("home_settings_action")?.text =
            getString("settings_open_action")
        view.findViewByName<TextView>("display_settings_title")?.text =
            getString("settings_menu_display_title")
        view.findViewByName<TextView>("display_settings_summary")?.text =
            getString("settings_menu_display_summary")
        view.findViewByName<TextView>("display_settings_action")?.text =
            getString("settings_open_action")
        view.findViewByName<TextView>("cache_settings_title")?.text =
            getString("settings_menu_cache_title")
        view.findViewByName<TextView>("cache_settings_summary")?.text =
            getString("settings_menu_cache_summary")
        view.findViewByName<TextView>("cache_settings_action")?.text =
            getString("settings_open_action")
        view.findViewByName<TextView>("advanced_search_settings_title")?.text =
            getString("settings_menu_advanced_search_title")
        view.findViewByName<TextView>("advanced_search_settings_summary")?.text =
            getString("settings_menu_advanced_search_summary")
        val advancedSearchAction: TextView? = view.findViewByName("advanced_search_settings_action")
        advancedSearchAction?.text =
            getString("settings_open_action")
        view.findViewByName<TextView>("site_url_label")?.text =
            getString("site_url_label")

        val homeSettingsCard: View? = view.findViewByName("home_settings_card")
        val displaySettingsCard: View? = view.findViewByName("display_settings_card")
        val cacheSettingsCard: View? = view.findViewByName("cache_settings_card")
        val advancedSearchSettingsCard: View? = view.findViewByName("advanced_search_settings_card")
        val advancedSearchSwitch: Switch? = view.findViewByName("advanced_search_settings_switch")
        val siteUrlContainer: View? = view.findViewByName("site_url_container")
        val siteUrlInput: EditText? = view.findViewByName("site_url_input")
        val siteUrlClear: ImageButton? = view.findViewByName("site_url_clear")
        val reportFeedbackButton: TextView? = view.findViewByName("report_feedback_btn")
        val resetSettingsButton: TextView? = view.findViewByName("reset_settings_btn")
        val advancedSearchActionEnabledColor = advancedSearchAction?.currentTextColor ?: Color.WHITE

        listOf(homeSettingsCard, displaySettingsCard, cacheSettingsCard, advancedSearchSettingsCard).forEach { card ->
            card?.makeTvCompatible()
        }
        siteUrlContainer?.applyOutlineBackground()
        reportFeedbackButton?.makeTvCompatible()
        resetSettingsButton?.makeTvCompatible()
        reportFeedbackButton?.background = getDrawable("outline_feedback")
        reportFeedbackButton?.setTextColor(Color.parseColor("#FF9ED0FF"))
        resetSettingsButton?.background = getDrawable("outline_danger")
        resetSettingsButton?.setTextColor(Color.parseColor("#FFFF7F7F"))

        siteUrlInput?.hint = getString("site_url_hint")
        siteUrlInput?.setText(AnimeUnityPlugin.getConfiguredSiteUrl(sharedPref))
        setupSiteUrlResetButton(siteUrlClear, siteUrlInput)
        advancedSearchSwitch?.text = ""
        advancedSearchSwitch?.isChecked =
            sharedPref?.getBoolean(AnimeUnityPlugin.PREF_ENABLE_ADVANCED_SEARCH, false) ?: false
        updateAdvancedSearchActionState(
            advancedSearchAction,
            advancedSearchActionEnabledColor,
            advancedSearchSwitch?.isChecked == true
        )
        reportFeedbackButton?.text = getString("settings_feedback_button")
        resetSettingsButton?.text = getString("settings_reset_button")
        siteUrlInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val currentValue = s?.toString()
                siteUrlInput.error = if (
                    currentValue.isNullOrBlank() || AnimeUnityPlugin.isValidSiteUrl(currentValue)
                ) {
                    null
                } else {
                    getString("site_url_invalid")
                }
            }
        })
        advancedSearchSwitch?.setOnCheckedChangeListener { _, isChecked ->
            sharedPref?.edit {
                putBoolean(AnimeUnityPlugin.PREF_ENABLE_ADVANCED_SEARCH, isChecked)
            }
            updateAdvancedSearchActionState(
                advancedSearchAction,
                advancedSearchActionEnabledColor,
                isChecked
            )
        }

        setupSaveButton(view) {
            val rawSiteUrl = siteUrlInput?.text?.toString()
            val isValidSiteUrl = AnimeUnityPlugin.isValidSiteUrl(rawSiteUrl)
            val validatedSiteUrl = AnimeUnityPlugin.getValidatedSiteUrl(rawSiteUrl)

            sharedPref?.edit {
                putString(AnimeUnityPlugin.PREF_SITE_URL, validatedSiteUrl)
            }

            promptRestartAfterSave(
                if (rawSiteUrl.isNullOrBlank() || isValidSiteUrl) {
                    getString("settings_saved_restart_message")
                        ?: "Impostazioni salvate. Vuoi riavviare l'applicazione ora per applicare subito le modifiche?"
                } else {
                    getString("site_url_fallback_restart_message")
                        ?: "Link non valido: verra usato quello predefinito. Vuoi riavviare l'applicazione ora per applicare subito le modifiche?"
                }
            )
        }

        resetSettingsButton?.setOnClickListener {
            val context = context ?: return@setOnClickListener

            AlertDialog.Builder(context)
                .setTitle(getString("settings_reset_title") ?: "Ripristina impostazioni")
                .setMessage(
                    getString("settings_reset_message")
                        ?: "Vuoi ripristinare tutti i valori di AnimeUnity a quelli predefiniti?"
                )
                .setPositiveButton(getString("settings_reset_confirm") ?: "Ripristina") { _, _ ->
                    resetAllSettings(
                        siteUrlInput,
                        advancedSearchSwitch,
                        advancedSearchAction,
                        advancedSearchActionEnabledColor
                    )
                }
                .setNegativeButton(getString("settings_reset_cancel") ?: "Annulla", null)
                .show()
        }

        reportFeedbackButton?.setOnClickListener {
            showFeedbackDialog()
        }

        homeSettingsCard?.setOnClickListener {
            AnimeUnityHomeSettingsFragment().show(
                parentFragmentManager,
                "AnimeUnityHomeSettings"
            )
        }

        displaySettingsCard?.setOnClickListener {
            AnimeUnityDisplaySettingsFragment().show(
                parentFragmentManager,
                "AnimeUnityDisplaySettings"
            )
        }

        cacheSettingsCard?.setOnClickListener {
            AnimeUnityCacheSettingsFragment().show(
                parentFragmentManager,
                "AnimeUnityCacheSettings"
            )
        }

        advancedSearchSettingsCard?.setOnClickListener {
            if (advancedSearchSwitch?.isChecked == true) {
                AnimeUnityAdvancedSearchSettingsFragment().show(
                    parentFragmentManager,
                    "AnimeUnityAdvancedSearchSettings"
                )
            }
        }
    }
}

class AnimeUnityHomeSettingsFragment : AnimeUnityBaseSettingsFragment() {

    override val layoutName: String = "settings_home"

    private data class SectionRow(
        val key: String,
        val rowId: String,
        val labelStringName: String,
        val titlePrefKey: String,
        val switchPrefKey: String,
        val countPrefKey: String?,
        val defaultEnabled: Boolean = true,
    )

    private fun getSectionTitle(sectionRow: SectionRow): String {
        val defaultTitle = getString(sectionRow.labelStringName).orEmpty()
        return AnimeUnityPlugin.getConfiguredSectionTitle(
            sharedPref,
            sectionRow.titlePrefKey,
            defaultTitle,
        )
    }

    private fun getDefaultCount(prefKey: String): Int {
        return if (prefKey == AnimeUnityPlugin.PREF_RANDOM_COUNT) {
            AnimeUnityPlugin.DEFAULT_RANDOM_COUNT
        } else {
            AnimeUnityPlugin.DEFAULT_SECTION_COUNT
        }
    }

    private fun getCount(prefKey: String): Int {
        val defaultCount = getDefaultCount(prefKey)
        return (sharedPref?.getInt(prefKey, defaultCount)
            ?: defaultCount).coerceIn(1, AnimeUnityPlugin.MAX_SECTION_COUNT)
    }

    private fun parseCount(input: EditText?, prefKey: String): Int {
        val defaultCount = getDefaultCount(prefKey)
        return input?.text
            ?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(1, AnimeUnityPlugin.MAX_SECTION_COUNT)
            ?: defaultCount
    }

    private fun setupCountInput(input: EditText?, prefKey: String) {
        input ?: return

        input.filters = arrayOf(InputFilter.LengthFilter(3))
        input.isEnabled = true
        input.setText(getCount(prefKey).toString())
        input.setSelection(input.text.length)

        var isUpdating = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return

                val currentValue = s?.toString()?.trim().orEmpty()
                val parsedValue = currentValue.toIntOrNull() ?: return
                if (parsedValue <= AnimeUnityPlugin.MAX_SECTION_COUNT) return

                isUpdating = true
                input.setText(AnimeUnityPlugin.MAX_SECTION_COUNT.toString())
                input.setSelection(input.text.length)
                isUpdating = false
            }
        })
    }

    private fun moveRow(container: LinearLayout, row: View, delta: Int) {
        val currentIndex = container.indexOfChild(row)
        if (currentIndex == -1) return

        val targetIndex = (currentIndex + delta).coerceIn(0, container.childCount - 1)
        if (targetIndex == currentIndex) return

        container.removeViewAt(currentIndex)
        container.addView(row, targetIndex)
        updateMoveButtons(container)
    }

    private fun updateMoveButtons(container: LinearLayout) {
        for (index in 0 until container.childCount) {
            val row = container.getChildAt(index)
            val canMoveUp = index > 0
            val canMoveDown = index < container.childCount - 1

            row.findViewByName<ImageButton>("move_up_btn")?.apply {
                isEnabled = canMoveUp
                alpha = if (canMoveUp) 1f else 0.35f
            }
            row.findViewByName<ImageButton>("move_down_btn")?.apply {
                isEnabled = canMoveDown
                alpha = if (canMoveDown) 1f else 0.35f
            }
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewByName<TextView>("header_tw")?.text =
            getString("settings_home_title")
        view.findViewByName<TextView>("section_name_header")?.text =
            getString("section_name_header")
        view.findViewByName<TextView>("section_enabled_header")?.text =
            getString("section_enabled_header")
        view.findViewByName<TextView>("section_limit_header")?.text =
            getString("section_limit_header")
        view.findViewByName<TextView>("section_move_header")?.text =
            getString("section_move_header")

        val rowsContainer: LinearLayout = view.findViewByName("sections_rows_container") ?: return

        val sectionRows = listOf(
            SectionRow(
                key = AnimeUnitySections.LATEST,
                rowId = "latest_row",
                labelStringName = "latest_count_label",
                titlePrefKey = AnimeUnityPlugin.PREF_LATEST_TITLE,
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_LATEST_EPISODES,
                countPrefKey = AnimeUnityPlugin.PREF_LATEST_COUNT,
            ),
            SectionRow(
                key = AnimeUnitySections.CALENDAR,
                rowId = "calendar_row",
                labelStringName = "calendar_switch_text",
                titlePrefKey = AnimeUnityPlugin.PREF_CALENDAR_TITLE,
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_CALENDAR,
                countPrefKey = AnimeUnityPlugin.PREF_CALENDAR_COUNT,
            ),
            SectionRow(
                key = AnimeUnitySections.ONGOING,
                rowId = "ongoing_row",
                labelStringName = "ongoing_count_label",
                titlePrefKey = AnimeUnityPlugin.PREF_ONGOING_TITLE,
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_ONGOING,
                countPrefKey = AnimeUnityPlugin.PREF_ONGOING_COUNT,
            ),
            SectionRow(
                key = AnimeUnitySections.POPULAR,
                rowId = "popular_row",
                labelStringName = "popular_count_label",
                titlePrefKey = AnimeUnityPlugin.PREF_POPULAR_TITLE,
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_POPULAR,
                countPrefKey = AnimeUnityPlugin.PREF_POPULAR_COUNT,
            ),
            SectionRow(
                key = AnimeUnitySections.BEST,
                rowId = "best_row",
                labelStringName = "best_count_label",
                titlePrefKey = AnimeUnityPlugin.PREF_BEST_TITLE,
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_BEST,
                countPrefKey = AnimeUnityPlugin.PREF_BEST_COUNT,
            ),
            SectionRow(
                key = AnimeUnitySections.UPCOMING,
                rowId = "upcoming_row",
                labelStringName = "upcoming_count_label",
                titlePrefKey = AnimeUnityPlugin.PREF_UPCOMING_TITLE,
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_UPCOMING,
                countPrefKey = AnimeUnityPlugin.PREF_UPCOMING_COUNT,
            ),
            SectionRow(
                key = AnimeUnitySections.RANDOM,
                rowId = "random_row",
                labelStringName = "random_count_label",
                titlePrefKey = AnimeUnityPlugin.PREF_RANDOM_TITLE,
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_RANDOM,
                countPrefKey = AnimeUnityPlugin.PREF_RANDOM_COUNT,
            ),
        )

        val rowViewByKey = sectionRows.associate { sectionRow ->
            val rowView = view.findViewByName<View>(sectionRow.rowId) ?: error("Missing row ${sectionRow.rowId}")
            sectionRow.key to rowView
        }

        val rowKeyByViewId = rowViewByKey.mapValues { (_, rowView) -> rowView.id }
            .entries
            .associate { (key, viewId) -> viewId to key }

        sectionRows.forEach { sectionRow ->
            val rowView = rowViewByKey.getValue(sectionRow.key)
            rowView.applyOutlineBackground()

            rowView.findViewByName<EditText>("row_label")?.apply {
                setText(getSectionTitle(sectionRow))
                setSelection(text.length)
                hint = getString(sectionRow.labelStringName)
            }

            val switchView = rowView.findViewByName<Switch>("row_switch")
            switchView?.text = ""
            switchView?.isChecked =
                sharedPref?.getBoolean(sectionRow.switchPrefKey, sectionRow.defaultEnabled)
                    ?: sectionRow.defaultEnabled

            val countInput = rowView.findViewByName<EditText>("row_count_input")
            if (sectionRow.countPrefKey != null) {
                setupCountInput(countInput, sectionRow.countPrefKey)
            }

            rowView.findViewByName<ImageButton>("move_up_btn")?.apply {
                contentDescription = getString("move_up_action")
                setOnClickListener { moveRow(rowsContainer, rowView, -1) }
            }
            rowView.findViewByName<ImageButton>("move_down_btn")?.apply {
                contentDescription = getString("move_down_action")
                setOnClickListener { moveRow(rowsContainer, rowView, 1) }
            }
        }

        rowsContainer.removeAllViews()
        val orderedKeys = AnimeUnityPlugin.getConfiguredSectionOrder(sharedPref).split(",")
        orderedKeys.mapNotNull { rowViewByKey[it] }.forEach(rowsContainer::addView)
        sectionRows.map { it.key }
            .filter { key -> rowsContainer.indexOfChild(rowViewByKey.getValue(key)) == -1 }
            .forEach { key -> rowsContainer.addView(rowViewByKey.getValue(key)) }

        updateMoveButtons(rowsContainer)

        setupSaveButton(view) {
            val validatedSectionOrder = (0 until rowsContainer.childCount)
                .mapNotNull { index -> rowKeyByViewId[rowsContainer.getChildAt(index).id] }
                .joinToString(",")

            sharedPref?.edit {
                putString(
                    AnimeUnityPlugin.PREF_SECTION_ORDER,
                    AnimeUnityPlugin.getValidatedSectionOrder(validatedSectionOrder)
                )

                sectionRows.forEach { sectionRow ->
                    val rowView = rowViewByKey.getValue(sectionRow.key)
                    putBoolean(
                        sectionRow.switchPrefKey,
                        rowView.findViewByName<Switch>("row_switch")?.isChecked ?: sectionRow.defaultEnabled
                    )

                    sectionRow.countPrefKey?.let { prefKey ->
                        putInt(
                            prefKey,
                            parseCount(rowView.findViewByName("row_count_input"), prefKey)
                        )
                    }

                    val titleInput = rowView.findViewByName<EditText>("row_label")
                    val trimmedTitle = titleInput?.text?.toString()?.trim().orEmpty()
                    if (trimmedTitle.isEmpty()) {
                        remove(sectionRow.titlePrefKey)
                    } else {
                        putString(sectionRow.titlePrefKey, trimmedTitle)
                    }
                }
            }

            showToast(
                getString("settings_saved")
                    ?: "Impostazioni salvate. Riavvia l'applicazione per applicarle"
            )
            dismiss()
        }
    }
}

class AnimeUnityCacheSettingsFragment : AnimeUnityBaseSettingsFragment() {

    override val layoutName: String = "settings_cache"

    companion object {
        private const val CACHE_ENTRY_KEY_PREVIEW_LENGTH = 120
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(java.util.Locale.ITALY, "%.1f MB", mb)
    }

    private fun formatTimestamp(timestampMs: Long): String {
        if (timestampMs <= 0L) return "-"
        return SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).format(Date(timestampMs))
    }

    private fun String.shortCacheKey(): String {
        return if (length <= CACHE_ENTRY_KEY_PREVIEW_LENGTH) {
            this
        } else {
            take(CACHE_ENTRY_KEY_PREVIEW_LENGTH) + "..."
        }
    }

    private fun EditText?.readCacheInt(defaultValue: Int, minValue: Int, maxValue: Int): Int {
        return this?.text
            ?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(minValue, maxValue)
            ?: defaultValue
    }

    private fun updateStats(view: View) {
        AnimeUnityCache.init(view.context.applicationContext, sharedPref)
        val stats = AnimeUnityCache.stats()
        view.findViewByName<TextView>("cache_stats_summary")?.text =
            (getString("settings_cache_stats_summary")
                ?: "%1\$d elementi | %2\$d schede anime | %3\$d pagine anime | %4\$d scaduti | %5\$s").format(
                stats.entryCount,
                stats.detailEntryCount,
                stats.animePageEntryCount,
                stats.expiredEntryCount,
                formatBytes(stats.totalBytes),
            )
    }

    private fun updateSettingsValues(view: View) {
        view.findViewByName<EditText>("cache_max_entries_input")?.setText(
            AnimeUnityPlugin.getCacheMaxEntries(sharedPref).toString()
        )
        view.findViewByName<EditText>("cache_max_size_input")?.setText(
            AnimeUnityPlugin.getCacheMaxSizeMb(sharedPref).toString()
        )
    }

    private fun saveCacheLimits(view: View) {
        val context = context ?: return
        val maxEntries = view.findViewByName<EditText>("cache_max_entries_input").readCacheInt(
            defaultValue = AnimeUnityPlugin.DEFAULT_CACHE_MAX_ENTRIES,
            minValue = AnimeUnityPlugin.MIN_CACHE_MAX_ENTRIES,
            maxValue = AnimeUnityPlugin.MAX_CACHE_MAX_ENTRIES,
        )
        val maxSizeMb = view.findViewByName<EditText>("cache_max_size_input").readCacheInt(
            defaultValue = AnimeUnityPlugin.DEFAULT_CACHE_MAX_SIZE_MB,
            minValue = AnimeUnityPlugin.MIN_CACHE_MAX_SIZE_MB,
            maxValue = AnimeUnityPlugin.MAX_CACHE_MAX_SIZE_MB,
        )

        sharedPref?.edit {
            putInt(AnimeUnityPlugin.PREF_CACHE_MAX_ENTRIES, maxEntries)
            putInt(AnimeUnityPlugin.PREF_CACHE_MAX_SIZE_MB, maxSizeMb)
        }

        AnimeUnityCache.init(context.applicationContext, sharedPref)
        updateSettingsValues(view)
        updateStats(view)
        showToast(getString("settings_cache_saved") ?: "Impostazioni cache salvate")
    }

    private fun cacheEntryLabel(entry: AnimeUnityCache.CacheEntrySnapshot): String {
        val status = if (entry.isExpired) "scaduta" else "valida"
        return buildString {
            append(entry.namespace.ifBlank { "unknown" })
            append(" | ")
            append(formatBytes(entry.sizeBytes))
            append(" | ")
            append(status)
            append("\n")
            append(entry.key.shortCacheKey())
        }
    }

    private fun cacheEntryDetail(entry: AnimeUnityCache.CacheEntrySnapshot): String {
        val expiresAt = if (entry.expiresAtMs > 0L) formatTimestamp(entry.expiresAtMs) else "Mai"
        return buildString {
            appendLine("Namespace: ${entry.namespace}")
            appendLine("File: ${entry.fileName}")
            appendLine("Dimensione: ${formatBytes(entry.sizeBytes)} (${entry.sizeBytes} byte)")
            appendLine("Creata: ${formatTimestamp(entry.createdAtMs)}")
            appendLine("Aggiornata: ${formatTimestamp(entry.updatedAtMs)}")
            appendLine("Ultimo accesso: ${formatTimestamp(entry.lastAccessedAtMs)}")
            appendLine("Scadenza: $expiresAt")
            appendLine("Scaduta: ${if (entry.isExpired) "si" else "no"}")
            appendLine("Priorita: ${entry.priority}")
            appendLine("Pinned: ${if (entry.pinned) "si" else "no"}")
            appendLine()
            appendLine("Key:")
            appendLine(entry.key)
            appendLine()
            appendLine("Payload:")
            append(entry.payload)
        }
    }

    private fun showCacheEntryDetail(entry: AnimeUnityCache.CacheEntrySnapshot) {
        val context = context ?: return
        val detailText = TextView(context).apply {
            text = cacheEntryDetail(entry)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(24, 18, 24, 18)
        }
        val scrollView = ScrollView(context).apply {
            addView(detailText)
        }

        AlertDialog.Builder(context)
            .setTitle(getString("settings_cache_entry_detail_title") ?: "Dettaglio cache")
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showCacheEntries(view: View) {
        val context = context ?: return
        AnimeUnityCache.init(context.applicationContext, sharedPref)
        val entries = AnimeUnityCache.entries()

        if (entries.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(getString("settings_cache_entries_title") ?: "Elementi cache")
                .setMessage(getString("settings_cache_entries_empty") ?: "Nessun elemento salvato in cache.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            updateStats(view)
            return
        }

        AlertDialog.Builder(context)
            .setTitle(
                "${getString("settings_cache_entries_title") ?: "Elementi cache"} (${entries.size})"
            )
            .setItems(entries.map(::cacheEntryLabel).toTypedArray()) { _, which ->
                showCacheEntryDetail(entries[which])
            }
            .setNegativeButton(getString("settings_reset_cancel") ?: "Annulla", null)
            .show()
        updateStats(view)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewByName<TextView>("header_tw")?.text =
            getString("settings_cache_title")
        setupSaveButton(view) {
            saveCacheLimits(view)
        }
        view.findViewByName<View>("cache_stats_card")?.applyOutlineBackground()
        view.findViewByName<View>("cache_limits_card")?.applyOutlineBackground()
        view.findViewByName<TextView>("cache_stats_title")?.text =
            getString("settings_cache_stats_title")
        view.findViewByName<TextView>("cache_limits_title")?.text =
            getString("settings_cache_limits_title")
        view.findViewByName<TextView>("cache_max_entries_label")?.text =
            getString("settings_cache_max_entries_label")
        view.findViewByName<TextView>("cache_max_size_label")?.text =
            getString("settings_cache_max_size_label")
        view.findViewByName<EditText>("cache_max_entries_input")?.filters =
            arrayOf(InputFilter.LengthFilter(5))
        view.findViewByName<EditText>("cache_max_size_input")?.filters =
            arrayOf(InputFilter.LengthFilter(4))

        val clearCacheButton: TextView? = view.findViewByName("clear_cache_btn")
        val viewCacheEntriesButton: TextView? = view.findViewByName("view_cache_entries_btn")
        viewCacheEntriesButton?.apply {
            makeTvCompatible()
            text = getString("settings_cache_view_entries_button")
        }
        clearCacheButton?.apply {
            makeTvCompatible()
            background = getDrawable("outline_danger")
            setTextColor(Color.parseColor("#FFFF7F7F"))
            text = getString("settings_cache_clear_button")
        }
        updateSettingsValues(view)
        updateStats(view)

        viewCacheEntriesButton?.setOnClickListener {
            showCacheEntries(view)
        }

        clearCacheButton?.setOnClickListener {
            val context = context ?: return@setOnClickListener
            AlertDialog.Builder(context)
                .setTitle(getString("settings_cache_clear_title") ?: "Svuota cache")
                .setMessage(
                    getString("settings_cache_clear_message")
                        ?: "Vuoi eliminare i dati salvati di AnimeUnity?"
                )
                .setPositiveButton(getString("settings_cache_clear_confirm") ?: "Svuota") { _, _ ->
                    AnimeUnity.clearAllCaches()
                    updateStats(view)
                    showToast(getString("settings_cache_cleared") ?: "Cache svuotata")
                }
                .setNegativeButton(getString("settings_reset_cancel") ?: "Annulla", null)
                .show()
        }
    }
}

class AnimeUnityDisplaySettingsFragment : AnimeUnityBaseSettingsFragment() {

    override val layoutName: String = "settings_display"

    private data class SwitchSetting(
        val prefKey: String,
        val rowId: String,
        val labelId: String,
        val viewId: String,
        val labelTextName: String,
        val defaultValue: Boolean = true,
    )

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewByName<TextView>("header_tw")?.text =
            getString("settings_display_title")
        view.findViewByName<View>("display_options_card")?.applyOutlineBackground()
        view.findViewByName<TextView>("display_options_title")?.text =
            getString("display_options_title")

        val switchSettings = listOf(
            SwitchSetting(
                AnimeUnityPlugin.PREF_UNIFY_DUB_SUB_CARDS,
                "unify_dub_sub_cards_row",
                "unify_dub_sub_cards_label",
                "unify_dub_sub_cards_switch",
                "unify_dub_sub_cards_switch_text",
                AnimeUnityPlugin.DEFAULT_UNIFY_DUB_SUB_CARDS,
            ),
            SwitchSetting(
                AnimeUnityPlugin.PREF_SHOW_DUB_SUB,
                "dub_sub_row",
                "dub_sub_label",
                "dub_sub_switch",
                "dub_sub_switch_text"
            ),
            SwitchSetting(
                AnimeUnityPlugin.PREF_SHOW_EPISODE_NUMBER,
                "episode_number_row",
                "episode_number_label",
                "episode_number_switch",
                "episode_number_switch_text"
            ),
            SwitchSetting(
                AnimeUnityPlugin.PREF_SHOW_SCORE,
                "score_row",
                "score_label",
                "score_switch",
                "score_switch_text"
            ),
        )

        switchSettings.forEach { setting ->
            view.findViewByName<View>(setting.rowId)?.applyOutlineBackground()
            view.findViewByName<TextView>(setting.labelId)?.text =
                getString(setting.labelTextName)

            val switchView = view.findViewByName<Switch>(setting.viewId)
            switchView?.text = ""
            switchView?.isChecked =
                sharedPref?.getBoolean(setting.prefKey, setting.defaultValue) ?: setting.defaultValue
        }

        setupSaveButton(view) {
            sharedPref?.edit {
                switchSettings.forEach { setting ->
                    putBoolean(
                        setting.prefKey,
                        view.findViewByName<Switch>(setting.viewId)?.isChecked ?: setting.defaultValue
                    )
                }
            }

            showToast(
                getString("settings_saved")
                    ?: "Impostazioni salvate. Riavvia l'applicazione per applicarle"
            )
            dismiss()
        }
    }
}

class AnimeUnityAdvancedSearchSettingsFragment : AnimeUnityBaseSettingsFragment() {

    override val layoutName: String = "settings_advanced_search"

    private fun setupSelectAllOnFocus(
        input: EditText?,
        onFocusLost: (() -> Unit)? = null,
    ) {
        input ?: return
        input.setOnClickListener {
            input.post { input.selectAll() }
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                input.post { input.selectAll() }
            } else {
                onFocusLost?.invoke()
            }
        }
    }

    private fun setupClearButton(
        button: ImageButton?,
        onClear: () -> Unit,
    ) {
        button ?: return
        button.setImageDrawable(getDrawable("clear_icon"))
        button.setOnClickListener { onClear() }
    }

    private fun <T> bindSelectableInput(
        input: AutoCompleteTextView?,
        items: List<SpinnerItem<T?>>,
        selectedValue: T?,
    ) {
        val context = context ?: return
        input ?: return

        val adapter = ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, items)
        input.setAdapter(adapter)
        input.threshold = 0

        val selectedItem = items.firstOrNull { it.value == selectedValue } ?: items.first()
        input.setText(selectedItem.label, false)
        input.setOnClickListener {
            input.post {
                input.selectAll()
                input.showDropDown()
            }
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                input.post {
                    input.selectAll()
                    input.showDropDown()
                }
            } else {
                normalizeSelectableInput(input, items)
            }
        }
        input.setOnEditorActionListener { _, _, _ ->
            normalizeSelectableInput(input, items)
            false
        }
    }

    private fun <T> normalizeSelectableInput(
        input: AutoCompleteTextView?,
        items: List<SpinnerItem<T?>>,
    ) {
        input ?: return

        val typedValue = input.text?.toString()?.trim().orEmpty()
        val selectedItem = items.firstOrNull { it.label.equals(typedValue, ignoreCase = true) }
            ?: items.first()

        if (input.text?.toString() != selectedItem.label) {
            input.setText(selectedItem.label, false)
        }
        input.dismissDropDown()
    }

    private fun putOptionalString(
        editor: SharedPreferences.Editor,
        key: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            editor.remove(key)
        } else {
            editor.putString(key, value)
        }
    }

    private fun getAdvancedSearchCount(): Int {
        return (sharedPref?.getInt(
            AnimeUnityPlugin.PREF_ADVANCED_SEARCH_COUNT,
            AnimeUnityPlugin.DEFAULT_ADVANCED_SEARCH_COUNT,
        ) ?: AnimeUnityPlugin.DEFAULT_ADVANCED_SEARCH_COUNT)
            .coerceIn(1, AnimeUnityPlugin.MAX_SECTION_COUNT)
    }

    private fun parseAdvancedSearchCount(input: EditText?): Int {
        return input?.text
            ?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(1, AnimeUnityPlugin.MAX_SECTION_COUNT)
            ?: AnimeUnityPlugin.DEFAULT_ADVANCED_SEARCH_COUNT
    }

    private fun setupAdvancedSearchCountInput(input: EditText?) {
        input ?: return

        input.filters = arrayOf(InputFilter.LengthFilter(3))
        input.setText(getAdvancedSearchCount().toString())
        input.setSelection(input.text.length)

        var isUpdating = false
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return

                val parsedValue = s?.toString()?.trim()?.toIntOrNull() ?: return
                if (parsedValue <= AnimeUnityPlugin.MAX_SECTION_COUNT) return

                isUpdating = true
                input.setText(AnimeUnityPlugin.MAX_SECTION_COUNT.toString())
                input.setSelection(input.text.length)
                isUpdating = false
            }
        })

        input.setOnEditorActionListener { _, _, _ ->
            if (input.text?.toString()?.trim().isNullOrEmpty()) {
                input.setText(AnimeUnityPlugin.DEFAULT_ADVANCED_SEARCH_COUNT.toString())
                input.setSelection(input.text.length)
            }
            false
        }
    }

    private fun <T> getSelectedValue(
        input: AutoCompleteTextView?,
        items: List<SpinnerItem<T?>>,
    ): T? {
        normalizeSelectableInput(input, items)
        val typedValue = input?.text?.toString()?.trim().orEmpty()
        return items.firstOrNull { it.label.equals(typedValue, ignoreCase = true) }?.value
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewByName<TextView>("header_tw")?.text =
            getString("settings_advanced_search_title")
        view.findViewByName<TextView>("advanced_search_name_label")?.text =
            getString("advanced_search_name_label")
        view.findViewByName<TextView>("advanced_search_genre_label")?.text =
            getString("advanced_search_genre_label")
        view.findViewByName<TextView>("advanced_search_year_label")?.text =
            getString("advanced_search_year_label")
        view.findViewByName<TextView>("advanced_search_order_label")?.text =
            getString("advanced_search_order_label")
        view.findViewByName<TextView>("advanced_search_status_label")?.text =
            getString("advanced_search_status_label")
        view.findViewByName<TextView>("advanced_search_type_label")?.text =
            getString("advanced_search_type_label")
        view.findViewByName<TextView>("advanced_search_season_label")?.text =
            getString("advanced_search_season_label")

        val nameRow: View? = view.findViewByName("advanced_search_name_row")
        val countRow: View? = view.findViewByName("advanced_search_count_row")
        val genreRow: View? = view.findViewByName("advanced_search_genre_row")
        val yearRow: View? = view.findViewByName("advanced_search_year_row")
        val orderRow: View? = view.findViewByName("advanced_search_order_row")
        val statusRow: View? = view.findViewByName("advanced_search_status_row")
        val typeRow: View? = view.findViewByName("advanced_search_type_row")
        val seasonRow: View? = view.findViewByName("advanced_search_season_row")
        val nameInput: EditText? = view.findViewByName("advanced_search_name_input")
        val countInput: EditText? = view.findViewByName("advanced_search_count_input")
        val nameClear: ImageButton? = view.findViewByName("advanced_search_name_clear")
        val countClear: ImageButton? = view.findViewByName("advanced_search_count_clear")
        view.findViewByName<TextView>("advanced_search_count_label")?.text =
            getString("advanced_search_count_label")
        val genreInput: AutoCompleteTextView? = view.findViewByName("advanced_search_genre_spinner")
        val yearInput: AutoCompleteTextView? = view.findViewByName("advanced_search_year_spinner")
        val orderInput: AutoCompleteTextView? = view.findViewByName("advanced_search_order_spinner")
        val statusInput: AutoCompleteTextView? = view.findViewByName("advanced_search_status_spinner")
        val typeInput: AutoCompleteTextView? = view.findViewByName("advanced_search_type_spinner")
        val seasonInput: AutoCompleteTextView? = view.findViewByName("advanced_search_season_spinner")
        val genreClear: ImageButton? = view.findViewByName("advanced_search_genre_clear")
        val yearClear: ImageButton? = view.findViewByName("advanced_search_year_clear")
        val orderClear: ImageButton? = view.findViewByName("advanced_search_order_clear")
        val statusClear: ImageButton? = view.findViewByName("advanced_search_status_clear")
        val typeClear: ImageButton? = view.findViewByName("advanced_search_type_clear")
        val seasonClear: ImageButton? = view.findViewByName("advanced_search_season_clear")

        listOf(nameRow, countRow, genreRow, yearRow, orderRow, statusRow, typeRow, seasonRow)
            .forEach { row -> row?.applyOutlineBackground() }

        val config = AnimeUnityPlugin.getAdvancedSearchConfig(sharedPref)
        val anyOptionLabel = getString("advanced_search_any_option") ?: "Qualsiasi"

        nameInput?.hint = getString("advanced_search_name_hint")
        nameInput?.setText(config.title)
        countInput?.hint = getString("advanced_search_count_hint")
        genreInput?.hint = getString("advanced_search_genre_hint")
        yearInput?.hint = getString("advanced_search_year_hint")
        orderInput?.hint = getString("advanced_search_order_hint")
        statusInput?.hint = getString("advanced_search_status_hint")
        typeInput?.hint = getString("advanced_search_type_hint")
        seasonInput?.hint = getString("advanced_search_season_hint")
        setupAdvancedSearchCountInput(countInput)
        setupSelectAllOnFocus(nameInput)
        setupSelectAllOnFocus(countInput) {
            countInput?.let { input ->
                if (input.text?.toString()?.trim().isNullOrEmpty()) {
                    input.setText(AnimeUnityPlugin.DEFAULT_ADVANCED_SEARCH_COUNT.toString())
                    input.setSelection(input.text.length)
                }
            }
        }

        val genreItems = listOf(SpinnerItem<ArchiveGenreOption?>(anyOptionLabel, null)) +
            AnimeUnityPlugin.getAdvancedSearchGenres()
                .map { SpinnerItem<ArchiveGenreOption?>(it.name, it) }
        val yearItems = listOf(SpinnerItem<String?>(anyOptionLabel, null)) +
            AnimeUnityPlugin.getAdvancedSearchYearOptions()
                .map { SpinnerItem<String?>(it, it) }
        val orderItems = listOf(SpinnerItem<String?>(anyOptionLabel, null)) +
            AnimeUnityPlugin.getAdvancedSearchOrderOptions()
                .map { SpinnerItem<String?>(it, it) }
        val statusItems = listOf(SpinnerItem<String?>(anyOptionLabel, null)) +
            AnimeUnityPlugin.getAdvancedSearchStatusOptions()
                .map { SpinnerItem<String?>(it, it) }
        val typeItems = listOf(SpinnerItem<String?>(anyOptionLabel, null)) +
            AnimeUnityPlugin.getAdvancedSearchTypeOptions()
                .map { SpinnerItem<String?>(it, it) }
        val seasonItems = listOf(SpinnerItem<String?>(anyOptionLabel, null)) +
            AnimeUnityPlugin.getAdvancedSearchSeasonOptions()
                .map { SpinnerItem<String?>(it, it) }

        bindSelectableInput(
            genreInput,
            genreItems,
            config.genre,
        )
        bindSelectableInput(
            yearInput,
            yearItems,
            config.year,
        )
        bindSelectableInput(
            orderInput,
            orderItems,
            config.order,
        )
        bindSelectableInput(
            statusInput,
            statusItems,
            config.status,
        )
        bindSelectableInput(
            typeInput,
            typeItems,
            config.type,
        )
        bindSelectableInput(
            seasonInput,
            seasonItems,
            config.season,
        )

        setupClearButton(nameClear) {
            nameInput?.setText("")
        }
        setupClearButton(countClear) {
            countInput?.let { input ->
                input.setText(AnimeUnityPlugin.DEFAULT_ADVANCED_SEARCH_COUNT.toString())
                input.setSelection(input.text.length)
            }
        }
        setupClearButton(genreClear) {
            genreInput?.setText(anyOptionLabel, false)
            genreInput?.dismissDropDown()
        }
        setupClearButton(yearClear) {
            yearInput?.setText(anyOptionLabel, false)
            yearInput?.dismissDropDown()
        }
        setupClearButton(orderClear) {
            orderInput?.setText(anyOptionLabel, false)
            orderInput?.dismissDropDown()
        }
        setupClearButton(statusClear) {
            statusInput?.setText(anyOptionLabel, false)
            statusInput?.dismissDropDown()
        }
        setupClearButton(typeClear) {
            typeInput?.setText(anyOptionLabel, false)
            typeInput?.dismissDropDown()
        }
        setupClearButton(seasonClear) {
            seasonInput?.setText(anyOptionLabel, false)
            seasonInput?.dismissDropDown()
        }

        setupSaveButton(view) {
            sharedPref?.edit {
                putOptionalString(
                    this,
                    AnimeUnityPlugin.PREF_ADVANCED_SEARCH_TITLE,
                    nameInput?.text?.toString()?.trim(),
                )
                putInt(
                    AnimeUnityPlugin.PREF_ADVANCED_SEARCH_COUNT,
                    parseAdvancedSearchCount(countInput),
                )

                getSelectedValue(genreInput, genreItems)?.let { genre ->
                    putInt(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_GENRE_ID, genre.id)
                } ?: remove(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_GENRE_ID)

                putOptionalString(
                    this,
                    AnimeUnityPlugin.PREF_ADVANCED_SEARCH_YEAR,
                    getSelectedValue(yearInput, yearItems),
                )
                putOptionalString(
                    this,
                    AnimeUnityPlugin.PREF_ADVANCED_SEARCH_ORDER,
                    getSelectedValue(orderInput, orderItems),
                )
                putOptionalString(
                    this,
                    AnimeUnityPlugin.PREF_ADVANCED_SEARCH_STATUS,
                    getSelectedValue(statusInput, statusItems),
                )
                putOptionalString(
                    this,
                    AnimeUnityPlugin.PREF_ADVANCED_SEARCH_TYPE,
                    getSelectedValue(typeInput, typeItems),
                )
                putOptionalString(
                    this,
                    AnimeUnityPlugin.PREF_ADVANCED_SEARCH_SEASON,
                    getSelectedValue(seasonInput, seasonItems),
                )
            }

            showToast(
                getString("settings_saved")
                    ?: "Impostazioni salvate. Riavvia l'applicazione per applicarle"
            )
            dismiss()
        }
    }
}
