package it.dogior.hadEnough.iptv

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

object StreamCenterIptv {
    private const val PLAYLIST_ROOT =
        "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists"
    const val ROUTE_PREFIX = "https://streamcenter.local/iptv/channel/"

    suspend fun fetchChannels(regionKey: String): List<Channel> {
        val region = regions.firstOrNull { it.key == regionKey } ?: regions.first { it.key == "italy" }
        return parsePlaylist(app.get("$PLAYLIST_ROOT/playlist_${region.key}.m3u8").text, region)
    }

    suspend fun testChannels(
        selectedIds: Set<String>,
        onProgress: suspend (ChannelTestProgress) -> Unit = {},
    ): List<ChannelTestResult> {
        if (selectedIds.isEmpty()) return emptyList()
        val channelsById = selectedIds.map { it.substringBefore(':') }.distinct().flatMap { regionKey ->
            runCatching { fetchChannels(regionKey) }.getOrDefault(emptyList())
        }.associateBy(Channel::id)
        val orderedIds = selectedIds.sortedWith(compareBy { channelsById[it]?.name?.lowercase() ?: it })
        val semaphore = Semaphore(6)
        return supervisorScope {
            orderedIds.map { id ->
                async {
                    semaphore.withPermit {
                        val channel = channelsById[id]
                        if (channel == null) {
                            val result = ChannelTestResult(id, id.substringAfter(':'), false, "Non piu in lista")
                            onProgress(ChannelTestProgress(id, result.name, false, result))
                            return@withPermit result
                        }
                        onProgress(ChannelTestProgress(id, channel.name, true))
                        val headers = buildMap {
                            channel.userAgent?.let { put("User-Agent", it) }
                            channel.referer?.let { put("Referer", it) }
                        }
                        val response = runCatching {
                            app.get(channel.streamUrl, headers = headers, allowRedirects = true, timeout = 10L)
                        }.getOrNull()
                        val ok = response?.code in 200..399
                        val result = ChannelTestResult(
                            id = id,
                            name = channel.name,
                            working = ok,
                            detail = response?.code?.let { "HTTP $it" } ?: "Nessuna risposta",
                        )
                        onProgress(ChannelTestProgress(id, channel.name, false, result))
                        result
                    }
                }
            }.awaitAll().sortedBy { it.name.lowercase() }
        }
    }

    fun parsePlaylist(content: String, region: Region = regions.first { it.key == "italy" }): List<Channel> {
        val channels = mutableListOf<Channel>()
        var metadata: String? = null
        val options = linkedMapOf<String, String>()
        content.lineSequence().map(String::trim).forEach { line ->
            when {
                line.startsWith("#EXTINF:", true) -> {
                    metadata = line
                    options.clear()
                }
                metadata != null && line.startsWith("#EXTVLCOPT:", true) -> {
                    val option = line.substringAfter(':')
                    options[option.substringBefore('=').lowercase()] = option.substringAfter('=', "")
                }
                metadata != null && line.isNotBlank() && !line.startsWith('#') -> {
                    parseChannel(metadata.orEmpty(), line, options, region)?.let(channels::add)
                    metadata = null
                    options.clear()
                }
            }
        }
        return channels
            .filterNot { it.streamUrl.substringBefore('?').lowercase().let { url ->
                url.endsWith(".mp3") || url.endsWith(".aac") || url.endsWith(".m4a")
            } }
            .distinctBy { it.id }
    }

    private fun parseChannel(
        metadata: String,
        streamUrl: String,
        options: Map<String, String>,
        region: Region,
    ): Channel? {
        val name = metadata.substringAfterLast(',').replace("Ⓖ", "").trim().takeIf(String::isNotBlank) ?: return null
        val attributes = ATTRIBUTE.findAll(metadata).associate { it.groupValues[1] to it.groupValues[2] }
        val tvgName = attributes["tvg-name"]?.trim().orEmpty()
        val rawId = attributes["tvg-id"]?.trim().orEmpty()
        val localId = when {
            rawId.isNotBlank() && rawId != "canaletv" -> rawId
            tvgName.isNotBlank() -> tvgName
            else -> name
        }.lowercase().replace(CHANNEL_ID_SEPARATOR, "-").trim('-')
        return Channel(
            id = "${region.key}:$localId",
            name = name,
            logo = attributes["tvg-logo"]?.takeIf(String::isNotBlank),
            group = attributes["group-title"].orEmpty().ifBlank { "TV Italia" },
            streamUrl = streamUrl,
            userAgent = options["http-user-agent"]?.takeIf(String::isNotBlank),
            referer = options["http-referrer"]?.takeIf(String::isNotBlank),
            regionKey = region.key,
            regionName = region.name,
        )
    }

    data class Channel(
        val id: String,
        val name: String,
        val logo: String?,
        val group: String,
        val streamUrl: String,
        val userAgent: String?,
        val referer: String?,
        val regionKey: String,
        val regionName: String,
    ) {
        fun playbackData(): String = JSONObject().apply {
            put("streamcenterIptv", true)
            put("name", name)
            put("url", streamUrl)
            userAgent?.let { put("userAgent", it) }
            referer?.let { put("referer", it) }
        }.toString()
    }

    data class PresetChannel(
        val tvgId: String,
        val name: String,
    )

    data class Preset(
        val key: String,
        val title: String,
        val icon: String,
        val regionKey: String = "italy",
        val channels: List<PresetChannel>,
    )

    val presets = listOf(
        Preset(
            key = "rai",
            title = "TV - Rai",
            icon = "📘",
            channels = listOf(
                PresetChannel("Rai1.it", "Rai 1"),
                PresetChannel("Rai2.it", "Rai 2"),
                PresetChannel("Rai3.it", "Rai 3"),
                PresetChannel("Rai4.it", "Rai 4"),
                PresetChannel("Rai5.it", "Rai 5"),
                PresetChannel("RaiMovie.it", "Rai Movie"),
                PresetChannel("RaiPremium.it", "Rai Premium"),
                PresetChannel("RaiGulp.it", "Rai Gulp"),
                PresetChannel("RaiYoyo.it", "Rai YoYo"),
                PresetChannel("RaiNews24.it", "Rai News 24"),
                PresetChannel("RaiStoria.it", "Rai Storia"),
                PresetChannel("RaiScuola.it", "Rai Scuola"),
                PresetChannel("RaiSport.it", "Rai Sport"),
                PresetChannel("RaiRadio2Visual.it", "Rai Radio 2 Visual Radio"),
                PresetChannel("Rai4K.it", "Rai 4K"),
            ),
        ),
        Preset(
            key = "mediaset",
            title = "TV - Mediaset",
            icon = "📙",
            channels = listOf(
                PresetChannel("Rete.4.it", "Rete 4"),
                PresetChannel("Canale.5.it", "Canale 5"),
                PresetChannel("Italia.1.it", "Italia 1"),
                PresetChannel("20.it", "20 Mediaset"),
                PresetChannel("Iris.it", "Iris"),
                PresetChannel("27Twentyseven.it", "27 Twentyseven"),
                PresetChannel("La5.it", "La 5"),
                PresetChannel("Cine34.it", "Cine34"),
                PresetChannel("Focus.it", "Focus"),
                PresetChannel("Top.Crime.it", "Top Crime"),
                PresetChannel("Boing.it", "BOING"),
                PresetChannel("Cartoonito.it", "Cartoonito"),
                PresetChannel("Italia.2.it", "Italia 2"),
                PresetChannel("TGCom.it", "TGCOM 24"),
                PresetChannel("Mediaset.Extra.it", "Mediaset Extra"),
            ),
        ),
        Preset(
            key = "discovery",
            title = "TV - Discovery",
            icon = "📗",
            channels = listOf(
                PresetChannel("Nove.it", "Nove"),
                PresetChannel("Real.Time.it", "Real Time"),
                PresetChannel("Food.Network.it", "Food Network"),
                PresetChannel("Discovery.Channel.it", "Discovery Channel"),
                PresetChannel("Giallo.TV.it", "Giallo"),
                PresetChannel("K2.it", "K2"),
                PresetChannel("Frisbee.it", "Frisbee"),
                PresetChannel("DMAX.it", "DMAX"),
                PresetChannel("HGTVItaly.it", "HGTV – Home & Garden Tv"),
                PresetChannel("Motor.Trend.it", "Turbo"),
            ),
        ),
        Preset(
            key = "sky",
            title = "TV - Sky in chiaro",
            icon = "📕",
            channels = listOf(
                PresetChannel("TV8.HD.it", "TV8"),
                PresetChannel("cielo.it", "Cielo"),
                PresetChannel("Sky.TG24.it", "Sky TG24"),
            ),
        ),
        Preset(
            key = "la7",
            title = "TV - La7",
            icon = "📒",
            channels = listOf(
                PresetChannel("LA7.HD.it", "La7"),
                PresetChannel("LA7.Cinema.it", "La7 Cinema"),
            ),
        ),
    )

    fun resolvePresetChannels(channels: List<Channel>, preset: Preset): List<Channel> {
        val availableById = channels.associateBy { it.id.substringAfter(':') }
        val availableByName = channels.groupBy { presetNameKey(it.name) }
        val usedIds = mutableSetOf<String>()
        return preset.channels.mapNotNull { target ->
            val idSuffix = target.tvgId.lowercase().replace(CHANNEL_ID_SEPARATOR, "-").trim('-')
            val channel = availableById[idSuffix]
                ?: availableByName[presetNameKey(target.name)]?.firstOrNull { it.id !in usedIds }
            channel?.takeIf { usedIds.add(it.id) }
        }
    }

    private fun presetNameKey(value: String): String {
        return value.lowercase().replace(CHANNEL_ID_SEPARATOR, " ").trim()
    }

    data class Region(val key: String, val name: String)

    fun languageCodeFor(regionKey: String): String = when (regionKey) {
        "italy", "san_marino" -> "it"
        "spain", "argentina", "chile", "costa_rica", "dominican_republic", "mexico",
        "paraguay", "peru", "venezuela" -> "es"
        "france", "monaco", "chad" -> "fr"
        "germany", "austria", "switzerland" -> "de"
        "portugal", "brazil" -> "pt"
        "uk", "ireland", "usa", "canada", "australia", "kenya" -> "en"
        "albania" -> "sq"
        "andorra" -> "ca"
        "armenia" -> "hy"
        "azerbaijan" -> "az"
        "belarus" -> "be"
        "belgium", "netherlands" -> "nl"
        "bosnia_and_herzegovina" -> "bs"
        "bulgaria" -> "bg"
        "china", "hong_kong", "macau", "taiwan" -> "zh"
        "croatia" -> "hr"
        "cyprus", "greece" -> "el"
        "czech_republic" -> "cs"
        "denmark" -> "da"
        "egypt", "iraq", "qatar", "saudi_arabia", "united_arab_emirates" -> "ar"
        "estonia" -> "et"
        "faroe_islands" -> "fo"
        "finland" -> "fi"
        "georgia" -> "ka"
        "greenland" -> "kl"
        "hungary" -> "hu"
        "iceland" -> "is"
        "india" -> "hi"
        "indonesia" -> "id"
        "iran" -> "fa"
        "israel" -> "he"
        "japan" -> "ja"
        "korea", "north_korea" -> "ko"
        "kosovo" -> "sq"
        "latvia" -> "lv"
        "lithuania" -> "lt"
        "luxembourg" -> "lb"
        "malta" -> "mt"
        "moldova", "romania" -> "ro"
        "montenegro", "serbia" -> "sr"
        "north_macedonia" -> "mk"
        "norway" -> "no"
        "poland" -> "pl"
        "russia" -> "ru"
        "slovakia" -> "sk"
        "slovenia" -> "sl"
        "somalia" -> "so"
        "sweden" -> "sv"
        "turkey" -> "tr"
        "ukraine" -> "uk"
        else -> "en"
    }
    data class ChannelTestResult(
        val id: String,
        val name: String,
        val working: Boolean,
        val detail: String,
    )

    data class ChannelTestProgress(
        val id: String,
        val name: String,
        val testing: Boolean,
        val result: ChannelTestResult? = null,
    )

    val regions = listOf(
        Region("italy", "Italia"),
        Region("albania", "Albania"), Region("andorra", "Andorra"),
        Region("argentina", "Argentina"), Region("armenia", "Armenia"),
        Region("australia", "Australia"), Region("austria", "Austria"),
        Region("azerbaijan", "Azerbaigian"), Region("belarus", "Bielorussia"),
        Region("belgium", "Belgio"), Region("bosnia_and_herzegovina", "Bosnia ed Erzegovina"),
        Region("brazil", "Brasile"), Region("bulgaria", "Bulgaria"),
        Region("canada", "Canada"), Region("chad", "Ciad"), Region("chile", "Cile"),
        Region("china", "Cina"), Region("costa_rica", "Costa Rica"),
        Region("croatia", "Croazia"), Region("cyprus", "Cipro"),
        Region("czech_republic", "Repubblica Ceca"), Region("denmark", "Danimarca"),
        Region("dominican_republic", "Repubblica Dominicana"), Region("egypt", "Egitto"),
        Region("estonia", "Estonia"), Region("faroe_islands", "Isole Faroe"),
        Region("finland", "Finlandia"), Region("france", "Francia"),
        Region("georgia", "Georgia"), Region("germany", "Germania"),
        Region("greece", "Grecia"), Region("greenland", "Groenlandia"),
        Region("hong_kong", "Hong Kong"), Region("hungary", "Ungheria"),
        Region("iceland", "Islanda"), Region("india", "India"),
        Region("indonesia", "Indonesia"), Region("iran", "Iran"), Region("iraq", "Iraq"),
        Region("ireland", "Irlanda"), Region("israel", "Israele"), Region("japan", "Giappone"),
        Region("kenya", "Kenya"), Region("korea", "Corea del Sud"), Region("kosovo", "Kosovo"),
        Region("latvia", "Lettonia"), Region("lithuania", "Lituania"),
        Region("luxembourg", "Lussemburgo"), Region("macau", "Macao"), Region("malta", "Malta"),
        Region("mexico", "Messico"), Region("moldova", "Moldavia"), Region("monaco", "Monaco"),
        Region("montenegro", "Montenegro"), Region("netherlands", "Paesi Bassi"),
        Region("north_korea", "Corea del Nord"), Region("north_macedonia", "Macedonia del Nord"),
        Region("norway", "Norvegia"), Region("paraguay", "Paraguay"), Region("peru", "Perù"),
        Region("poland", "Polonia"), Region("portugal", "Portogallo"), Region("qatar", "Qatar"),
        Region("romania", "Romania"), Region("russia", "Russia"),
        Region("san_marino", "San Marino"), Region("saudi_arabia", "Arabia Saudita"),
        Region("serbia", "Serbia"), Region("slovakia", "Slovacchia"),
        Region("slovenia", "Slovenia"), Region("somalia", "Somalia"), Region("spain", "Spagna"),
        Region("sweden", "Svezia"), Region("switzerland", "Svizzera"),
        Region("taiwan", "Taiwan"), Region("trinidad", "Trinidad e Tobago"),
        Region("turkey", "Turchia"), Region("uk", "Regno Unito"), Region("ukraine", "Ucraina"),
        Region("united_arab_emirates", "Emirati Arabi Uniti"), Region("usa", "Stati Uniti"),
        Region("venezuela", "Venezuela"),
    )

    private val ATTRIBUTE = Regex("""([\w-]+)="([^"]*)"""")
    private val CHANNEL_ID_SEPARATOR = Regex("[^a-z0-9]+")
}
