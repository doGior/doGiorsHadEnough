package it.dogior.hadEnough.remote

import com.lagradost.cloudstream3.LoadResponse

internal object StreamCenterRemoteCards {
    private const val MAX_CARDS = 64
    private val cards = object : LinkedHashMap<Pair<String, String>, RemoteCard>(MAX_CARDS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, RemoteCard>): Boolean =
            size > MAX_CARDS
    }

    fun remember(response: LoadResponse) = synchronized(cards) {
        cards[response.apiName to response.name] = RemoteCard(response.url, response.apiName, response.name)
    }

    fun find(apiName: String, name: String): RemoteCard? = synchronized(cards) { cards[apiName to name] }
}
