package it.dogior.hadEnough.settings

import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.extensions.InstalledExtensionSources
import it.dogior.hadEnough.extensions.InstalledExtension
import it.dogior.hadEnough.stremio.StreamCenterStremioAddon

import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.Normalizer
import java.util.Locale

internal enum class SettingsScreenId {
    HOME,
    BASE_CATALOG,
    SOURCES,
    INTERFACE,
    SYSTEM,
    CACHE,
    LOGS,
}

internal data class SettingsToggleBinding(
    val read: (SharedPreferences?) -> Boolean,
    val write: (SharedPreferences?, Boolean) -> Unit,
    val defaultChecked: Boolean,
)

internal data class SettingsIndexEntry(
    val title: String,
    val trail: String,
    val icon: String,
    val accent: String,
    val screen: SettingsScreenId,
    val keywords: String = "",
    val toggle: SettingsToggleBinding? = null,
    val installedExtension: InstalledExtension? = null,
)

private fun normalize(value: String): String {
    val decomposed = Normalizer.normalize(value.lowercase(Locale.ITALY), Normalizer.Form.NFD)
    return decomposed.replace("\\p{Mn}+".toRegex(), "")
}

private fun booleanPref(key: String, default: Boolean): SettingsToggleBinding {
    return SettingsToggleBinding(
        read = { prefs -> prefs?.getBoolean(key, default) ?: default },
        write = { prefs, value -> prefs?.edit { putBoolean(key, value) } },
        defaultChecked = default,
    )
}

internal object StreamCenterSettingsIndex {
    fun entries(prefs: SharedPreferences?): List<SettingsIndexEntry> {
        return staticEntries() + homeCategoryEntries() + sourceEntries() + addonEntries(prefs) + extensionEntries()
    }

    private fun extensionEntries(): List<SettingsIndexEntry> = InstalledExtensionSources.available().map { source ->
        SettingsIndexEntry(
            title = source.name,
            trail = "Fonti › Estensioni installate",
            icon = "\uD83E\uDDE9",
            accent = COLOR_SOURCES,
            screen = SettingsScreenId.SOURCES,
            keywords = "estensione installata cloudstream video " + source.sources.joinToString(" ") { "${it.api.name} ${it.api.lang}" },
            installedExtension = source,
            toggle = SettingsToggleBinding(
                read = { source.isEnabled(InstalledExtensionSources.enabledKeys(it)) },
                write = { prefs, enabled -> InstalledExtensionSources.setEnabled(prefs, source, enabled) },
                defaultChecked = false,
            ),
        )
    }

    fun search(prefs: SharedPreferences?, query: String): List<SettingsIndexEntry> {
        val needle = normalize(query.trim())
        if (needle.isEmpty()) return emptyList()
        return entries(prefs)
            .mapNotNull { entry ->
                val title = normalize(entry.title)
                val rank = when {
                    title.startsWith(needle) -> 0
                    title.contains(needle) -> 1
                    normalize(entry.keywords).contains(needle) -> 2
                    normalize(entry.trail).contains(needle) -> 3
                    else -> return@mapNotNull null
                }
                rank to entry
            }
            .sortedWith(compareBy({ it.first }, { it.second.title }))
            .map { it.second }
    }

    private fun staticEntries(): List<SettingsIndexEntry> = listOf(
        SettingsIndexEntry(
            title = "Ordinamento libero",
            trail = "Home › Ordinamento libero",
            icon = "⇅",
            accent = COLOR_HOME,
            screen = SettingsScreenId.BASE_CATALOG,
            keywords = "sezioni ordine mescolare anime film serie tv tmdb myanimelist kitsu anilist simkl estensioni cloudstream",
        ),
        SettingsIndexEntry(
            title = "Cerca su TMDB se non ci sono risultati",
            trail = "Fonti › Ricerca",
            icon = "🔎",
            accent = COLOR_SOURCES,
            screen = SettingsScreenId.SOURCES,
            keywords = "ricerca vuota nessun risultato fallback film serie tv tmdb",
            toggle = booleanPref(StreamCenterPlugin.PREF_TMDB_SEARCH_FALLBACK, true),
        ),
        SettingsIndexEntry(
            title = "Estensioni installate",
            trail = "Fonti",
            icon = "\uD83E\uDDE9",
            accent = COLOR_SOURCES,
            screen = SettingsScreenId.SOURCES,
            keywords = "cloudstream altre estensioni fonti video provider plugin",
        ),
        SettingsIndexEntry(
            title = "Valutazione",
            trail = "Home › Schede",
            icon = "⭐",
            accent = COLOR_SCORE,
            screen = SettingsScreenId.HOME,
            keywords = "voto punteggio stelle rating giudizio",
            toggle = booleanPref(StreamCenterPlugin.PREF_SHOW_HOME_SCORE, true),
        ),
        SettingsIndexEntry(
            title = "SUB/DUB",
            trail = "Home › Schede",
            icon = "🎙️",
            accent = COLOR_ANIME_VARIANTS,
            screen = SettingsScreenId.HOME,
            keywords = "doppiaggio doppiato sottotitolato sottotitoli lingua audio anime",
            toggle = booleanPref(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_DUB_STATUS, true),
        ),
        SettingsIndexEntry(
            title = "Numero episodi",
            trail = "Home › Schede",
            icon = "🔢",
            accent = COLOR_EPISODES,
            screen = SettingsScreenId.HOME,
            keywords = "puntate ultimo episodio conteggio anime",
            toggle = booleanPref(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_EPISODE_NUMBER, true),
        ),
        SettingsIndexEntry(
            title = "Unifica SUB e DUB",
            trail = "Home › Schede",
            icon = "🔗",
            accent = COLOR_ANIME_VARIANTS,
            screen = SettingsScreenId.HOME,
            keywords = "raggruppa unisci varianti doppie schede anime duplicati",
            toggle = booleanPref(StreamCenterPlugin.PREF_GROUP_ANIME_DUB_SUB, true),
        ),
        SettingsIndexEntry(
            title = "Unifica stagioni Anime",
            trail = "Home › Schede",
            icon = "📚",
            accent = COLOR_ANIME_VARIANTS,
            screen = SettingsScreenId.HOME,
            keywords = "stagioni parti cour raggruppa anime tracciamento disattivato anilist mal kitsu simkl",
            toggle = booleanPref(StreamCenterPlugin.PREF_GROUP_ANIME_SEASONS, false),
        ),
        SettingsIndexEntry(
            title = "Titolo Anime",
            trail = "Home › Schede",
            icon = "✍️",
            accent = COLOR_DISPLAY,
            screen = SettingsScreenId.HOME,
            keywords = "romaji inglese giapponese nome lingua originale",
        ),
        SettingsIndexEntry(
            title = "ID di tracciamento",
            trail = "Home › Schede",
            icon = "🆔",
            accent = COLOR_TRACKING_IDS,
            screen = SettingsScreenId.HOME,
            keywords = "myanimelist mal anilist kitsu simkl imdb tmdb codici",
            toggle = booleanPref(StreamCenterPlugin.PREF_SHOW_TRACKING_IDS, false),
        ),
        SettingsIndexEntry(
            title = "Ordine delle sezioni",
            trail = "Home › Ordinamento libero",
            icon = "↕️",
            accent = COLOR_HOME,
            screen = SettingsScreenId.BASE_CATALOG,
            keywords = "riordina sposta sezioni righe caroselli disposizione",
        ),
        SettingsIndexEntry(
            title = "Aggiornamento automatico",
            trail = "Fonti",
            icon = "🔄",
            accent = COLOR_SOURCE_UPDATE,
            screen = SettingsScreenId.SOURCES,
            keywords = "dominio indirizzo url cambio sito automatico",
            toggle = booleanPref(StreamCenterPlugin.PREF_AUTO_UPDATE_SOURCE_URLS, true),
        ),
        SettingsIndexEntry(
            title = "Verifica API e Fonti",
            trail = "Fonti",
            icon = "📡",
            accent = COLOR_API_CHECK,
            screen = SettingsScreenId.SOURCES,
            keywords = "test controlla diagnostica raggiungibile stato ping",
        ),
        SettingsIndexEntry(
            title = "Torrent",
            trail = "Fonti",
            icon = "🧲",
            accent = COLOR_TORRENT,
            screen = SettingsScreenId.SOURCES,
            keywords = "magnet libretorrent ext provider peer seed",
            toggle = SettingsToggleBinding(
                read = { prefs -> StreamCenterPlugin.isTorrentEnabled(prefs) },
                write = { prefs, value -> StreamCenterPlugin.setTorrentEnabled(prefs, value) },
                defaultChecked = StreamCenterPlugin.isTorrentEnabled(null),
            ),
        ),
        SettingsIndexEntry(
            title = "Add-on Stremio",
            trail = "Fonti",
            icon = "🔌",
            accent = COLOR_STREMIO,
            screen = SettingsScreenId.SOURCES,
            keywords = "torrentio manifest catalogo estensione installa",
        ),
        SettingsIndexEntry(
            title = "Modalità Prestazioni",
            trail = "Interfaccia",
            icon = "⚡",
            accent = COLOR_PERFORMANCE,
            screen = SettingsScreenId.INTERFACE,
            keywords = "veloce leggera fluida lag scatti risparmio batteria",
            toggle = booleanPref(StreamCenterPlugin.PREF_PERFORMANCE_MODE, false),
        ),
        SettingsIndexEntry(
            title = "Modalità TV",
            trail = "Interfaccia",
            icon = "📺",
            accent = COLOR_DISPLAY,
            screen = SettingsScreenId.INTERFACE,
            keywords = "televisore telecomando android tv leanback firestick d-pad",
            toggle = booleanPref(StreamCenterPlugin.PREF_FORCE_TV_MODE, false),
        ),
        SettingsIndexEntry(
            title = "Animazioni",
            trail = "Interfaccia › Effetti",
            icon = "🎞️",
            accent = COLOR_VISUAL_EFFECTS,
            screen = SettingsScreenId.INTERFACE,
            keywords = "movimento transizioni motion effetti",
            toggle = booleanPref(StreamCenterPlugin.PREF_VISUAL_EFFECTS_ANIMATIONS, true),
        ),
        SettingsIndexEntry(
            title = "Sfocatura finestre",
            trail = "Interfaccia › Effetti",
            icon = "🌫️",
            accent = COLOR_VISUAL_BLUR,
            screen = SettingsScreenId.INTERFACE,
            keywords = "blur sfondo vetro trasparenza",
            toggle = booleanPref(StreamCenterPlugin.PREF_VISUAL_EFFECTS_BLUR, true),
        ),
        SettingsIndexEntry(
            title = "Intestazione StreamCenter",
            trail = "Interfaccia › Effetti",
            icon = "✨",
            accent = COLOR_VISUAL_HEADER,
            screen = SettingsScreenId.INTERFACE,
            keywords = "titolo gradiente bagliore testata header",
            toggle = booleanPref(StreamCenterPlugin.PREF_VISUAL_EFFECTS_TITLE, true),
        ),
        SettingsIndexEntry(
            title = "Universo animato",
            trail = "Interfaccia › Effetti",
            icon = "🌌",
            accent = COLOR_PARTICLES,
            screen = SettingsScreenId.INTERFACE,
            keywords = "particelle stelle aurora sfondo galassia",
            toggle = booleanPref(StreamCenterPlugin.PREF_VISUAL_EFFECTS_PARTICLES, true),
        ),
        SettingsIndexEntry(
            title = "Mostra IP pubblico",
            trail = "Interfaccia › Effetti",
            icon = "🌐",
            accent = COLOR_PUBLIC_IP,
            screen = SettingsScreenId.INTERFACE,
            keywords = "indirizzo rete connessione ip",
            toggle = booleanPref(StreamCenterPlugin.PREF_VISUAL_EFFECTS_PUBLIC_IP, true),
        ),
        SettingsIndexEntry(
            title = "Protezione VPN",
            trail = "Sistema › Rete",
            icon = "🛡️",
            accent = COLOR_VPN_GUARD,
            screen = SettingsScreenId.SYSTEM,
            keywords = "blocca internet privacy tunnel sicurezza killswitch",
            toggle = booleanPref(StreamCenterPlugin.PREF_REQUIRE_VPN, false),
        ),
        SettingsIndexEntry(
            title = "Esporta e importa",
            trail = "Sistema › Dati",
            icon = "💾",
            accent = COLOR_BACKUP,
            screen = SettingsScreenId.SYSTEM,
            keywords = "backup salva ripristina copia file configurazione",
        ),
        SettingsIndexEntry(
            title = "Sync locale",
            trail = "Sistema › Dati",
            icon = "🔐",
            accent = COLOR_LOCAL_SYNC,
            screen = SettingsScreenId.SYSTEM,
            keywords = "sincronizza dispositivi rete locale wifi trasferisci",
        ),
        SettingsIndexEntry(
            title = "Cache",
            trail = "Sistema › Dati",
            icon = "⚡",
            accent = COLOR_CACHE,
            screen = SettingsScreenId.CACHE,
            keywords = "memoria svuota spazio schede calendario temporanea",
        ),
        SettingsIndexEntry(
            title = "Log",
            trail = "Sistema › Diagnostica",
            icon = "📋",
            accent = COLOR_LOG,
            screen = SettingsScreenId.LOGS,
            keywords = "registro errori debug diagnostica cronologia",
        ),
        SettingsIndexEntry(
            title = "Invia feedback",
            trail = "Sistema › Diagnostica",
            icon = "💬",
            accent = COLOR_FEEDBACK,
            screen = SettingsScreenId.SYSTEM,
            keywords = "segnala bug telegram github contatta supporto",
        ),
        SettingsIndexEntry(
            title = "Ripristina tutte le impostazioni",
            trail = "Sistema › Diagnostica",
            icon = "♻️",
            accent = COLOR_RESET,
            screen = SettingsScreenId.SYSTEM,
            keywords = "reset azzera predefinite iniziali cancella",
        ),
    )

    private fun homeCategoryEntries(): List<SettingsIndexEntry> {
        return listOf(
            Triple("Anime", "🎌", COLOR_HOME_ANIME) to "cartoni giapponesi sub dub",
            Triple("Serie TV", "📺", COLOR_HOME_TV) to "telefilm show stagioni",
            Triple("Film", "🎬", COLOR_HOME_MOVIE) to "cinema lungometraggi",
            Triple("Live", "📡", COLOR_HOME_CHANNELS) to "iptv canali diretta playlist m3u",
            Triple("Tracciamento", "🔖", COLOR_HOME_TRACKING) to "lista guardando completati anilist mal simkl",
            Triple("Cataloghi", "🗂️", COLOR_CATALOGS) to "stremio catalogo esterni raccolte",
        ).map { (meta, keywords) ->
            val (title, icon, accent) = meta
            SettingsIndexEntry(
                title = title,
                trail = "Home › Sezioni",
                icon = icon,
                accent = accent,
                screen = SettingsScreenId.HOME,
                keywords = keywords,
            )
        }
    }

    private fun sourceEntries(): List<SettingsIndexEntry> {
        return StreamCenterPlugin.streamingSources.map { source ->
            SettingsIndexEntry(
                title = source.title,
                trail = "Fonti",
                icon = "🌐",
                accent = if (source.category == "anime") COLOR_SOURCE_ANIME else COLOR_SOURCE_TV,
                screen = SettingsScreenId.SOURCES,
                keywords = "fonte sito streaming indirizzo url ${source.category}",
                toggle = SettingsToggleBinding(
                    read = { prefs -> StreamCenterPlugin.isStreamingSourceEnabled(prefs, source.key) },
                    write = { prefs, value -> prefs?.edit { putBoolean(source.key, value) } },
                    defaultChecked = source.defaultEnabled,
                ),
            )
        }
    }

    private fun addonEntries(prefs: SharedPreferences?): List<SettingsIndexEntry> {
        return StreamCenterPlugin.getStremioAddons(prefs).map { addon: StreamCenterStremioAddon ->
            SettingsIndexEntry(
                title = addon.name,
                trail = "Fonti › Add-on Stremio",
                icon = "🔌",
                accent = COLOR_STREMIO,
                screen = SettingsScreenId.SOURCES,
                keywords = "stremio addon manifest catalogo",
            )
        }
    }
}
