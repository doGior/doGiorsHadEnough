package it.dogior.hadEnough.settings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import it.dogior.hadEnough.BuildConfig
import it.dogior.hadEnough.YouTubePlugin

/**
 * A simple [Fragment] subclass.
 * Use the [SettingsFragment] factory method to
 * create an instance of this fragment.
 */
class SettingsFragment(
    private val plugin: YouTubePlugin,
    val sharedPref: SharedPreferences?
) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to read resources")

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun View.makeTvCompatible() {
        this.setPadding(
            this.paddingLeft + 10,
            this.paddingTop + 10,
            this.paddingRight + 10,
            this.paddingBottom + 10
        )
        this.background = getDrawable("outline")
    }

    private fun getDrawable(name: String): Drawable? {
        val id =
            res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return ResourcesCompat.getDrawable(res, id, null)
    }

    private fun getString(name: String): String? {
        val id =
            res.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getString(id)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        // Inflate the layout for this fragment
        val id = res.getIdentifier(
            "settings_fragment",
            "layout",
            BuildConfig.LIBRARY_PACKAGE_NAME
        )
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val headerTw = view.findView<TextView>("header_tw")
        headerTw.text = getString("header_tw")

        val localizationTW = view.findView<TextView>("localization_tw")
        val homepageTW = view.findView<TextView>("homepage_tw")

        localizationTW.text = getString("localization_tw")
        homepageTW.text = getString("homepage_tw")


        val changeLocalizationButton = view.findView<ImageButton>("changeLocalization_button")
        changeLocalizationButton.setImageDrawable(getDrawable("settings_icon"))
        changeLocalizationButton.makeTvCompatible()

        changeLocalizationButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                LocalizationSettings(plugin, sharedPref).show(
                    activity?.supportFragmentManager
                        ?: throw Exception("Unable to open localization settings"),
                    ""
                )
                dismiss()
            }
        })

        val changeHomepageButton = view.findView<ImageButton>("changeHomepage_button")
        changeHomepageButton.setImageDrawable(getDrawable("settings_icon"))
        changeHomepageButton.makeTvCompatible()

        changeHomepageButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                HomepageSettings(plugin, sharedPref).show(
                    activity?.supportFragmentManager
                        ?: throw Exception("Unable to open localization settings"),
                    ""
                )
                dismiss()
            }
        })

    }
}
