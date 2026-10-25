package com.cloudstream.tr.fourkhdhub

import com.cloudstream.tr.core.resolvers.FourKhubResolver
import com.cloudstream.tr.core.resolvers.SubtitlesResolver
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import java.net.URLEncoder

class FourKHDHub : MainAPI() {
    override var mainUrl = "https://4khdhub.one"
    override var name = "4KHDHub"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana Sayfa"
    )

    internal fun parseCards(document: Document): List<SearchResponse> =
        document.select("div.card-grid a, a:has(.movie-card-content), article, div.post-item").mapNotNull { element ->
            val href = element.attr("href").takeIf { it.isNotBlank() } ?: element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val fullUrl = fixUrl(href)
            val h3 = element.selectFirst("h3, .movie-card-content h3")
            val rawTitle = h3?.text() ?: element.selectFirst("h2.entry-title, a[rel='bookmark']")?.text() ?: element.selectFirst(".movie-card-content")?.text() ?: element.text()
            val cleanTitle = rawTitle.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return@mapNotNull null
            if (cleanTitle.isBlank()) return@mapNotNull null

            val poster = element.selectFirst("img")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
            }

            val isTv = fullUrl.contains("-series-") || cleanTitle.contains("Series", ignoreCase = true)
            val tvType = if (isTv) TvType.TvSeries else TvType.Movie

            if (tvType == TvType.TvSeries) {
                newTvSeriesSearchResponse(cleanTitle, fullUrl, tvType) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(cleanTitle, fullUrl, tvType) {
                    this.posterUrl = poster
                }
            }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(targetUrl).document
        val items = parseCards(document)

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val targetUrl = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(targetUrl).document
        return parseCards(document)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1.entry-title, h1")?.text() ?: document.title()
        val title = rawTitle.replace(" - 4KHDHub", "").replace(" Download", "").trim()
        val poster = document.selectFirst("div.entry-content img, img[src*='catimages'], img[src*='image.tmdb.org'], main img")?.attr("src")
        val plot = document.select("div.entry-content p, p.synopsis, div.description p, p").firstOrNull { it.text().isNotBlank() }?.text()

        val isTv = url.contains("-series-") || title.contains("Series", ignoreCase = true)
        val loadData = title

        return if (isTv) {
            val episodes = mutableListOf<Episode>()
            val downloadAnchors = document.select("a[href*='greenmotors'], a[href*='hubcloud'], a[href*='drive'], a:has(button)")
            if (downloadAnchors.isNotEmpty()) {
                downloadAnchors.forEachIndexed { idx, a ->
                    val epName = a.text().ifBlank { "Episode ${idx + 1}" }
                    episodes.add(
                        newEpisode(a.attr("href")) {
                            this.name = epName
                            this.episode = idx + 1
                        }
                    )
                }
            } else {
                episodes.add(
                    newEpisode(url) {
                        this.name = "Full Series"
                        this.episode = 1
                    }
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, loadData) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        SubtitlesResolver.resolveTurkishSubtitles(
            title = data,
            isMovie = true,
            callback = subtitleCallback
        )
        
        val links = FourKhubResolver.resolve(title = data, isMovie = true)
        var found = false
        for (link in links) {
            callback(link)
            found = true
        }
        return found
    }
}

