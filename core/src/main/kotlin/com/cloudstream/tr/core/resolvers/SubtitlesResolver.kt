package com.cloudstream.tr.core.resolvers

import com.cloudstream.tr.core.diagnostics.DiagnosticCategory
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.cloudstream.tr.core.diagnostics.DiagnosticStage
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenSubtitlesResponse(
    @JsonProperty("subtitles") val subtitles: List<OpenSubtitleItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class OpenSubtitleItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("subtitleFileName") val subtitleFileName: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemetaResponse(
    @JsonProperty("metas") val metas: List<CinemetaMeta>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemetaMeta(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("releaseInfo") val releaseInfo: String? = null
) {
    val effectiveId: String? get() = imdbId ?: id
    val effectiveYear: String? get() = year ?: releaseInfo
}

object SubtitlesResolver {
    private val BASE_URLS = listOf(
        "https://opensubtitles-v3.strem.io/subtitles"
    )

    suspend fun resolveImdbId(title: String, year: Int? = null, isMovie: Boolean = true): String? {
        // Strip parenthetical text like (İrlandalı) or (1989) to prevent search failure in Cinemeta
        val baseTitle = title.replace(Regex("""\([^)]*\)"""), "").trim()
        val cleanTitle = (if (baseTitle.isNotBlank()) baseTitle else title)
            .replace(Regex("""[^\w\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (cleanTitle.isBlank()) return null
        val type = if (isMovie) "movie" else "series"
        return try {
            val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
            val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json"
            val resp = app.get(url, timeout = 5).parsedSafe<CinemetaResponse>()
            val metas = resp?.metas.orEmpty()
            if (year != null) {
                metas.firstOrNull { it.effectiveYear?.contains(year.toString()) == true }?.effectiveId
                    ?: metas.firstOrNull()?.effectiveId
            } else {
                metas.firstOrNull()?.effectiveId
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolveTurkishSubtitles(
        imdbId: String? = null,
        title: String? = null,
        year: Int? = null,
        isMovie: Boolean = true,
        season: Int? = null,
        episode: Int? = null,
        callback: (SubtitleFile) -> Unit
    ) {
        val resolvedId = if (!imdbId.isNullOrBlank()) {
            imdbId
        } else if (!title.isNullOrBlank()) {
            resolveImdbId(title, year, isMovie)
        } else {
            null
        } ?: return

        val type = if (isMovie) "movie" else "series"
        val idPath = if (isMovie) resolvedId else "$resolvedId:$season:$episode"

        val seenUrls = mutableSetOf<String>()

        for (baseUrl in BASE_URLS) {
            val targetUrl = "$baseUrl/$type/$idPath.json"

            try {
                val response = app.get(targetUrl, timeout = 8).parsedSafe<OpenSubtitlesResponse>()
                val items = response?.subtitles ?: continue

                val turkishSubs = items.filter { sub ->
                    val lang = sub.lang?.lowercase()
                    lang == "tur" || lang == "tr" || lang == "turkish"
                }

                if (turkishSubs.isEmpty()) continue

                var index = 1
                for (sub in turkishSubs) {
                    val rawUrl = sub.url?.takeIf { it.isNotBlank() } ?: continue

                    // ExoPlayer requires .srt or .vtt extension to reliably detect the subtitle parser
                    val finalUrl = when {
                        rawUrl.endsWith(".srt", ignoreCase = true) || rawUrl.endsWith(".vtt", ignoreCase = true) -> rawUrl
                        rawUrl.contains("?") -> "$rawUrl&format=.srt"
                        else -> "$rawUrl.srt"
                    }

                    if (!seenUrls.add(finalUrl)) continue

                    val fileName = sub.subtitleFileName ?: ""
                    val targetStr = (title ?: "") + " " + fileName + " " + (sub.id ?: "")
                    val isBluray = targetStr.contains("bluray", ignoreCase = true) || targetStr.contains("bdrip", ignoreCase = true)
                    val isWebdl = targetStr.contains("web-dl", ignoreCase = true) || targetStr.contains("webrip", ignoreCase = true)

                    val label = when {
                        index == 1 -> "Türkçe"
                        isBluray -> "Türkçe (BluRay)"
                        isWebdl -> "Türkçe (WEB-DL)"
                        else -> "Türkçe #$index"
                    }

                    callback(newSubtitleFile(label, finalUrl))
                    index++
                }

                // If subtitles were found from this provider mirror, no need to query other mirrors
                if (seenUrls.isNotEmpty()) break

            } catch (e: Exception) {
                DiagnosticLogger.log(
                    provider = "SubtitlesResolver",
                    stage = DiagnosticStage.LOAD,
                    category = DiagnosticCategory.NETWORK,
                    message = "Failed to fetch Turkish subtitles from $baseUrl for $resolvedId: ${e.message}"
                )
            }
        }
    }
}
