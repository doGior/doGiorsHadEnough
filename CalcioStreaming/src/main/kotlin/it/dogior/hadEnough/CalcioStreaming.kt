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
    override var mainUrl = "https://guardacalcio.cam" // Cambio totale di dominio. Valutare un cambio di naming dell'estensione
    override var name = "CalcioStreaming"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    val cfKiller = CloudflareKiller()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/partite-streaming.html").document
        val sections = document.select("div.slider-title").filter {it -> it.select("div.item").isNotEmpty()}

        if (sections.isEmpty()) throw ErrorLoadingException()

        return HomePageResponse(sections.map { it ->
            val categoryName = it.selectFirst("h2 > strong")!!.text()
            val shows = it.select("div.item").map {
                val href = it.selectFirst("a")!!.attr("href")
                val name = it.selectFirst("a > div > h1")!!.text()
                val posterUrl = fixUrl(it.selectFirst("a > img")!!.attr("src"))
                LiveSearchResponse(
                    name,
                    href,
                    this@CalcioStreaming.name,
                    TvType.Live,
                    posterUrl,
                )
            }
            HomePageList(
                categoryName,
                shows,
                isHorizontalImages = true
            )

        })

    }


    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document
        val poster =  fixUrl(document.select("#title-single > div").attr("style").substringAfter("url(").substringBeforeLast(")"))
        val matchStart = document.select("div.info-wrap > div").textNodes().joinToString("").trim()
        return LiveStreamLoadResponse(
            document.selectFirst(" div.info-t > h1")!!.text(),
            url,
            this.name,
            url,
            poster,
            plot = matchStart
        )
    }

    private fun getStreamUrl(document: Document) : String? {
        val scripts = document.body().select("script")
        val obfuscatedScript = scripts.findLast { it.data().contains("eval(") }
        val script = obfuscatedScript?.let { getAndUnpack(it.data()) } ?: return null

        val url = script.substringAfter("var src=\"").substringBefore("\";")
//        Log.d("CalcioStreaming", "Url: $url")
        return url
    }

    private suspend fun extractVideoLinks(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        document.select("button.btn").forEach { button ->
            var link = button.attr("data-link")
            var oldLink = link
            var videoNotFound = true
            while (videoNotFound) {
                if (link.toHttpUrlOrNull() == null) break
                val doc = app.get(link).document
                link = doc.selectFirst("iframe")?.attr("src") ?: break
                val newPage = app.get(fixUrl(link), referer = oldLink).document
                oldLink = link
                val streamUrl = getStreamUrl(newPage)
                Log.d("CalcioStreaming", "Url: $streamUrl")
                if (newPage.select("script").size >= 6 && streamUrl != null){
                    videoNotFound = false
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = button.text(),
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ){
                            this.quality = 0
                            this.referer = fixUrl(link)
                        }
                    )
                }
            }
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        extractVideoLinks(data, callback)
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return object :Interceptor{
            override fun intercept(chain: Interceptor.Chain): Response {
                val response = cfKiller.intercept(chain)
                return response
            }
        }
    }
}
