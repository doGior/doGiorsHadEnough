package it.dogior.hadEnough

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse

open class SimklProvider : MainAPI() {
    final override var mainUrl = "https://simkl.com"
    override var name = "Simkl"
    override var lang = "en"
    override val hasMainPage = false
    override val hasQuickSearch = false

    private fun parseDurationToMinutes(input: String): Int {
        val regex = Regex("""(?:(\d+)h)?\s*(?:(\d+)m)?""")
        val match = regex.matchEntire(input.trim()) ?: return 0

        val hours = match.groups[1]?.value?.toIntOrNull() ?: 0
        val minutes = match.groups[2]?.value?.toIntOrNull() ?: 0

        return hours * 60 + minutes
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        val doc = response.document
        val itemElement = doc.select("div.SimklHeaderBgShaddow > div.SimklMyTVShowAboutDiv > table")
        val title = itemElement.select("div.SimklTVAboutTitleText h1").text()
        val rating = itemElement.select("span.SimklTVRatingAverage").text().split(" ").last()
        val description = doc.selectFirst("td.SimklTVAboutDetailsText")
            ?.ownText() + " " + (doc.selectFirst("#moreDesc")?.ownText() ?: "")
        val genres = itemElement.select("td.SimklTVAboutGenre a").text()
        val links = doc.select(".SimklTVAboutTabsDetailsLinks > a")
        val tmdbLink = links.firstOrNull { a -> a.text().contains("TMDB") }?.attr("href")
        val year =
            itemElement.select("span.detailYearInfo > a").attr("data-title").substringAfterLast("/")
        val duration = try {
            itemElement.select(".SimklTVAboutYearCountry > span")
                .first { it.attr("data-title").contains("Length") }.ownText()
        } catch (e: NoSuchElementException) {
            null
        }


        val poster = if (!tmdbLink.isNullOrEmpty()) {
            val resp = app.get(tmdbLink).document
            resp.select("img.poster.w-full").attr("srcset").split(", ").last()
        } else {
            fixUrlNull(itemElement.select("#detailPosterImg").attr("src"))
        }

        val recommendationGrid = doc.select("#tvdetailrecommendations div.tvdetailrelations")
        val recommendationsElem = recommendationGrid.select("a.tvdetailrelationsitem")
        val recommendations = recommendationsElem.map {
            val link = fixUrl(it.attr("href"))
            val img = it.selectFirst("img")!!
            val recPoster = fixUrl(img.attr("src"))
            val recTitle = img.attr("alt")
            newTvSeriesSearchResponse(recTitle, link) {
                this.posterUrl = recPoster
            }
        }

        val type = if (duration != null) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        val loadResponse = if (duration != null) {
            newMovieLoadResponse(title, "", type, "")
        } else {
            newTvSeriesLoadResponse(title, "", type, emptyList())
        }
        return loadResponse.apply {
            this.plot = description
            this.tags = genres.split(" ")
            this.posterUrl = poster
            this.recommendations = recommendations
            this.year = year.toIntOrNull()
            addScore(rating)
            duration?.let { this.duration = parseDurationToMinutes(it) }
        }
    }

}