package it.dogior.hadEnough

import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.ui.SyncWatchType
import it.dogior.hadEnough.StreamCenter.Companion.MINIMAL_LOAD_RESPONSE_TITLE
import it.dogior.hadEnough.StreamCenter.Companion.TRACKING_PROVIDER_PAGE_SIZE
import it.dogior.hadEnough.catalog.*
import it.dogior.hadEnough.extensions.*
import it.dogior.hadEnough.extractor.*
import it.dogior.hadEnough.model.*
import it.dogior.hadEnough.stremio.*
import it.dogior.hadEnough.torrent.*
import it.dogior.hadEnough.tracking.*
import it.dogior.hadEnough.tracking.StreamCenterSimklTracking
import it.dogior.hadEnough.util.StreamCenterHostGate
import it.dogior.hadEnough.util.StreamCenterLogger
import it.dogior.hadEnough.util.optNullableString
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import org.json.JSONObject

internal suspend fun StreamCenter.resolveKitsuLibraryMediaId(id: Int): Int {
    val account = AccountManager.syncApis
        .firstOrNull { it.syncIdName == SyncIdName.Kitsu }
        ?.authData()
        ?: return id
    val root = runCatching {
        JSONObject(
            StreamCenterHostGate.request("https://kitsu.io/api/edge/library-entries/$id?include=anime") {
                app.get(
                    "https://kitsu.io/api/edge/library-entries/$id?include=anime",
                    headers = mapOf("Authorization" to "Bearer ${account.token.accessToken}"),
                    cacheTime = 0,
                )
            }.text,
        )
    }.getOrNull() ?: return id
    return root.optJSONObject("data")
        ?.optJSONObject("relationships")
        ?.optJSONObject("anime")
        ?.optJSONObject("data")
        ?.optNullableString("id")
        ?.toIntOrNull()
        ?: root.optJSONArray("included")
            ?.optJSONObject(0)
            ?.optNullableString("id")
            ?.toIntOrNull()
        ?: id
}

internal suspend fun StreamCenter.resolveAniListTrackingUrl(name: SyncIdName, id: Int): String? {
    if (name == SyncIdName.Anilist) return "https://anilist.co/anime/$id"
    val ids = resolveAnimeTrackingIds(name, id) ?: return null
    val anilistId = ids.anilist ?: ids.mal?.let { malId ->
        aniListMetadataClient.fetchMetadata(null, malId, forceFullMetadata = true)?.anilistId
    }
    return anilistId?.let { "https://anilist.co/anime/$it" }
}

internal suspend fun StreamCenter.resolveMyAnimeListTrackingUrl(name: SyncIdName, id: Int): String? {
    if (name == SyncIdName.MyAnimeList) return "https://myanimelist.net/anime/$id"
    val ids = resolveAnimeTrackingIds(name, id) ?: return null
    val malId = ids.mal ?: ids.anilist?.let { anilistId ->
        aniListMetadataClient.fetchMetadata(anilistId, null, forceFullMetadata = true)?.malId
    }
    return malId?.let { "https://myanimelist.net/anime/$it" }
}

internal suspend fun StreamCenter.resolveKitsuTrackingUrl(name: SyncIdName, id: Int): String? {
    if (name == SyncIdName.Kitsu) return "https://kitsu.io/anime/$id"
    val ids = resolveAnimeTrackingIds(name, id) ?: return null
    val kitsuId = ids.kitsu ?: kitsuMetadataClient.resolveAnimeId(ids.mal, ids.anilist)
    return kitsuId?.let { "https://kitsu.io/anime/$it" }
}

internal suspend fun StreamCenter.resolveSimklTrackingUrl(
    name: SyncIdName,
    id: Int,
    allowedCategories: Set<String> = setOf("movies", "tv", "anime"),
): String? {
    return when (name) {
        SyncIdName.Simkl -> simklCatalog.resolveMediaUrl(
            simkl = id,
            allowedCategories = allowedCategories,
        )
        SyncIdName.Anilist -> simklCatalog.resolveMediaUrl(
            anilist = id,
            allowedCategories = allowedCategories,
        )
        SyncIdName.MyAnimeList -> simklCatalog.resolveMediaUrl(
            mal = id,
            allowedCategories = allowedCategories,
        )
        SyncIdName.Kitsu -> {
            val ids = resolveAnimeTrackingIds(name, id) ?: return null
            simklCatalog.resolveMediaUrl(
                mal = ids.mal,
                anilist = ids.anilist,
                allowedCategories = allowedCategories,
            )
        }
        else -> null
    }
}

internal suspend fun StreamCenter.resolveSimklId(
    imdb: String? = null,
    tmdb: String? = null,
    mal: Int? = null,
    anilist: Int? = null,
    allowedCategories: Set<String>,
): Int? = runCatching {
    simklCatalog.resolveMediaUrl(
        imdb = imdb,
        tmdb = tmdb,
        mal = mal,
        anilist = anilist,
        allowedCategories = allowedCategories,
    )?.let(simklCatalog::mediaRoute)?.second
}.getOrNull()

internal suspend fun StreamCenter.resolveTmdbTrackingUrl(
    name: SyncIdName,
    id: Int,
    allowedCategories: Set<String>,
): String? {
    val simklUrl = resolveSimklTrackingUrl(name, id, allowedCategories) ?: return null
    val media = runCatching { simklCatalog.media(simklUrl) }.getOrNull() ?: return null
    val tmdbId = media.ids.tmdb ?: return null
    val type = if (media.category == "movies") "movie" else "tv"
    return "https://www.themoviedb.org/$type/$tmdbId"
}

internal suspend fun StreamCenter.resolveAnimeTrackingIds(name: SyncIdName, id: Int): StreamCenterTrackingIds? {
    return when (name) {
        SyncIdName.Anilist -> aniListMetadataClient.fetchMetadata(
            anilistId = id,
            malId = null,
            forceFullMetadata = true,
        )?.let { metadata ->
            StreamCenterTrackingIds(anilist = metadata.anilistId, mal = metadata.malId)
        } ?: StreamCenterTrackingIds(anilist = id)
        SyncIdName.MyAnimeList -> aniListMetadataClient.fetchMetadata(
            anilistId = null,
            malId = id,
            forceFullMetadata = true,
        )?.let { metadata ->
            StreamCenterTrackingIds(anilist = metadata.anilistId, mal = metadata.malId)
        } ?: StreamCenterTrackingIds(mal = id)
        SyncIdName.Kitsu -> runCatching {
            kitsuCatalog.media("https://kitsu.io/anime/$id")
        }.getOrNull()?.let { media ->
            StreamCenterTrackingIds(
                anilist = media.anilistId,
                mal = media.malId,
                kitsu = media.id,
            )
        }
        SyncIdName.Simkl -> resolveSimklTrackingUrl(name, id, setOf("anime"))
            ?.let { url -> runCatching { simklCatalog.media(url) }.getOrNull() }
            ?.let { media ->
                StreamCenterTrackingIds(
                    anilist = media.ids.anilist,
                    mal = media.ids.mal,
                    kitsu = media.ids.kitsu,
                    simkl = media.ids.simkl,
                )
            }
        else -> null
    }
}

internal fun StreamCenter.trackingRepo(service: StreamCenterTrackingService) =
    AccountManager.syncApis.firstOrNull { it.syncIdName == service.syncIdName }

internal fun StreamCenter.catalogTrackingService(
    section: StreamCenterCatalogSection,
): StreamCenterTrackingService? {
    val serviceKey = section.trackingServiceKey ?: return null
    return StreamCenterPlugin.trackingServices.firstOrNull { it.key == serviceKey }
}

internal fun StreamCenter.catalogTrackingConfig(
    section: StreamCenterCatalogSection,
): StreamCenterTrackingListConfig? {
    val service = catalogTrackingService(section) ?: return null
    val listKey = section.trackingListKey ?: return null
    val status = service.statuses.firstOrNull { it.key == listKey } ?: return null
    return StreamCenterTrackingListConfig(
        service,
        status.copy(title = section.trackingMediaCategory.statusTitle(status.key, status.title)),
        section.trackingMediaCategory,
    )
}

internal fun StreamCenter.isCatalogSectionAvailable(section: StreamCenterCatalogSection): Boolean {
    if (section.trackingServiceKey == null) return true
    return catalogTrackingService(section)?.let { trackingServiceIsConnected(it.syncIdName) } == true
}

internal fun StreamCenter.trackingServiceIsConnected(name: SyncIdName): Boolean {
    return AccountManager.syncApis.firstOrNull { it.syncIdName == name }?.authData() != null
}

internal fun StreamCenter.trackingRepo(config: StreamCenterTrackingListConfig) = trackingRepo(config.service)

internal suspend fun StreamCenter.allTrackingLibraryItems(
    service: StreamCenterTrackingService,
): List<SyncAPI.LibraryItem> {
    val repo = AccountManager.syncApis.firstOrNull { it.syncIdName == service.syncIdName }
        ?: return emptyList()
    val account = repo.authData() ?: return emptyList()
    return repo.api.library(account)?.allLibraryLists?.flatMap { it.items }.orEmpty()
}

internal suspend fun StreamCenter.trackingServiceDetails(
    service: StreamCenterTrackingService,
    syncId: String,
): SyncAPI.SyncResult? {
    val repo = trackingRepo(service) ?: return null
    val account = repo.authData() ?: return null
    return runCatching { repo.api.load(account, syncId) }.getOrNull()
}

internal fun StreamCenter.trackingMediaId(
    service: StreamCenterTrackingService,
    item: SyncAPI.LibraryItem,
): String {
    return if (service.syncIdName == SyncIdName.Kitsu) {
        kitsuCatalog.mediaId(item.url)?.toString() ?: item.syncId
    } else {
        item.syncId
    }
}

internal data class TrackingPersonalMetadata(
    val status: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
)

internal fun StreamCenter.trackingStatusLabel(status: SyncWatchType?): String? = when (status) {
    SyncWatchType.WATCHING -> "Guardando"
    SyncWatchType.COMPLETED -> "Completato"
    SyncWatchType.ONHOLD -> "In pausa"
    SyncWatchType.DROPPED -> "Interrotto"
    SyncWatchType.PLANTOWATCH -> "Da guardare"
    SyncWatchType.REWATCHING -> "Riguardando"
    else -> null
}

internal fun StreamCenter.trackingStatusLabel(status: String?): String? = when (status?.trim()?.uppercase(Locale.ROOT)) {
    "CURRENT", "WATCHING", "WATCHED" -> "Guardando"
    "COMPLETED", "COMPLETE" -> "Completato"
    "PAUSED", "ON_HOLD", "ONHOLD", "HOLD" -> "In pausa"
    "DROPPED" -> "Interrotto"
    "PLANNING", "PLAN_TO_WATCH", "PLANTOWATCH", "PLANNED" -> "Da guardare"
    "REPEATING", "REWATCHING" -> "Riguardando"
    else -> null
}

internal fun JSONObject.optTrackingText(key: String): String? = optString(key)
    .trim()
    .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

internal fun StreamCenter.formatTrackingDate(value: String?): String? {
    val match = value?.trim()?.let {
        Regex("""^(\\d{4})-(\\d{1,2})-(\\d{1,2})""").find(it)
    } ?: return null
    return "${match.groupValues[3].padStart(2, '0')}/${match.groupValues[2].padStart(2, '0')}/${match.groupValues[1]}"
}

internal fun StreamCenter.formatTrackingDate(value: JSONObject?): String? {
    val year = value?.optInt("year") ?: 0
    val month = value?.optInt("month") ?: 0
    val day = value?.optInt("day") ?: 0
    return when {
        year <= 0 -> null
        month <= 0 -> year.toString()
        day <= 0 -> "${month.toString().padStart(2, '0')}/$year"
        else -> "${day.toString().padStart(2, '0')}/${month.toString().padStart(2, '0')}/$year"
    }
}

internal suspend fun StreamCenter.myAnimeListPersonalMetadata(
    account: AuthData,
    syncId: String,
): TrackingPersonalMetadata? {
    val body = StreamCenterHostGate.request("https://api.myanimelist.net/v2/anime/$syncId") {
        app.get(
            "https://api.myanimelist.net/v2/anime/$syncId",
            params = mapOf("fields" to "my_list_status{status,start_date,finish_date}"),
            headers = mapOf("Authorization" to "Bearer ${account.token.accessToken}"),
            cacheTime = 0,
        )
    }.text
    val status = JSONObject(body).optJSONObject("my_list_status") ?: return null
    return TrackingPersonalMetadata(
        status = trackingStatusLabel(status.optTrackingText("status")),
        startedAt = formatTrackingDate(status.optTrackingText("start_date")),
        finishedAt = formatTrackingDate(status.optTrackingText("finish_date")),
    )
}

internal suspend fun StreamCenter.aniListPersonalMetadata(
    account: AuthData,
    syncId: String,
): TrackingPersonalMetadata? {
    val mediaId = syncId.toIntOrNull() ?: return null
    val query = """
        query {
            MediaList(mediaId: $mediaId, userId: ${account.user.id}) {
                status
                startedAt { year month day }
                completedAt { year month day }
            }
        }
    """.trimIndent()
    val body = StreamCenterHostGate.request("https://graphql.anilist.co/") {
        app.post(
            "https://graphql.anilist.co/",
            headers = mapOf(
                "Authorization" to "Bearer ${account.token.accessToken}",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
                "Origin" to "https://anilist.co",
                "Referer" to "https://anilist.co/",
            ),
            data = mapOf("query" to URLEncoder.encode(query, StandardCharsets.UTF_8.name())),
            cacheTime = 0,
            timeout = 5,
        )
    }.text.replace("\\/", "/")
    val status = JSONObject(body).optJSONObject("data")?.optJSONObject("MediaList") ?: return null
    return TrackingPersonalMetadata(
        status = trackingStatusLabel(status.optTrackingText("status")),
        startedAt = formatTrackingDate(status.optJSONObject("startedAt")),
        finishedAt = formatTrackingDate(status.optJSONObject("completedAt")),
    )
}

internal suspend fun StreamCenter.kitsuPersonalMetadata(
    account: AuthData,
    syncId: String,
): TrackingPersonalMetadata? {
    val body = StreamCenterHostGate.request("https://kitsu.io/api/edge/library-entries/$syncId") {
        app.get(
            "https://kitsu.io/api/edge/library-entries/$syncId",
            headers = mapOf("Authorization" to "Bearer ${account.token.accessToken}"),
            cacheTime = 0,
        )
    }.text
    val attributes = JSONObject(body).optJSONObject("data")?.optJSONObject("attributes") ?: return null
    return TrackingPersonalMetadata(
        status = trackingStatusLabel(attributes.optTrackingText("status")),
        startedAt = formatTrackingDate(
            attributes.optTrackingText("startedAt") ?: attributes.optTrackingText("started_at"),
        ),
        finishedAt = formatTrackingDate(
            attributes.optTrackingText("finishedAt") ?: attributes.optTrackingText("finished_at"),
        ),
    )
}

internal suspend fun StreamCenter.trackingPersonalMetadata(
    service: StreamCenterTrackingService,
    libraryId: String,
    mediaId: String,
): TrackingPersonalMetadata {
    val repo = trackingRepo(service) ?: return TrackingPersonalMetadata()
    val account = repo.authData() ?: return TrackingPersonalMetadata()
    val fallbackStatus = runCatching {
        trackingStatusLabel(repo.api.status(account, mediaId)?.status)
    }.getOrNull()
    val serviceMetadata = runCatching {
        when (service.key) {
            "myanimelist" -> myAnimeListPersonalMetadata(account, mediaId)
            "anilist" -> aniListPersonalMetadata(account, mediaId)
            "kitsu" -> kitsuPersonalMetadata(account, libraryId)
            else -> null
        }
    }.getOrNull()
    return serviceMetadata?.copy(status = serviceMetadata.status ?: fallbackStatus)
        ?: TrackingPersonalMetadata(status = fallbackStatus)
}

internal suspend fun StreamCenter.trackingLibraryItems(
    config: StreamCenterTrackingListConfig,
): List<SyncAPI.LibraryItem> {
    if (config.service.key == "simkl" && !config.mediaCategory.supportsStatus(config.status.key)) {
        return emptyList()
    }
    val context = StreamCenterPlugin.activeContext ?: return emptyList()
    val requestedListName = context.getString(config.status.watchType.stringRes)
    val repo = trackingRepo(config) ?: return emptyList()
    val account = repo.authData() ?: return emptyList()
    val items = repo.api.library(account)?.allLibraryLists
        ?.firstOrNull { it.name.asString(context) == requestedListName }?.items.orEmpty()
    return if (config.service.key == "simkl") {
        StreamCenterSimklTracking.libraryItems(account, items, config.mediaCategory)
    } else {
        items
    }
}

internal suspend fun StreamCenter.fetchTrackingListHome(
    config: StreamCenterTrackingListConfig,
    limit: Int,
): List<SearchResponse> {
    return trackingLibraryItems(config)
        .take(limit)
        .map { item -> trackingSearchResponse(config.service, item) }
}

internal suspend fun StreamCenter.fetchTrackingListHomePage(
    config: StreamCenterTrackingListConfig,
    page: Int,
): StreamCenterCatalogPage {
    if (page < 1 || !trackingServiceIsConnected(config.service.syncIdName)) {
        return StreamCenterCatalogPage(emptyList(), false)
    }
    val library = trackingLibraryItems(config).distinctBy(SyncAPI.LibraryItem::syncId)
    val offset = (page - 1) * TRACKING_PROVIDER_PAGE_SIZE
    val pageItems = library.drop(offset).take(TRACKING_PROVIDER_PAGE_SIZE)
    return StreamCenterCatalogPage(
        items = pageItems.map { item -> trackingSearchResponse(config.service, item) },
        hasNext = offset + pageItems.size < library.size,
    )
}

internal fun StreamCenter.trackingSearchResponse(
    service: StreamCenterTrackingService,
    item: SyncAPI.LibraryItem,
): SearchResponse {
    val route = "$mainUrl$trackingHomePath${service.key}/${URLEncoder.encode(item.syncId, StandardCharsets.UTF_8.name())}"
    val score = item.score ?: item.personalRating
    val itemType = item.type
    return when (itemType) {
        TvType.TvSeries -> newTvSeriesSearchResponse(item.name, route, TvType.TvSeries) {
            posterUrl = item.posterUrl
            score?.let { this.score = it }
        }
        TvType.Anime, TvType.AnimeMovie, TvType.OVA -> newAnimeSearchResponse(item.name, route, itemType) {
            posterUrl = item.posterUrl
            score?.let { this.score = it }
        }
        else -> newMovieSearchResponse(item.name, route, TvType.Movie) {
            posterUrl = item.posterUrl
            score?.let { this.score = it }
        }
    }
}

internal fun StreamCenter.applyManualTrackingIds(response: LoadResponse, trackingKey: String) {
    runCatching {
        StreamCenterTrackingOverrides.applyOverride(
            response = response,
            override = StreamCenterTrackingOverrides.override(sharedPref, trackingKey),
            showAsTags = catalogDefinition == null && StreamCenterPlugin.shouldShowTrackingIds(sharedPref),
            visibleServices = StreamCenterPlugin.visibleTrackingIdServices(sharedPref),
        )
    }.onFailure { error ->
        StreamCenterLogger.logTabError(
            tabName = response.name,
            action = "Applicazione ID di tracciamento manuali non riuscita",
            throwable = error,
        )
    }
}

internal fun StreamCenter.rememberTrackingIds(
    trackingKey: String,
    response: LoadResponse,
    detected: Map<ManualTrackingService, String>,
    groupedSeasons: Boolean,
) {
    if (response.name == MINIMAL_LOAD_RESPONSE_TITLE) return
    runCatching {
        StreamCenterTrackingOverrides.remember(trackingKey, name, response, detected, groupedSeasons)
    }
}

internal suspend fun StreamCenter.loadTrackingLibraryItem(url: String): LoadResponse {
    val route = url.substringAfter(trackingHomePath).trimStart('/')
    val serviceKey = route.substringBefore('/').takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Lista di tracciamento non valida")
    val syncId = route.substringAfter('/', "")
        .takeIf { it.isNotBlank() }
        ?.let { URLDecoder.decode(it.substringBefore('?'), StandardCharsets.UTF_8.name()) }
        ?: throw IllegalArgumentException("Elemento della lista non valido")
    val service = StreamCenterPlugin.trackingServices.firstOrNull { it.key == serviceKey }
        ?: throw IllegalArgumentException("Servizio di tracciamento non supportato")
    val item = allTrackingLibraryItems(service).firstOrNull { it.syncId == syncId }
        ?: throw IllegalStateException("Elemento non più presente nella lista di ${service.title}")

    providerTrackingRoute(service, item)?.let { providerUrl ->
        return when (service.key) {
            "anilist" -> loadAniListCatalogMedia(providerUrl)
            "myanimelist" -> loadMyAnimeListMedia(providerUrl)
            "kitsu" -> loadKitsuMedia(providerUrl)
            "simkl" -> loadSimklMedia(providerUrl)
            else -> error("Servizio di tracciamento non supportato")
        }
    }

    val mediaId = trackingMediaId(service, item)
    val details = trackingServiceDetails(service, mediaId)
    val personal = trackingPersonalMetadata(service, item.syncId, mediaId)
    return createTrackingLoadResponse(url, service, item, mediaId, details, personal)
}

internal suspend fun StreamCenter.providerTrackingRoute(
    service: StreamCenterTrackingService,
    item: SyncAPI.LibraryItem,
): String? {
    return when (service.key) {
        "anilist" -> (aniListCatalog.mediaId(item.url) ?: item.syncId.toIntOrNull())
            ?.let { "https://anilist.co/anime/$it" }
        "myanimelist" -> (myAnimeListCatalog.mediaId(item.url) ?: item.syncId.toIntOrNull())
            ?.let { "https://myanimelist.net/anime/$it" }
        "kitsu" -> trackingMediaId(service, item).toIntOrNull()
            ?.let { "https://kitsu.io/anime/$it" }
        "simkl" -> resolveSimklLibraryRoute(item)
        else -> null
    }
}

internal suspend fun StreamCenter.resolveSimklLibraryRoute(item: SyncAPI.LibraryItem): String? {
    val parsedRoute = simklCatalog.mediaRoute(item.url)
    val simklId = parsedRoute?.second
        ?: item.syncId.substringBefore('/').trim().toIntOrNull()
        ?: return null
    val canonicalRoute = runCatching {
        simklCatalog.resolveMediaUrl(simkl = simklId)
    }.getOrNull()
    if (canonicalRoute != null) return canonicalRoute

    return parsedRoute?.let { (category, id) ->
        "https://simkl.com/$category/$id"
    } ?: run {
        val category = when (item.type) {
            TvType.Movie -> "movies"
            TvType.Anime, TvType.AnimeMovie, TvType.OVA -> "anime"
            else -> "tv"
        }
        "https://simkl.com/$category/$simklId"
    }
}

internal data class TrackingCardMetadata(
    val title: String,
    val poster: String?,
    val background: String?,
    val plot: String?,
    val tags: List<String>,
    val year: Int?,
    val score: Score?,
    val showStatus: ShowStatus?,
    val nextAiring: NextAiring?,
    val actors: List<ActorData>,
    val duration: Int?,
    val synonyms: List<String>,
)

internal fun StreamCenter.trackingCardMetadata(
    item: SyncAPI.LibraryItem,
    details: SyncAPI.SyncResult?,
    personal: TrackingPersonalMetadata,
): TrackingCardMetadata {
    val title = details?.title?.takeIf(String::isNotBlank) ?: item.name
    val personalRating = item.personalRating?.takeIf { it.toDouble() > 0 }
    val progress = when {
        item.episodesCompleted != null && item.episodesTotal != null ->
            "Progresso: ${item.episodesCompleted}/${item.episodesTotal}"
        item.episodesCompleted != null -> "Progresso: ${item.episodesCompleted} episodi"
        else -> null
    }
    val serviceTags = details?.genres?.filterIsInstance<String>().orEmpty()
    val libraryTags = item.tags?.filterIsInstance<String>().orEmpty()
    val tags = (serviceTags.ifEmpty { libraryTags } +
        listOfNotNull(
            progress,
            personal.status?.let { "Stato: $it" },
            personal.startedAt?.let { "Data inizio: $it" },
            personal.finishedAt?.let { "Data fine: $it" },
            personalRating?.let { "Personale: $it/10" },
        ))
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
    val year = item.releaseDate?.let { date ->
        Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
    } ?: details?.startDate?.let { timestamp ->
        val milliseconds = if (timestamp < 10_000_000_000L) timestamp * 1_000L else timestamp
        Calendar.getInstance().apply { timeInMillis = milliseconds }.get(Calendar.YEAR)
    }
    return TrackingCardMetadata(
        title = title,
        poster = details?.posterUrl ?: item.posterUrl,
        background = details?.backgroundPosterUrl ?: item.posterUrl,
        plot = details?.synopsis?.takeIf(String::isNotBlank) ?: item.plot,
        tags = tags,
        year = year,
        score = details?.publicScore ?: item.score,
        showStatus = details?.airStatus,
        nextAiring = details?.nextAiring,
        actors = details?.actors.orEmpty(),
        duration = details?.duration,
        synonyms = details?.synonyms.orEmpty(),
    )
}

internal suspend fun StreamCenter.createTrackingLoadResponse(
    route: String,
    service: StreamCenterTrackingService,
    item: SyncAPI.LibraryItem,
    mediaId: String,
    details: SyncAPI.SyncResult?,
    personal: TrackingPersonalMetadata,
): LoadResponse {
    val metadata = trackingCardMetadata(item, details, personal)
    val applySharedMetadata: suspend LoadResponse.() -> Unit = {
        apiName = this@createTrackingLoadResponse.name
        if (!performanceMode) {
            posterUrl = metadata.poster
            backgroundPosterUrl = metadata.background
            plot = metadata.plot
            tags = metadata.tags
            year = metadata.year
            duration = metadata.duration
            actors = metadata.actors
            posterHeaders = item.posterHeaders
            addScore(metadata.score)
        }
        addStreamCenterTrackingId(service.syncIdName, mediaId)
    }
    val response = when (item.type) {
        TvType.TvSeries -> newTvSeriesLoadResponse(
            metadata.title,
            route,
            TvType.TvSeries,
            emptyList(),
        ) {
            applySharedMetadata()
            if (!performanceMode) {
                showStatus = metadata.showStatus
                nextAiring = metadata.nextAiring
            }
        }
        TvType.Anime, TvType.AnimeMovie, TvType.OVA -> newAnimeLoadResponse(
            metadata.title,
            route,
            item.type ?: TvType.Anime,
        ) {
            applySharedMetadata()
            applyAnimeCatalogTitles(null, null, metadata.synonyms)
            if (!performanceMode) {
                showStatus = metadata.showStatus
                nextAiring = metadata.nextAiring
            }
        }
        else -> newMovieLoadResponse(
            metadata.title,
            route,
            item.type ?: TvType.Movie,
            dataUrl = "",
        ) {
            applySharedMetadata()
        }
    }
    val librarySource = "${service.title} (libreria personale)"
    val detailsSource = "${service.title} (dettagli metadati)"
    return response.withCardProvenance(
        defaultSource = service.title,
        fieldSources = mapOf(
            "titolo" to listOf(
                if (!details?.title.isNullOrBlank()) detailsSource else librarySource,
            ),
            "poster" to listOf(
                if (!details?.posterUrl.isNullOrBlank()) detailsSource else librarySource,
            ),
            "sfondo" to listOf(
                if (!details?.backgroundPosterUrl.isNullOrBlank()) detailsSource else librarySource,
            ),
            "trama" to listOf(
                if (!details?.synopsis.isNullOrBlank()) detailsSource else librarySource,
            ),
            "tag" to listOf(detailsSource, librarySource, "${service.title} (dati personali)"),
            "anno" to listOf(
                if (item.releaseDate != null) librarySource else detailsSource,
            ),
            "punteggio" to listOf(
                if (details?.publicScore != null) detailsSource else librarySource,
            ),
            "durata_minuti" to listOf(detailsSource),
            "cast" to listOf(detailsSource),
            "stato_trasmissione" to listOf(detailsSource),
            "prossimo_episodio" to listOf(detailsSource),
            "titoli_alternativi" to listOf(detailsSource),
            "header_poster" to listOf(librarySource),
            "id_sincronizzazione" to listOf(service.title),
            "episodi" to listOf("StreamCenter (nessun episodio fornito dalla libreria)"),
        ),
        fieldNotes = mapOf(
            "tag" to "Il valore finale può combinare generi pubblici, progresso e dati personali della libreria.",
        ),
    )
}
