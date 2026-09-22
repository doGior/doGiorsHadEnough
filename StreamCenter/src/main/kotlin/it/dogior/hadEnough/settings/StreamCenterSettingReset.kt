package it.dogior.hadEnough.settings

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

internal object StreamCenterSettingReset {
    fun bind(view: View, reset: () -> Unit) {
        view.setOnLongClickListener {
            reset()
            true
        }
        ViewCompat.replaceAccessibilityAction(
            view,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            "Ripristina valore predefinito",
        ) { target, _ -> target.performLongClick() }
    }
}
