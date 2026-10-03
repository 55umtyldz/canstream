package com.cloudstream.tr.core.resolvers

import com.cloudstream.tr.core.diagnostics.DiagnosticCategory
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.cloudstream.tr.core.diagnostics.DiagnosticStage
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException

data class TorrentioResponse(
    @param:JsonProperty("streams") val streams: List<TorrentioStream>? = null
)

data class TorrentioBehaviorHints(
    @param:JsonProperty("filename") val filename: String? = null,
    @param:JsonProperty("bingeGroup") val bingeGroup: String? = null
)

data class TorrentioStream(
    @param:JsonProperty("name") val name: String? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("infoHash") val infoHash: String? = null,
    @param:JsonProperty("fileIdx") val fileIdx: Int? = null,
    @param:JsonProperty("behaviorHints") val behaviorHints: TorrentioBehaviorHints? = null,
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("sources") val sources: List<String>? = null
)

object TorrentioResolver {
    internal fun keepTopThreePerQuality(links: List<ExtractorLink>): List<ExtractorLink> {
        val qualityCounts = mutableMapOf<Int, Int>()
        return links.filter { link ->
            val count = qualityCounts.getOrDefault(link.quality, 0)
            if (count >= 3) {
                false
            } else {
                qualityCounts[link.quality] = count + 1
                true
            }
        }
    }

    internal fun qualityOf(vararg descriptions: String?): Int {
        for (description in descriptions) {
            val match = Regex("""\b(4K|[0-9]{3,4}p)\b""", RegexOption.IGNORE_CASE)
                .find(description.orEmpty()) ?: continue
            return if (match.value.equals("4K", ignoreCase = true)) 2160
            else match.value.dropLast(1).toIntOrNull() ?: Qualities.Unknown.value
        }
        return Qualities.Unknown.value
    }

    internal fun seedCount(title: String): Int {
        val value = Regex("""(?:\uD83D\uDC64|\uD83D\uDC65|\bseeds?\s*[:=]?)\s*([0-9][0-9,]*)""", RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.get(1)
            ?: Regex("""([0-9][0-9,]*)\s+seeds?\b""", RegexOption.IGNORE_CASE)
                .find(title)?.groupValues?.get(1)
        return value?.replace(",", "")?.toIntOrNull() ?: 0
    }

    suspend fun resolve(imdbId: String, isMovie: Boolean, season: Int? = null, episode: Int? = null): List<ExtractorLink> {
        if (!Regex("tt[0-9]+").matches(imdbId)) return emptyList()
        val type = if (isMovie) "movie" else "series"
        val idPath = if (isMovie) imdbId else "$imdbId:$season:$episode"
        val options = "qualityfilter=scr,cam|sort=quality|limit=10"
        val prefix = DebridConfig.getActiveDebridPrefix()?.let { "$it|" } ?: ""
        val fullOptions = "$prefix$options"
        val url = "https://torrentio.strem.fun/$fullOptions/stream/$type/$idPath.json"
        val hasDebrid = DebridConfig.isDebridEnabled

        try {
            val response = app.get(url, timeout = 10).parsedSafe<TorrentioResponse>()
            val streams = response?.streams ?: return emptyList()

            return mapStreams(streams, hasDebrid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticLogger.log(
                provider = "Torrentio", stage = DiagnosticStage.LOAD,
                category = DiagnosticCategory.NETWORK,
                message = "Torrentio request failed (${e.javaClass.simpleName})"
            )
            return emptyList()
        }
    }

    internal suspend fun mapStreams(streams: List<TorrentioStream>, hasDebrid: Boolean): List<ExtractorLink> {
        return streams.mapNotNull { stream ->
            val rawTitle = stream.title ?: stream.name ?: "Unknown"

            // Filter out CAM/TS/SCR if any slipped through
            if (Regex("""\b(CAM|HDCAM|TELESYNC|TS|SCR)\b""", RegexOption.IGNORE_CASE).containsMatchIn(rawTitle)) {
                return@mapNotNull null
            }

            val seedCount = seedCount(rawTitle)
            val isDirectUrl = stream.url?.let { it.startsWith("https://", true) || it.startsWith("http://", true) } == true
            if (!hasDebrid && !isDirectUrl && seedCount < 3) return@mapNotNull null

            val sizeMatch = Regex("""\b(\d+(?:\.\d+)?\s*[GgMm]B)\b""").find(rawTitle)
            val size = sizeMatch?.groupValues?.get(1) ?: ""

            val mappedQuality = qualityOf(stream.title, stream.behaviorHints?.filename, stream.name)
            val resolution = if (mappedQuality == Qualities.Unknown.value) "Unknown" else "${mappedQuality}p"

            val isRemux = rawTitle.contains("REMUX", ignoreCase = true)
            val isDV = rawTitle.contains("DV", ignoreCase = true) || rawTitle.contains("Dolby Vision", ignoreCase = true)
            val isHDR10Plus = rawTitle.contains("HDR10+", ignoreCase = true)
            val isHDR = rawTitle.contains("HDR", ignoreCase = true) && !isHDR10Plus
            val isTrueHD = rawTitle.contains("TrueHD", ignoreCase = true)
            val isDtsHdMa = rawTitle.contains("DTS-HD MA", ignoreCase = true) || rawTitle.contains("DTS-HD", ignoreCase = true)
            val isAtmos = rawTitle.contains("Atmos", ignoreCase = true)
            val is71 = rawTitle.contains("7.1")
            val is51 = rawTitle.contains("5.1") || rawTitle.contains("DD")

            var tags = ""
            if (isRemux) tags += " [REMUX]"
            if (isDV) tags += " [DV]"
            if (isHDR10Plus) tags += " [HDR10+]"
            else if (isHDR) tags += " [HDR]"

            if (isTrueHD && isAtmos && is71) tags += " [TrueHD Atmos 7.1]"
            else if (isDtsHdMa && is71) tags += " [DTS-HD MA 7.1]"
            else if (isAtmos && is71) tags += " [Atmos 7.1]"
            else if (isAtmos) tags += " [Atmos]"
            else if (is71) tags += " [7.1]"
            else if (is51) tags += " [5.1]"

            val seedStr = " ($seedCount seeds)"
            val prefixStr = if (hasDebrid) "🚀 Debrid " else "Torrentio • "
            val displayName = "$prefixStr$resolution$tags ${if (size.isNotBlank()) "[$size] " else ""}$seedStr".trim()

            if (isDirectUrl) {
                return@mapNotNull com.lagradost.cloudstream3.utils.newExtractorLink(
                    source = "Torrentio",
                    name = displayName,
                    url = stream.url ?: return@mapNotNull null,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = mappedQuality
                }
            }

            val hash = stream.infoHash?.takeIf { TorrentTrackers.isValidHash(it) } ?: return@mapNotNull null
            val rawFirstLine = rawTitle.lines().firstOrNull()?.trim() ?: "Torrent"
            val sanitizedTitle = rawFirstLine.replace(Regex("""[^\w\s\.\-\(\)\[\]]"""), "").trim().ifBlank { "Torrent" }
            val cleanTitle = stream.behaviorHints?.filename?.takeIf { it.isNotBlank() } ?: sanitizedTitle
            val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")

            val fileIndex = stream.fileIdx ?: 0
            if (fileIndex < 0) return@mapNotNull null
            if (stream.fileIdx == null) {
                DiagnosticLogger.log(
                    provider = "Torrentio", stage = DiagnosticStage.EXTRACTOR,
                    category = DiagnosticCategory.SOURCE_DISCOVERY,
                    message = "Torrent candidate has no file index; preserving index=0 fallback"
                )
            }
            val magnetUrl = "magnet:?xt=urn:btih:$hash&dn=$encodedTitle&index=$fileIndex${TorrentTrackers.magnetParams(stream.sources.orEmpty())}"

            com.lagradost.cloudstream3.utils.newExtractorLink(
                source = "Torrentio",
                name = displayName,
                url = magnetUrl,
                type = ExtractorLinkType.MAGNET
            ) {
                this.quality = mappedQuality
            }
        }
    }
}
