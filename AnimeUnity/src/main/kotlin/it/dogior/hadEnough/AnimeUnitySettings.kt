package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

class AnimeUnitySettings(
    private val plugin: AnimeUnityPlugin,
    private val sharedPref: SharedPreferences?,
) : BottomSheetDialogFragment() {

    private fun View.makeTvCompatible() {
        this.setPadding(
            this.paddingLeft + 10,
            this.paddingTop + 10,
            this.paddingRight + 10,
            this.paddingBottom + 10
        )
        this.background = getDrawable("outline")
    }

    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getString(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
    }

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id ?: return null)
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId =
            plugin.resources?.getIdentifier("settings", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return layoutId?.let {
            inflater.inflate(plugin.resources?.getLayout(it), container, false)
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = getString("header_tw")

        val sectionsLabel: TextView? = view.findViewByName("sections_label")
        sectionsLabel?.text = getString("sections_label")

        val displayLabel: TextView? = view.findViewByName("display_label")
        displayLabel?.text = getString("display_label")

        val latestEpisodesSwitch: Switch? = view.findViewByName("latest_episodes_switch")
        latestEpisodesSwitch?.text = getString("latest_episodes_switch_text")
        latestEpisodesSwitch?.isChecked =
            sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_LATEST_EPISODES, true) ?: true

        val calendarSwitch: Switch? = view.findViewByName("calendar_switch")
        calendarSwitch?.text = getString("calendar_switch_text")
        calendarSwitch?.isChecked =
            sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_CALENDAR, true) ?: true

        val ongoingSwitch: Switch? = view.findViewByName("ongoing_switch")
        ongoingSwitch?.text = getString("ongoing_switch_text")
        ongoingSwitch?.isChecked =
            sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_ONGOING, true) ?: true

        val popularSwitch: Switch? = view.findViewByName("popular_switch")
        popularSwitch?.text = getString("popular_switch_text")
        popularSwitch?.isChecked =
            sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_POPULAR, true) ?: true

        val bestSwitch: Switch? = view.findViewByName("best_switch")
        bestSwitch?.text = getString("best_switch_text")
        bestSwitch?.isChecked =
            sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_BEST, true) ?: true

        val upcomingSwitch: Switch? = view.findViewByName("upcoming_switch")
        upcomingSwitch?.text = getString("upcoming_switch_text")
        upcomingSwitch?.isChecked =
            sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_UPCOMING, true) ?: true

        val scoreSwitch: Switch? = view.findViewByName("score_switch")
        scoreSwitch?.text = getString("score_switch_text")
        scoreSwitch?.isChecked =
            sharedPref?.getBoolean(AnimeUnityPlugin.PREF_SHOW_SCORE, true) ?: true

        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))
        saveBtn?.setOnClickListener {
            sharedPref?.edit {
                putBoolean(
                    AnimeUnityPlugin.PREF_SHOW_LATEST_EPISODES,
                    latestEpisodesSwitch?.isChecked ?: true
                )
                putBoolean(
                    AnimeUnityPlugin.PREF_SHOW_CALENDAR,
                    calendarSwitch?.isChecked ?: true
                )
                putBoolean(
                    AnimeUnityPlugin.PREF_SHOW_ONGOING,
                    ongoingSwitch?.isChecked ?: true
                )
                putBoolean(
                    AnimeUnityPlugin.PREF_SHOW_POPULAR,
                    popularSwitch?.isChecked ?: true
                )
                putBoolean(
                    AnimeUnityPlugin.PREF_SHOW_BEST,
                    bestSwitch?.isChecked ?: true
                )
                putBoolean(
                    AnimeUnityPlugin.PREF_SHOW_UPCOMING,
                    upcomingSwitch?.isChecked ?: true
                )
                putBoolean(
                    AnimeUnityPlugin.PREF_SHOW_SCORE,
                    scoreSwitch?.isChecked ?: true
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
