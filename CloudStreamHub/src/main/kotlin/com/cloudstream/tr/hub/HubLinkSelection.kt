package com.cloudstream.tr.hub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeoutOrNull

internal object HubCandidateBudget {
    const val MAX_PER_PROVIDER = 128
    const val RESERVED_FEATURE = 32
    const val MAX_STANDARD = MAX_PER_PROVIDER - RESERVED_FEATURE

    /** Late-arriving 2160p / Blu-ray releases must not be dropped after 96 lower-quality links. */
    fun isFeatureCandidate(link: ExtractorLink): Boolean {
        if (link.quality >= 2160) return true
        return Regex("""\b(2160p|4k|remux|blu[ -]?ray|bdremux)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(link.name)
    }

    fun allow(receivedCount: Int): Boolean = receivedCount in 1..MAX_PER_PROVIDER

    /**
     * Each callback is counted in exactly one lane. Total admitted <= 128;
     * 32 slots remain available for feature candidates even if 96 standard links arrived first.
     */
    fun admit(link: ExtractorLink, standardCount: AtomicInteger, featureCount: AtomicInteger): Boolean {
        val (counter, limit) = if (isFeatureCandidate(link)) {
            featureCount to RESERVED_FEATURE
        } else {
            standardCount to MAX_STANDARD
        }
        return counter.incrementAndGet() <= limit
    }
}

/** Total federation time budget: prevents N provider groups each taking 30s. */
internal object HubSourceBudget {
    const val TORRENT_DISCOVERY_MS = 20_000L
    const val DIRECT_DISCOVERY_MS = 20_000L
    suspend fun run(limitMs: Long, block: suspend () -> Unit): Boolean =
        withTimeoutOrNull(limitMs) { block(); true } ?: false
}

internal object HubFederationBudget {
    const val DEFAULT_MS = 45_000L

    suspend fun run(budgetMs: Long = DEFAULT_MS, block: suspend () -> Unit): Boolean =
        withTimeoutOrNull(budgetMs) { block(); true } ?: false
}

internal object HubProviderTimeout {
    suspend fun resolve(timeoutMillis: Long = 30_000L, block: suspend () -> Unit): Boolean {
        return withTimeoutOrNull(timeoutMillis) { block(); true } ?: false
    }
}

internal object HubContentRouting {
    fun category(genres: List<Int>): String = when {
        99 in genres -> "documentary"
        16 in genres -> "animation"
        else -> "general"
    }

    fun type(isMovie: Boolean, genres: List<Int>): TvType = when (category(genres)) {
        "documentary" -> TvType.Documentary
        "animation" -> if (isMovie) TvType.AnimeMovie else TvType.Anime
        else -> if (isMovie) TvType.Movie else TvType.TvSeries
    }

    fun eligible(provider: MainAPI, payload: AggregatorLinkPayload): Boolean {
        val types = provider.supportedTypes
        val ordinary = if (payload.isMovie) TvType.Movie in types else TvType.TvSeries in types || TvType.AsianDrama in types
        if (payload.category == null && !payload.isMovie) return types.any {
            it in setOf(TvType.TvSeries, TvType.AsianDrama, TvType.Anime, TvType.Cartoon, TvType.Documentary)
        }
        return ordinary || when (payload.category) {
            "documentary" -> TvType.Documentary in types
            "animation" -> if (payload.isMovie) TvType.AnimeMovie in types || TvType.Cartoon in types
                else TvType.Anime in types || TvType.Cartoon in types
            else -> false
        }
    }

    fun linkData(response: LoadResponse, payload: AggregatorLinkPayload): String? {
        if (payload.isMovie) return (response as? MovieLoadResponse)?.dataUrl?.takeIf { it.isNotBlank() }
        val episodes = when (response) {
            is TvSeriesLoadResponse -> response.episodes
            is AnimeLoadResponse -> response.episodes.values.flatten()
            else -> emptyList()
        }
        // A seasonless anime episode can represent season one, never a later season.
        return episodes.firstOrNull {
            (it.season ?: 1) == payload.season && it.episode == payload.episode
        }?.data
    }
}

internal fun cacheableHubLinks(links: List<ExtractorLink>): List<ExtractorLink> =
    links.filter(HubTorrentSelector::isTorrent)

internal object HubTorrentSelector {
    fun cachedFallback(cached: List<ExtractorLink>, fresh: List<ExtractorLink>, directOrigins: Boolean): List<ExtractorLink> {
        val freshOrigins = fresh.map { it.source.lowercase(Locale.ROOT) }.toSet()
        return cached.filter {
            isTorrent(it) &&
                (it.source.lowercase(Locale.ROOT) in setOf("torrentio", "yts")) == directOrigins &&
                it.source.lowercase(Locale.ROOT) !in freshOrigins
        }
    }
    fun isTorrent(link: ExtractorLink): Boolean = link.type == ExtractorLinkType.MAGNET ||
        link.type == ExtractorLinkType.TORRENT || link.url.startsWith("magnet:", true)

    private fun params(link: ExtractorLink): Map<String, String> = link.url.substringAfter('?', "")
        .split('&').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size != 2) null else try {
                pair[0].lowercase(Locale.ROOT) to URLDecoder.decode(pair[1], "UTF-8")
            } catch (_: IllegalArgumentException) { null }
        }.toMap()

    fun swarm(link: ExtractorLink): String {
        val hash = params(link)["xt"]?.takeIf { it.startsWith("urn:btih:", true) }
            ?.substringAfterLast(':')?.uppercase(Locale.ROOT) ?: return link.url.trim()
        if (hash.matches(Regex("[A-F0-9]{40}"))) return hash
        if (hash.matches(Regex("[A-Z2-7]{32}"))) {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var buffer = 0
            var bits = 0
            val bytes = mutableListOf<Int>()
            for (char in hash) {
                buffer = (buffer shl 5) or alphabet.indexOf(char)
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    bytes.add((buffer shr bits) and 255)
                }
            }
            return bytes.joinToString("") { "%02X".format(it) }
        }
        return link.url.trim()
    }

    private fun file(link: ExtractorLink): String? = params(link)["index"]
    private fun identity(link: ExtractorLink): String = swarm(link) + ":" + (file(link) ?: "unknown")
    private fun seeds(link: ExtractorLink): Int = Regex("(\\d+)\\s+seeds", RegexOption.IGNORE_CASE)
        .find(link.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    /** Never mistake a marketing "4K" label for verified playable throughput. */
    internal fun sourceScore(link: ExtractorLink, profile: String = HubPerformanceSettings.profile): Int {
        val name = link.name.lowercase(Locale.ROOT)
        val peers = seeds(link).coerceIn(0, 100)
        var score = peers * 10
        val remux = Regex("""\b(remux|bdremux)\b""").containsMatchIn(name)
        val bluRay = Regex("""\b(blu[ .-]?ray|bdrip)\b""").containsMatchIn(name)
        val demanding = Regex("""\b(dovi|dv|truehd|dts-hd|hevc|x265)\b""").containsMatchIn(name)
        val isDv = Regex("""\b(dovi|dv|dolby[ -]?vision)\b""").containsMatchIn(name)
        val isHdr = Regex("""\b(hdr|hdr10|hdr10\+|atmos)\b""").containsMatchIn(name)
        val isQxr = Regex("""\b(qxr)\b""").containsMatchIn(name) || link.url.contains("qxr", ignoreCase = true)

        if (remux) score += if (profile == HubPerformanceSettings.FEATURE) 170 else 30
        else if (bluRay) score += 25

        // QxR MKVs contain zlib compressed subtitle tracks, causing ExoPlayer ParserException (3001)
        if (isQxr) score = -99999

        // Prioritize HDR10/Atmos (standard HEVC Main 10) for ExoPlayer hardware decoding compatibility.
        // Pure DV Profile 8 causes format_supported=NO_EXCEEDS_CAPABILITIES (4001) in Android MediaCodec.
        if (isHdr && !isDv) score += 60
        if (isDv) score -= 150

        if (profile == HubPerformanceSettings.COMPATIBLE && demanding) score -= 90
        return score
    }
    private val rank = compareByDescending<ExtractorLink> { sourceScore(it) }
        .thenBy { it.source.lowercase(Locale.ROOT) }.thenBy { swarm(it) }
        .thenBy { file(it).orEmpty() }.thenBy { it.url }

    fun select(candidates: List<ExtractorLink>, emitted: List<ExtractorLink> = emptyList()): List<ExtractorLink> {
        val usedFiles = emitted.filter(::isTorrent).map(::identity).toMutableSet()
        val result = mutableListOf<ExtractorLink>()
        val canonical = candidates.filter(::isTorrent).groupBy(::identity).values.map { sameSwarm ->
            val best = sameSwarm.sortedWith(rank).first()
            // Prefer YTS only when its file selection has exactly the same semantics.
            sameSwarm.sortedWith(rank).firstOrNull { it.source.equals("YTS", true) && file(it) == file(best) } ?: best
        }
        for ((quality, group) in canonical.groupBy { it.quality }.toSortedMap()) {
            // A single fast origin must not occupy every 2160p slot before a
            // later Blu-ray/REMUX source can be emitted by another origin.
            val highResolution = quality >= 2160
            val totalQuota = if (highResolution) 4 else 3
            val originQuota = if (highResolution) 3 else 3
            var remaining = (totalQuota - emitted.count { isTorrent(it) && it.quality == quality }).coerceAtLeast(0)
            val originCounts = emitted.filter { isTorrent(it) && it.quality == quality }
                .groupingBy { it.source.lowercase(Locale.ROOT) }.eachCount().toMutableMap()
            val sorted = group.filter { identity(it) !in usedFiles }.sortedWith(rank)
            val diverse = sorted.distinctBy { it.source.lowercase(Locale.ROOT) }
            val ordered = diverse + sorted.filter { it !in diverse }
            for (link in ordered) {
                if (remaining == 0) break
                val origin = link.source.lowercase(Locale.ROOT)
                if (originCounts.getOrDefault(origin, 0) >= originQuota) continue
                if (usedFiles.add(identity(link))) {
                    result.add(link)
                    originCounts[origin] = originCounts.getOrDefault(origin, 0) + 1
                    remaining--
                }
            }
        }
        return result
    }
}

/** Serializes callback delivery and applies the same torrent policy to fresh and cached links. */
internal class HubLinkCollector(
    private val torrentEnabled: Boolean,
    private val debridEnabled: Boolean,
    private val callback: (ExtractorLink) -> Unit
) {
    private val links = mutableListOf<ExtractorLink>()
    private val seen = mutableSetOf<String>()

    @Synchronized fun snapshot(): List<ExtractorLink> = links.toList()

    @Synchronized fun direct(link: ExtractorLink) {
        if (HubTorrentSelector.isTorrent(link)) return
        if (link.source.equals("YTS", true) && !torrentEnabled) return
        if (link.source.equals("Torrentio", true) && !torrentEnabled && !debridEnabled) return
        publish(link)
    }

    @Synchronized fun torrents(candidates: List<ExtractorLink>) {
        if (!torrentEnabled) return
        HubTorrentSelector.select(candidates, links).forEach(::publish)
    }

    private fun publish(link: ExtractorLink) {
        if (link.url.isBlank() || !seen.add(link.url.trim())) return
        links.add(link)
        callback(link)
    }
}
