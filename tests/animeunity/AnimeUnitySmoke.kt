package it.dogior.hadEnough

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

// Copied into an isolated test build by run.py; never packaged in the production plugin.
internal fun runAnimeUnitySmoke(context: Context) {
    Thread {
        runBlocking {
            val prefs = context.getSharedPreferences("AnimeUnityCxSmoke", Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            val provider = AnimeUnity(prefs)
            var passed = 0
            var failed = 0
            suspend fun test(name: String, block: suspend () -> Unit) {
                // The public API allows 60 requests/minute. Keep the suite below this rate.
                kotlinx.coroutines.delay(5000)
                try { block(); passed++; Log.i("AnimeUnitySmoke", "PASS $name") }
                catch (e: Throwable) { failed++; Log.e("AnimeUnitySmoke", "FAIL $name: ${e.message}", e) }
            }
            fun set(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).commit() }
            fun str(key: String, value: String) { prefs.edit().putString(key, value).commit() }
            suspend fun section(key: String, page: Int = 1): HomePageResponse {
                val request = provider.mainPage.first { it.data.startsWith("$key|") }
                return provider.getMainPage(page, MainPageRequest(request.name, request.data, false))
            }
            suspend fun advanced(): List<SearchResponse> = section("advanced").items.single().list
            test("domain normalization and saved .co migration") {
                for (domain in listOf("www.animeunity.cx", "animeunity.cx", "https://www.animeunity.co/", "https://animeunity.co/")) {
                    str(AnimeUnityPlugin.PREF_SITE_URL, domain)
                    check(provider.mainUrl == "https://animeunity.cx") { provider.mainUrl }
                }
                str(AnimeUnityPlugin.PREF_SITE_URL, "https://example.org/")
                check(provider.mainUrl == "https://example.org")
                prefs.edit().remove(AnimeUnityPlugin.PREF_SITE_URL).commit()
            }
            for (key in listOf("latest", "calendar", "ongoing", "popular", "best", "upcoming", "random")) {
                test("home $key") {
                    val response = section(key)
                    if (key != "calendar") check(response.items.single().list.isNotEmpty())
                    check(response.items.single().list.all { it.url.startsWith(provider.mainUrl) && !it.posterUrl.isNullOrBlank() })
                }
            }
            test("custom section names and visibility") {
                str(AnimeUnityPlugin.PREF_LATEST_TITLE, "Smoke latest")
                check(provider.mainPage.first { it.data.startsWith("latest|") }.name == "Smoke latest")
                set(AnimeUnityPlugin.PREF_SHOW_RANDOM, false)
                check(provider.mainPage.none { it.data.startsWith("random|") })
                prefs.edit().remove(AnimeUnityPlugin.PREF_LATEST_TITLE).remove(AnimeUnityPlugin.PREF_SHOW_RANDOM).commit()
            }
            test("archive pagination with counts 1, 17, 30, 45, 100") {
                for (count in listOf(1,17,30,45,100)) {
                    prefs.edit().putInt(AnimeUnityPlugin.PREF_BEST_COUNT, count).commit()
                    val first = section("best").items.single().list
                    val second = section("best", 2).items.single().list
                    check(first.size == count && second.size == count) { "count=$count: ${first.size}/${second.size}" }
                    check(first.map { it.url }.intersect(second.map { it.url }.toSet()).isEmpty()) { "overlap for $count" }
                }
            }
            test("latest pagination crosses 20-item API boundary") {
                prefs.edit().putInt(AnimeUnityPlugin.PREF_LATEST_COUNT, 30).commit()
                check(section("latest").hasNext)
                check(section("latest",2).items.single().list.isNotEmpty())
            }
            test("search and display preferences") {
                val results = provider.search("One Piece").filterIsInstance<AnimeSearchResponse>()
                val card = results.first { it.name == "One Piece" }
                check(card.dubStatus?.containsAll(setOf(DubStatus.Dubbed,DubStatus.Subbed)) == true)
                check(card.score != null)
                set(AnimeUnityPlugin.PREF_SHOW_SCORE, false)
                set(AnimeUnityPlugin.PREF_SHOW_DUB_SUB, false)
                check(provider.search("One Piece").all { it.score == null })
                check(provider.search("One Piece").filterIsInstance<AnimeSearchResponse>().all { it.dubStatus.isNullOrEmpty() })
                set(AnimeUnityPlugin.PREF_SHOW_SCORE, true)
                set(AnimeUnityPlugin.PREF_SHOW_DUB_SUB, true)
                set(AnimeUnityPlugin.PREF_SHOW_EPISODE_NUMBER, false)
                check(provider.search("One Piece").filterIsInstance<AnimeSearchResponse>().all { it.episodes.isNullOrEmpty() })
                set(AnimeUnityPlugin.PREF_SHOW_EPISODE_NUMBER, true)
            }
            test("separate SUB and DUB cards and episode lists") {
                set(AnimeUnityPlugin.PREF_UNIFY_DUB_SUB_CARDS, false)
                val cards = provider.search("One Piece").filter { it.name == "One Piece" }
                check(cards.size == 2)
                for (language in listOf("ita", "jpn")) {
                    val card = cards.first { it.url.endsWith("?language=$language") }
                    val loaded = provider.load(card.url) as AnimeLoadResponse
                    check(loaded.episodes.keys == setOf(if (language=="ita") DubStatus.Dubbed else DubStatus.Subbed))
                }
                set(AnimeUnityPlugin.PREF_UNIFY_DUB_SUB_CARDS, true)
            }
            test("long series pagination, merged episodes and SUB fallback") {
                val loaded = provider.load(provider.search("One Piece").first { it.name == "One Piece" }.url) as AnimeLoadResponse
                val sub = loaded.episodes.getValue(DubStatus.Subbed)
                val dub = loaded.episodes.getValue(DubStatus.Dubbed)
                check((sub.maxOf { it.episode ?: 0 }) >= 1180)
                check(sub.size == dub.size)
                check(dub.any { it.name?.startsWith("[SUB] - ") == true })
                val data = JSONObject(sub.first().data)
                check(!data.isNull("subUrl") && !data.isNull("dubUrl"))
            }
            test("old title bookmark resolves by slug, not obsolete ID") {
                val result = provider.load("https://www.animeunity.co/anime/999999-one-piece") as AnimeLoadResponse
                check(result.name == "One Piece")
            }
            test("old episode URL fails explicitly") {
                val failure = runCatching { provider.loadLinks("https://www.animeunity.co/anime/12-one-piece/999999", false, {}, {}) }.exceptionOrNull()
                check(failure?.message?.contains("vecchio sito") == true)
            }
            set(AnimeUnityPlugin.PREF_ENABLE_ADVANCED_SEARCH, true)
            test("advanced title, year, type, season, status and genre") {
                str(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_TITLE, "Naruto")
                check(advanced().any { it.name == "Naruto" })
                prefs.edit().remove(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_TITLE).commit()
                str(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_YEAR, "2002")
                str(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_TYPE, "TV")
                str(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_SEASON, "Autunno")
                str(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_STATUS, "Terminato")
                prefs.edit().putInt(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_GENRE_ID, 51).commit() // legacy Action ID
                val cards = advanced()
                check(cards.isNotEmpty())
                for (card in cards.take(3)) {
                    val loaded = provider.load(card.url) as AnimeLoadResponse
                    check(loaded.year == 2002 && loaded.type == TvType.Anime && loaded.showStatus == ShowStatus.Completed)
                    check(loaded.tags?.any { it.equals("Action", true) } == true)
                }
            }
            suspend fun metadata(card: SearchResponse): JSONObject {
                val document = app.get(card.url).document
                return JSONObject(document.selectFirst("[data-page]")!!.attr("data-page")).getJSONObject("props").getJSONObject("anime")
            }
            for (type in AnimeUnityPlugin.getAdvancedSearchTypeOptions()) {
                test("advanced type $type") {
                    prefs.edit().clear().putBoolean(AnimeUnityPlugin.PREF_ENABLE_ADVANCED_SEARCH,true)
                        .putInt(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_COUNT,1).putString(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_TYPE,type).commit()
                    val cards = advanced()
                    check(cards.isNotEmpty())
                    check(metadata(cards.first()).getString("type") == type.lowercase().replace(" ","_"))
                }
            }
            for ((season, value) in mapOf("Inverno" to "winter", "Primavera" to "spring", "Estate" to "summer", "Autunno" to "fall")) {
                test("advanced season $season") {
                    prefs.edit().clear().putBoolean(AnimeUnityPlugin.PREF_ENABLE_ADVANCED_SEARCH,true)
                        .putInt(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_COUNT,1).putString(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_SEASON,season).commit()
                    check(metadata(advanced().first()).getString("season") == value)
                }
            }
            for ((status, value) in mapOf("In Corso" to "ongoing", "Terminato" to "completed", "In Uscita" to "upcoming", "Droppato" to "dropped")) {
                test("advanced status $status") {
                    prefs.edit().clear().putBoolean(AnimeUnityPlugin.PREF_ENABLE_ADVANCED_SEARCH,true)
                        .putInt(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_COUNT,1).putString(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_STATUS,status).commit()
                    val anime = metadata(advanced().first())
                    val variants = anime.getJSONArray("variants")
                    check(anime.getString("default_status") == value || (0 until variants.length()).any { variants.getJSONObject(it).optString("status") == value })
                }
            }
            test("latest episode number remains visible with language badges disabled") {
                prefs.edit().clear().putBoolean(AnimeUnityPlugin.PREF_SHOW_DUB_SUB,false).commit()
                check(section("latest").items.single().list.any { it.name.contains(" - Ep. ") })
            }
            test("all advanced sort orders") {
                prefs.edit().clear().putBoolean(AnimeUnityPlugin.PREF_ENABLE_ADVANCED_SEARCH,true).commit()
                for (order in AnimeUnityPlugin.getAdvancedSearchOrderOptions()) {
                    str(AnimeUnityPlugin.PREF_ADVANCED_SEARCH_ORDER, order)
                    check(advanced().isNotEmpty())
                }
            }
            test("player sources for both languages") {
                val movie = provider.load(provider.search("Kuroko no Basket Movie 3").first().url) as AnimeLoadResponse
                for (status in listOf(DubStatus.Subbed, DubStatus.Dubbed)) {
                    val links = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
                    provider.loadLinks(movie.episodes.getValue(status).first().data, false, {}, { links.add(it) })
                    check(links.isNotEmpty()) { "no links for $status" }
                    check(links.all { it.url.startsWith("https://") })
                }
            }
            prefs.edit().clear().commit()
            Log.i("AnimeUnitySmoke", "COMPLETE passed=$passed failed=$failed")
        }
    }.start()
}
