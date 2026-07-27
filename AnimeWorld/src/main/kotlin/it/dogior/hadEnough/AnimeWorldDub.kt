package it.dogior.hadEnough

import com.lagradost.cloudstream3.*

class AnimeWorldDub(isSplit: Boolean) : AnimeWorldCore(isSplit, currentExtension = CurrentExtension.DUB) {
    override var name = "AnimeWorld Dub"
    override var lang = "it"

    override val mainPage = super.mainPage + mainPageOf(
        "$mainUrl/filter?status=0&language=it&sort=1" to "In Corso",
        "$mainUrl/filter?language=it&sort=1" to "Ultimi aggiunti",
        "$mainUrl/filter?language=it&sort=6" to "Più Visti",
        "$mainUrl/tops/dubbed?sort=1" to "Top 100 Anime",
    )
}
