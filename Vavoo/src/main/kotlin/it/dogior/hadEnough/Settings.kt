package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class Settings(
    private val plugin: VavooPlugin,
    private val sharedPref: SharedPreferences,
    countries: Map<String, Boolean>
) : BottomSheetDialogFragment() {
    private val savedContries = let {
        val c = sharedPref.getString("countries", "")
        if (!c.isNullOrEmpty()) {
            parseJson<Map<String, Boolean>>(c)
        } else {
            null
        }
    }
    private val enabledCountries = (savedContries ?: countries).toMutableMap()
    private fun View.makeTvCompatible() {
        this.setPadding(
            this.paddingLeft + 10,
            this.paddingTop + 10,
            this.paddingRight + 10,
            this.paddingBottom + 10
        )
        this.background = getDrawable("outline")
    }

    // Helper function to get a drawable resource by name
    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    // Helper function to get a string resource by name
    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getString(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
    }

    // Generic findView function to find views by name
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
        // Inflate the layout for this fragment
        val layoutId =
            plugin.resources?.getIdentifier("settings", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return layoutId?.let {
            inflater.inflate(plugin.resources?.getLayout(it), container, false)
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = getString("header_tw")
        val header2Tw: TextView? = view.findViewByName("header2_tw")
        header2Tw?.text = getString("header2_tw")



        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        val scrollView: LinearLayout? = view.findViewByName("list")
        enabledCountries.toSortedMap().forEach {
            scrollView?.addView(createRow(it))
        }

        saveBtn?.setOnClickListener {
            if (enabledCountries.all { !it.value }) {
                showToast("You must select at least one country to show")
            } else {
                with(sharedPref.edit()) {
                    this.clear()
                    this.putString("countries", enabledCountries.toJson())
                    this.apply()
                }
                showToast("Saved. Restart the app to apply the settings")
                dismiss()
            }
        }

    }

    private fun createRow(country: Map.Entry<String, Boolean>): RelativeLayout {
        // Create RelativeLayout
        val relativeLayout = RelativeLayout(this@Settings.requireContext()).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 0, 0, 8)
        }

        // Create TextView
        val textView = TextView(this@Settings.requireContext()).apply {
            id = View.generateViewId()
            text = country.key
            textSize = 16f
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }

        // Create CheckBox
        val checkBox = CheckBox(this@Settings.requireContext()).apply {
            id = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }

        checkBox.isChecked = country.value

        checkBox.setOnCheckedChangeListener { _, b ->
            enabledCountries[country.key] = b
        }

        textView.setOnClickListener {
            checkBox.isChecked = !checkBox.isChecked
        }

        // Add views to RelativeLayout
        relativeLayout.addView(textView)
        relativeLayout.addView(checkBox)

        // Set the RelativeLayout as the content view
        return relativeLayout
    }
}
