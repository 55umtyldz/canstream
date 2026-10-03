package com.cloudstream.tr.hub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URLDecoder
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

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
    private fun seeds(link: ExtractorLink): Int = Regex("(\\d+)\\s+seeds", RegexOption.IGNORE_CASE)
        .find(link.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    private val rank = compareByDescending<ExtractorLink> { seeds(it) }
        .thenBy { it.source.lowercase(Locale.ROOT) }.thenBy { swarm(it) }
        .thenBy { file(it).orEmpty() }.thenBy { it.url }

    fun select(candidates: List<ExtractorLink>, emitted: List<ExtractorLink> = emptyList()): List<ExtractorLink> {
        val usedSwarms = emitted.filter(::isTorrent).map(::swarm).toMutableSet()
        val result = mutableListOf<ExtractorLink>()
        val canonical = candidates.filter(::isTorrent).groupBy(::swarm).values.map { sameSwarm ->
            val best = sameSwarm.sortedWith(rank).first()
            // Prefer YTS only when its file selection has exactly the same semantics.
            sameSwarm.sortedWith(rank).firstOrNull { it.source.equals("YTS", true) && file(it) == file(best) } ?: best
        }
        for ((quality, group) in canonical.groupBy { it.quality }.toSortedMap()) {
            val remaining = (3 - emitted.count { isTorrent(it) && it.quality == quality }).coerceAtLeast(0)
            val sorted = group.filter { swarm(it) !in usedSwarms }.sortedWith(rank)
            val diverse = sorted.distinctBy { it.source.lowercase(Locale.ROOT) }
            val ordered = diverse + sorted.filter { it !in diverse }
            for (link in ordered.take(remaining)) {
                if (usedSwarms.add(swarm(link))) result.add(link)
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
