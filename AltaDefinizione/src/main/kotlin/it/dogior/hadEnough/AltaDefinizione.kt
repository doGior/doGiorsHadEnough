package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import it.dogior.hadEnough.extractors.DroploadExtractor
import it.dogior.hadEnough.extractors.MySupervideoExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AltaDefinizione : MainAPI() {
    override var mainUrl = "https://altadefinizionegratis.group"
    override var name = "AltaDefinizione"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Documentary)
    override var lang = "it"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/film/" to "Ultimi Aggiunti",
        "$mainUrl/cinema/" to "Ora al Cinema",
        "$mainUrl/netflix-streaming/" to "Netflix",
        "$mainUrl/animazione/" to "Animazione",
        "$mainUrl/avventura/" to "Avventura",
        "$mainUrl/azione/" to "Azione",
        "$mainUrl/biografico/" to "Biografico",
        "$mainUrl/commedia/" to "Commedia",
        "$mainUrl/crime/" to "Crimine",
        "$mainUrl/documentario/" to "Documentario",
        "$mainUrl/drammatico/" to "Drammatico",
        "$mainUrl/erotico/" to "Erotico",
        "$mainUrl/famiglia/" to "Famiglia",
        "$mainUrl/fantascienza/" to "Fantascienza",
        "$mainUrl/fantasy/" to "Fantasy",
        "$mainUrl/giallo/" to "Giallo",
        "$mainUrl/guerra/" to "Guerra",
        "$mainUrl/horror/" to "Horror",
        "$mainUrl/musical/" to "Musical",
        "$mainUrl/poliziesco/" to "Poliziesco",
        "$mainUrl/romantico/" to "Romantico",
        "$mainUrl/sportivo/" to "Sportivo",
        "$mainUrl/storico-streaming/" to "Storico",
        "$mainUrl/thriller/" to "Thriller",
        "$mainUrl/western/" to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}page/$page/"
        val doc = app.get(url).document
        val items = doc.select("#dle-content > .col-lg-3").mapNotNull {
            it.toSearchResponse()
        }
        val pagination = doc.select("div.pagin > a").last()?.text()?.toIntOrNull()
        val hasNext = page < (pagination ?: 0)

        return newHomePageResponse(HomePageList(request.name, items), hasNext = hasNext)
    }

    private fun Element.toSearchResponse(): MovieSearchResponse? {
        val box = this.selectFirst(".wrapperImage") ?: return null
        val img = box.selectFirst("img.wp-post-image")
        val href = box.selectFirst("a")?.attr("href") ?: return null
        val title = box.select("h2.titleFilm > a").text().trim()
        val poster = if (img?.attr("data-src").isNullOrEmpty()) {
            img?.attr("src")
        } else {
            img?.attr("data-src")
        }
        val rating = this.selectFirst("div.imdb-rate")?.ownText()
        return newMovieSearchResponse(title, href) {
            this.posterUrl = fixUrlNull(poster)
            this.score = Score.from(rating, 10)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?story=$query&do=search&subaction=search").document
        val container = doc.select("#dle-content > .col-lg-3")

        return container.select("div.box").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val content = doc.selectFirst("#dle-content") ?: return null
        val title = content.select("h2").text().ifEmpty { "Sconosciuto" }
        val poster = fixUrlNull(content.select("img.wp-post-image").attr("src"))
        val plot = content.selectFirst("#sfull")?.ownText()?.substringAfter("Trama: ")
        val rating = content.selectFirst("span.rateIMDB")?.text()?.substringAfter("IMDb: ")

        val details = content.select("#details > li")
        val genreElements = details.toList().first { it.text().contains("Genere: ") }
        val genres = genreElements.select("a").map { it.text() }
        val yearElements = details.toList().first { it.text().contains("Anno: ") }
        val year = yearElements.select("div").last()?.text()
        return if (url.contains("/serie-tv/")) {
            val episodes = getEpisodes(doc, poster)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                addScore(rating)
            }
        } else {
            val mostraGuardaLink = doc.select("#player1 > iframe").attr("src")
            val link = if (mostraGuardaLink.contains("mostraguarda")) {
                val mostraGuarda = app.get(mostraGuardaLink).document
                val mirrors = mostraGuarda.select("ul._player-mirrors > li").mapNotNull {
                    val l = it.attr("data-link")
                    if (l.contains("mostraguarda")) null
                    else fixUrlNull(l)
                }
                mirrors
            } else {
                emptyList()
            }
            newMovieLoadResponse(title, url, TvType.Movie, link) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
                this.year = year?.toIntOrNull()
                addScore(rating)
            }
        }
    }

    private fun getEpisodes(doc: Document, poster: String?): List<Episode> {
        val seasons =
            doc.selectFirst("div.tab-content")?.select("div.tab-pane") ?: return emptyList()

        return seasons.map {
            val seasonNumber = it.attr("id").substringAfter("season-").toIntOrNull()
            val episodes = it.select("li").map { ep ->
                val mirrors =
                    ep.select("div.mirrors > a.mr").map { mirror -> mirror.attr("data-link") }
                val epData = ep.select("a")
                val epNumber = epData.attr("data-num").substringAfter("x").toIntOrNull()
                val epTitle = epData.attr("data-title").substringBefore(":")
                val epPlot = epData.attr("data-title").substringAfter(": ")
                newEpisode(mirrors) {
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.name = epTitle
                    this.description = epPlot
                    this.posterUrl = poster
                }
            }
            episodes
        }.flatten()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("Altadefinizione", "Links: $data")
        val links = parseJson<List<String>>(data)
        links.map {
            if (it.contains("dropload.tv")) {
                DroploadExtractor().getUrl(it, null, subtitleCallback, callback)
            } else {
                MySupervideoExtractor().getUrl(it, null, subtitleCallback, callback)
//                loadExtractor(it, null, subtitleCallback, callback)
            }
        }
        return false
    }
}