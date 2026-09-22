package it.dogior.hadEnough.settings

import android.content.Context
import androidx.activity.OnBackPressedCallback
import com.google.android.material.bottomsheet.BottomSheetDialog

internal class StreamCenterSettingsDialog(
    context: Context,
    theme: Int,
    private val navigateBack: () -> Boolean,
) : BottomSheetDialog(context, theme) {
    private val navigationCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (!navigateBack()) cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        navigationCallback.remove()
        onBackPressedDispatcher.addCallback(navigationCallback)
    }

    override fun onDetachedFromWindow() {
        navigationCallback.remove()
        super.onDetachedFromWindow()
    }
}
