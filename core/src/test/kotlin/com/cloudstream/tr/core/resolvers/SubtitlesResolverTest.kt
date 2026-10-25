package com.cloudstream.tr.core.resolvers

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubtitlesResolverTest {

    @Test
    fun testParseOpenSubtitlesResponse() {
        val sampleJson = """
        {
          "subtitles": [
            {
              "id": "4414982",
              "url": "https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1952971600",
              "SubEncoding": "UTF-8",
              "lang": "eng",
              "subtitleFileName": "TheGuard_2011_DoNE.EN.srt"
            },
            {
              "id": "4414756",
              "url": "https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1952971295",
              "SubEncoding": "UTF-8",
              "lang": "tur",
              "subtitleFileName": "the.guard-done.srt"
            },
            {
              "id": "4439402",
              "url": "https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/1952997096",
              "SubEncoding": "UTF-8",
              "lang": "tr",
              "subtitleFileName": "The.Guard.2011.BluRay.720p.DTS.x264-CHD.srt"
            }
          ],
          "cacheMaxAge": 172800,
          "staleRevalidate": 518400
        }
        """.trimIndent()

        val parsed = AppUtils.tryParseJson<OpenSubtitlesResponse>(sampleJson)
        assertNotNull("OpenSubtitlesResponse failed to parse with unknown fields", parsed)
        assertNotNull("Subtitles list should not be null", parsed?.subtitles)
        assertEquals(3, parsed?.subtitles?.size)

        val turkishSubs = parsed?.subtitles?.filter { sub ->
            val lang = sub.lang?.lowercase()
            lang == "tur" || lang == "tr" || lang == "turkish"
        } ?: emptyList()

        assertEquals(2, turkishSubs.size)
    }

    @Test
    fun testLiveResolveTurkishSubtitlesForTheGuard() = runBlocking {
        val collectedSubs = mutableListOf<SubtitleFile>()
        SubtitlesResolver.resolveTurkishSubtitles(
            imdbId = "tt1540133",
            isMovie = true,
            title = "The Guard",
            callback = { sub ->
                collectedSubs.add(sub)
            }
        )
        assertTrue("Subtitles for The Guard should not be empty", collectedSubs.isNotEmpty())
        assertTrue("Subtitles should end with .srt for ExoPlayer compatibility", collectedSubs.all { it.url.endsWith(".srt") || it.url.endsWith(".vtt") })
        assertTrue("Subtitles should have clean Turkish label", collectedSubs.any { it.lang.startsWith("Türkçe") })
    }

    @Test
    fun testLiveResolveTurkishSubtitlesWithTitleOnly() = runBlocking {
        val collectedSubs = mutableListOf<SubtitleFile>()
        SubtitlesResolver.resolveTurkishSubtitles(
            title = "The Guard",
            year = 2011,
            isMovie = true,
            callback = { sub ->
                collectedSubs.add(sub)
            }
        )
        assertTrue("Subtitles resolved via title should not be empty", collectedSubs.isNotEmpty())
    }
}
