package it.dogior.hadEnough.util

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import it.dogior.hadEnough.StreamCenterPlugin

internal object StreamCenterVpnGuard {
    private const val BLOCKED_MESSAGE =
        "Le richieste Internet di StreamCenter sono bloccate: attiva una VPN o disattiva Protezione VPN."

    fun canUseInternet(sharedPref: SharedPreferences? = StreamCenterPlugin.activeSharedPref): Boolean {
        val preferences = sharedPref ?: StreamCenterPlugin.activeSharedPref
        return !StreamCenterPlugin.isVpnRequired(preferences) || isVpnActive(StreamCenterPlugin.activeContext)
    }

    fun requireInternetAccess(sharedPref: SharedPreferences? = StreamCenterPlugin.activeSharedPref) {
        check(canUseInternet(sharedPref)) { BLOCKED_MESSAGE }
    }

    @Suppress("DEPRECATION")
    fun isVpnActive(context: Context?): Boolean {
        val manager = context?.applicationContext
            ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return runCatching {
            val activeCapabilities = manager.activeNetwork
                ?.let(manager::getNetworkCapabilities)
            if (activeCapabilities != null) {
                activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } else {
                manager.allNetworks.any { network ->
                    manager.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
            }
        }.getOrDefault(false)
    }
}
