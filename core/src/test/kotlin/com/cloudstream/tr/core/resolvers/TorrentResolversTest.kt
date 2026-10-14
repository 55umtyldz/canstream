package com.cloudstream.tr.core.resolvers

import com.cloudstream.tr.core.diagnostics.DiagnosticEvent
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class TorrentResolversTest {
    private val hash = "a".repeat(40)

    private fun stream(index: Int? = 0, title: String = "Movie 1080p\n👤 693"): TorrentioStream =
        TorrentioStream(title = title, infoHash = hash, fileIdx = index)

    @Test
    fun trackersPreserveUpstreamAndFallbacksWithoutDhtOrDuplicates() {
        val upstream = "https://tracker.example/announce?passkey=one&mode=two"
        val trackers = TorrentTrackers.magnetParams(listOf(
            "tracker:$upstream", "tracker:$upstream", "dht:$hash", "file:///tmp/tracker",
            "tracker:${TorrentTrackers.list.first()}"
        )).split("&tr=").drop(1).map { URLDecoder.decode(it, "UTF-8") }
        assertEquals(upstream, trackers.first())
        assertEquals(1, trackers.count { it == upstream })
        assertEquals(1, trackers.count { it == TorrentTrackers.list.first() })
        assertTrue(trackers.containsAll(TorrentTrackers.list))
        assertFalse(trackers.any { it.startsWith("dht:") || it.startsWith("file:") })
    }

    @Test
    fun seedParsingHandlesEmojiCommasAndText() {
        assertEquals(693, TorrentioResolver.seedCount("👤 693"))
        assertEquals(1234, TorrentioResolver.seedCount("👥 1,234"))
        assertEquals(12, TorrentioResolver.seedCount("seeds: 12"))
        assertEquals(42, TorrentioResolver.seedCount("42 seeds"))
        assertEquals(0, TorrentioResolver.seedCount("Movie.2026.2160p"))
        assertNull(TorrentioResolver.reportedSeeds("Movie.2026.2160p"))
        assertEquals(0, TorrentioResolver.reportedSeeds("0 seeds"))
    }

    @Test
    fun qualityFallsBackToFilenameThenNameAndPreservesOtherResolutions() {
        assertEquals(720, TorrentioResolver.qualityOf("Movie 720p", "Movie.2160p.mkv", "1080p"))
        assertEquals(2160, TorrentioResolver.qualityOf("Movie HDR", "Movie.2160p.mkv", "1080p"))
        assertEquals(1440, TorrentioResolver.qualityOf("Movie HDR", null, "Torrentio 1440p"))
        assertEquals(480, TorrentioResolver.qualityOf("Movie 480p"))
        assertEquals(2160, TorrentioResolver.qualityOf("Movie 4K"))
        assertEquals(Qualities.Unknown.value, TorrentioResolver.qualityOf("HDR Atmos 7.1"))
    }

    @Test
    fun mappingPreservesExplicitIndicesAndReportsMissingIndex() = runBlocking {
        val events = mutableListOf<DiagnosticEvent>()
        val listener: (DiagnosticEvent) -> Unit = { events.add(it) }
        DiagnosticLogger.addListener(listener)
        try {
            val links = TorrentioResolver.mapStreams(listOf(stream(0), stream(7), stream(null)), false)
            assertEquals(2, links.size)
            assertTrue(links[0].url.contains("&index=0&"))
            assertTrue(links[1].url.contains("&index=7&"))
            assertEquals(1, events.count { it.message.contains("file index missing") })
        } finally {
            DiagnosticLogger.removeListener(listener)
        }
    }

    @Test
    fun mappingRejectsMalformedHashesAndNegativeIndexButKeepsDvAndAllCandidates() = runBlocking {
        val streams = (0..3).map { stream(it, "Movie 2160p DV HDR TrueHD Atmos 7.1\n👤 693") } +
            listOf(stream(-1), stream().copy(infoHash = "invalid&index=1"))
        val links = TorrentioResolver.mapStreams(streams, false)
        assertEquals(4, links.size)
        assertTrue(links.all { it.quality == 2160 && it.name.contains("[DV]") })
    }

    @Test
    fun unknownSeedCandidatesAndExplicitDolbyVisionTags() = runBlocking {
        val unknown = TorrentioResolver.mapStreams(listOf(stream(3, "Movie.2160p.REMUX")), false)
        assertEquals(1, unknown.size)
        assertTrue(unknown.single().name.contains("seeds unknown"))
        assertFalse(unknown.single().name.contains("[DV]"))
        val dolby = TorrentioResolver.mapStreams(listOf(stream(3, "Movie.2160p.DOVI.REMUX")), false)
        assertTrue(dolby.single().name.contains("[DV]"))
        assertTrue(TorrentioResolver.mapStreams(listOf(stream(3, "Movie.2160p (0 seeds)")), false).isEmpty())
    }

    @Test
    fun directLinksDoNotNeedSeedsOrHash() = runBlocking {
        val links = TorrentioResolver.mapStreams(listOf(
            TorrentioStream(title = "Movie", name = "Torrentio 1080p", url = "https://media.example/movie.mp4")
        ), false)
        assertEquals(1, links.size)
        assertEquals(ExtractorLinkType.VIDEO, links.single().type)
        assertEquals(1080, links.single().quality)
    }

    @Test
    fun ytsRejectsHtmlInvalidSchemaErrorsAndWrongMovie() = runBlocking {
        assertNull(YtsResolver.parseResponse("<html>blocked</html>"))
        assertNull(YtsResolver.parseResponse("{invalid json}"))
        assertTrue(YtsResolver.mapResponse(YtsResolver.parseResponse("{}"), "tt123", false).isEmpty())
        val movie = YtsMovie(imdbCode = "tt999", torrents = listOf(YtsTorrent(hash = hash, seeds = 10)))
        assertTrue(YtsResolver.mapResponse(YtsResponse("ok", YtsData(listOf(movie))), "tt123", false).isEmpty())
        assertTrue(YtsResolver.mapResponse(YtsResponse("error", YtsData(listOf(movie))), "tt999", false).isEmpty())
    }

    @Test
    fun ytsParsesApiFixtureWithAdditionalMetadata() = runBlocking {
        val response = YtsResolver.parseResponse("""
            {"status":"ok","status_message":"Query was successful","data":{
              "movie_count":1,"movies":[{"id":1,"imdb_code":"tt123","title":"Movie",
                "slug":"movie","torrents":[{"hash":"$hash","quality":"1080p","seeds":30,
                  "date_uploaded":"2026-01-01"}]}]},"@meta":{"server_time":1}}
        """.trimIndent())
        assertNotNull(response)
        assertEquals(1080, YtsResolver.mapResponse(response, "tt123", false).single().quality)
    }

    @Test
    fun ytsReturnsEveryQualityOnlyForRequestedImdb() = runBlocking {
        val qualities = listOf("480p", "720p", "1080p", "2160p")
        val movie = YtsMovie(imdbCode = "tt123", title = "Movie & More", torrents =
            qualities.map { YtsTorrent(hash = hash, quality = it, seeds = 10) } +
                YtsTorrent(hash = "bad", quality = "1080p", seeds = 999))
        val response = YtsResponse("ok", YtsData(listOf(YtsMovie(imdbCode = "tt999"), movie)))
        val links = YtsResolver.mapResponse(response, "tt123", false)
        assertEquals(listOf(480, 720, 1080, 2160), links.map { it.quality })
        assertTrue(links.all { it.url.contains("&index=0&") && it.name.contains("(10 seeds)") })
    }
}
