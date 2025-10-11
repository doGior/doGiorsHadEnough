package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class DaddyLiveTVProvider : MainAPI() {
    override var mainUrl = "https://dlhd.dad"
    override var name = "DaddyLive TV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "un"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    @Suppress("ConstPropertyName")
    companion object {
        val channelsName: MutableMap<String, String> = mutableMapOf()
        private const val poster =
            "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"
        val countries = listOf(
            "Andorra",
            "UAE",
            "Afghanistan",
            "Antigua and Barbuda",
            "Anguilla",
            "Albania",
            "Armenia",
            "Angola",
            "Antarctica",
            "Argentina",
            "American Samoa",
            "Austria",
            "Australia",
            "Aruba",
            "�land",
            "Azerbaijan",
            "Bosnia and Herzegovina",
            "Barbados",
            "Bangladesh",
            "Belgium",
            "Burkina Faso",
            "Bulgaria",
            "Bahrain",
            "Burundi",
            "Benin",
            "Saint Barth�lemy",
            "Bermuda",
            "Brunei",
            "Bolivia",
            "Bonaire, Sint Eustatius, and Saba",
            "Brasil",
            "Bahamas",
            "Bhutan",
            "Bouvet Island",
            "Botswana",
            "Belarus",
            "Belize",
            "Canada",
            "Cocos (Keeling) Islands",
            "DR Congo",
            "Central African Republic",
            "Congo Republic",
            "Switzerland",
            "Ivory Coast",
            "Cook Islands",
            "Chile",
            "Cameroon",
            "China",
            "Colombia",
            "Costa Rica",
            "Cuba",
            "Cabo Verde",
            "Cura�ao",
            "Christmas Island",
            "Cyprus",
            "Czechia",
            "Germany",
            "Djibouti",
            "Denmark",
            "Dominica",
            "Dominican Republic",
            "Algeria",
            "Ecuador",
            "Estonia",
            "Egypt",
            "Western Sahara",
            "Eritrea",
            "Spain",
            "Ethiopia",
            "Finland",
            "Fiji",
            "Falkland Islands",
            "Micronesia",
            "Faroe Islands",
            "France",
            "Gabon",
            "UK",
            "Grenada",
            "Georgia",
            "French Guiana",
            "Guernsey",
            "Ghana",
            "Gibraltar",
            "Greenland",
            "The Gambia",
            "Guinea",
            "Guadeloupe",
            "Equatorial Guinea",
            "Greece",
            "South Georgia and South Sandwich Islands",
            "Guatemala",
            "Guam",
            "Guinea-Bissau",
            "Guyana",
            "Hong Kong",
            "Heard and McDonald Islands",
            "Honduras",
            "Croatia",
            "Haiti",
            "Hungary",
            "Indonesia",
            "Ireland",
            "Israel",
            "Isle of Man",
            "India",
            "British Indian Ocean Territory",
            "Iraq",
            "Iran",
            "Iceland",
            "Italy",
            "Jersey",
            "Jamaica",
            "Jordan",
            "Japan",
            "Kenya",
            "Kyrgyzstan",
            "Cambodia",
            "Kiribati",
            "Comoros",
            "St Kitts and Nevis",
            "North Korea",
            "South Korea",
            "Kuwait",
            "Cayman Islands",
            "Kazakhstan",
            "Laos",
            "Lebanon",
            "Saint Lucia",
            "Liechtenstein",
            "Sri Lanka",
            "Liberia",
            "Lesotho",
            "Lithuania",
            "Luxembourg",
            "Latvia",
            "Libya",
            "Morocco",
            "Monaco",
            "Moldova",
            "Montenegro",
            "Saint Martin",
            "Madagascar",
            "Marshall Islands",
            "North Macedonia",
            "Mali",
            "Myanmar",
            "Mongolia",
            "Macao",
            "Northern Mariana Islands",
            "Martinique",
            "Mauritania",
            "Montserrat",
            "Malta",
            "Mauritius",
            "Maldives",
            "Malawi",
            "Mexico",
            "Malaysia",
            "Mozambique",
            "Namibia",
            "New Caledonia",
            "Niger",
            "Norfolk Island",
            "Nigeria",
            "Nicaragua",
            "The Netherlands",
            "Norway",
            "Nepal",
            "Nauru",
            "Niue",
            "New Zealand",
            "Oman",
            "Panama",
            "Peru",
            "French Polynesia",
            "Papua New Guinea",
            "Philippines",
            "Pakistan",
            "Poland",
            "Saint Pierre and Miquelon",
            "Pitcairn Islands",
            "Puerto Rico",
            "Palestine",
            "Portugal",
            "Palau",
            "Paraguay",
            "Qatar",
            "R�union",
            "Romania",
            "Serbia",
            "Russia",
            "Rwanda",
            "Saudi Arabia",
            "Solomon Islands",
            "Seychelles",
            "Sudan",
            "Sweden",
            "Singapore",
            "Saint Helena",
            "Slovenia",
            "Svalbard and Jan Mayen",
            "Slovakia",
            "Sierra Leone",
            "San Marino",
            "Senegal",
            "Somalia",
            "Suriname",
            "South Sudan",
            "S�o Tom� and Pr�ncipe",
            "El Salvador",
            "Sint Maarten",
            "Syria",
            "Eswatini",
            "Turks and Caicos Islands",
            "Chad",
            "French Southern Territories",
            "Togo",
            "Thailand",
            "Tajikistan",
            "Tokelau",
            "Timor-Leste",
            "Turkmenistan",
            "Tunisia",
            "Tonga",
            "Turkey",
            "Trinidad and Tobago",
            "Tuvalu",
            "Taiwan",
            "Tanzania",
            "Ukraine",
            "Uganda",
            "U.S. Outlying Islands",
            "USA",
            "Uruguay",
            "Uzbekistan",
            "Vatican City",
            "St Vincent and Grenadines",
            "Venezuela",
            "British Virgin Islands",
            "U.S. Virgin Islands",
            "Vietnam",
            "Vanuatu",
            "Wallis and Futuna",
            "Samoa",
            "Kosovo",
            "Yemen",
            "Mayotte",
            "South Africa",
            "Zambia",
            "Zimbabwe",
        )
    }

    private suspend fun searchResponseBuilder(): List<LiveSearchResponse> {
        val channelsUrl = "$mainUrl/24-7-channels.php"
        val response = app.get(channelsUrl).document
        val channels = response.select("div.grid > a")

        return channels.map {
            val name = it.select("div.card__title").text()
            val url = fixUrl(it.attr("href"))
            channelsName[url] = name
            newLiveSearchResponse(name, url) {
                posterUrl = poster
            }
        }
    }


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val searchResponses = searchResponseBuilder()
        val groupedSearchResponses = searchResponses.groupBy {
            val c = it.name.substringAfterLast(" ")
                .replace(")", "").trim()
            if (countries.any { country -> country.lowercase() in c.lowercase() }) {
                c
            } else {
                "Unknown"
            }
        }
        val sections = groupedSearchResponses.map {
            HomePageList(
                it.key,
                it.value,
                false
            )
        }.sortedBy { it.name }
        return newHomePageResponse(
            sections,
            false
        )
    }

    // this function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponses = searchResponseBuilder()
        val matches = searchResponses.filter {
            query.lowercase().replace(" ", "") in
                    it.name.lowercase().replace(" ", "")
        }
        return matches
    }

    override suspend fun load(url: String): LoadResponse {
        val resp = app.get(url)
        val h2 = resp.document.selectFirst("h2")?.text()
        val title =
            h2?.substringBefore('(') ?: channelsName[url] ?: "Channel"
        val id = h2?.substringAfter("ID ")?.substringBefore(')')
        val dataUrl = "$mainUrl/%s/stream-$id.php"
        return newLiveStreamLoadResponse(title, url, dataUrl) {
            posterUrl = poster
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("DDL", data)
        val players = listOf("stream", "cast", "watch", "plus", "casting", "player")

        val output = players.map {
            val url = data.format(it)
            Log.d("DDL - Servers", url)
            loadExtractor(url, null, subtitleCallback, callback)
        }

        return output.any { it }
    }
}