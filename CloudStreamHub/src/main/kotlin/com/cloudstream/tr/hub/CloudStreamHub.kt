package com.cloudstream.tr.hub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.cloudstream.tr.core.concurrency.BoundedParallelResolver
import com.cloudstream.tr.core.diagnostics.DiagnosticCategory
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.cloudstream.tr.core.diagnostics.DiagnosticStage
import com.cloudstream.tr.core.model.ProviderModels
import com.cloudstream.tr.core.network.StreamValidator
import com.cloudstream.tr.core.network.SafeHttpClient
import com.cloudstream.tr.core.extractors.CloseLoadExtractor
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.Jsoup
import java.util.regex.Pattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class CloudStreamHub : MainAPI() {
    override var mainUrl = "https://api.themoviedb.org/3"
    override var name = "CanStreamHub"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Cartoon,
        TvType.Documentary
    )

    private val tmdbApiKey = "90ad3ec891e5923150283b99719d890f"
    private val tmdbToken = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI5MGFkM2VjODkxZTU5MjMxNTAyODNiOTk3MTlkODkwZiIsIm5iZiI6MTc4OTgxODY1Mi40NzksInN1YiI6IjZhYWU3NzFjOTZlY2VmMDkzYmExZGU4NSIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.2iN-8AMjIO4zMGL60TwvBY0sVdDThmf66GRfkanrtvc"
    private val imageBase = "https://image.tmdb.org/t/p/w500"

    private val authHeaders = mapOf(
        "Authorization" to "Bearer $tmdbToken",
        "Accept" to "application/json"
    )

    private val availableHomePages by lazy { mainPageOf(
        // 1. Genel Akış
        "${mainUrl}/trending/all/day?language=tr-TR&api_key=${tmdbApiKey}" to "Günün Trendleri",
        "${mainUrl}/movie/now_playing?language=tr-TR&api_key=${tmdbApiKey}" to "Vizyondaki Filmler",

        // 2. Netflix Ayrı Dizi ve Film Kategorileri (with_networks & with_companies)
        "${mainUrl}/discover/tv?with_networks=213&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Netflix Dizileri",
        "${mainUrl}/discover/movie?with_companies=178464|81447&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Netflix Filmleri",

        // 3. Disney+ & Marvel Ayrı Dizi ve Film Kategorileri
        "${mainUrl}/discover/tv?with_networks=2739&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Disney+ Dizileri",
        "${mainUrl}/discover/movie?with_companies=2|420&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Disney & Marvel Filmleri",

        // 4. HBO Max Dizileri
        "${mainUrl}/discover/tv?with_networks=49|3186&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "HBO Max Dizileri",

        // 5. Apple TV+ & Amazon Prime
        "${mainUrl}/discover/tv?with_networks=2552&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Apple TV+ Dizileri",
        "${mainUrl}/discover/tv?with_networks=1024&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Amazon Prime Dizileri",

        // 6. Yerli Dizi ve Filmler
        "${mainUrl}/discover/tv?with_origin_country=TR&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Yerli Diziler",
        "${mainUrl}/discover/movie?with_original_language=tr&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Yerli Filmler",

        // 7. IMDb Puanına Göre Kültler
        "${mainUrl}/discover/movie?sort_by=vote_average.desc&vote_count.gte=1000&language=tr-TR&api_key=${tmdbApiKey}" to "IMDb Kült Filmler",
        "${mainUrl}/discover/tv?sort_by=vote_average.desc&vote_count.gte=500&language=tr-TR&api_key=${tmdbApiKey}" to "IMDb Kült Diziler",

        // 8. Saf Türler (Çakışmalar without_genres ile engellendi)
        "${mainUrl}/discover/movie?with_genres=28&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Aksiyon Filmleri",
        "${mainUrl}/discover/movie?with_genres=878&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Bilim Kurgu Filmleri",
        "${mainUrl}/discover/movie?with_genres=27&without_genres=35&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Korku ve Gerilim Filmleri",
        "${mainUrl}/discover/movie?with_genres=35&without_genres=27&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Komedi Filmleri",
        "${mainUrl}/discover/movie?with_genres=16&without_genres=28&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Animasyon Filmleri",
        "${mainUrl}/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Popüler Animeler",
        "${mainUrl}/discover/tv?with_genres=99&sort_by=popularity.desc&language=tr-TR&api_key=${tmdbApiKey}" to "Belgesel Dizileri"
    ) }

    override val mainPage: List<MainPageData>
        get() = availableHomePages

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = "${request.data}&page=${page}"
        val resp = app.get(targetUrl, headers = authHeaders).parsedSafe<TmdbPageResponse>()
        val items = resp?.results?.mapNotNull { parseTmdbItem(it) } ?: emptyList()
        val totalPages = resp?.totalPages ?: 1

        return newHomePageResponse(request.name, items, hasNext = page < totalPages)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val targetUrl = "${mainUrl}/search/multi?query=${query}&language=tr-TR&page=${page}&api_key=${tmdbApiKey}"
        val resp = app.get(targetUrl, headers = authHeaders).parsedSafe<TmdbPageResponse>()
        val items = resp?.results?.mapNotNull { parseTmdbItem(it) } ?: emptyList()
        val deduped = ProviderModels.dedupSearchResults(items)
        val totalPages = resp?.totalPages ?: 1

        return newSearchResponseList(deduped, hasNext = page < totalPages)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    fun parseTmdbItem(item: TmdbItem): SearchResponse? {
        val id = item.id ?: return null
        val isMovie = when (item.mediaType) {
            "movie" -> true
            "tv" -> false
            else -> item.title != null
        }

        val rawTitle = if (isMovie) {
            item.title ?: item.originalTitle
        } else {
            item.name ?: item.originalName
        } ?: return null

        val poster = item.posterPath?.let { "$imageBase$it" }
        val date = if (isMovie) item.releaseDate else item.firstAirDate
        val year = date?.take(4)?.toIntOrNull()
        val score = item.voteAverage?.toString()

        val dataUrl = if (isMovie) {
            "${mainUrl}/movie/${id}?api_key=${tmdbApiKey}&language=tr-TR&append_to_response=credits,videos,external_ids"
        } else {
            "${mainUrl}/tv/${id}?api_key=${tmdbApiKey}&language=tr-TR&append_to_response=credits,videos,external_ids"
        }

        return if (isMovie) {
            newMovieSearchResponse(rawTitle, dataUrl, HubContentRouting.type(true, item.genreIds.orEmpty())) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(score)
            }
        } else {
            newTvSeriesSearchResponse(rawTitle, dataUrl, HubContentRouting.type(false, item.genreIds.orEmpty())) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val resp = app.get(url, headers = authHeaders).parsedSafe<TmdbDetailResponse>() ?: return null
        return parseTmdbDetail(resp, url)
    }

    suspend fun parseTmdbDetail(resp: TmdbDetailResponse, url: String): LoadResponse? {
        val id = resp.id ?: return null
        val isMovie = url.contains("/movie/")
        val title = (if (isMovie) resp.title ?: resp.originalTitle else resp.name ?: resp.originalName) ?: return null
        val poster = resp.posterPath?.let { "$imageBase$it" }
        val backdrop = resp.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
        val date = if (isMovie) resp.releaseDate else resp.firstAirDate
        val year = date?.take(4)?.toIntOrNull()
        val score = resp.voteAverage?.toString()
        val tags = resp.genres?.mapNotNull { it.name } ?: emptyList()
        val actors = resp.credits?.cast?.mapNotNull { cast ->
            cast.name?.let { name ->
                Actor(name, cast.profilePath?.let { "$imageBase$it" })
            }
        } ?: emptyList()
        val trailerKey = resp.videos?.results?.firstOrNull { it.site == "YouTube" && (it.type == "Trailer" || it.type == "Teaser") }?.key
        val trailerUrl = trailerKey?.let { "https://www.youtube.com/watch?v=$it" }

        if (isMovie) {
            val payload = AggregatorLinkPayload(
                title = title,
                year = year,
                isMovie = true,
                tmdbId = id,
                imdbId = resp.imdbId?.takeIf { it.isNotBlank() } ?: resp.externalIds?.imdbId?.takeIf { it.isNotBlank() },
                originalTitle = resp.originalTitle ?: resp.originalName,
                category = HubContentRouting.category(resp.genres.orEmpty().mapNotNull { it.id })
            ).toUrlData()

            return newMovieLoadResponse(title, url, HubContentRouting.type(true, resp.genres.orEmpty().mapNotNull { it.id }), payload) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = resp.overview
                this.year = year
                this.tags = tags
                this.score = Score.from10(score)
                addActors(actors)
                addTrailer(trailerUrl)
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val seasons = resp.seasons?.filter { (it.seasonNumber ?: 0) > 0 } ?: emptyList()

            for (season in seasons) {
                val sNum = season.seasonNumber ?: 1
                val epCount = season.episodeCount ?: 0
                for (ep in 1..epCount) {
                    val epPayload = AggregatorLinkPayload(
                        title = title,
                        year = year,
                        isMovie = false,
                        season = sNum,
                        episode = ep,
                        tmdbId = id,
                        imdbId = resp.imdbId?.takeIf { it.isNotBlank() } ?: resp.externalIds?.imdbId?.takeIf { it.isNotBlank() },
                        originalTitle = resp.originalTitle ?: resp.originalName,
                        category = HubContentRouting.category(resp.genres.orEmpty().mapNotNull { it.id })
                    ).toUrlData()

                    episodes.add(
                        newEpisode(epPayload) {
                            this.name = "${sNum}. Sezon ${ep}. Bölüm"
                            this.season = sNum
                            this.episode = ep
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, HubContentRouting.type(false, resp.genres.orEmpty().mapNotNull { it.id }), episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = resp.overview
                this.year = year
                this.tags = tags
                this.score = Score.from10(score)
                addActors(actors)
                addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            com.lagradost.cloudstream3.ui.player.Torrent.hasAcceptedTorrentForThisSession = true
        } catch (_: Throwable) {}
        val payload = AggregatorLinkPayload.fromUrlData(data) ?: return false
        val torrentSourcesEnabled = com.cloudstream.tr.core.resolvers.DebridConfig.enableTorrentSources
        val debridEnabled = com.cloudstream.tr.core.resolvers.DebridConfig.isDebridEnabled
        val collector = HubLinkCollector(torrentSourcesEnabled, debridEnabled, callback)
        val federationTorrents = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val interceptedCallback: (ExtractorLink) -> Unit = { link ->
            if (HubTorrentSelector.isTorrent(link)) federationTorrents.add(link)
            else collector.direct(link)
        }

        val cacheKey = com.cloudstream.tr.core.streaming.StreamCacheManager.buildKey(
            title = payload.title,
            year = payload.year,
            isMovie = payload.isMovie,
            season = payload.season,
            episode = payload.episode
        ) + (payload.tmdbId?.let { "_tmdb_$it" } ?: "")

        fun flushCollectedLinks() {
            val collectedLinks = collector.snapshot()
            if (collectedLinks.isEmpty()) return

            val linksForCache = collectedLinks.toList().map { link ->
                val baseName = link.name.removePrefix("[⚡ Smart Play] ")
                if (baseName == link.name) {
                    link
                } else {
                    ExtractorLink(
                        source = link.source,
                        name = baseName,
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        type = link.type,
                        headers = link.headers,
                        extractorData = link.extractorData
                    )
                }
            }
            val deterministicLinks = linksForCache.sortedWith(
                compareBy<ExtractorLink> { it.source.lowercase() }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.url }
            )
            val sortedLinks = com.cloudstream.tr.core.model.StreamPrioritySorter.sortByPriority(deterministicLinks)
            HubTorrentCandidateCache.put(cacheKey, cacheableHubLinks(sortedLinks))
        }

        val cachedLinks = HubTorrentCandidateCache.get(cacheKey)

        return coroutineScope {
            val subtitleJob = launch(Dispatchers.IO) {
                com.cloudstream.tr.core.resolvers.SubtitlesResolver.resolveTurkishSubtitles(
                    imdbId = payload.imdbId,
                    title = payload.originalTitle ?: payload.title,
                    year = payload.year,
                    isMovie = payload.isMovie,
                    season = payload.season,
                    episode = payload.episode,
                    callback = subtitleCallback
                )
            }
            val directJob = launch {
                val originStart = System.nanoTime()
                val budget = HubAdaptiveTimeout.limit("direct", HubSourceBudget.DIRECT_DISCOVERY_MS)
                val completed = HubSourceBudget.run(budget) {
                    try {
                        com.cloudstream.tr.core.resolvers.FourKhubResolver.resolve(
                            title = payload.originalTitle ?: payload.title,
                            year = payload.year,
                            isMovie = payload.isMovie,
                            season = payload.season,
                            episode = payload.episode,
                            imdbId = payload.imdbId
                        ).forEach(interceptedCallback)
                    } catch (e: CancellationException) { throw e } catch (_: Exception) {}

                    try {
                        HdfilmcehennemiResolver.resolve(
                            title = payload.title,
                            originalTitle = payload.originalTitle,
                            year = payload.year,
                            isMovie = payload.isMovie,
                            season = payload.season,
                            episode = payload.episode,
                            callback = interceptedCallback
                        )
                    } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                }
                val elapsed = (System.nanoTime() - originStart) / 1_000_000
                HubAdaptiveTimeout.observe("direct", elapsed, completed)
                HubPerformanceTelemetry.record("direct_discovery", elapsed, if (completed) "ok" else "timeout")
                if (!completed) DiagnosticLogger.log(
                    provider = name, stage = DiagnosticStage.LOAD, category = DiagnosticCategory.NETWORK,
                    message = "Direct source discovery exceeded adaptive time budget."
                )
            }
            val torrentJob = launch {
                val imdbId = payload.imdbId ?: com.cloudstream.tr.core.resolvers.SubtitlesResolver.resolveImdbId(
                    payload.originalTitle ?: payload.title, payload.year, payload.isMovie
                )
                if (imdbId != null && (torrentSourcesEnabled || debridEnabled)) {
                    val fresh = Collections.synchronizedList(mutableListOf<ExtractorLink>())
                    val torrentDiscoveryStartedNs = System.nanoTime()
                    val resolvers = mutableListOf<suspend () -> List<ExtractorLink>>(
                        {
                            val getLinks: suspend () -> List<ExtractorLink> = {
                                com.cloudstream.tr.core.resolvers.TorrentioResolver.resolve(
                                    imdbId, payload.isMovie, payload.season, payload.episode
                                )
                            }
                            if (debridEnabled) getLinks()
                            else HubSourceMemoRegistry.torrentio.resolve(
                                listOf("torrentio", imdbId, payload.isMovie, payload.season,
                                    payload.episode).joinToString(":")
                            ) {
                                val evidence = com.cloudstream.tr.core.resolvers.TorrentioResolver.resolveWithEvidence(
                                    imdbId, payload.isMovie, payload.season, payload.episode
                                )
                                HubLookupOutcome(evidence.links, evidence.authoritativeEmpty)
                            }
                        }
                    )
                    if (payload.isMovie && torrentSourcesEnabled) {
                        resolvers.add {
                            if (debridEnabled) {
                                com.cloudstream.tr.core.resolvers.YtsResolver.resolve(imdbId, true)
                            } else {
                                HubSourceMemoRegistry.yts.resolve("yts:" + imdbId) {
                                    HubLookupOutcome(
                                        com.cloudstream.tr.core.resolvers.YtsResolver.resolve(imdbId, true)
                                    )
                                }
                            }
                        }
                    }
                    val discoveryBudget = HubAdaptiveTimeout.limit("torrent", HubSourceBudget.TORRENT_DISCOVERY_MS)
                    val completed = HubSourceBudget.run(discoveryBudget) {
                    resolveHubSourcesProgressively(
                        resolvers = resolvers,
                        onBatch = { batch ->
                            DiagnosticLogger.log(
                                provider = name, stage = DiagnosticStage.LOAD,
                                category = DiagnosticCategory.SOURCE_DISCOVERY,
                                message = "Torrent source batch: ${batch.size} results in ${(System.nanoTime() - torrentDiscoveryStartedNs) / 1_000_000} ms"
                            )
                            fresh.addAll(batch)
                            batch.filterNot(HubTorrentSelector::isTorrent).forEach(collector::direct)
                            collector.torrents(batch.filter(HubTorrentSelector::isTorrent))
                        },
                        onFailure = { failure ->
                            DiagnosticLogger.log(
                                provider = name, stage = DiagnosticStage.LOAD,
                                category = DiagnosticCategory.NETWORK,
                                message = "Torrent source failed (${failure.javaClass.simpleName})"
                            )
                        }
                    )
                    }
                    val elapsed = (System.nanoTime() - torrentDiscoveryStartedNs) / 1_000_000
                    HubAdaptiveTimeout.observe("torrent", elapsed, completed)
                    HubPerformanceTelemetry.record("torrent_discovery", elapsed, if (completed) "ok" else "timeout")
                    if (!completed) DiagnosticLogger.log(
                        provider = name, stage = DiagnosticStage.LOAD, category = DiagnosticCategory.NETWORK,
                        message = "Torrent discovery exceeded adaptive time budget; completed batches retained."
                    )
                    val fallback = HubTorrentSelector.cachedFallback(cachedLinks.orEmpty(), fresh.toList(), true)
                    collector.torrents(fallback)
                }
            }

            val discoveredProviders = CloudStreamProviderRegistryAdapter.getRegisteredProviders(excludeName = this@CloudStreamHub.name)
            val providers = discoveredProviders.filter { HubContentRouting.eligible(it, payload) }
            if (providers.isEmpty()) DiagnosticLogger.log(
                provider = name,
                stage = DiagnosticStage.LOAD,
                category = DiagnosticCategory.SOURCE_DISCOVERY,
                message = "No eligible providers available from the CloudStream registry or CloudStreamHub bundle."
            )
            val federationFinished = HubFederationBudget.run {
            BoundedParallelResolver.resolveProgressive(
                candidates = providers,
                maxConcurrency = 4,
                provider = name,
                resolver = { provider, emitLink ->
                    val completed = HubProviderTimeout.resolve {
                        try {
                            val targetTitles = listOfNotNull(payload.title, payload.originalTitle).distinct()
                            val searchQueries = targetTitles
                                .filter { it.isNotBlank() }
                                .distinctBy { it.trim().lowercase() }
                            val matched = HubMatchingEngine.findConfidentMatchFromSearches(
                                searchQueries = searchQueries,
                                targetTitles = targetTitles,
                                targetYear = payload.year,
                                isMovie = payload.isMovie,
                                search = { query -> HubMatchingEngine.searchProvider(provider, query) }
                            ) ?: run {
                                DiagnosticLogger.log(
                                    provider = provider.name,
                                    stage = DiagnosticStage.SEARCH,
                                    category = DiagnosticCategory.SOURCE_DISCOVERY,
                                    message = "Federation result: no confident match for '${payload.title}' or '${payload.originalTitle}' (${payload.year ?: "N/A"}); queries=${searchQueries.size}; arbitrary first results rejected."
                                )
                                return@resolve
                            }

                            DiagnosticLogger.log(
                                provider = provider.name,
                                stage = DiagnosticStage.SEARCH,
                                category = DiagnosticCategory.SOURCE_DISCOVERY,
                                message = "Federation result: confident ${matched.type} match '${matched.name}'; loading provider result."
                            )
                            val loadRes = provider.load(matched.url) ?: run {
                                DiagnosticLogger.log(
                                    provider = provider.name,
                                    stage = DiagnosticStage.LOAD,
                                    category = DiagnosticCategory.SOURCE_DISCOVERY,
                                    message = "Federation result: matched '${matched.name}', but provider.load returned no response."
                                )
                                return@resolve
                            }
                            val targetLinkData = HubContentRouting.linkData(loadRes, payload)

                            if (targetLinkData != null) {
                                val channel = Channel<ExtractorLink>(capacity = HubCandidateBudget.MAX_PER_PROVIDER)
                                val seenUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
                                val rawLinksReceived = AtomicInteger(0)
                                val standardCandidates = AtomicInteger(0)
                                val featureCandidates = HubFeatureTopK(HubCandidateBudget.RESERVED_FEATURE - 1)
                                val firstFeature = java.util.concurrent.atomic.AtomicBoolean(false)
                                val duplicatesDropped = AtomicInteger(0)
                                val channelOverflowDropped = AtomicInteger(0)
                                val preflightValid = AtomicInteger(0)
                                val preflightInvalid = AtomicInteger(0)
                                val preflightIndeterminate = AtomicInteger(0)
                                val linksEmitted = AtomicInteger(0)

                                coroutineScope {
                                    val verifySlots = Semaphore(3)
                                    val consumerJob = launch {
                                        val checks = mutableListOf<Job>()
                                        for (rawLink in channel) {
                                            currentCoroutineContext().ensureActive()
                                            val rawUrl = rawLink.url
                                            if (rawUrl.isBlank() || rawUrl.contains("youtube.com") || rawUrl.contains("youtu.be")) continue
                                            if (!seenUrls.add(rawUrl)) {
                                                duplicatesDropped.incrementAndGet()
                                                continue
                                            }
                                            if (HubTorrentSelector.isTorrent(rawLink)) {
                                                emitLink(rawLink)
                                                linksEmitted.incrementAndGet()
                                                continue
                                            }
                                            checks.add(launch {
                                                verifySlots.withPermit {
                                                    val reqHeaders = rawLink.headers.toMutableMap()
                                                    if (rawLink.referer.isNotBlank() &&
                                                        !reqHeaders.containsKey("Referer") &&
                                                        !reqHeaders.containsKey("referer")) {
                                                        reqHeaders["Referer"] = rawLink.referer
                                                    }
                                                    val started = System.nanoTime()
                                                    val result = HubGlobalPreflight.run {
                                                        StreamValidator.validateStream(
                                                            url = rawUrl,
                                                            headers = reqHeaders,
                                                            provider = provider.name
                                                        )
                                                    }
                                                    HubPerformanceTelemetry.record(
                                                        "preflight", (System.nanoTime() - started) / 1_000_000,
                                                        result.status.name
                                                    )
                                                    when (result.status) {
                                                        com.cloudstream.tr.core.network.ValidationStatus.VALID -> preflightValid.incrementAndGet()
                                                        com.cloudstream.tr.core.network.ValidationStatus.INVALID -> preflightInvalid.incrementAndGet()
                                                        com.cloudstream.tr.core.network.ValidationStatus.INDETERMINATE -> preflightIndeterminate.incrementAndGet()
                                                    }
                                                    if (result.isValid ||
                                                        result.status == com.cloudstream.tr.core.network.ValidationStatus.INDETERMINATE) {
                                                        val tagged = ExtractorLink(
                                                            source = provider.name,
                                                            name = ProviderModels.formatSourceTitle(
                                                                sourceName = provider.name,
                                                                resolution = rawLink.name
                                                            ),
                                                            url = rawLink.url,
                                                            referer = rawLink.referer,
                                                            quality = rawLink.quality,
                                                            type = result.streamType,
                                                            headers = reqHeaders,
                                                            extractorData = rawLink.extractorData
                                                        )
                                                        currentCoroutineContext().ensureActive()
                                                        emitLink(tagged)
                                                        linksEmitted.incrementAndGet()
                                                    }
                                                }
                                            })
                                        }
                                        checks.joinAll()
                                    }

                                    try {
                                        provider.loadLinks(
                                            data = targetLinkData,
                                            isCasting = isCasting,
                                            subtitleCallback = subtitleCallback,
                                            callback = { rawLink ->
                                                rawLinksReceived.incrementAndGet()
                                                when {
                                                    HubCandidateBudget.isFeatureCandidate(rawLink) -> {
                                                        if (firstFeature.compareAndSet(false, true)) {
                                                            if (!channel.trySend(rawLink).isSuccess)
                                                                featureCandidates.offer(rawLink)
                                                        } else {
                                                            featureCandidates.offer(rawLink)
                                                        }
                                                    }
                                                    standardCandidates.incrementAndGet() <= HubCandidateBudget.MAX_STANDARD -> {
                                                        if (!channel.trySend(rawLink).isSuccess)
                                                            channelOverflowDropped.incrementAndGet()
                                                    }
                                                    else -> channelOverflowDropped.incrementAndGet()
                                                }
                                            }
                                        )
                                    } finally {
                                        try {
                                            for (feature in featureCandidates.snapshot()) channel.send(feature)
                                        } finally {
                                            channel.close(null)
                                        }
                                    }

                                    consumerJob.join()

                                    DiagnosticLogger.log(
                                        provider = provider.name,
                                        stage = DiagnosticStage.STREAM_PREFLIGHT,
                                        category = if (linksEmitted.get() > 0) DiagnosticCategory.SOURCE_DISCOVERY else DiagnosticCategory.NETWORK,
                                        message = "Federation result: matched='${matched.name}', raw=$rawLinksReceived, providerEmitted=$linksEmitted (before hub dedupe and source-policy filtering), valid=$preflightValid, indeterminate=$preflightIndeterminate, invalid=$preflightInvalid, duplicates=$duplicatesDropped, dropped=$channelOverflowDropped. Preflight does not confirm sustained playback."
                                    )
                                }
                            } else {
                                DiagnosticLogger.log(
                                    provider = provider.name,
                                    stage = if (payload.isMovie) DiagnosticStage.LOAD else DiagnosticStage.EPISODE_DISCOVERY,
                                    category = DiagnosticCategory.SOURCE_DISCOVERY,
                                    message = "Federation result: matched '${matched.name}', but no movie or requested episode link data was available."
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            DiagnosticLogger.log(
                                provider = provider.name,
                                stage = DiagnosticStage.LOAD,
                                category = DiagnosticCategory.EXTRACTOR,
                                message = "Provider resolution failed during aggregation: ${e.message}",
                                throwable = e
                            )
                        }
                    }
                    if (!completed) DiagnosticLogger.log(
                        provider = provider.name,
                        stage = DiagnosticStage.LOAD,
                        category = DiagnosticCategory.NETWORK,
                        message = "Federation result: provider timed out after 30 seconds; previously emitted links retained."
                    )
                },
                onLinkFound = { link ->
                    interceptedCallback(link)
                }
            )

            }
            if (!federationFinished) DiagnosticLogger.log(
                provider = name,
                stage = DiagnosticStage.LOAD,
                category = DiagnosticCategory.NETWORK,
                message = "Federation time budget reached; already emitted streams retained."
            )
            torrentJob.join()
            directJob.join()
            try { subtitleJob.join() } catch (_: Exception) {}
            val freshFederation = federationTorrents.toList()
            collector.torrents(freshFederation + HubTorrentSelector.cachedFallback(
                cachedLinks.orEmpty(), collector.snapshot() + freshFederation, false
            ))
            if (!torrentSourcesEnabled) DiagnosticLogger.log(
                provider = name, stage = DiagnosticStage.LOAD, category = DiagnosticCategory.SOURCE_DISCOVERY,
                message = "P2P disabled; cached torrents were not emitted."
            )
            flushCollectedLinks()
            collector.snapshot().isNotEmpty()
        }
    }
}

object HdfilmcehennemiResolver {
    private const val MAIN_URL = "https://www.hdfilmcehennemi.nl"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    data class SearchApiResponse(
        @JsonProperty("results") val results: List<String>? = null
    )

    suspend fun resolve(
        title: String,
        originalTitle: String?,
        year: Int?,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val searchTitles = listOfNotNull(originalTitle, title).distinct()
        for (q in searchTitles) {
            try {
                val res = app.get(
                    "$MAIN_URL/search?q=$q",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "X-Requested-With" to "fetch",
                        "Content-Type" to "application/json"
                    )
                ).parsedSafe<SearchApiResponse>()

                val candidateUrls = mutableListOf<String>()
                for (rawHtml in res?.results.orEmpty()) {
                    val doc = Jsoup.parse(rawHtml)
                    val a = doc.selectFirst("a.search-result") ?: doc.selectFirst("a") ?: continue
                    val href = a.attr("href")
                    val full = if (href.startsWith("http")) href else "$MAIN_URL${if (href.startsWith("/")) "" else "/"}$href"
                    if (isMovie && !href.contains("/dizi/")) {
                        candidateUrls.add(full)
                    } else if (!isMovie && href.contains("/dizi/")) {
                        candidateUrls.add(full)
                    }
                }

                for (targetUrl in candidateUrls) {
                    val pageDoc = app.get(targetUrl, headers = mapOf("User-Agent" to USER_AGENT)).document

                    val streamPageUrl: String? = if (isMovie) {
                        targetUrl
                    } else {
                        var matchedEpUrl: String? = null
                        for (a in pageDoc.select("div.seasons-tab-content a.mini-poster, div.seasons a[href*='bolum']")) {
                            val epHref = a.attr("href")
                            val epTitle = a.selectFirst(".mini-poster-title")?.text()?.trim() ?: a.text().trim()
                            val sMatch = Regex("""(\d+)\.\s*Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                            val eMatch = Regex("""(\d+)\.\s*B[öo]l[üu]m""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                            if (sMatch == (season ?: 1) && eMatch == (episode ?: 1)) {
                                matchedEpUrl = if (epHref.startsWith("http")) epHref else "$MAIN_URL${if (epHref.startsWith("/")) "" else "/"}$epHref"
                                break
                            }
                        }
                        matchedEpUrl
                    }

                    if (streamPageUrl != null) {
                        extractFromPage(streamPageUrl, callback)
                        return
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun extractFromPage(pageUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = app.get(pageUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
            val iframes = mutableListOf<String>()
            for (el in doc.select("iframe, [data-frame]")) {
                val src = el.attr("data-frame").ifEmpty { el.attr("data-src").ifEmpty { el.attr("src") } }
                if (src.isNotBlank() && !src.contains("youtube.com") && !src.contains("youtu.be")) {
                    val fixed = if (src.startsWith("http")) src else if (src.startsWith("//")) "https:$src" else "$MAIN_URL${if (src.startsWith("/")) "" else "/"}$src"
                    iframes.add(fixed)
                }
            }

            for (iframeUrl in iframes.distinct()) {
                val host = Regex("""https?://[^/]+""").find(iframeUrl)?.value ?: MAIN_URL
                val headers = SafeHttpClient.defaultHeaders(referer = pageUrl)
                val rawHtml = app.get(iframeUrl, headers = headers).document.html()

                var streamUrl = CloseLoadExtractor.extractStreamUrl(rawHtml)
                if (streamUrl.isNullOrBlank()) {
                    val arrMatcher = Pattern.compile("\\[\\s*\"[^\\]]+\"\\s*\\]").matcher(rawHtml)
                    while (arrMatcher.find()) {
                        val arrStr = arrMatcher.group(0) ?: continue
                        if (arrStr.contains(".jpg") || arrStr.contains(".png") || arrStr.contains(".webp")) continue
                        try {
                            val legacyUrl = decodeRapidrame(rawHtml, arrStr)
                            if (legacyUrl.isNotEmpty() && (legacyUrl.contains(".m3u8") || legacyUrl.contains(".txt") || legacyUrl.contains("/hls/"))) {
                                streamUrl = legacyUrl
                                break
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (!streamUrl.isNullOrBlank()) {
                    val fullStream = if (streamUrl.startsWith("http")) streamUrl else if (streamUrl.startsWith("//")) "https:$streamUrl" else "$host/$streamUrl"
                    val preflight = StreamValidator.validateStream(
                        url = fullStream,
                        headers = mapOf("Referer" to "$host/"),
                        provider = "HDFilmCehennemi"
                    )
                    if (preflight.isValid || preflight.status == com.cloudstream.tr.core.network.ValidationStatus.INDETERMINATE) {
                        callback(
                            newExtractorLink(
                                source = "HDFilmCehennemi",
                                name = "HDFilmCehennemi [TR Dublaj / Dual]",
                                url = fullStream,
                                type = preflight.streamType
                            ) {
                                this.referer = "$host/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun decodeRapidrame(jsCode: String, arrStr: String): String {
        val arrMatcher = Pattern.compile("\"([^\"]+)\"").matcher(arrStr)
        val sb = StringBuilder()
        while (arrMatcher.find()) {
            sb.append(arrMatcher.group(1))
        }
        var kspgo = sb.toString()

        val keysMatcher = Pattern.compile("var\\s+[a-zA-Z0-9_]+\\s*=\\s*\"([^\"]+)\";\\s*var\\s+[a-zA-Z0-9_]+\\s*=\\s*\"([^\"]+)\";").matcher(jsCode)
        if (!keysMatcher.find()) return ""
        val key1 = keysMatcher.group(1) ?: return ""
        val key2 = keysMatcher.group(2) ?: return ""

        var o0v = 0
        var rbc = 0
        for (ro7 in key1.indices) {
            val wlv = key1[ro7].code
            o0v = (o0v * 31 + wlv) % 251
            rbc = (rbc xor (wlv + ro7)) and 255
        }

        val lbxe = (o0v + rbc) % 256
        val u2u5r = (o0v % 13) + 3
        var d6en9 = ((o0v * 256 + rbc) % 65521) + 1

        for (ro7 in key2.length - 1 downTo 0) {
            val x7ed6 = key2[ro7]
            if (x7ed6 == 'b') {
                var padded = kspgo
                val missing = padded.length % 4
                if (missing != 0) padded += "=".repeat(4 - missing)
                val decodedBytes = com.cloudstream.tr.core.utils.Base64Utils.decode(padded)
                kspgo = String(decodedBytes, Charsets.ISO_8859_1)
            } else if (x7ed6 == 'v') {
                kspgo = kspgo.reversed()
            } else {
                val ql55 = (26 - ((x7ed6.code - 64) % 26)) % 26
                val chars = StringBuilder()
                for (c in kspgo) {
                    if (c.isLetter()) {
                        val y85 = c.code
                        val fro = if (y85 <= 90) 65 else 97
                        chars.append(((y85 - fro + ql55) % 26 + fro).toChar())
                    } else chars.append(c)
                }
                kspgo = chars.toString()
            }
        }

        val trn = kspgo.length
        val npz8 = IntArray(trn)
        for (ro7 in trn - 1 downTo 1) {
            d6en9 = (d6en9 * 75 + 74) % 65537
            npz8[ro7] = d6en9 % (ro7 + 1)
        }

        val oe7 = kspgo.toCharArray()
        for (ro7 in 1 until trn) {
            val fy6 = npz8[ro7]
            val awn = oe7[ro7]
            oe7[ro7] = oe7[fy6]
            oe7[fy6] = awn
        }
        kspgo = String(oe7)

        var tds = lbxe
        val v8y7l = StringBuilder()
        for (ro7 in kspgo.indices) {
            val wlv = kspgo[ro7].code
            tds = (tds + u2u5r) % 256
            v8y7l.append((wlv xor tds).toChar())
            tds = (tds + wlv) % 256
        }
        return v8y7l.toString()
    }
}
