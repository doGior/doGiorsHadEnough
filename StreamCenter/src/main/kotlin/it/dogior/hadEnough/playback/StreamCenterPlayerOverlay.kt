package it.dogior.hadEnough.playback

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal object StreamCenterPlayerOverlay {
    private const val SIDE_BAR_TAG = "streamcenter_player_side_bar"
    private const val PILL_COLOR = "#B3000000"
    private const val PILL_FOCUSED_COLOR = "#E6000000"

    fun host(root: View): FrameLayout? = root.findViewByName("player_holder") as? FrameLayout ?: root as? FrameLayout

    fun controlsVisible(topHolder: View?): Boolean =
        topHolder != null && topHolder.visibility == View.VISIBLE && topHolder.alpha > 0.1f

    fun sideBar(host: FrameLayout): LinearLayout =
        host.findViewWithTag(SIDE_BAR_TAG) as? LinearLayout ?: LinearLayout(host.context).apply {
            tag = SIDE_BAR_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            host.addView(this, sideParams(host.context))
        }

    fun addFloating(container: FrameLayout, view: View) {
        container.addView(view, sideParams(container.context))
    }

    private fun sideParams(context: Context) = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.END or Gravity.CENTER_VERTICAL,
    ).apply { marginEnd = dp(context, 24) }

    fun addToSideBar(host: FrameLayout, view: View, first: Boolean) {
        val bar = sideBar(host)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.END }
        if (first) bar.addView(view, 0, params) else bar.addView(view, params)
        updateSideBarMargins(bar)
    }

    fun updateSideBarMargins(view: View) {
        val bar = (view as? LinearLayout) ?: (view.parent as? LinearLayout) ?: return
        var visible = 0
        for (index in 0 until bar.childCount) {
            val child = bar.getChildAt(index)
            val params = child.layoutParams as LinearLayout.LayoutParams
            val margin = if (child.visibility == View.GONE || visible == 0) 0 else dp(bar.context, 10)
            if (params.topMargin != margin) {
                params.topMargin = margin
                child.layoutParams = params
            }
            if (child.visibility != View.GONE) visible++
        }
    }

    fun pill(context: Context, text: String, focusable: Boolean): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        isFocusable = focusable
        val padding = dp(context, 10)
        setPadding(padding * 2, padding, padding * 2, padding)
        val normal = pillBackground(context, PILL_COLOR, null)
        background = normal
        if (focusable) {
            val focused = pillBackground(context, PILL_FOCUSED_COLOR, Color.WHITE)
            setOnFocusChangeListener { _, hasFocus -> background = if (hasFocus) focused else normal }
        }
    }

    fun pillBackground(context: Context, color: String, strokeColor: Int?): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(context, 22).toFloat()
        setColor(Color.parseColor(color))
        strokeColor?.let { setStroke(dp(context, 2), it) }
    }

    fun View.findViewByName(name: String): View? {
        val id = resources.getIdentifier(name, "id", context.packageName).takeIf { it != 0 } ?: return null
        return findViewById(id)
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
