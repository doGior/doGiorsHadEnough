package it.dogior.hadEnough

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.iptv.StreamCenterIptv
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.remote.StreamCenterRemoteCards
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.util.StreamCenterLogger

internal data class CardProvenance(
    val defaultSource: String,
    val fieldSources: Map<String, List<String>> = emptyMap(),
    val fieldNotes: Map<String, String> = emptyMap(),
) {
    fun sources(path: String): List<String> {
        val direct = fieldSources[path]
        if (!direct.isNullOrEmpty()) return direct
        var parent = path
        while ('.' in parent) {
            parent = parent.substringBeforeLast('.')
            val inherited = fieldSources[parent]
            if (!inherited.isNullOrEmpty()) return inherited
        }
        return listOf(defaultSource)
    }

    fun note(path: String): String? {
        return fieldNotes[path] ?: fieldNotes.entries
            .firstOrNull { (key, _) -> path.startsWith("$key.") }
            ?.value
    }
}

internal fun StreamCenter.logLoadedResponse(response: LoadResponse, route: String, identityUrl: String, attachExtensions: Boolean = true): LoadResponse {
    response.url = identityUrl
    response.recommendations = response.recommendations?.let(::animeSeasonCards)
    if (attachExtensions && InstalledExtensionSources.enabledKeys(sharedPref).isNotEmpty()) response.attachExtensionPlayback()
    StreamCenterRemoteCards.remember(response)
    if (!StreamCenterLogger.isEnabled(sharedPref)) {
        pendingCardProvenance.remove(response)
        return response
    }
    val provenance = pendingCardProvenance.remove(response) ?: defaultCardProvenance(route)
    val fields = completeCardLogFields(response, provenance)
    fields["rotta_caricamento"] = sourcedCardValue(
        provenance = provenance,
        path = "rotta_caricamento",
        value = route,
        sources = listOf("StreamCenter (routing)"),
    )
    fields["catalogo_streamcenter"] = sourcedCardValue(
        provenance = provenance,
        path = "catalogo_streamcenter",
        value = catalogDefinition?.displayName,
        sources = listOf("Configurazione StreamCenter"),
    )
    fields["modalita_prestazioni"] = sourcedCardValue(
        provenance = provenance,
        path = "modalita_prestazioni",
        value = performanceMode,
        sources = listOf("Configurazione StreamCenter"),
        note = if (performanceMode) {
            "Alcuni valori possono essere null perché la modalità prestazioni evita richieste opzionali."
        } else {
            null
        },
    )
    StreamCenterLogger.logTab(
        tabName = response.name,
        action = "Scheda completa: tutti i valori finali e relativa provenienza",
        metadata = fields,
    )
    return response
}

internal fun StreamCenter.defaultCardProvenance(route: String): CardProvenance {
    val source = when {
        route == "iptv" -> "Playlist IPTV"
        route == "streamingcommunity" -> "StreamingCommunity"
        route == "anilist" || route == "anime" -> "AniList e fonti anime configurate"
        route == "myanimelist" -> "MyAnimeList (Jikan) e fonti anime configurate"
        route == "kitsu" -> "Kitsu e fonti anime configurate"
        route == "simkl" -> "Simkl e fonti configurate"
        route == "tmdb" -> "TMDB e fonti configurate"
        route == "tracciamento" -> "Servizio di tracciamento"
        route.startsWith("catalogo:") -> catalogDefinition?.displayName
            ?.let { "Catalogo $it" }
            ?: route.removePrefix("catalogo:")
        else -> "StreamCenter"
    }
    return CardProvenance(defaultSource = source)
}

internal fun StreamCenter.completeCardLogFields(
    response: LoadResponse,
    provenance: CardProvenance,
): LinkedHashMap<String, Any?> {
    val fields = linkedMapOf<String, Any?>(
        "titolo" to sourcedCardValue(provenance, "titolo", response.name),
        "url_scheda" to sourcedCardValue(provenance, "url_scheda", response.url),
        "api_visualizzata" to sourcedCardValue(
            provenance,
            "api_visualizzata",
            response.apiName,
            sources = listOf("Configurazione StreamCenter"),
        ),
        "tipo_contenuto" to sourcedCardValue(
            provenance,
            "tipo_contenuto",
            response.type.name,
            sources = provenance.sources("tipo_contenuto"),
        ),
        "poster" to sourcedCardValue(provenance, "poster", response.posterUrl),
        "sfondo" to sourcedCardValue(provenance, "sfondo", response.backgroundPosterUrl),
        "logo" to sourcedCardValue(provenance, "logo", response.logoUrl),
        "anno" to sourcedCardValue(provenance, "anno", response.year),
        "trama" to sourcedCardValue(provenance, "trama", response.plot),
        "punteggio" to sourcedCardValue(
            provenance,
            "punteggio",
            response.score?.toString(),
        ),
        "tag" to sourcedCardValue(provenance, "tag", response.tags),
        "durata_minuti" to sourcedCardValue(provenance, "durata_minuti", response.duration),
        "classificazione_contenuti" to sourcedCardValue(
            provenance,
            "classificazione_contenuti",
            response.contentRating,
        ),
        "in_arrivo" to sourcedCardValue(provenance, "in_arrivo", response.comingSoon),
        "url_univoco" to sourcedCardValue(provenance, "url_univoco", response.uniqueUrl),
        "id_sincronizzazione" to response.syncData.entries.map { (key, value) ->
            linkedMapOf(
                "servizio" to sourcedCardValue(
                    provenance,
                    "id_sincronizzazione.$key.servizio",
                    key,
                ),
                "id" to sourcedCardValue(
                    provenance,
                    "id_sincronizzazione.$key.id",
                    value,
                ),
            )
        },
        "header_poster" to response.posterHeaders.orEmpty().entries.map { (key, value) ->
            linkedMapOf(
                "nome" to sourcedCardValue(
                    provenance,
                    "header_poster.nome",
                    key,
                ),
                "valore" to sourcedCardValue(
                    provenance,
                    "header_poster.$key",
                    value,
                ),
            )
        },
        "trailer" to response.trailers.map { trailer ->
            linkedMapOf(
                "url" to sourcedCardValue(
                    provenance,
                    "trailer.url",
                    trailer.extractorUrl,
                ),
                "referer" to sourcedCardValue(
                    provenance,
                    "trailer.referer",
                    trailer.referer,
                ),
                "raw" to sourcedCardValue(
                    provenance,
                    "trailer.raw",
                    trailer.raw,
                ),
                "header" to sourcedCardValue(
                    provenance,
                    "trailer.header",
                    trailer.headers,
                ),
            )
        },
        "cast" to response.actors.orEmpty().map { actorData ->
            linkedMapOf(
                "nome" to sourcedCardValue(
                    provenance,
                    "cast.nome",
                    actorData.actor.name,
                ),
                "immagine" to sourcedCardValue(
                    provenance,
                    "cast.immagine",
                    actorData.actor.image,
                ),
                "ruolo" to sourcedCardValue(
                    provenance,
                    "cast.ruolo",
                    actorData.role?.name,
                ),
                "descrizione_ruolo" to sourcedCardValue(
                    provenance,
                    "cast.descrizione_ruolo",
                    actorData.roleString,
                ),
                "doppiatore_nome" to sourcedCardValue(
                    provenance,
                    "cast.doppiatore_nome",
                    actorData.voiceActor?.name,
                ),
                "doppiatore_immagine" to sourcedCardValue(
                    provenance,
                    "cast.doppiatore_immagine",
                    actorData.voiceActor?.image,
                ),
            )
        },
        "raccomandazioni" to response.recommendations.orEmpty().map { recommendation ->
            linkedMapOf(
                "titolo" to sourcedCardValue(
                    provenance,
                    "raccomandazioni.titolo",
                    recommendation.name,
                ),
                "url" to sourcedCardValue(
                    provenance,
                    "raccomandazioni.url",
                    recommendation.url,
                ),
                "api" to sourcedCardValue(
                    provenance,
                    "raccomandazioni.api",
                    recommendation.apiName,
                ),
                "tipo" to sourcedCardValue(
                    provenance,
                    "raccomandazioni.tipo",
                    recommendation.type?.name,
                ),
                "poster" to sourcedCardValue(
                    provenance,
                    "raccomandazioni.poster",
                    recommendation.posterUrl,
                ),
                "header_poster" to sourcedCardValue(
                    provenance,
                    "raccomandazioni.header_poster",
                    recommendation.posterHeaders,
                ),
                "id_locale" to sourcedCardValue(
                    provenance,
                    "raccomandazioni.id_locale",
                    recommendation.id,
                ),
                "qualita" to sourcedCardValue(
                    provenance,
                    "raccomandazioni.qualita",
                    recommendation.quality?.name,
                ),
                "punteggio" to sourcedCardValue(
                    provenance,
                    "raccomandazioni.punteggio",
                    recommendation.score?.toString(),
                ),
            )
        },
    )

    when (response) {
        is AnimeLoadResponse -> {
            fields["titolo_inglese"] = sourcedCardValue(
                provenance,
                "titolo_inglese",
                response.engName,
            )
            fields["titolo_originale"] = sourcedCardValue(
                provenance,
                "titolo_originale",
                response.japName,
            )
            fields["titoli_alternativi"] = sourcedCardValue(
                provenance,
                "titoli_alternativi",
                response.synonyms,
            )
            fields["stato_trasmissione"] = sourcedCardValue(
                provenance,
                "stato_trasmissione",
                response.showStatus?.name,
            )
            fields["prossimo_episodio"] = response.nextAiring?.let { next ->
                linkedMapOf(
                    "episodio" to sourcedCardValue(
                        provenance,
                        "prossimo_episodio.episodio",
                        next.episode,
                    ),
                    "stagione" to sourcedCardValue(
                        provenance,
                        "prossimo_episodio.stagione",
                        next.season,
                    ),
                    "timestamp_unix" to sourcedCardValue(
                        provenance,
                        "prossimo_episodio.timestamp_unix",
                        next.unixTime,
                    ),
                )
            } ?: sourcedCardValue(provenance, "prossimo_episodio", null)
            fields["stagioni"] = loggedSeasons(response.seasonNames.orEmpty(), provenance)
            fields["episodi"] = response.episodes.entries.flatMap { (dubStatus, episodes) ->
                episodes.mapIndexed { index, episode ->
                    loggedEpisode(
                        episode = episode,
                        provenance = provenance,
                        index = index,
                        audio = dubStatus.name,
                    )
                }
            }
        }

        is TvSeriesLoadResponse -> {
            fields["stato_trasmissione"] = sourcedCardValue(
                provenance,
                "stato_trasmissione",
                response.showStatus?.name,
            )
            fields["prossimo_episodio"] = response.nextAiring?.let { next ->
                linkedMapOf(
                    "episodio" to sourcedCardValue(
                        provenance,
                        "prossimo_episodio.episodio",
                        next.episode,
                    ),
                    "stagione" to sourcedCardValue(
                        provenance,
                        "prossimo_episodio.stagione",
                        next.season,
                    ),
                    "timestamp_unix" to sourcedCardValue(
                        provenance,
                        "prossimo_episodio.timestamp_unix",
                        next.unixTime,
                    ),
                )
            } ?: sourcedCardValue(provenance, "prossimo_episodio", null)
            fields["stagioni"] = loggedSeasons(response.seasonNames.orEmpty(), provenance)
            fields["episodi"] = response.episodes.mapIndexed { index, episode ->
                loggedEpisode(episode, provenance, index)
            }
        }

        is MovieLoadResponse -> {
            fields["dati_riproduzione"] = sourcedCardValue(
                provenance,
                "dati_riproduzione",
                response.dataUrl,
            )
        }

        is LiveStreamLoadResponse -> {
            fields["dati_riproduzione"] = sourcedCardValue(
                provenance,
                "dati_riproduzione",
                response.dataUrl,
            )
        }
    }
    return fields
}

internal fun StreamCenter.loggedSeasons(
    seasons: List<SeasonData>,
    provenance: CardProvenance,
): List<Map<String, Any?>> {
    return seasons.map { season ->
        linkedMapOf(
            "stagione" to sourcedCardValue(
                provenance,
                "stagioni.stagione",
                season.season,
            ),
            "nome" to sourcedCardValue(
                provenance,
                "stagioni.nome",
                season.name,
            ),
            "stagione_visualizzata" to sourcedCardValue(
                provenance,
                "stagioni.stagione_visualizzata",
                season.displaySeason,
            ),
        )
    }
}

internal fun StreamCenter.loggedEpisode(
    episode: Episode,
    provenance: CardProvenance,
    index: Int,
    audio: String? = null,
): Map<String, Any?> {
    return linkedMapOf(
        "indice_lista" to sourcedCardValue(
            provenance,
            "episodi.indice_lista",
            index,
            sources = listOf("StreamCenter (ordinamento finale)"),
        ),
        "audio" to sourcedCardValue(
            provenance,
            "episodi.audio",
            audio,
            sources = listOf("StreamCenter (raggruppamento audio)"),
        ),
        "nome" to sourcedCardValue(provenance, "episodi.nome", episode.name),
        "stagione" to sourcedCardValue(provenance, "episodi.stagione", episode.season),
        "episodio" to sourcedCardValue(provenance, "episodi.episodio", episode.episode),
        "poster" to sourcedCardValue(provenance, "episodi.poster", episode.posterUrl),
        "punteggio" to sourcedCardValue(
            provenance,
            "episodi.punteggio",
            episode.score?.toString(),
        ),
        "descrizione" to sourcedCardValue(
            provenance,
            "episodi.descrizione",
            episode.description,
        ),
        "data_timestamp" to sourcedCardValue(provenance, "episodi.data", episode.date),
        "durata_minuti" to sourcedCardValue(
            provenance,
            "episodi.durata_minuti",
            episode.runTime,
        ),
        "dati_riproduzione" to sourcedCardValue(
            provenance,
            "episodi.dati_riproduzione",
            episode.data,
        ),
    )
}

internal fun StreamCenter.sourcedCardValue(
    provenance: CardProvenance,
    path: String,
    value: Any?,
    sources: List<String> = provenance.sources(path),
    note: String? = provenance.note(path),
): StreamCenterLogger.SourcedValue {
    return StreamCenterLogger.SourcedValue(
        value = value,
        sources = sources,
        note = note,
    )
}

internal fun StreamCenter.animeProviderCardSources(
    metadataSource: String,
    playbackSources: List<String>,
    episodeMetadataSources: List<String> = listOf(metadataSource),
    trackingSources: List<String> = listOf(metadataSource),
    torrentContext: StreamCenterTorrentPlaybackContext? = null,
): Map<String, List<String>> {
    val finalPlaybackSources = (
        playbackSources +
            "Add-on Stremio abilitati" +
            torrentPlaybackProvenance(torrentContext) +
            "StreamCenter (payload di riproduzione)"
        ).distinct()
    val episodeSources = (episodeMetadataSources + finalPlaybackSources).distinct()
    return mapOf(
        "titolo" to listOf(metadataSource),
        "titolo_inglese" to listOf(metadataSource),
        "titolo_originale" to listOf(metadataSource),
        "titoli_alternativi" to listOf(metadataSource),
        "poster" to listOf(metadataSource),
        "sfondo" to listOf(metadataSource),
        "trama" to listOf(metadataSource),
        "tag" to listOf(metadataSource, "StreamCenter (etichette derivate)"),
        "anno" to listOf(metadataSource),
        "punteggio" to listOf(metadataSource),
        "durata_minuti" to listOf(metadataSource),
        "classificazione_contenuti" to listOf(metadataSource),
        "cast" to listOf(metadataSource),
        "raccomandazioni" to listOf(metadataSource),
        "trailer" to listOf(metadataSource),
        "stato_trasmissione" to listOf(metadataSource),
        "prossimo_episodio" to listOf(metadataSource),
        "in_arrivo" to listOf(metadataSource),
        "id_sincronizzazione" to trackingSources,
        "stagioni" to listOf(metadataSource, "StreamCenter (normalizzazione stagioni)"),
        "episodi" to episodeSources,
        "episodi.nome" to episodeMetadataSources + "StreamCenter (fallback nome)",
        "episodi.poster" to episodeMetadataSources + "$metadataSource (poster scheda fallback)",
        "episodi.punteggio" to episodeMetadataSources,
        "episodi.descrizione" to episodeMetadataSources,
        "episodi.data" to episodeMetadataSources,
        "episodi.durata_minuti" to episodeMetadataSources,
        "episodi.stagione" to episodeMetadataSources + "StreamCenter (normalizzazione)",
        "episodi.episodio" to episodeSources,
        "episodi.dati_riproduzione" to finalPlaybackSources,
        "dati_riproduzione" to finalPlaybackSources,
    )
}

internal fun StreamCenter.logSearchCards(
    action: String,
    contextDetails: Map<String, Any?>,
    items: List<SearchResponse>,
) {
    if (!StreamCenterLogger.isEnabled(sharedPref)) return
    StreamCenterLogger.logMenu(
        action = action,
        metadata = linkedMapOf<String, Any?>().apply {
            contextDetails.forEach { (key, value) ->
                put(
                    key,
                    StreamCenterLogger.SourcedValue(
                        value = value,
                        sources = listOf("StreamCenter"),
                    ),
                )
            }
            put(
                "numero_schede",
                StreamCenterLogger.SourcedValue(
                    value = items.size,
                    sources = listOf("StreamCenter (conteggio finale)"),
                ),
            )
            put(
                "schede_complete",
                StreamCenterLogger.SourcedValue(
                    value = items.mapIndexed(this@logSearchCards::completeSearchCardLogFields),
                    sources = listOf("Fonti indicate accanto a ogni campo"),
                    note = "Nessun elemento o valore della lista è troncato.",
                ),
            )
        },
    )
}

internal fun StreamCenter.completeSearchCardLogFields(
    index: Int,
    response: SearchResponse,
): Map<String, Any?> {
    val source = searchCardSource(response)
    fun sourced(value: Any?, vararg overrideSources: String): StreamCenterLogger.SourcedValue {
        return StreamCenterLogger.SourcedValue(
            value = value,
            sources = overrideSources.toList().takeIf { it.isNotEmpty() } ?: source,
        )
    }

    return linkedMapOf<String, Any?>(
        "indice" to sourced(index, "StreamCenter (ordinamento finale)"),
        "titolo" to sourced(response.name),
        "url" to sourced(response.url),
        "api" to sourced(response.apiName, "Configurazione StreamCenter"),
        "tipo" to sourced(
            response.type?.name,
            *(source + "StreamCenter (conversione tipo)").toTypedArray(),
        ),
        "poster" to sourced(response.posterUrl),
        "header_poster" to sourced(response.posterHeaders),
        "id_locale" to sourced(response.id, "CloudStream / StreamCenter"),
        "qualita" to sourced(response.quality?.name),
        "punteggio" to sourced(response.score?.toString()),
    ).apply {
        when (response) {
            is AnimeSearchResponse -> {
                put("anno", sourced(response.year))
                put("stato_doppiaggio", sourced(response.dubStatus?.map { it.name }))
                put("altro_titolo", sourced(response.otherName))
                put(
                    "episodi_disponibili",
                    sourced(
                        response.episodes.entries.associate { (status, count) ->
                            status.name to count
                        },
                    ),
                )
            }

            is MovieSearchResponse -> put("anno", sourced(response.year))
            is TvSeriesSearchResponse -> {
                put("anno", sourced(response.year))
                put("episodi_disponibili", sourced(response.episodes))
            }

            is LiveSearchResponse -> put("lingua", sourced(response.lang))
        }
    }
}

internal fun StreamCenter.searchCardSource(response: SearchResponse): List<String> {
    val url = response.url
    return when {
        catalogDefinition?.stremioAddon != null -> listOf(
            "Add-on Stremio: ${catalogDefinition.stremioAddon.name}",
        )
        catalogDefinition != null -> listOf("Catalogo ${catalogDefinition.displayName}")
        url.startsWith(StreamCenterIptv.ROUTE_PREFIX) -> listOf("Playlist IPTV")
        url.contains(trackingHomePath) -> listOf("Servizio di tracciamento")
        url.contains(scHomePath) -> listOf("StreamingCommunity")
        url.contains(animeMarker) || isAnilistOnlyUrl(url) || isMalOnlyUrl(url) ->
            listOf("AnimeUnity", "AniList")
        url.contains("anilist.co", ignoreCase = true) -> listOf("AniList")
        url.contains("myanimelist.net", ignoreCase = true) -> listOf("MyAnimeList (Jikan)")
        url.contains("kitsu.", ignoreCase = true) -> listOf("Kitsu")
        url.contains("simkl.com", ignoreCase = true) -> listOf("Simkl")
        url.contains("themoviedb.org", ignoreCase = true) -> listOf("TMDB")
        else -> listOf("StreamCenter (fonte non identificata dalla rotta)")
    }
}

internal fun StreamCenter.animeCardProvenance(
    playbackSources: List<String>,
    trackingSources: List<String>,
    torrentContext: StreamCenterTorrentPlaybackContext?,
): Map<String, List<String>> {
    return animeProviderCardSources(
        metadataSource = "TMDB",
        playbackSources = playbackSources,
        episodeMetadataSources = listOf("TMDB"),
        trackingSources = trackingSources,
        torrentContext = torrentContext,
    ) + mapOf(
        "raccomandazioni" to listOf("AnimeUnity"),
        "anno" to listOf("AniList"),
        "tag" to listOf("TMDB", "AniList", "StreamCenter (etichette derivate)"),
    )
}
