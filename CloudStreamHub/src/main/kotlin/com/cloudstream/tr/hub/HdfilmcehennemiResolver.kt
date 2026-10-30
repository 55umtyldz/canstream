package com.cloudstream.tr.hub

import com.cloudstream.tr.core.extractors.CloseLoadExtractor
import com.cloudstream.tr.core.network.SafeHttpClient
import com.cloudstream.tr.core.network.StreamValidator
import com.cloudstream.tr.core.network.DynamicDomainResolver
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.util.regex.Pattern

object HdfilmcehennemiResolver {
    private var mainUrl = "https://www.hdfilmcehennemi.nl"
    private const val PROVIDER_NAME = "HDFilmCehennemi"
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
        try {
            mainUrl = DynamicDomainResolver.resolve(PROVIDER_NAME, mainUrl)
        } catch (_: Exception) {}

        val sNum = season ?: 1
        val epNum = episode ?: 1
        val searchQueries = listOfNotNull(originalTitle, title).distinct()

        for (query in searchQueries) {
            try {
                // 1. Sitenin kendi kullandığı fetch arama API'si
                val searchUrl = "$mainUrl/search?q=$query"
                val res = app.get(
                    searchUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "X-Requested-With" to "fetch",
                        "Content-Type" to "application/json",
                        "Referer" to "$mainUrl/"
                    )
                ).parsedSafe<SearchApiResponse>()

                val matchedShowUrls = mutableListOf<String>()

                for (rawHtml in res?.results.orEmpty()) {
                    val doc = Jsoup.parse(rawHtml)
                    val a = doc.selectFirst("a.search-result") ?: doc.selectFirst("a") ?: continue
                    val href = a.attr("href")
                    val isTv = href.contains("/dizi/")

                    if ((isMovie && !isTv) || (!isMovie && isTv)) {
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith("/")) "" else "/"}$href"
                        matchedShowUrls.add(fullUrl)
                    }
                }

                // Eğer API sonuç vermezse HTML aramasını dene
                if (matchedShowUrls.isEmpty()) {
                    try {
                        val htmlDoc = app.get("$mainUrl/?s=$query", headers = mapOf("User-Agent" to USER_AGENT)).document
                        for (el in htmlDoc.select("a.poster, a.card, div.poster a")) {
                            val href = el.attr("href")
                            val isTv = href.contains("/dizi/")
                            if ((isMovie && !isTv) || (!isMovie && isTv)) {
                                val fullUrl = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith("/")) "" else "/"}$href"
                                matchedShowUrls.add(fullUrl)
                            }
                        }
                    } catch (_: Exception) {}
                }

                for (showUrl in matchedShowUrls.distinct()) {
                    val targetPageUrls = mutableListOf<String>()

                    if (isMovie) {
                        targetPageUrls.add(showUrl)
                    } else {
                        try {
                            val showDoc = app.get(showUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
                            for (a in showDoc.select("div.seasons-tab-content a.mini-poster, div.seasons a[href*='bolum']")) {
                                val epHref = a.attr("href")
                                val epTitle = a.selectFirst(".mini-poster-title")?.text()?.trim() ?: a.text().trim()

                                val sMatch = Regex("""(\d+)\.\s*Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                                val eMatch = Regex("""(\d+)\.\s*B[öo]l[üu]m""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                                if (sMatch == sNum && eMatch == epNum) {
                                    val fullEpHref = if (epHref.startsWith("http")) epHref else "$mainUrl${if (epHref.startsWith("/")) "" else "/"}$epHref"
                                    targetPageUrls.add(fullEpHref)
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    for (pageUrl in targetPageUrls.distinct()) {
                        extractStreams(pageUrl, callback)
                    }

                    if (targetPageUrls.isNotEmpty()) break
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun extractStreams(pageUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            val doc = app.get(pageUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")).document
            val iframes = mutableListOf<String>()

            for (iframe in doc.select("iframe, [data-frame]")) {
                val src = iframe.attr("data-frame").ifEmpty { iframe.attr("data-src").ifEmpty { iframe.attr("src") } }
                if (src.isNotBlank() && !src.contains("youtube.com") && !src.contains("youtu.be")) {
                    val fixed = if (src.startsWith("http")) src else if (src.startsWith("//")) "https:$src" else "$mainUrl${if (src.startsWith("/")) "" else "/"}$src"
                    iframes.add(fixed)
                }
            }

            for (iframeUrl in iframes.distinct()) {
                val host = Regex("""https?://[^/]+""").find(iframeUrl)?.value ?: mainUrl
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
                        provider = PROVIDER_NAME
                    )

                    if (preflight.isValid || preflight.status == com.cloudstream.tr.core.network.ValidationStatus.INDETERMINATE) {
                        callback(
                            newExtractorLink(
                                source = PROVIDER_NAME,
                                name = "HDFilmCehennemi [TR Dublaj / DUAL]",
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
