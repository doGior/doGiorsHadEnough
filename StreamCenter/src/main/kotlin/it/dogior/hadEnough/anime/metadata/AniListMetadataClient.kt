package it.dogior.hadEnough.anime.metadata

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.app
import it.dogior.hadEnough.model.AnilistEpisodeMetadata
import it.dogior.hadEnough.model.AnilistLoadMetadata
import it.dogior.hadEnough.model.AnilistRecommendation
import it.dogior.hadEnough.util.cleanText
import it.dogior.hadEnough.util.optNullableInt
import it.dogior.hadEnough.util.optNullableString
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class AniListMetadataClient(
    private val performanceMode: () -> Boolean,
    private val minRequestIntervalMs: () -> Long,
) {
    suspend fun fetchMetadata(
        anilistId: Int?,
        malId: Int?,
        forceFullMetadata: Boolean = false,
    ): AnilistLoadMetadata? {
        if (anilistId == null && malId == null) {
            MetadataLog.info(
                SOURCE,
                "Recupero metadati ignorato",
                mapOf("motivo" to "id_anilist_e_myanimelist_assenti"),
            )
            return null
        }
        val usePerformanceQuery = performanceMode() && !forceFullMetadata
        val requestDetails = buildMap<String, Any?> {
            anilistId?.let { put("id_anilist", it) }
            malId?.let { put("id_myanimelist", it) }
            put("modalita_metadati", if (usePerformanceQuery) "ridotta" else "completa")
            put("metadati_completi_forzati", forceFullMetadata)
        }
        val variables = JSONObject().apply {
            if (anilistId != null) put("id", anilistId) else malId?.let { put("idMal", it) }
        }
        val query = if (usePerformanceQuery) MEDIA_PERFORMANCE_QUERY else MEDIA_QUERY
        MetadataLog.info(SOURCE, "Recupero metadati avviato", requestDetails)
        val media = graphQL(
            query = query,
            variables = variables,
            operation = "Metadati anime AniList",
            requestDetails = requestDetails,
        )?.optJSONObject("Media") ?: run {
            MetadataLog.warning(SOURCE, "Metadati anime non disponibili", requestDetails)
            return null
        }
        val metadata = parseMedia(media)
        MetadataLog.info(
            SOURCE,
            "Metadati anime elaborati",
            requestDetails + mapOf(
                "id_anilist_risolto" to metadata.anilistId,
                "id_myanimelist_risolto" to metadata.malId,
                "titolo_risolto" to metadata.title,
                "episodi_anilist" to metadata.episodeMetadata.size,
                "personaggi" to metadata.characters.size,
                "raccomandazioni" to metadata.recommendations.size,
                "generi" to metadata.genres.size,
                "tag" to metadata.tags.size,
            ),
        )
        return metadata
    }

    suspend fun execute(query: String, variables: JSONObject): JSONObject? {
        return graphQL(
            query = query,
            variables = variables,
            operation = "Query catalogo AniList",
            requestDetails = safeVariableDetails(variables),
        )
    }

    suspend fun fetchScores(ids: Collection<Int>): Map<Int, String> {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) {
            MetadataLog.info(SOURCE, "Recupero punteggi ignorato", mapOf("motivo" to "nessun_id"))
            return emptyMap()
        }
        val chunks = distinctIds.chunked(SCORE_PAGE_SIZE)
        MetadataLog.info(
            SOURCE,
            "Recupero punteggi avviato",
            mapOf("id_richiesti" to distinctIds.size, "richieste_previste" to chunks.size),
        )
        val result = mutableMapOf<Int, String>()
        chunks.forEachIndexed { index, chunk ->
            val scores = requestScores(chunk, index + 1, chunks.size)
            if (scores == null) {
                MetadataLog.warning(
                    SOURCE,
                    "Punteggi non disponibili per gruppo",
                    mapOf("gruppo" to index + 1, "id_nel_gruppo" to chunk.size),
                )
                return@forEachIndexed
            }
            chunk.forEach { id ->
                scores[id]?.takeIf(String::isNotBlank)?.let { result[id] = it }
            }
        }
        MetadataLog.info(
            SOURCE,
            "Recupero punteggi completato",
            mapOf("id_richiesti" to distinctIds.size, "punteggi_risolti" to result.size),
        )
        return result
    }

    suspend fun fetchTitleAliases(ids: Collection<Int>): Map<Int, List<String>> {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) {
            MetadataLog.info(SOURCE, "Recupero titoli alternativi ignorato", mapOf("motivo" to "nessun_id"))
            return emptyMap()
        }
        val missingIds = distinctIds.filterNot(titleAliasCache::containsKey)
        val chunks = missingIds.chunked(SCORE_PAGE_SIZE)
        MetadataLog.info(
            SOURCE,
            "Recupero titoli alternativi avviato",
            mapOf(
                "id_richiesti" to distinctIds.size,
                "id_in_cache" to distinctIds.size - missingIds.size,
                "id_da_richiedere" to missingIds.size,
                "richieste_previste" to chunks.size,
            ),
        )
        chunks.forEachIndexed { index, chunk ->
            val aliases = requestTitleAliases(chunk, index + 1, chunks.size)
            if (aliases == null) {
                MetadataLog.warning(
                    SOURCE,
                    "Titoli alternativi non disponibili per gruppo",
                    mapOf("gruppo" to index + 1, "id_nel_gruppo" to chunk.size),
                )
                return@forEachIndexed
            }
            aliases.forEach { (id, titles) -> titleAliasCache[id] = titles }
        }
        val result = buildMap {
            distinctIds.forEach { id ->
                titleAliasCache[id]?.let { put(id, it) }
            }
        }
        MetadataLog.info(
            SOURCE,
            "Recupero titoli alternativi completato",
            mapOf("id_richiesti" to distinctIds.size, "id_risolti" to result.size),
        )
        return result
    }

    fun showStatus(status: String?): ShowStatus? = when (status?.uppercase(Locale.ROOT)) {
        "FINISHED" -> ShowStatus.Completed
        "RELEASING", "HIATUS" -> ShowStatus.Ongoing
        else -> null
    }

    fun seasonLabel(season: String?, year: Int?): String? {
        val name = when (season?.uppercase(Locale.ROOT)) {
            "WINTER" -> "Inverno"
            "SPRING" -> "Primavera"
            "SUMMER" -> "Estate"
            "FALL" -> "Autunno"
            else -> return null
        }
        return "Stagione: $name${year?.let { " $it" }.orEmpty()}"
    }

    fun sourceLabel(source: String?): String? {
        val name = when (source?.uppercase(Locale.ROOT)) {
            "MANGA" -> "Manga"
            "LIGHT_NOVEL" -> "Light novel"
            "NOVEL" -> "Romanzo"
            "ORIGINAL" -> "Originale"
            "VIDEO_GAME" -> "Videogioco"
            "VISUAL_NOVEL" -> "Visual novel"
            "WEB_NOVEL" -> "Web novel"
            "DOUJINSHI" -> "Doujinshi"
            "MULTIMEDIA_PROJECT" -> "Progetto multimediale"
            null -> return null
            else -> source.lowercase(Locale.ROOT).replace('_', ' ')
                .replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        return "Fonte: $name"
    }

    fun formatLabel(format: String?): String? = when (format?.uppercase(Locale.ROOT)) {
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "SPECIAL" -> "Speciale"
        "TV_SHORT" -> "Corto TV"
        "MUSIC" -> "Video musicale"
        else -> null
    }

    private suspend fun graphQL(
        query: String,
        variables: JSONObject,
        operation: String,
        requestDetails: Map<String, Any?>,
    ): JSONObject? {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        repeat(REQUEST_ATTEMPTS) { attempt ->
            val attemptNumber = attempt + 1
            if (attempt > 0) {
                MetadataLog.info(
                    SOURCE,
                    "Nuovo tentativo query AniList",
                    requestDetails + mapOf(
                        "operazione" to operation,
                        "tentativo" to attemptNumber,
                        "attesa_prima_del_tentativo_ms" to RETRY_DELAY_MS * attempt,
                    ),
                )
                delay(RETRY_DELAY_MS * attempt)
            }
            throttle(operation)
            val attemptDetails = requestDetails + mapOf(
                "operazione" to operation,
                "tentativo" to attemptNumber,
                "tentativi_massimi" to REQUEST_ATTEMPTS,
            )
            MetadataLog.info(SOURCE, "Richiesta GraphQL avviata", attemptDetails)
            val responseResult = runCatching {
                app.post(
                    API_URL,
                    headers = JSON_HEADERS,
                    requestBody = body.toRequestBody(JSON_MEDIA_TYPE),
                    cacheTime = 0,
                )
            }
            val response = responseResult.getOrNull()
            if (response == null) {
                MetadataLog.failure(
                    source = SOURCE,
                    action = "Richiesta GraphQL non riuscita",
                    error = responseResult.exceptionOrNull(),
                    details = attemptDetails + mapOf(
                        "motivo" to "errore_di_rete",
                        "nuovo_tentativo" to (attemptNumber < REQUEST_ATTEMPTS),
                    ),
                )
                return@repeat
            }
            val rootResult = runCatching { JSONObject(response.text) }
            val root = rootResult.getOrNull()
            if (root == null) {
                MetadataLog.failure(
                    source = SOURCE,
                    action = "Risposta GraphQL non valida",
                    error = rootResult.exceptionOrNull(),
                    details = attemptDetails + mapOf(
                        "stato_http" to response.code,
                        "motivo" to "json_non_interpretabile",
                        "nuovo_tentativo" to (attemptNumber < REQUEST_ATTEMPTS),
                    ),
                )
                return@repeat
            }
            val data = root.optJSONObject("data")
            val errors = root.optJSONArray("errors")
            if (data != null && (errors == null || errors.length() == 0)) {
                MetadataLog.info(
                    SOURCE,
                    "Risposta GraphQL elaborata",
                    attemptDetails + mapOf(
                        "stato_http" to response.code,
                        "sezioni_dati" to data.length(),
                    ),
                )
                return data
            }
            MetadataLog.warning(
                SOURCE,
                "Risposta GraphQL senza dati utilizzabili",
                attemptDetails + buildMap<String, Any?> {
                    put("stato_http", response.code)
                    put("dati_presenti", data != null)
                    put("errori_graphql", errors?.length() ?: 0)
                    put("nuovo_tentativo", attemptNumber < REQUEST_ATTEMPTS)
                },
            )
        }
        MetadataLog.warning(
            SOURCE,
            "Query AniList terminata senza risultato",
            requestDetails + mapOf("operazione" to operation, "tentativi_eseguiti" to REQUEST_ATTEMPTS),
        )
        return null
    }

    private suspend fun requestScores(
        ids: List<Int>,
        group: Int,
        totalGroups: Int,
    ): Map<Int, String>? {
        val details = mapOf(
            "gruppo" to group,
            "gruppi_totali" to totalGroups,
            "id_nel_gruppo" to ids.size,
            "id_anilist_gruppo" to ids,
        )
        val body = JSONObject()
            .put("query", SCORES_QUERY)
            .put("variables", JSONObject().put("ids", JSONArray(ids)))
            .toString()
        MetadataLog.info(
            SOURCE,
            "Richiesta punteggi AniList avviata",
            details + mapOf("tentativi_massimi" to 1),
        )
        val requestResult = runCatching {
            throttle("Punteggi AniList")
            val text = app.post(
                API_URL,
                headers = JSON_HEADERS,
                requestBody = body.toRequestBody(JSON_MEDIA_TYPE),
                cacheTime = 0,
            ).text
            val media = JSONObject(text)
                .optJSONObject("data")
                ?.optJSONObject("Page")
                ?.optJSONArray("media")
                ?: return@runCatching null
            buildMap<Int, String> {
                for (index in 0 until media.length()) {
                    val entry = media.optJSONObject(index) ?: continue
                    val id = entry.optNullableInt("id") ?: continue
                    entry.optNullableInt("averageScore")?.let { put(id, formatScore(it)) }
                }
            }
        }
        requestResult.exceptionOrNull()?.let { error ->
            MetadataLog.failure(
                source = SOURCE,
                action = "Richiesta punteggi AniList non riuscita",
                error = error,
                details = details,
            )
        }
        val result = requestResult.getOrNull() ?: return null
        MetadataLog.info(
            SOURCE,
            "Punteggi AniList elaborati",
            details + mapOf("punteggi_risolti" to result.size),
        )
        return result
    }

    private suspend fun requestTitleAliases(
        ids: List<Int>,
        group: Int,
        totalGroups: Int,
    ): Map<Int, List<String>>? {
        val details = mapOf(
            "gruppo" to group,
            "gruppi_totali" to totalGroups,
            "id_nel_gruppo" to ids.size,
            "id_anilist_gruppo" to ids,
        )
        val body = JSONObject()
            .put("query", TITLE_ALIASES_QUERY)
            .put("variables", JSONObject().put("ids", JSONArray(ids)))
            .toString()
        MetadataLog.info(
            SOURCE,
            "Richiesta titoli alternativi AniList avviata",
            details + mapOf("tentativi_massimi" to 1),
        )
        val requestResult = runCatching {
            throttle("Titoli alternativi AniList")
            val text = app.post(
                API_URL,
                headers = JSON_HEADERS,
                requestBody = body.toRequestBody(JSON_MEDIA_TYPE),
                cacheTime = 0,
            ).text
            val media = JSONObject(text)
                .optJSONObject("data")
                ?.optJSONObject("Page")
                ?.optJSONArray("media")
                ?: return@runCatching null
            buildMap<Int, List<String>> {
                for (index in 0 until media.length()) {
                    val entry = media.optJSONObject(index) ?: continue
                    val id = entry.optNullableInt("id") ?: continue
                    val titles = entry.optJSONObject("title")
                        ?.let { title ->
                            listOfNotNull(
                                title.optNullableString("romaji"),
                                title.optNullableString("english"),
                                title.optNullableString("native"),
                            )
                        }
                        .orEmpty() + entry.optJSONArray("synonyms")?.toStringList().orEmpty()
                    put(id, titles.distinct())
                }
            }
        }
        requestResult.exceptionOrNull()?.let { error ->
            MetadataLog.failure(
                source = SOURCE,
                action = "Richiesta titoli alternativi AniList non riuscita",
                error = error,
                details = details,
            )
        }
        val result = requestResult.getOrNull() ?: return null
        MetadataLog.info(
            SOURCE,
            "Titoli alternativi AniList elaborati",
            details + mapOf("id_risolti" to result.size),
        )
        return result
    }

    private suspend fun throttle(operation: String) {
        requestMutex.withLock {
            val now = System.currentTimeMillis()
            val minIntervalMs = minRequestIntervalMs()
            val wait = minIntervalMs - (now - lastRequestAtMs)
            if (wait > 0) {
                MetadataLog.info(
                    SOURCE,
                    "Attesa rate limit",
                    mapOf(
                        "operazione" to operation,
                        "attesa_ms" to wait,
                        "intervallo_minimo_ms" to minIntervalMs,
                    ),
                )
                delay(wait)
            }
            lastRequestAtMs = System.currentTimeMillis()
        }
    }

    private fun safeVariableDetails(variables: JSONObject): Map<String, Any?> = buildMap {
        val keys = buildList {
            val iterator = variables.keys()
            while (iterator.hasNext()) add(iterator.next())
        }.sorted()
        put("variabili_presenti", keys)
        variables.optNullableInt("id")?.let { put("id_anilist", it) }
        variables.optNullableInt("idMal")?.let { put("id_myanimelist", it) }
        variables.optNullableInt("page")?.let { put("pagina", it) }
        variables.optNullableInt("perPage")?.let { put("elementi_per_pagina", it) }
        variables.optJSONArray("ids")?.let { put("id_richiesti", it.length()) }
        if (variables.has("search")) put("ricerca_testuale_presente", true)
    }

    private fun parseMedia(media: JSONObject): AnilistLoadMetadata {
        val titleObj = media.optJSONObject("title")
        val romaji = titleObj?.optNullableString("romaji")
        val english = titleObj?.optNullableString("english")
        val native = titleObj?.optNullableString("native")
        val title = romaji ?: english ?: native ?: "Sconosciuto"
        val synonyms = media.optJSONArray("synonyms")?.toStringList().orEmpty()
        val candidates = (listOfNotNull(romaji, english, native) + synonyms)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val coverImage = media.optJSONObject("coverImage")
        val poster = coverImage?.let {
            it.optNullableString("extraLarge")
                ?: it.optNullableString("large")
                ?: it.optNullableString("medium")
        }
        val year = media.optNullableInt("seasonYear")
            ?: media.optJSONObject("startDate")?.optNullableInt("year")

        return AnilistLoadMetadata(
            anilistId = media.optNullableInt("id") ?: 0,
            malId = media.optNullableInt("idMal"),
            title = title,
            titleRomaji = romaji,
            titleEnglish = english,
            titleNative = native,
            titleCandidates = candidates,
            originalTitle = candidates.firstOrNull { !it.equals(title, ignoreCase = true) },
            format = media.optNullableString("format"),
            poster = poster,
            background = media.optNullableString("bannerImage") ?: poster,
            description = cleanDescription(media.optNullableString("description")),
            score = media.optNullableInt("averageScore")?.let(::formatScore),
            year = year,
            duration = media.optNullableInt("duration"),
            episodes = media.optNullableInt("episodes"),
            status = media.optNullableString("status"),
            genres = media.optJSONArray("genres")?.toStringList().orEmpty(),
            tags = parseTags(media.optJSONArray("tags")),
            isAdult = media.optBoolean("isAdult", false),
            trailerUrl = parseTrailer(media.optJSONObject("trailer")),
            characters = parseCharacters(media.optJSONObject("characters")),
            recommendations = parseRecommendations(media.optJSONObject("recommendations")),
            episodeMetadata = parseEpisodes(media.optJSONArray("streamingEpisodes")),
            studios = media.optJSONObject("studios")?.optJSONArray("nodes")?.let { nodes ->
                buildList {
                    for (index in 0 until nodes.length()) {
                        nodes.optJSONObject(index)?.optNullableString("name")?.let(::add)
                    }
                }
            }.orEmpty(),
            source = media.optNullableString("source"),
            season = media.optNullableString("season"),
            nextAiringEpisode = media.optJSONObject("nextAiringEpisode")?.optNullableInt("episode"),
            nextAiringAtSeconds = media.optJSONObject("nextAiringEpisode")
                ?.optLong("airingAt", 0L)
                ?.takeIf { it > 0L },
        )
    }

    private fun parseEpisodes(streamingEpisodes: JSONArray?): List<AnilistEpisodeMetadata> {
        val episodes = streamingEpisodes ?: return emptyList()
        val seenNumbers = mutableSetOf<Int>()
        return buildList {
            for (index in 0 until episodes.length()) {
                val entry = episodes.optJSONObject(index) ?: continue
                val rawTitle = entry.optNullableString("title")
                val number = rawTitle
                    ?.let {
                        Regex("""(?i)episod[eio]\s*(\d+)""")
                            .find(it)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    }
                    ?: (index + 1)
                if (!seenNumbers.add(number)) continue
                val cleanTitle = rawTitle
                    ?.replace(Regex("""(?i)^\s*episod[eio]\s*\d+\s*([-:]\s*)?"""), "")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                add(
                    AnilistEpisodeMetadata(
                        number = number,
                        title = cleanTitle,
                        posterUrl = entry.optNullableString("thumbnail"),
                    )
                )
            }
        }
    }

    private fun parseTags(tags: JSONArray?): List<String> {
        val entries = tags ?: return emptyList()
        return buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                if (entry.optBoolean("isMediaSpoiler", false)) continue
                if (entry.optInt("rank", 0) < MINIMUM_TAG_RANK) continue
                entry.optNullableString("name")?.let(::add)
                if (size >= TAGS_LIMIT) break
            }
        }
    }

    private fun parseRecommendations(recommendationsObj: JSONObject?): List<AnilistRecommendation> {
        val nodes = recommendationsObj?.optJSONArray("nodes") ?: return emptyList()
        val seen = mutableSetOf<Int>()
        return buildList {
            for (index in 0 until nodes.length()) {
                val media = nodes.optJSONObject(index)?.optJSONObject("mediaRecommendation") ?: continue
                val anilistId = media.optNullableInt("id") ?: continue
                if (!seen.add(anilistId)) continue
                val titleObj = media.optJSONObject("title")
                val title = titleObj?.optNullableString("romaji")
                    ?: titleObj?.optNullableString("english")
                    ?: continue
                val poster = media.optJSONObject("coverImage")?.let {
                    it.optNullableString("large") ?: it.optNullableString("medium")
                }
                add(
                    AnilistRecommendation(
                        anilistId = anilistId,
                        malId = media.optNullableInt("idMal"),
                        title = title,
                        format = media.optNullableString("format"),
                        posterUrl = poster,
                    )
                )
                if (size >= RECOMMENDATIONS_LIMIT) break
            }
        }
    }

    private fun parseCharacters(charactersObj: JSONObject?): List<ActorData> {
        val edges = charactersObj?.optJSONArray("edges") ?: return emptyList()
        return buildList {
            for (index in 0 until edges.length()) {
                val edge = edges.optJSONObject(index) ?: continue
                val node = edge.optJSONObject("node") ?: continue
                val name = node.optJSONObject("name")?.optNullableString("full") ?: continue
                val image = node.optJSONObject("image")?.let {
                    it.optNullableString("large") ?: it.optNullableString("medium")
                }
                val voiceActorObj = edge.optJSONArray("voiceActors")?.optJSONObject(0)
                val voiceActor = voiceActorObj
                    ?.optJSONObject("name")
                    ?.optNullableString("full")
                    ?.let { voiceActorName ->
                        val voiceActorImage = voiceActorObj.optJSONObject("image")?.let {
                            it.optNullableString("large") ?: it.optNullableString("medium")
                        }
                        Actor(voiceActorName, voiceActorImage)
                    }
                val role = when (edge.optNullableString("role")?.uppercase(Locale.ROOT)) {
                    "MAIN" -> ActorRole.Main
                    "SUPPORTING" -> ActorRole.Supporting
                    "BACKGROUND" -> ActorRole.Background
                    else -> null
                }
                add(ActorData(Actor(name, image), role = role, voiceActor = voiceActor))
            }
        }.distinctBy { it.actor.name }
    }

    private fun parseTrailer(trailer: JSONObject?): String? {
        val id = trailer?.optNullableString("id") ?: return null
        return when (trailer.optNullableString("site")?.lowercase(Locale.ROOT)) {
            "youtube" -> "https://www.youtube.com/watch?v=$id"
            "dailymotion" -> "https://www.dailymotion.com/video/$id"
            else -> null
        }
    }

    private fun cleanDescription(description: String?): String? {
        if (description.isNullOrBlank()) return null
        val withBreaks = description.replace(Regex("(?i)<br\\s*/?>"), "\n")
        return cleanText(Jsoup.parse(withBreaks).text())
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) {
            (opt(index) as? String)?.takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun formatScore(averageScore: Int): String {
        return String.format(Locale.US, "%.1f", averageScore / 10.0)
    }

    private companion object {
        const val SOURCE = "AniList"
        const val API_URL = "https://graphql.anilist.co"
        const val REQUEST_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1_000L
        const val SCORE_PAGE_SIZE = 50
        const val RECOMMENDATIONS_LIMIT = 40
        const val MINIMUM_TAG_RANK = 60
        const val TAGS_LIMIT = 20
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val JSON_HEADERS = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        )
        val requestMutex = Mutex()
        val titleAliasCache = ConcurrentHashMap<Int, List<String>>()
        var lastRequestAtMs = 0L

        val SCORES_QUERY = """
            query (${'$'}ids: [Int]) {
              Page(perPage: 50) {
                media(id_in: ${'$'}ids, type: ANIME) {
                  id
                  averageScore
                }
              }
            }
        """.trimIndent()

        val TITLE_ALIASES_QUERY = """
            query (${'$'}ids: [Int]) {
              Page(perPage: 50) {
                media(id_in: ${'$'}ids, type: ANIME) {
                  id
                  title { romaji english native }
                  synonyms
                }
              }
            }
        """.trimIndent()

        val MEDIA_QUERY = """
            query (${'$'}id: Int, ${'$'}idMal: Int) {
              Media(id: ${'$'}id, idMal: ${'$'}idMal, type: ANIME) {
                id
                idMal
                format
                episodes
                duration
                status
                isAdult
                genres
                tags { name rank isMediaSpoiler }
                averageScore
                season
                seasonYear
                source(version: 3)
                startDate { year }
                bannerImage
                description(asHtml: false)
                title { romaji english native }
                synonyms
                coverImage { extraLarge large medium }
                trailer { id site }
                studios(isMain: true) { nodes { name } }
                nextAiringEpisode { airingAt episode }
                streamingEpisodes { title thumbnail }
                characters(sort: [ROLE, RELEVANCE], perPage: 25) {
                  edges {
                    role
                    node { name { full } image { large medium } }
                    voiceActors(language: JAPANESE, sort: [RELEVANCE]) {
                      name { full }
                      image { large medium }
                    }
                  }
                }
                recommendations(sort: [RATING_DESC], perPage: 40) {
                  nodes {
                    mediaRecommendation {
                      id
                      idMal
                      format
                      title { romaji english }
                      coverImage { large medium }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val MEDIA_PERFORMANCE_QUERY = """
            query (${'$'}id: Int, ${'$'}idMal: Int) {
              Media(id: ${'$'}id, idMal: ${'$'}idMal, type: ANIME) {
                id
                idMal
                format
                title { romaji english native }
                synonyms
              }
            }
        """.trimIndent()
    }
}
