package it.dogior.hadEnough.util

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import it.dogior.hadEnough.StreamCenterPlugin

internal object StreamCenterDevice {
    fun isTvLike(context: Context?, sharedPref: SharedPreferences? = StreamCenterPlugin.activeSharedPref): Boolean {
        if (StreamCenterPlugin.isForceTvModeEnabled(sharedPref)) return true
        val ctx = context ?: return false
        val pm = ctx.packageManager
        val uiMode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun isLowPower(context: Context?): Boolean {
        val ctx = context ?: return false
        val activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return isTvLike(ctx) || activityManager?.isLowRamDevice == true
    }
}
