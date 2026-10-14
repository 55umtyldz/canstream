package com.cloudstream.tr.hub

import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HubTorrentReadinessTest {
    private val url = "http://127.0.0.1:8090/stream/selected.mkv?link=abc&index=2&play&preload&stat"
    private fun range(start: Long, end: Long, size: Long) =
        TorrentProbeResponse(206, "bytes $start-$end/$size", ByteArray((end - start + 1).toInt()))

    @Test fun normalizesFlagsWithoutChangingSelectedFile() {
        val prepared = torrentPreparationUrl(url, true).toHttpUrl()
        assertEquals("/stream/selected.mkv", prepared.encodedPath)
        assertEquals("2", prepared.queryParameter("index"))
        assertEquals("abc", prepared.queryParameter("link"))
        assertEquals(8090, prepared.port)
        assertFalse(prepared.queryParameterNames.contains("play"))
        assertEquals(setOf("link", "index", "preload", "stat"), prepared.queryParameterNames)
        val play = torrentPreparationUrl(url, false).toHttpUrl()
        assertEquals(setOf("link", "index", "play"), play.queryParameterNames)
    }

    @Test fun rejectsRemoteMissingOrAmbiguousIndexBeforeNetwork() {
        listOf(url.replace("127.0.0.1", "example.com"), url.replace("index=2", "index=-1"),
            url.replace("index=2", "index=2&index=3"), url.replace("index=2&", ""))
            .forEach { bad ->
                try { torrentPreparationUrl(bad, true); fail(bad) } catch (_: ErrorLoadingException) { }
            }
    }

    @Test fun preloadThenHeadThenTailAreRequiredForReadiness() = runBlocking {
        val calls = mutableListOf<String?>()
        val transport = TorrentReadinessTransport { request, requestedRange, _, _ ->
            calls.add(requestedRange)
            assertEquals("2", request.toHttpUrl().queryParameter("index"))
            when (requestedRange) {
                null -> TorrentProbeResponse(200, null, "{}".toByteArray())
                "bytes=0-65535" -> range(0, 65535, 200_000)
                "bytes=134464-199999" -> range(134464, 199999, 200_000)
                else -> throw AssertionError(requestedRange)
            }
        }
        awaitTorrentReadiness(url, transport, diagnostic = {})
        assertEquals(listOf(null, "bytes=0-65535", "bytes=134464-199999"), calls)
    }

    @Test fun smallFilesAndWarmRetryUseActualBytes() = runBlocking {
        var heads = 0
        awaitTorrentReadiness(url, TorrentReadinessTransport { _, requestedRange, _, _ ->
            when {
                requestedRange == null -> throw IOException("Preload response timeout")
                heads++ == 0 -> throw IOException("Not ready")
                else -> range(0, 9, 10)
            }
        }, budgetMs = 3000, diagnostic = {})
        assertEquals(3, heads)
    }

    @Test fun rejectsStatusRedirectWrongRangeChangedSizeAndShortBody() {
        val valid = range(0, 9, 10)
        listOf(valid.copy(code = 200), valid.copy(code = 302), valid.copy(range = "bytes 1-9/10"),
            valid.copy(range = "bytes 0-8/10"), valid.copy(bytes = ByteArray(9)),
            valid.copy(range = "bytes 0-9/*")).forEach {
            try { validateTorrentRange(it, 0, 65535); fail("Expected rejection") }
            catch (_: ErrorLoadingException) { }
        }
        try { validateTorrentRange(valid, 0, 9, 11); fail("Size changed") }
        catch (_: ErrorLoadingException) { }
    }

    @Test fun preparationTimeoutIsFriendlyAndDoesNotBecomeReady() = runBlocking {
        try {
            awaitTorrentReadiness(url, TorrentReadinessTransport { _, _, _, _ -> awaitCancellation() },
                budgetMs = 30, diagnostic = {})
            fail("Expected timeout")
        } catch (e: ErrorLoadingException) { assertTrue(e.message.orEmpty().contains("hazırlanamadı")) }
    }

    @Test fun callerCancellationPropagates() = runBlocking {
        try {
            withTimeout(30) {
                awaitTorrentReadiness(url, TorrentReadinessTransport { _, _, _, _ -> awaitCancellation() },
                    diagnostic = {})
            }
            fail("Expected caller cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun httpFailureDoesNotLaunchReadinessProbes() = runBlocking {
        var calls = 0
        try {
            awaitTorrentReadiness(url, TorrentReadinessTransport { _, _, _, _ ->
                calls++; TorrentProbeResponse(403, null, "{}".toByteArray())
            }, diagnostic = {})
            fail("Expected rejection")
        } catch (_: ErrorLoadingException) { assertEquals(1, calls) }
    }
}
