package com.cloudstream.tr.core.resolvers

import com.cloudstream.tr.core.diagnostics.DiagnosticCategory
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.cloudstream.tr.core.diagnostics.DiagnosticStage
import com.cloudstream.tr.core.network.StreamValidator
import com.cloudstream.tr.core.network.ValidationStatus
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

data class RemoteDomainsConfig(
    @param:JsonProperty("4khdhub") val fourKhub: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HdHubStream(
    @param:JsonProperty("name") val name: String? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("url") val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HdHubResponse(
    @param:JsonProperty("streams") val streams: List<HdHubStream>? = null
)

object FourKhubResolver {
    private const val DOMAINS_CONFIG_URL = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
    private const val HDHUB_STREMIO_BASE = "https://hdhub.thevolecitor.qzz.io/eyJ0b3Jib3giOiJ1bnNldCIsInF1YWxpdGllcyI6IjIxNjBwLDEwODBwIiwic29ydCI6ImRlc2MiLCJjb250ZW50IjoibGF0aW4sYXNpYW4ifQ"
    private val defaultDomains = listOf(
        "https://4khdhub.one",
        "https://4khdhub.com"
    )

    private var activeDomains: List<String>? = null

    private fun normalizeSearchResultUrl(href: String, domain: String): String = when {
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> "$domain$href"
        else -> href
    }

    private suspend fun getBaseDomains(): List<String> {
        activeDomains?.let { return it }

        var remoteDomain: String? = null
        try {
            val cfg = app.get(DOMAINS_CONFIG_URL, timeout = 5).parsedSafe<RemoteDomainsConfig>()
            remoteDomain = cfg?.fourKhub?.takeIf { it.isNotBlank() }?.trimEnd('/')
        } catch (_: Exception) {}

        activeDomains = listOfNotNull(defaultDomains.first(), remoteDomain, defaultDomains.last()).distinct()
        return activeDomains!!
    }

    suspend fun resolveHdHubDirect(
        imdbId: String,
        isMovie: Boolean,
        season: Int? = null,
        episode: Int? = null
    ): List<ExtractorLink> {
        val type = if (isMovie) "movie" else "series"
        val idPath = if (isMovie) "$imdbId.json" else "$imdbId:$season:$episode.json"
        val endpoint = "$HDHUB_STREMIO_BASE/stream/$type/$idPath"
        return try {
            val resp = app.get(
                endpoint,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Referer" to "https://web.stremio.com/",
                    "Origin" to "https://web.stremio.com"
                ),
                timeout = 8
            ).parsedSafe<HdHubResponse>()
            val streams = resp?.streams ?: return emptyList()
            val validLinks = mutableListOf<ExtractorLink>()
            for (stream in streams) {
                var directUrl = stream.url?.takeIf { it.startsWith("http") } ?: continue
                if (directUrl.contains("pixeldrain.com/u/")) {
                    directUrl = directUrl.replace("pixeldrain.com/u/", "pixeldrain.dev/api/file/") + "?download"
                } else if (directUrl.contains("pixeldrain.dev/u/")) {
                    directUrl = directUrl.replace("pixeldrain.dev/u/", "pixeldrain.dev/api/file/") + "?download"
                }
                val rawTitle = (stream.title ?: "").ifBlank { stream.name ?: "" }
                val nameLower = ((stream.name ?: "") + " " + rawTitle).lowercase()
                val is4K = nameLower.contains("2160") || nameLower.contains("4k")
                val quality = if (is4K) Qualities.P2160.value else Qualities.P1080.value
                val serverName = when {
                    directUrl.contains("pixeldrain") -> "PixelDrain"
                    directUrl.contains("hubcloud") -> "HubCloud"
                    directUrl.contains("fastdl") -> "FastDL"
                    else -> "FastCloud"
                }
                val sizeMatch = Regex("""\b(\d+(?:\.\d+)?\s*[GgMm]B)\b""").find(rawTitle)
                val sizeStr = sizeMatch?.groupValues?.get(1)?.let { " [$it]" } ?: ""
                val qualityStr = if (is4K) "4K UHD" else "1080p"
                val displayName = "⚡ 4KHDHub • $serverName $qualityStr$sizeStr"

                val preflight = StreamValidator.validateStream(
                    url = directUrl,
                    headers = mapOf("Referer" to "https://web.stremio.com/", "Origin" to "https://web.stremio.com"),
                    provider = "FourKhubResolver"
                )
                if (preflight.status == ValidationStatus.VALID && preflight.hasMediaEvidence) {
                    validLinks.add(
                        newExtractorLink(
                            source = "4KHDHub",
                            name = displayName,
                            url = directUrl,
                            type = preflight.streamType
                        ) {
                            this.referer = "https://web.stremio.com/"
                            this.quality = quality
                            this.headers = mapOf("Referer" to "https://web.stremio.com/", "Origin" to "https://web.stremio.com")
                        }
                    )
                }
            }
            validLinks
        } catch (e: Exception) {
            DiagnosticLogger.log(
                provider = "FourKhubResolver",
                stage = DiagnosticStage.LOAD,
                category = DiagnosticCategory.NETWORK,
                message = "Failed to fetch direct HDHub streams for $imdbId: ${e.message}"
            )
            emptyList()
        }
    }

    suspend fun resolve(
        title: String,
        year: Int? = null,
        isMovie: Boolean,
        season: Int? = null,
        episode: Int? = null,
        imdbId: String? = null
    ): List<ExtractorLink> {
        if (!imdbId.isNullOrBlank()) {
            val directStreams = resolveHdHubDirect(imdbId, isMovie, season, episode)
            if (directStreams.isNotEmpty()) return directStreams
        }

        if (title.isBlank()) return emptyList()

        val cleanTitle = title.replace(Regex("""[^\w\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
        for (domain in getBaseDomains()) {
            try {
                val encodedQuery = URLEncoder.encode(cleanTitle, "UTF-8")
                val searchUrl = "$domain/?s=$encodedQuery"
                val searchDoc = Jsoup.parse(app.get(searchUrl, timeout = 10).text)

                // Extract articles / entries (support both legacy WP and new card-grid Tailwind layouts)
                val entries = searchDoc.select("div.card-grid a, div.movie-card a, article, div.post-item, div.entry-content, h2.entry-title a, a[href*='-movie-'], a[href*='-series-']")
                var matchedUrl: String? = null

                for (entry in entries) {
                    val linkTag = if (entry.tagName() == "a") entry else entry.selectFirst("h2 a, a[rel='bookmark'], a")
                    val itemTitle = linkTag?.text() ?: ""
                    val rawHref = linkTag?.attr("href") ?: ""
                    if (rawHref.isBlank()) continue
                    val itemHref = normalizeSearchResultUrl(rawHref, domain)

                    // Check title match
                    val isTitleMatch = itemTitle.contains(cleanTitle, ignoreCase = true) ||
                            cleanTitle.split(" ").all { word -> itemTitle.contains(word, ignoreCase = true) }

                    val isYearMatch = year == null || itemTitle.contains(year.toString())

                    if (isTitleMatch && isYearMatch) {
                        matchedUrl = itemHref
                        break
                    }
                }

                val targetPageUrl = matchedUrl?.takeIf { it.isNotBlank() } ?: continue
                val detailDoc = Jsoup.parse(app.get(targetPageUrl, timeout = 10).text)

                // Extract HubCloud / HubDrive / download links
                val downloadHrefs = detailDoc.select("a[href*='hubcloud'], a[href*='hubdrive'], a[href*='greenmotors.club'], div.download-item a, a.btn")
                    .map { it.attr("href") }
                    .filter { it.isNotBlank() && (it.contains("hubcloud") || it.contains("hubdrive") || it.contains("drive") || it.contains("greenmotors")) }
                    .distinct()

                if (downloadHrefs.isEmpty()) continue

                val links = mutableListOf<ExtractorLink>()

                for (hubUrl in downloadHrefs.take(4)) {
                    try {
                        var hubPageHtml = app.get(hubUrl, timeout = 10).text
                        var hubDoc = Jsoup.parse(hubPageHtml)
                        var buttonPageUrl = hubUrl

                        if (hubUrl.contains("greenmotors.club")) {
                            val nextUrl = hubDoc.select("a[href*='hubcloud'], a[href*='hubdrive']").firstOrNull()?.attr("href") ?: hubDoc.select("a.btn, a[rel='nofollow']").firstOrNull()?.attr("href")
                            if (nextUrl != null && nextUrl.isNotBlank()) {
                                buttonPageUrl = URI(hubUrl).resolve(nextUrl).toString()
                                hubPageHtml = app.get(buttonPageUrl, timeout = 10).text
                                hubDoc = Jsoup.parse(hubPageHtml)
                            }
                        }

                        // Find download buttons: FSL Server, Download File (HubCloud), V-Cloud
                        val buttons = hubDoc.select("div.card-body a.btn, a.btn")
                        val headerText = hubDoc.select("div.card-header, h1, title").text()

                        for (btn in buttons) {
                            val btnText = btn.text()
                            val btnHref = btn.attr("href")
                            if (btnHref.isBlank() || !btnHref.startsWith("http")) continue

                            val isFsl = btnText.contains("FSL Server", ignoreCase = true)
                            val isHubCloud = btnText.contains("Download", ignoreCase = true) || btnText.contains("HubCloud", ignoreCase = true)
                            val isVCloud = btnText.contains("V-Cloud", ignoreCase = true) || btnText.contains("VCloud", ignoreCase = true)

                            if (!isFsl && !isHubCloud && !isVCloud) continue

                            val serverType = when {
                                isFsl -> "FSL Server"
                                isVCloud -> "V-Cloud"
                                else -> "Hub-Cloud"
                            }

                            // Technology and resolution tags
                            val fullInfo = "$headerText $btnText"
                            val is4K = fullInfo.contains("4K", ignoreCase = true) || fullInfo.contains("2160p", ignoreCase = true)
                            val is1080p = fullInfo.contains("1080p", ignoreCase = true)
                            val isAtmos = fullInfo.contains("Atmos", ignoreCase = true)
                            val isDD = fullInfo.contains("DD5", ignoreCase = true) || fullInfo.contains("DD 5", ignoreCase = true) || fullInfo.contains("DD 7", ignoreCase = true) || fullInfo.contains("5.1", ignoreCase = true)
                            val isHevc = fullInfo.contains("HEVC", ignoreCase = true) || fullInfo.contains("x265", ignoreCase = true)
                            val isBluRay = fullInfo.contains("BluRay", ignoreCase = true) || fullInfo.contains("Blu-Ray", ignoreCase = true)

                            var techTags = ""
                            if (isHevc) techTags += " HEVC"
                            if (isBluRay) techTags += " Blu-Ray"
                            if (isAtmos) techTags += " Atmos"
                            if (isDD && !isAtmos) techTags += " DD 5.1"

                            val qualityLabel = when {
                                is4K -> "4K"
                                is1080p -> "1080p"
                                else -> "HD"
                            }

                            val mappedQuality = when (qualityLabel) {
                                "4K" -> Qualities.P2160.value
                                "1080p" -> Qualities.P1080.value
                                else -> Qualities.P720.value
                            }

                            val displayName = "⚡ 4KHDHub $serverType [$qualityLabel$techTags]".trim()
                            val preflight = StreamValidator.validateStream(
                                url = btnHref,
                                headers = mapOf("Referer" to buttonPageUrl),
                                provider = "FourKhubResolver"
                            )
                            if (preflight.status != ValidationStatus.VALID || !preflight.hasMediaEvidence) continue

                            links.add(
                                newExtractorLink(
                                    source = "4KHDHub",
                                    name = displayName,
                                    url = btnHref,
                                    type = preflight.streamType
                                ) {
                                    this.referer = buttonPageUrl
                                    this.quality = mappedQuality
                                }
                            )
                        }
                    } catch (e: Exception) {
                        DiagnosticLogger.log(
                            provider = "FourKhubResolver",
                            stage = DiagnosticStage.LOAD,
                            category = DiagnosticCategory.EXTRACTOR,
                            message = "Error resolving HubCloud page $hubUrl: ${e.message}"
                        )
                    }
                }

                if (links.isNotEmpty()) return links
            } catch (e: Exception) {
                DiagnosticLogger.log(
                    provider = "FourKhubResolver",
                    stage = DiagnosticStage.SEARCH,
                    category = DiagnosticCategory.NETWORK,
                    message = "Failed to search 4KHDHub at $domain for $cleanTitle: ${e.message}"
                )
            }
        }

        return emptyList()
    }
}
