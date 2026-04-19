package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

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

    private fun resetAllSettings(siteUrlInput: EditText?) {
        sharedPref?.edit {
            clear()
        }

        siteUrlInput?.error = null
        siteUrlInput?.setText(AnimeUnityPlugin.DEFAULT_SITE_URL)

        promptRestartAfterSave(
            getString("settings_reset_restart_message")
                ?: "Impostazioni ripristinate. Vuoi riavviare l'applicazione ora per applicare subito i valori predefiniti?"
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewByName<TextView>("header_tw")?.text =
            getString("settings_menu_title")
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
        view.findViewByName<TextView>("site_url_label")?.text =
            getString("site_url_label")

        val homeSettingsCard: View? = view.findViewByName("home_settings_card")
        val displaySettingsCard: View? = view.findViewByName("display_settings_card")
        val siteUrlContainer: View? = view.findViewByName("site_url_container")
        val siteUrlInput: EditText? = view.findViewByName("site_url_input")
        val resetSettingsButton: TextView? = view.findViewByName("reset_settings_btn")

        listOf(homeSettingsCard, displaySettingsCard).forEach { card ->
            card?.makeTvCompatible()
        }
        siteUrlContainer?.applyOutlineBackground()
        resetSettingsButton?.makeTvCompatible()

        siteUrlInput?.hint = getString("site_url_hint")
        siteUrlInput?.setText(AnimeUnityPlugin.getConfiguredSiteUrl(sharedPref))
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
                    resetAllSettings(siteUrlInput)
                }
                .setNegativeButton(getString("settings_reset_cancel") ?: "Annulla", null)
                .show()
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
    }
}

class AnimeUnityHomeSettingsFragment : AnimeUnityBaseSettingsFragment() {

    override val layoutName: String = "settings_home"

    private data class SectionRow(
        val key: String,
        val rowId: String,
        val labelStringName: String,
        val switchPrefKey: String,
        val countPrefKey: String?,
        val defaultEnabled: Boolean = true,
    )

    private fun getCount(prefKey: String): Int {
        return (sharedPref?.getInt(prefKey, AnimeUnityPlugin.DEFAULT_SECTION_COUNT)
            ?: AnimeUnityPlugin.DEFAULT_SECTION_COUNT).coerceIn(1, AnimeUnityPlugin.MAX_SECTION_COUNT)
    }

    private fun parseCount(input: EditText?): Int {
        return input?.text
            ?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(1, AnimeUnityPlugin.MAX_SECTION_COUNT)
            ?: AnimeUnityPlugin.DEFAULT_SECTION_COUNT
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
                key = "latest",
                rowId = "latest_row",
                labelStringName = "latest_count_label",
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_LATEST_EPISODES,
                countPrefKey = AnimeUnityPlugin.PREF_LATEST_COUNT,
            ),
            SectionRow(
                key = "calendar",
                rowId = "calendar_row",
                labelStringName = "calendar_switch_text",
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_CALENDAR,
                countPrefKey = AnimeUnityPlugin.PREF_CALENDAR_COUNT,
            ),
            SectionRow(
                key = "ongoing",
                rowId = "ongoing_row",
                labelStringName = "ongoing_count_label",
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_ONGOING,
                countPrefKey = AnimeUnityPlugin.PREF_ONGOING_COUNT,
            ),
            SectionRow(
                key = "popular",
                rowId = "popular_row",
                labelStringName = "popular_count_label",
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_POPULAR,
                countPrefKey = AnimeUnityPlugin.PREF_POPULAR_COUNT,
            ),
            SectionRow(
                key = "best",
                rowId = "best_row",
                labelStringName = "best_count_label",
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_BEST,
                countPrefKey = AnimeUnityPlugin.PREF_BEST_COUNT,
            ),
            SectionRow(
                key = "upcoming",
                rowId = "upcoming_row",
                labelStringName = "upcoming_count_label",
                switchPrefKey = AnimeUnityPlugin.PREF_SHOW_UPCOMING,
                countPrefKey = AnimeUnityPlugin.PREF_UPCOMING_COUNT,
            ),
            SectionRow(
                key = "random",
                rowId = "random_row",
                labelStringName = "random_count_label",
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

            rowView.findViewByName<TextView>("row_label")?.text =
                getString(sectionRow.labelStringName)

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
                            parseCount(rowView.findViewByName("row_count_input"))
                        )
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

        val switchSettings = listOf(
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
