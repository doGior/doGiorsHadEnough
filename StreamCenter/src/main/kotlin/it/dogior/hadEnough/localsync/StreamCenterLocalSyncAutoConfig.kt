package it.dogior.hadEnough.localsync

import android.content.Context
import android.content.SharedPreferences

internal enum class StreamCenterLocalSyncCategory(
    val wireValue: String,
    val title: String,
) {
    LIBRARY("library", "Libreria e progressi"),
    CLOUDSTREAM_CONFIG("cloudstream", "Configurazione CloudStream"),
    STREAMCENTER_CONFIG("streamcenter", "Configurazione StreamCenter"),
    ;

    companion object {
        fun fromWireValue(value: String?): StreamCenterLocalSyncCategory? =
            entries.firstOrNull { category -> category.wireValue == value }
    }
}

internal enum class StreamCenterLocalSyncPeerMode(
    val wireValue: String,
    val title: String,
    val summary: String,
    val canSend: Boolean,
    val canReceive: Boolean,
) {
    BIDIRECTIONAL(
        wireValue = "both",
        title = "Bidirezionale",
        summary = "Entrambi i dispositivi scambiano le modifiche più recenti",
        canSend = true,
        canReceive = true,
    ),
    SEND_ONLY(
        wireValue = "send",
        title = "Questo dispositivo invia",
        summary = "Invia solo le voci più recenti e non riceve modifiche",
        canSend = true,
        canReceive = false,
    ),
    RECEIVE_ONLY(
        wireValue = "receive",
        title = "Questo dispositivo riceve",
        summary = "Riceve solo le voci più recenti e non invia modifiche",
        canSend = false,
        canReceive = true,
    ),
    ;

    companion object {
        fun fromWireValue(value: String?): StreamCenterLocalSyncPeerMode? =
            entries.firstOrNull { mode -> mode.wireValue == value }
    }
}

internal object StreamCenterLocalSyncAutoConfig {
    private const val PREFS = "streamcenter_local_sync_auto"
    private const val KEY_ENABLED = "auto_enabled"
    private const val KEY_CATEGORIES = "auto_categories"
    private const val KEY_INTERVAL_MINUTES = "auto_interval_minutes"
    private const val KEY_PEER_MODE_PREFIX = "auto_peer_mode_"

    private const val DEFAULT_INTERVAL_MINUTES = 5
    private const val MIN_INTERVAL_MINUTES = 1
    private const val MAX_INTERVAL_MINUTES = 240

    private val DEFAULT_CATEGORIES = setOf(StreamCenterLocalSyncCategory.LIBRARY)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun categories(context: Context): Set<StreamCenterLocalSyncCategory> {
        val stored = prefs(context).getString(KEY_CATEGORIES, null) ?: return DEFAULT_CATEGORIES
        val parsed = stored.split(",")
            .mapNotNull { value -> StreamCenterLocalSyncCategory.fromWireValue(value.trim()) }
            .toSet()
        return parsed.ifEmpty { emptySet() }
    }

    fun setCategories(context: Context, categories: Set<StreamCenterLocalSyncCategory>) {
        prefs(context).edit()
            .putString(KEY_CATEGORIES, categories.joinToString(",") { category -> category.wireValue })
            .apply()
    }

    fun isCategoryEnabled(context: Context, category: StreamCenterLocalSyncCategory): Boolean =
        category in categories(context)

    fun setCategoryEnabled(context: Context, category: StreamCenterLocalSyncCategory, enabled: Boolean) {
        val current = categories(context).toMutableSet()
        if (enabled) current.add(category) else current.remove(category)
        setCategories(context, current)
    }

    fun intervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
            .coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(KEY_INTERVAL_MINUTES, minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES))
            .apply()
    }

    fun peerMode(context: Context, peerId: String): StreamCenterLocalSyncPeerMode =
        StreamCenterLocalSyncPeerMode.fromWireValue(
            prefs(context).getString(KEY_PEER_MODE_PREFIX + peerId, null),
        ) ?: StreamCenterLocalSyncPeerMode.BIDIRECTIONAL

    fun setPeerMode(context: Context, peerId: String, mode: StreamCenterLocalSyncPeerMode) {
        prefs(context).edit().putString(KEY_PEER_MODE_PREFIX + peerId, mode.wireValue).apply()
    }

    fun removePeerMode(context: Context, peerId: String) {
        prefs(context).edit().remove(KEY_PEER_MODE_PREFIX + peerId).apply()
    }

    fun isOperational(context: Context): Boolean =
        isEnabled(context) &&
            categories(context).isNotEmpty() &&
            StreamCenterLocalSyncTrust.trustedPeers(context).isNotEmpty()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
