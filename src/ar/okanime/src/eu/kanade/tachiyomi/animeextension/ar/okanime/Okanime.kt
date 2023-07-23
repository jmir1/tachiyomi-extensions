package eu.kanade.tachiyomi.animeextension.ar.okanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable

class Okanime : ParsedAnimeHttpSource() {

    override val name = "Okanime"

    override val baseUrl = "https://www.okanime.xyz"

    override val lang = "ar"

    override val supportsLatest = true

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "div.container > div.section:last-child div.anime-card"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("div.anime-title > h4 > a")!!.also {
            setUrlWithoutDomain(it.attr("href"))
            title = it.text()
        }
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/espisode-list?page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "ul.pagination > li:last-child:not(.disabled)"

    // =============================== Search ===============================
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        ("$baseUrl/search/?s=$query")
            .let { if (page > 1) "$it&page=$page" else it }
            .let(::GET)

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("div.author-info-title > h1")!!.text()
        genre = document.select("div.review-author-info a").eachText().joinToString()

        val infosdiv = document.selectFirst("div.text-right")!!
        thumbnail_url = infosdiv.selectFirst("img")!!.attr("src")
        status = infosdiv.selectFirst("div.full-list-info:contains(حالة الأنمي) a").let {
            when (it?.text() ?: "") {
                "يعرض الان" -> SAnime.ONGOING
                "مكتمل" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
        description = buildString {
            document.selectFirst("div.review-content")
                ?.text()
                ?.let { append("$it\n") }

            infosdiv.select("div.full-list-info").forEach { info ->
                info.select("small")
                    .eachText()
                    .joinToString(": ")
                    .let { append("\n$it") }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.row div.episode-card div.anime-title a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        element.text().also {
            name = it
            episode_number = it.substringAfterLast(" ").toFloatOrNull() ?: 1F
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
