package com.cloudstream.tr.diziizle

import com.cloudstream.tr.core.network.DynamicDomainResolver
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class DiziIzle : MainAPI() {
    override var mainUrl = "https://www.diziizle.you"
    override var name = "DiziIzle"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)
    override val mainPage = mainPageOf("/diziler/" to "Türk Dizileri")

    private suspend fun resolveDomain() {
        mainUrl = DynamicDomainResolver.resolve(name, mainUrl)
    }

    internal fun sourceUrl(value: String): String? {
        val uri = runCatching { URI(mainUrl).resolve(value) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != URI(mainUrl).host || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443) || uri.fragment != null) return null
        return uri.toString()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveDomain()
        val document = app.get(catalogPageUrl(page)).document
        return newHomePageResponse(request.name, parseCatalog(document), hasNext = hasNextCatalogPage(document, page))
    }

    internal fun catalogPageUrl(page: Int): String =
        if (page <= 1) "$mainUrl/diziler/" else "$mainUrl/diziler/sayfa-$page/"

    internal fun hasNextCatalogPage(document: Document, page: Int): Boolean =
        document.select("ul.sayfala a[href]").any { sourceUrl(it.attr("href")) == catalogPageUrl(page + 1) }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        resolveDomain()
        val items = if (page == 1) {
            parseCatalog(app.get("$mainUrl/?ara=${URLEncoder.encode(query, "UTF-8")}").document)
        } else emptyList()
        return newSearchResponseList(items, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    fun parseCatalog(document: Document): List<SearchResponse> = document.select("li.film-dizi")
        .mapNotNull { card ->
            val link = card.selectFirst(".ad a[href]") ?: return@mapNotNull null
            val url = sourceUrl(link.attr("href")) ?: return@mapNotNull null
            val title = link.attr("title").trim().removeSuffix(" izle").trim()
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = fixUrlNull(card.selectFirst("img")?.attr("src"))
            }
        }.distinctBy { it.url }

    fun parseEpisodes(document: Document): List<Episode> = document.select("ol.bl-liste > li.bl-satir")
        .mapNotNull { row ->
            if (row.selectFirst(".bl-etiket-fragman") != null) return@mapNotNull null
            val link = row.selectFirst("a[href]") ?: return@mapNotNull null
            val number = row.selectFirst(".bl-no")?.text()?.toIntOrNull()?.takeIf { it > 0 }
                ?: return@mapNotNull null
            val url = sourceUrl(link.attr("href")) ?: return@mapNotNull null
            newEpisode(url, initializer = {
                name = row.selectFirst(".bl-ad")?.text()
                season = 1
                episode = number
            }, fix = false)
        }.distinctBy { it.data }.sortedBy { it.episode }

    internal suspend fun fetchEpisodes(first: Document, fetch: suspend (String) -> Document?): List<Episode>? {
        val list = first.selectFirst("ol.bl-liste") ?: return null
        val lastPage = list.attr("data-sayfa-son").toIntOrNull()?.takeIf { it > 0 } ?: return null
        val canonical = sourceUrl(list.attr("data-kanonik")) ?: return null
        val path = URI(canonical).path.trimEnd('/')
        if (!Regex("^/[^/]+$").matches(path)) return null
        val episodes = parseEpisodes(first).toMutableList()
        for (page in 2..lastPage) {
            val document = fetch("$mainUrl$path/sayfa-$page/") ?: return null
            val pageList = document.selectFirst("ol.bl-liste") ?: return null
            if (pageList.attr("data-sayfa").toIntOrNull() != page) return null
            val batch = parseEpisodes(document)
            if (batch.isEmpty() || batch.any { item -> episodes.any { it.data == item.data } }) return null
            episodes.addAll(batch)
        }
        return episodes.sortedBy { it.episode }
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveDomain()
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        if (!Regex("^/[^/]+/?$").matches(path)) return null
        val detailUrl = "$mainUrl$path"
        val document = app.get(detailUrl).document
        val episodes = fetchEpisodes(document) { app.get(it).document } ?: return null
        if (episodes.isEmpty()) return null
        val poster = document.selectFirst("img.bl-afis")
        val title = poster?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text() ?: return null
        return newTvSeriesLoadResponse(title, detailUrl, TvType.TvSeries, episodes) {
            posterUrl = fixUrlNull(poster?.attr("src"))
            plot = document.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    fun parsePlayerUrl(document: Document): String? {
        val value = document.selectFirst("iframe#player[src]")?.attr("src") ?: return null
        val url = sourceUrl(value) ?: return null
        val uri = URI(url)
        return url.takeIf { uri.path == "/player/" && uri.rawQuery?.matches(Regex("v=\\d+")) == true }
    }

    fun parseHls(html: String): String? {
        val quoted = Regex("""["']?file["']?\s*:\s*("(?:\\.|[^"\\])*")""")
            .find(html)?.groupValues?.get(1) ?: return null
        val url = runCatching { AppUtils.parseJson<String>(quoted) }.getOrNull() ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf {
            uri.scheme == "https" && uri.host == "video.twimg.com" && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) && uri.path.endsWith(".m3u8") && uri.fragment == null
        }
    }

    internal suspend fun masterLink(url: String): ExtractorLink = newExtractorLink(
        source = name, name = "$name HLS", url = url, type = ExtractorLinkType.M3U8
    ) { quality = Qualities.Unknown.value }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        resolveDomain()
        val path = runCatching { URI(data).path }.getOrNull() ?: return false
        if (!Regex("^/[^/]+/bolum-\\d+/?$").matches(path)) return false
        val episodeUrl = "$mainUrl$path"
        val playerUrl = parsePlayerUrl(app.get(episodeUrl).document) ?: return false
        val hls = parseHls(app.get(playerUrl, referer = episodeUrl).text) ?: return false
        // Keep the master intact: its audio group is lost when only video variants are emitted.
        callback(masterLink(hls))
        return true
    }
}
