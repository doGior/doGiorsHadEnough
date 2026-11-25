package it.dogior.hadEnough.settings

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.LoadResponse
import it.dogior.hadEnough.StreamCenter
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.anime.metadata.AnimeSeasonRoutes
import it.dogior.hadEnough.tracking.StreamCenterTrackingOverrides
import java.util.Collections
import java.util.WeakHashMap

internal object StreamCenterResultTrackingButton {
    private const val URL_ARGUMENT = "url"
    private const val API_ARGUMENT = "apiName"
    private const val EDITOR_TAG = "StreamCenterCardTrackingIds"
    private const val BUTTON_TAG = "streamcenter_tracking_ids_button"
    private const val DEFAULT_LOAD_TRAILERS_MASK = 64

    private val attachedActivities = Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())
    private val cards = WeakHashMap<Fragment, String>()
    private var registered = false

    fun reload(key: String): Boolean = runCatching {
        val fragment = synchronized(cards) { cards.entries.lastOrNull { it.value == key && it.key.isAdded }?.key }
            ?: return false
        val activity = fragment.activity ?: return false
        val viewModel = generateSequence<Class<*>>(fragment.javaClass) { it.superclass }
            .firstNotNullOfOrNull { type -> runCatching { type.getDeclaredMethod("getViewModel") }.getOrNull() }
            ?.apply { isAccessible = true }
            ?.invoke(fragment)
            ?: return false
        val response = viewModel.javaClass.getDeclaredField("currentResponse")
            .apply { isAccessible = true }
            .get(viewModel) as? LoadResponse
        val resultFragment = Class.forName("com.lagradost.cloudstream3.ui.result.ResultFragment")
        val stored = resultFragment.getMethod("getStoredData", Fragment::class.java)
            .invoke(resultFragment.getField("INSTANCE").get(null), fragment)
            ?: return false
        fun storedValue(getter: String): Any? = stored.javaClass.getMethod(getter).invoke(stored)
        val apiName = storedValue("getApiName") as String
        val api = APIHolder.getApiFromNameNull(apiName) as? StreamCenter ?: return false
        val applied = response == null || StreamCenterTrackingOverrides.reapply(
            response, StreamCenterPlugin.activeSharedPref, key, restoreTags = api.catalogDefinition == null,
        )
        if (!applied) return false
        viewModel.javaClass.methods.first { it.name == "load\$default" }.invoke(
            null, viewModel, activity,
            storedValue("getUrl"), apiName, storedValue("getShowFillers"),
            storedValue("getDubStatus"), storedValue("getStart"), false, DEFAULT_LOAD_TRAILERS_MASK, null,
        )
        true
    }.getOrDefault(false)

    @Synchronized
    fun install(context: Context) {
        if (!registered) {
            (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(activityCallbacks)
            registered = true
        }
        (context as? FragmentActivity)?.let(::attach)
    }

    private fun attach(activity: FragmentActivity) {
        if (!attachedActivities.add(activity)) return
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
    }

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, state: Bundle?) {
            runCatching { decorate(fragment, view) }
        }
    }

    private fun decorate(fragment: Fragment, root: View) {
        val arguments = fragment.arguments ?: return
        val url = arguments.getString(URL_ARGUMENT)?.takeIf(String::isNotBlank) ?: return
        val apiName = arguments.getString(API_ARGUMENT)?.takeIf(String::isNotBlank) ?: return
        if (APIHolder.getApiFromNameNull(apiName) !is StreamCenter) return
        val key = AnimeSeasonRoutes.unwrap(url) ?: url
        synchronized(cards) { cards[fragment] = key }
        val syncButton = root.findViewByName<Button>("result_sync_set_score") ?: return
        val column = syncButton.parent as? LinearLayout ?: return
        if (column.findViewWithTag<View>(BUTTON_TAG) != null) return
        val button = trackingButton(syncButton).apply {
            setOnClickListener {
                val activity = fragment.activity as? FragmentActivity ?: return@setOnClickListener
                if (activity.supportFragmentManager.findFragmentByTag(EDITOR_TAG) != null) return@setOnClickListener
                val title = root.findViewByName<TextView>("result_title")?.text?.toString().orEmpty()
                StreamCenterTrackingIdsSettingsFragment.forCard(key, apiName, title)
                    .show(activity.supportFragmentManager, EDITOR_TAG)
            }
        }
        column.addView(button, column.indexOfChild(syncButton) + 1)
    }

    private fun trackingButton(template: Button): Button {
        val button = (template as? MaterialButton)?.let { source ->
            MaterialButton(source.context).apply {
                backgroundTintList = source.backgroundTintList
                cornerRadius = source.cornerRadius
                strokeColor = source.strokeColor
                strokeWidth = source.strokeWidth
                rippleColor = source.rippleColor
                insetTop = source.insetTop
                insetBottom = source.insetBottom
            }
        } ?: Button(template.context).apply { background = template.background?.constantState?.newDrawable()?.mutate() }
        return button.apply {
            tag = BUTTON_TAG
            text = "ID di tracciamento"
            isAllCaps = template.isAllCaps
            typeface = template.typeface
            setTextColor(template.textColors)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, template.textSize)
            minHeight = template.minHeight
            minimumHeight = template.minimumHeight
            setPadding(template.paddingLeft, template.paddingTop, template.paddingRight, template.paddingBottom)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(template.layoutParams as ViewGroup.LayoutParams).also { params ->
                (template.layoutParams as? ViewGroup.MarginLayoutParams)?.let { source ->
                    params.setMargins(source.leftMargin, source.topMargin, source.rightMargin, source.bottomMargin)
                }
            }
        }
    }

    private fun <T : View> View.findViewByName(name: String): T? {
        val id = resources.getIdentifier(name, "id", context.packageName).takeIf { it != 0 } ?: return null
        return findViewById(id)
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            (activity as? FragmentActivity)?.let(::attach)
        }

        override fun onActivityDestroyed(activity: Activity) {
            (activity as? FragmentActivity)?.let { attachedActivities.remove(it) }
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
