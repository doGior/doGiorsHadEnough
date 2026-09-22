package it.dogior.hadEnough.settings

import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation
import androidx.fragment.app.FragmentTransaction

internal object StreamCenterSettingsNavigation {
    fun configure(transaction: FragmentTransaction): FragmentTransaction = transaction
        .setReorderingAllowed(true)
        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
        .setCustomAnimations(
            android.R.anim.fade_in,
            android.R.anim.fade_out,
            android.R.anim.slide_in_left,
            android.R.anim.slide_out_right,
        )

    fun animation(
        transit: Int,
        entering: Boolean,
        enabled: Boolean,
        rtl: Boolean,
        nextAnim: Int = 0,
    ): Animation? {
        val returning = nextAnim == android.R.anim.slide_in_left ||
            nextAnim == android.R.anim.slide_out_right ||
            transit == FragmentTransaction.TRANSIT_FRAGMENT_CLOSE
        if (transit != FragmentTransaction.TRANSIT_FRAGMENT_OPEN &&
            !returning
        ) return null

        if (!enabled) return AlphaAnimation(1f, 1f).apply { duration = 0 }

        val direction = (if (returning) -1f else 1f) * (if (rtl) -1f else 1f)
        val distance = if (returning) 0.16f else 0.08f
        return AnimationSet(true).apply {
            duration = if (returning) 300 else if (entering) 260 else 180
            interpolator = DecelerateInterpolator(2f)
            addAnimation(AlphaAnimation(if (entering) 0f else 1f, if (entering) 1f else 0f))
            addAnimation(TranslateAnimation(
                Animation.RELATIVE_TO_SELF, if (entering) direction * distance else 0f,
                Animation.RELATIVE_TO_SELF, if (entering) 0f else -direction * distance,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
            ))
            val fromScale = if (entering) 0.985f else 1f
            val toScale = if (entering) 1f else 0.985f
            addAnimation(ScaleAnimation(fromScale, toScale, fromScale, toScale,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f))
        }
    }
}
