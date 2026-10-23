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
    ROOT,
    HOME,
    CARDS,
    BASE_CATALOG,
    SOURCES,
    TORRENT,
    INTERFACE,
    CACHE,
    LOGS,
    TRACKING_IDS,
    WATCHING,
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
            accent = COLOR_ACCENT,
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
            title = "Chiave API TMDB",
            trail = "Fonti › TMDB",
            icon = "🔑",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.SOURCES,
            keywords = "tmdb api key chiave token v3 v4 metadati episodi attribuzione",
        ),
        SettingsIndexEntry(
            title = "User-Agent TMDB",
            trail = "Fonti › TMDB",
            icon = "🌐",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.SOURCES,
            keywords = "tmdb user agent user-agent custom personalizzato header http sito api",
        ),
        SettingsIndexEntry(
            title = "Cerca su TMDB se non ci sono risultati",
            trail = "Fonti › Ricerca",
            icon = "🔎",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.SOURCES,
            keywords = "ricerca vuota nessun risultato fallback film serie tv tmdb",
            toggle = booleanPref(StreamCenterPlugin.PREF_TMDB_SEARCH_FALLBACK, true),
        ),
        SettingsIndexEntry(
            title = "Estensioni installate",
            trail = "Fonti",
            icon = "\uD83E\uDDE9",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.SOURCES,
            keywords = "cloudstream altre estensioni fonti video provider plugin",
        ),
        SettingsIndexEntry(
            title = "Valutazione",
            trail = "Schede",
            icon = "⭐",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.CARDS,
            keywords = "voto punteggio stelle rating giudizio",
            toggle = booleanPref(StreamCenterPlugin.PREF_SHOW_HOME_SCORE, true),
        ),
        SettingsIndexEntry(
            title = "SUB/DUB",
            trail = "Schede",
            icon = "🎙️",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.CARDS,
            keywords = "doppiaggio doppiato sottotitolato sottotitoli lingua audio anime",
            toggle = booleanPref(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_DUB_STATUS, true),
        ),
        SettingsIndexEntry(
            title = "Numero episodi",
            trail = "Schede",
            icon = "🔢",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.CARDS,
            keywords = "puntate ultimo episodio conteggio anime",
            toggle = booleanPref(StreamCenterPlugin.PREF_SHOW_ANIME_HOME_EPISODE_NUMBER, true),
        ),
        SettingsIndexEntry(
            title = "Unifica SUB e DUB",
            trail = "Schede",
            icon = "🔗",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.CARDS,
            keywords = "raggruppa unisci varianti doppie schede anime duplicati",
            toggle = booleanPref(StreamCenterPlugin.PREF_GROUP_ANIME_DUB_SUB, true),
        ),
        SettingsIndexEntry(
            title = "Unifica stagioni Anime",
            trail = "Schede",
            icon = "📚",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.CARDS,
            keywords = "stagioni parti cour raggruppa anime tracciamento disattivato anilist mal kitsu simkl",
            toggle = booleanPref(StreamCenterPlugin.PREF_GROUP_ANIME_SEASONS, false),
        ),
        SettingsIndexEntry(
            title = "Titolo Anime",
            trail = "Schede",
            icon = "✍️",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.CARDS,
            keywords = "romaji inglese giapponese nome lingua originale",
        ),
        SettingsIndexEntry(
            title = "ID di tracciamento",
            trail = "Schede",
            icon = "🆔",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.CARDS,
            keywords = "myanimelist mal anilist kitsu simkl imdb tmdb codici",
            toggle = booleanPref(StreamCenterPlugin.PREF_SHOW_TRACKING_IDS, false),
        ),
        SettingsIndexEntry(
            title = "Guardando",
            trail = "Contenuti",
            icon = "▶️",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.WATCHING,
            keywords = "watching in pari episodi mancanti arretrati recuperare progresso serie seguo continua",
        ),
        SettingsIndexEntry(
            title = "ID manuali",
            trail = "Schede › ID di tracciamento",
            icon = "✎",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.TRACKING_IDS,
            keywords = "correggi modifica sbagliato manuale myanimelist mal anilist kitsu simkl imdb tmdb tracciamento sincronizzazione",
        ),
        SettingsIndexEntry(
            title = "Ordine delle sezioni",
            trail = "Home › Disposizione",
            icon = "⇅",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.BASE_CATALOG,
            keywords = "riordina sposta sezioni righe caroselli disposizione ordinamento libero mescolare tmdb myanimelist kitsu anilist simkl estensioni cloudstream",
        ),
        SettingsIndexEntry(
            title = "Aggiornamento automatico",
            trail = "Fonti",
            icon = "🔄",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.SOURCES,
            keywords = "dominio indirizzo url cambio sito automatico",
            toggle = booleanPref(StreamCenterPlugin.PREF_AUTO_UPDATE_SOURCE_URLS, true),
        ),
        SettingsIndexEntry(
            title = "Verifica fonti",
            trail = "Fonti",
            icon = "📡",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.SOURCES,
            keywords = "test controlla diagnostica raggiungibile stato ping",
        ),
        SettingsIndexEntry(
            title = "Torrent",
            trail = "Fonti › Altre fonti",
            icon = "🧲",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.TORRENT,
            keywords = "magnet libretorrent ext provider peer seed",
            toggle = SettingsToggleBinding(
                read = { prefs -> StreamCenterPlugin.isTorrentEnabled(prefs) },
                write = { prefs, value -> StreamCenterPlugin.setTorrentEnabled(prefs, value) },
                defaultChecked = StreamCenterPlugin.isTorrentEnabled(null),
            ),
        ),
        SettingsIndexEntry(
            title = "Endpoint EXT",
            trail = "Torrent › Collegamento",
            icon = "🔗",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.TORRENT,
            keywords = "ext dominio proxy cloudflare verifica collegamento indirizzo",
        ),
        SettingsIndexEntry(
            title = "Dove cercare",
            trail = "Torrent › Ricerca",
            icon = "🔎",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.TORRENT,
            keywords = "titolo nome file posizione ricerca torrent",
        ),
        SettingsIndexEntry(
            title = "Lingua dei torrent",
            trail = "Torrent › Ricerca",
            icon = "🌐",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.TORRENT,
            keywords = "italiano ita audio doppiato sub lingua parole chiave",
        ),
        SettingsIndexEntry(
            title = "Filtri dei torrent",
            trail = "Torrent › Risultati",
            icon = "🎚️",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.TORRENT,
            keywords = "seed seeders dimensione qualità risoluzione 1080p 4k cam escludi limite risultati",
        ),
        SettingsIndexEntry(
            title = "Codec video",
            trail = "Torrent › Risultati",
            icon = "🎞️",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.TORRENT,
            keywords = "hevc x265 h265 av1 x264 h264 formato",
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
            title = "Tema",
            trail = "Aspetto",
            icon = "🎨",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.INTERFACE,
            keywords = "colori palette aspetto rotazione acqua notturna guida tv telecomando scuro personalizzato crea modifica importa esporta tema json sottobosco tramonto ghiacciaio oled",
        ),
        SettingsIndexEntry(
            title = "Modalità Prestazioni",
            trail = "Aspetto",
            icon = "⚡",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.INTERFACE,
            keywords = "veloce leggera fluida lag scatti risparmio batteria",
            toggle = booleanPref(StreamCenterPlugin.PREF_PERFORMANCE_MODE, false),
        ),
        SettingsIndexEntry(
            title = "Modalità TV",
            trail = "Aspetto",
            icon = "📺",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.INTERFACE,
            keywords = "televisore telecomando android tv leanback firestick d-pad",
            toggle = booleanPref(StreamCenterPlugin.PREF_FORCE_TV_MODE, false),
        ),
        SettingsIndexEntry(
            title = "Animazioni",
            trail = "Aspetto › Effetti",
            icon = "🎞️",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.INTERFACE,
            keywords = "movimento transizioni motion effetti",
            toggle = booleanPref(StreamCenterPlugin.PREF_VISUAL_EFFECTS_ANIMATIONS, true),
        ),
        SettingsIndexEntry(
            title = "Sfocatura finestre",
            trail = "Aspetto › Effetti",
            icon = "🌫️",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.INTERFACE,
            keywords = "blur sfondo vetro trasparenza",
            toggle = booleanPref(StreamCenterPlugin.PREF_VISUAL_EFFECTS_BLUR, true),
        ),
        SettingsIndexEntry(
            title = "Intestazione StreamCenter",
            trail = "Aspetto › Effetti",
            icon = "✨",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.INTERFACE,
            keywords = "titolo gradiente bagliore testata header",
            toggle = booleanPref(StreamCenterPlugin.PREF_VISUAL_EFFECTS_TITLE, true),
        ),
        SettingsIndexEntry(
            title = "Sfondo animato",
            trail = "Aspetto › Effetti",
            icon = "🌌",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.INTERFACE,
            keywords = "particelle puntini stelle aurora onda sfondo universo",
            toggle = booleanPref(StreamCenterPlugin.PREF_VISUAL_EFFECTS_PARTICLES, true),
        ),
        SettingsIndexEntry(
            title = "Mostra IP pubblico",
            trail = "Aspetto › Effetti",
            icon = "🌐",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.INTERFACE,
            keywords = "indirizzo rete connessione ip",
            toggle = booleanPref(StreamCenterPlugin.PREF_VISUAL_EFFECTS_PUBLIC_IP, true),
        ),
        SettingsIndexEntry(
            title = "Sottotitoli ASS avanzati",
            trail = "App",
            icon = "💬",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.ROOT,
            keywords = "libass ssa anime karaoke font stili typesetting sub mkv",
            toggle = booleanPref(StreamCenterPlugin.PREF_LIBASS_SUBTITLES, true),
        ),
        SettingsIndexEntry(
            title = "Protezione VPN",
            trail = "App",
            icon = "🛡️",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.ROOT,
            keywords = "blocca internet privacy tunnel sicurezza killswitch",
            toggle = booleanPref(StreamCenterPlugin.PREF_REQUIRE_VPN, true),
        ),
        SettingsIndexEntry(
            title = StreamCenterLegalNotice.TITLE,
            trail = "Aiuto",
            icon = "⚖️",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.ROOT,
            keywords = "legale dmca copyright diritto d'autore responsabilità disclaimer note",
        ),
        SettingsIndexEntry(
            title = "Backup",
            trail = "Dati",
            icon = "💾",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.ROOT,
            keywords = "esporta importa salva ripristina copia file configurazione",
        ),
        SettingsIndexEntry(
            title = "Sync tra dispositivi",
            trail = "Dati",
            icon = "🔐",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.ROOT,
            keywords = "sync locale sincronizza dispositivi rete locale wifi trasferisci",
        ),
        SettingsIndexEntry(
            title = "Cache",
            trail = "Dati",
            icon = "⚡",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.CACHE,
            keywords = "memoria svuota spazio schede calendario temporanea",
        ),
        SettingsIndexEntry(
            title = "Log",
            trail = "Aiuto",
            icon = "📋",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.LOGS,
            keywords = "registro errori debug diagnostica cronologia",
        ),
        SettingsIndexEntry(
            title = "Conservazione dei log",
            trail = "Log › Registrazione",
            icon = "🧹",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.LOGS,
            keywords = "elimina vecchi giorni pulizia spazio log",
        ),
        SettingsIndexEntry(
            title = "Sessioni di log",
            trail = "Log › Sessioni",
            icon = "📄",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.LOGS,
            keywords = "archivio esporta apri elimina sessione log",
        ),
        SettingsIndexEntry(
            title = "Limiti della cache",
            trail = "Cache › Schede",
            icon = "📐",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.CACHE,
            keywords = "dimensione massima mb numero schede spazio",
        ),
        SettingsIndexEntry(
            title = "Durata della cache",
            trail = "Cache › Schede",
            icon = "🕒",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.CACHE,
            keywords = "scadenza giorni conclusi in corso",
        ),
        SettingsIndexEntry(
            title = "Calendario anime in cache",
            trail = "Cache › Calendario Home",
            icon = "📅",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.CACHE,
            keywords = "calendario giorno home svuota veloce",
        ),
        SettingsIndexEntry(
            title = "Invia feedback",
            trail = "Aiuto",
            icon = "💬",
            accent = COLOR_ACCENT,
            screen = SettingsScreenId.ROOT,
            keywords = "segnala bug telegram github contatta supporto",
        ),
        SettingsIndexEntry(
            title = "Ripristina tutto",
            trail = "Aiuto",
            icon = "♻️",
            accent = COLOR_DANGER,
            screen = SettingsScreenId.ROOT,
            keywords = "reset azzera impostazioni predefinite iniziali cancella",
        ),
    )

    private fun homeCategoryEntries(): List<SettingsIndexEntry> {
        return listOf(
            Triple("Anime", "🎌", COLOR_ACCENT) to "cartoni giapponesi sub dub",
            Triple("Serie TV", "📺", COLOR_ACCENT) to "telefilm show stagioni",
            Triple("Film", "🎬", COLOR_ACCENT) to "cinema lungometraggi",
            Triple("Live", "📡", COLOR_ACCENT) to "iptv canali diretta playlist m3u",
            Triple("Tracciamento", "🔖", COLOR_ACCENT) to "lista guardando completati anilist mal simkl",
            Triple("Cataloghi", "🗂️", COLOR_ACCENT) to "stremio catalogo esterni raccolte",
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
                accent = if (source.category == "anime") COLOR_ACCENT else COLOR_ACCENT,
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
