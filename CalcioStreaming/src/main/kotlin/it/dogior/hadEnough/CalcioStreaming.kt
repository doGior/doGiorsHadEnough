package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document

class CalcioStreaming : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://uno.direttecommunity.online/"
    override var name = "CalcioStreaming"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    override var sequentialMainPage = true
    val cfKiller = CloudflareKiller()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(
            "$mainUrl/partite-streaming.html"
        ).document
        val sections =
            document.select("div.slider-title")
                .filter { it -> it.select("div.owl-carousel").isNotEmpty() }
        if (sections.isEmpty()) throw ErrorLoadingException()

        return newHomePageResponse(sections.mapNotNull { it ->
            val categoryName = it.selectFirst("div.header-wrap > div.label")!!.text()
            val shows = it.select("div.owl-carousel > .slider-tile-inner > .box-16x9").map {
                val href = it.selectFirst("a")!!.attr("href")
                val name = ""
                val posterUrl = fixUrl(it.selectFirst("img.tile-image")!!.attr("src"))
                    .replace("//uploads", "/uploads")
                newLiveSearchResponse(name, href, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            }
            if (shows.isEmpty()) return@mapNotNull null
            HomePageList(
                categoryName,
                shows,
                isHorizontalImages = true
            )
        }, false)

    }


    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val posterUrl =
            document.select("div.background-image.bg-image").attr("style").substringAfter("url(")
                .substringBefore(");")
        val infoBlock = document.select(".info-wrap")
        val title = infoBlock.select("h1").text()
        val description = infoBlock.select("div.info-span > span").toList().joinToString(" - ")
        return newLiveStreamLoadResponse(name = title, url = url, dataUrl = url) {
            this.posterUrl = fixUrl(posterUrl)
                .replace("//uploads", "/uploads")
            this.plot = description
        }
    }

    private fun getStreamUrl(document: Document): String? {
        val scripts = document.body().select("script")
        val obfuscatedScript = scripts.findLast { it.data().contains("eval(") }
        val url = obfuscatedScript?.let {
            val data = getAndUnpack(it.data())
//            Log.d("CalcioStreaming", data)
            val sourceRegex = "(?<=src=\")([^\"]+)".toRegex()
            val source = sourceRegex.find(data)?.value ?: return null
            source
        } ?: return null

        return url
    }

    private suspend fun extractVideoStream(url: String, ref: String, n: Int): Pair<String, String>? {
        if (url.toHttpUrlOrNull() == null) return null
        if (n > 10) return null

        val doc = app.get(url).document
        val link = doc.selectFirst("iframe")?.attr("src") ?: return null
        val newPage = app.get(fixUrl(link), referer = ref).document
        val streamUrl = getStreamUrl(newPage)
        return if (newPage.select("script").size >= 6 && !streamUrl.isNullOrEmpty()) {
            streamUrl to fixUrl(link)
        } else {
            extractVideoStream(url = link, ref = url, n = n + 1)
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val links = document.select("div.embed-container > div.langs > button").mapNotNull {
            val lang = it.text()
            val url = it.attr("data-link")
            val link = extractVideoStream(url, url.substringBefore("channels"), 1)
            if (link == null) return@mapNotNull null
            Link(lang, link.first, link.second)
        }
        links.map {
            Log.d("CalcioStreaming", it.toString())
            callback(
                newExtractorLink(
                    source = this.name,
                    name = it.lang,
                    url = it.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = 0
                    this.referer = it.ref
                }
            )
        }
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val response = cfKiller.intercept(chain)
                return response
            }
        }
    }

    data class Link(
        val lang: String,
        val url: String,
        val ref: String
    )
}
