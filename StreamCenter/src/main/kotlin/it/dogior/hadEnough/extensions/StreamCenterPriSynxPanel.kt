package it.dogior.hadEnough.extensions

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.Plugin

internal object StreamCenterPriSynxPanel {
    private const val DIALOG = "prisynx.hub.HubSettingsDialog"

    fun create(context: Context, plugin: Plugin): View {
        val access = StreamCenterExtensionSettingsAccess(plugin)
        val dialog = access.singleton(DIALOG)
        val dp = { value: Int -> (value * context.resources.displayMetrics.density).toInt() }
        fun color(name: String) = access.constant(dialog, name) as Int
        val tabs = (access.constant(dialog, "tabs") as List<*>).map { it.toString() }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }
        val ui = access.create("$DIALOG\$Ui", context, content, scroll)
        @Suppress("UNCHECKED_CAST")
        val tabViews = access.call(ui, "getTabViews") as MutableList<TextView>
        val select = { index: Int -> access.call(dialog, "select", ui, index, false) }
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(4), dp(10))
        }
        tabs.forEachIndexed { index, name ->
            val tab = TextView(context).apply {
                text = name
                setTextColor(color("TEXT"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minWidth = dp(88)
                minHeight = dp(48)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isFocusable = true
                isClickable = true
                background = access.call(dialog, "tabBackground", context) as Drawable
                id = View.generateViewId()
                setOnClickListener { runCatching { select(index) } }
                setOnFocusChangeListener { _, focused -> if (focused) runCatching { select(index) } }
            }
            tabViews.add(tab)
            tabRow.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = dp(8) })
        }
        select(0)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(color("BG"))
                cornerRadius = dp(18).toFloat()
            }
            clipToOutline = true
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(color("BAR"))
                addView(tabRow)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(TextView(context).apply {
                text = "OK = attiva o disattiva · ▲ ▼ = sposta · salvate subito in PriSynx, ricarica la Home per vederle"
                setTextColor(color("SUB"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setBackgroundColor(color("BAR"))
                setPadding(dp(16), dp(8), dp(16), dp(8))
            })
        }
    }
}
