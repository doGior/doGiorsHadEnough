package it.dogior.hadEnough.availability

import android.content.SharedPreferences
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import it.dogior.hadEnough.StreamCenter
import it.dogior.hadEnough.StreamCenterPlugin
import it.dogior.hadEnough.catalog.StreamCenterCatalogs
import it.dogior.hadEnough.extensions.ExtensionCall
import it.dogior.hadEnough.extensions.InstalledExtension
import it.dogior.hadEnough.extensions.InstalledExtensionSources
import it.dogior.hadEnough.stremio.StreamCenterStremioAddonClient
import it.dogior.hadEnough.torrent.StreamCenterExtAvailability
import it.dogior.hadEnough.torrent.StreamCenterExtDomainStatus
import it.dogior.hadEnough.torrent.StreamCenterExtTorrentClient
import it.dogior.hadEnough.torrent.StreamCenterTorrentPreferences
import it.dogior.hadEnough.util.runCatchingCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object StreamCenterAvailabilityChecker {
    private const val MAX_RESPONSE_BYTES = 2_000_000L
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5L, TimeUnit.SECONDS).readTimeout(7L, TimeUnit.SECONDS)
        .writeTimeout(7L, TimeUnit.SECONDS).callTimeout(10L, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build()
    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.5,en;q=0.3",
        "Cache-Control" to "no-cache",
    )

    fun checks(sharedPref: SharedPreferences?): List<AvailabilityCheck> = buildList {
        StreamCenterPlugin.streamingSources.forEach { source ->
            add(AvailabilityCheck(
                id = "site:${source.key}", name = source.title, group = AvailabilityGroup.SITES,
                enabled = StreamCenterPlugin.isStreamingSourceEnabled(sharedPref, source.key),
            ) {
                val url = StreamCenterPlugin.getSourceBaseUrl(sharedPref, source.key)
                if (source.key == StreamCenterPlugin.PREF_SOURCE_VIDXGO) isVidxGoAvailable(url) else urlReachable(url)
            })
        }
        add(AvailabilityCheck("metadata:anizip", "AniZip", AvailabilityGroup.METADATA) {
            jsonApiReachable("https://api.ani.zip/mappings?anilist_id=1", expectedKey = "episodes")
        })
        add(AvailabilityCheck("metadata:jikan", "MyAnimeList (Jikan)", AvailabilityGroup.METADATA) {
            jsonApiReachable("https://api.jikan.moe/v4/genres/anime")
        })
        add(AvailabilityCheck("metadata:anilist", "AniList", AvailabilityGroup.METADATA) { isAnilistAvailable() })
        add(AvailabilityCheck("metadata:kitsu", "Kitsu", AvailabilityGroup.METADATA) {
            jsonApiReachable("https://kitsu.io/api/edge/anime/1", accept = "application/vnd.api+json")
        })
        add(AvailabilityCheck("metadata:tmdb", "TMDB", AvailabilityGroup.METADATA) {
            urlReachable("https://www.themoviedb.org").let { result ->
                if (result.state == AvailabilityState.SUCCESS) result.copy(detail = "Sito raggiungibile; API autenticata non verificata")
                else result
            }
        })
        val torrentEnabled = StreamCenterPlugin.isTorrentEnabled(sharedPref)
        StreamCenterTorrentPreferences.domainOrder(sharedPref).forEach { domain ->
            add(AvailabilityCheck(
                id = "torrent:${domain.preferenceValue}", name = domain.title, group = AvailabilityGroup.TORRENT,
                enabled = torrentEnabled && StreamCenterTorrentPreferences.isDomainEnabled(sharedPref, domain),
                disabledReason = if (!torrentEnabled) "Torrent disattivati nelle impostazioni" else "Endpoint EXT disattivato",
                timeoutMs = 20_000L,
            ) { torrentResult(StreamCenterExtTorrentClient.checkAvailability(domain)) })
        }
        StreamCenterPlugin.getStremioAddons(sharedPref).forEach { addon ->
            add(AvailabilityCheck(
                id = "stremio:${addon.key}", name = addon.name, group = AvailabilityGroup.STREMIO,
                enabled = StreamCenterPlugin.isStremioAddonEnabled(sharedPref, addon.key),
            ) {
                val url = StreamCenterStremioAddonClient.normalizeManifestUrl(addon.manifestUrl)
                val response = execute(request(url, requestHeaders + ("Accept" to "application/json")))
                httpProblem(response.code, response.body, response.cloudflareMitigated)
                    ?: stremioResult(url, response.body, addon.id, response.secure)
            })
        }
        try {
            addAll(extensionChecks(InstalledExtensionSources.available(), InstalledExtensionSources.enabledKeys(sharedPref)))
        } catch (_: Exception) {
            add(AvailabilityCheck("extensions:discovery", "Elenco estensioni", AvailabilityGroup.EXTENSIONS) {
                failure("Impossibile leggere le estensioni installate; riapri CloudStream")
            })
        }
        addAll(sectionChecks(StreamCenter(sharedPref), "home"))
        if (StreamCenterPlugin.isHomeCategoryEnabled(sharedPref, StreamCenterCatalogs.CATEGORY_KEY)) {
            StreamCenterCatalogs.configuredCatalogs(sharedPref).forEach { catalog ->
                addAll(sectionChecks(StreamCenter(sharedPref, catalogDefinition = catalog), "catalog:${catalog.key}"))
            }
        }
    }

    internal fun sectionChecks(api: MainAPI, scope: String): List<AvailabilityCheck> =
        api.mainPage.map { section ->
            AvailabilityCheck(
                id = "section:$scope:${section.data}",
                name = "${api.name} / ${section.name}",
                group = AvailabilityGroup.SECTIONS,
                timeoutMs = 30_000L,
            ) {
                withContext(ExtensionCall) {
                    val response = api.getMainPage(1, MainPageRequest(section.name, section.data, section.horizontalImages))
                        ?: return@withContext failure("La sezione non ha restituito una risposta")
                    val count = response.items.sumOf { it.list.size }
                    val emptyLists = response.items.count { it.list.isEmpty() }
                    when {
                        count == 0 -> warning("Sezione vuota; caricamento dei contenuti non confermato")
                        emptyLists > 0 -> warning("Prima pagina: $count contenuti, $emptyLists liste vuote")
                        else -> success("Prima pagina caricata: $count contenuti")
                    }
                }
            }
        }

    internal fun extensionChecks(extensions: List<InstalledExtension>, selected: Set<String>): List<AvailabilityCheck> =
        extensions.flatMap { extension ->
            extension.sources.map { source ->
                AvailabilityCheck(
                    id = "${extension.key}:${source.key}",
                    name = if (extension.name == source.api.name) extension.name else "${extension.name} / ${source.api.name}",
                    group = AvailabilityGroup.EXTENSIONS, enabled = extension.isEnabled(selected),
                ) { extensionResult(source.api) }
            }
        }.distinctBy { it.id }

    internal suspend fun extensionResult(api: MainAPI): AvailabilityResult = withContext(ExtensionCall) {
        val page = if (api.hasMainPage) api.mainPage.firstOrNull() else null
        if (page != null) {
            val response = api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
            when {
                response == null -> warning("Il catalogo non ha restituito una risposta")
                response.items.any { it.list.isNotEmpty() } -> success("Catalogo disponibile")
                else -> warning("Catalogo vuoto; disponibilità dei contenuti non confermata")
            }
        } else {
            val response = api.search("Naruto", 1)
            when {
                response == null -> warning("La ricerca di prova non ha restituito una risposta")
                response.items.isNotEmpty() -> success("Ricerca disponibile")
                else -> warning("Ricerca di prova senza risultati; disponibilità dei contenuti non confermata")
            }
        }
    }

    internal fun torrentResult(status: StreamCenterExtDomainStatus): AvailabilityResult {
        val http = status.httpCode?.let { " (HTTP $it)" }.orEmpty()
        return when (status.availability) {
            StreamCenterExtAvailability.AVAILABLE -> success("Pagina di ricerca disponibile$http")
            StreamCenterExtAvailability.VERIFICATION_REQUIRED -> warning("Verifica Cloudflare richiesta: apri Torrent > Endpoint EXT nelle Fonti$http")
            StreamCenterExtAvailability.RATE_LIMITED -> warning("Limite temporaneo di richieste: attendi prima di riprovare$http")
            StreamCenterExtAvailability.INVALID_RESPONSE -> failure("Il server non ha restituito una pagina di ricerca valida$http")
            StreamCenterExtAvailability.UNAVAILABLE -> failure("Endpoint EXT non raggiungibile$http")
        }
    }

    internal fun stremioResult(url: String, body: String, expectedId: String, secure: Boolean = true): AvailabilityResult {
        if (!secure) return failure("Il manifest ha reindirizzato a un indirizzo non HTTPS")
        if (body.length > 512_000) return failure("Manifest troppo grande")
        val manifest = try {
            StreamCenterStremioAddonClient.parseManifest(url, body)
        } catch (_: Exception) { return failure("Manifest Stremio non valido") }
        return when {
            !manifest.hasStreamingResources -> warning("Manifest valido, ma non offre stream o sottotitoli")
            manifest.id != expectedId -> warning("Il manifest identifica un add-on diverso: aggiorna la configurazione")
            else -> success("Manifest valido · " + manifest.resources.map { it.name }
                .filter { it.equals("stream", true) || it.equals("subtitles", true) }
                .distinct().joinToString(" e ") + " dichiarati")
        }
    }

    private suspend fun isAnilistAvailable(): AvailabilityResult {
        val request = request("https://graphql.anilist.co", requestHeaders + mapOf(
            "Accept" to "application/json", "Origin" to "https://anilist.co", "Referer" to "https://anilist.co/",
        )).newBuilder().post(
            "{\"query\":\"query { Media(id: 1, type: ANIME) { id } }\"}".toRequestBody("application/json".toMediaType()),
        ).build()
        val response = execute(request)
        return httpProblem(response.code, response.body, response.cloudflareMitigated) ?: try {
            val json = JSONObject(response.body)
            if (json.optJSONArray("errors")?.length()?.let { it > 0 } == true) failure("L'API GraphQL ha restituito errori")
            else if (json.optJSONObject("data")?.optJSONObject("Media")?.optInt("id") == 1) success("API GraphQL disponibile")
            else failure("Risposta API priva dei dati richiesti")
        } catch (_: Exception) { failure("Risposta JSON non valida") }
    }

    private suspend fun jsonApiReachable(url: String, accept: String = "application/json", expectedKey: String = "data"): AvailabilityResult {
        val response = execute(request(url, requestHeaders + ("Accept" to accept)))
        return httpProblem(response.code, response.body, response.cloudflareMitigated) ?: jsonResult(response.body, expectedKey)
    }

    internal fun jsonResult(body: String, expectedKey: String = "data"): AvailabilityResult = try {
        val json = JSONObject(body)
        val data = json.opt(expectedKey)
        when {
            json.optInt("status") == 429 -> warning("Limite temporaneo di richieste")
            data is JSONObject || data is org.json.JSONArray -> success("API disponibile; risposta JSON valida")
            else -> failure("Risposta JSON priva dei dati richiesti")
        }
    } catch (_: Exception) { failure("Risposta JSON non valida") }

    private suspend fun urlReachable(url: String): AvailabilityResult {
        if (url.isBlank()) return failure("URL non configurato")
        val response = execute(request(url, requestHeaders))
        return httpProblem(response.code, response.body, response.cloudflareMitigated)
            ?: if (response.body.isBlank()) warning("Il sito risponde, ma la pagina è vuota")
            else success("Sito raggiungibile (HTTP ${response.code})")
    }

    private suspend fun isVidxGoAvailable(baseUrl: String): AvailabilityResult {
        if (baseUrl.isBlank()) return failure("URL non configurato")
        val base = baseUrl.trimEnd('/')
        val headers = requestHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$base/", "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "cross-site",
        )
        var lastResult = failure("Risposta non disponibile")
        for (url in listOf("$base/26657236", "$base/")) {
            val response = runCatchingCancellable { execute(request(url, headers)) }
                .getOrElse { lastResult = failure(availabilityFailureDetail(it)); null } ?: continue
            val problem = httpProblem(response.code, response.body, response.cloudflareMitigated)
            if (problem == null) return if (response.body.isBlank()) warning("Il lettore risponde, ma la pagina è vuota")
                else success("Host del lettore raggiungibile (HTTP ${response.code})")
            if (problem.state == AvailabilityState.WARNING) return problem
            lastResult = problem
        }
        return lastResult
    }

    internal fun httpProblem(code: Int, body: String = "", cloudflareMitigated: String? = null): AvailabilityResult? = when {
        code == 429 -> warning("Limite temporaneo di richieste (HTTP 429)")
        cloudflareMitigated.equals("challenge", ignoreCase = true) ||
            (body.contains("_cf_chl_opt", true) && Jsoup.parse(body).title().equals("Just a moment...", true)) ->
            warning("Accesso protetto: pagina di verifica ricevuta (HTTP $code)")
        code == 401 -> warning("Autenticazione richiesta (HTTP 401): controlla la configurazione")
        code == 403 -> warning("Accesso negato o protetto (HTTP 403)")
        code !in 200..299 -> failure("HTTP $code")
        else -> null
    }

    private fun request(url: String, headers: Map<String, String>): Request = Request.Builder().url(url).apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.build()

    private suspend fun execute(request: Request): HttpResponse = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val body = response.peekBody(MAX_RESPONSE_BYTES + 1).bytes()
                        if (body.size > MAX_RESPONSE_BYTES) throw IOException("Response too large")
                        HttpResponse(response.code, body.toString(Charsets.UTF_8), response.request.url.isHttps,
                            response.header("cf-mitigated"))
                    }
                }
                result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    private data class HttpResponse(val code: Int, val body: String, val secure: Boolean, val cloudflareMitigated: String?)
    private fun success(detail: String) = AvailabilityResult(AvailabilityState.SUCCESS, detail)
    private fun warning(detail: String) = AvailabilityResult(AvailabilityState.WARNING, detail)
    private fun failure(detail: String) = AvailabilityResult(AvailabilityState.FAILURE, detail)
}
