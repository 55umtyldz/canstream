package com.cloudstream.tr.hub

import com.cloudstream.tr.core.streaming.StreamCacheManager
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.junit.Assert.*
import org.junit.Test

/** Regression: cache admission must not expose expired direct CDN URLs. */
class HubCachePolicyTest {
    private fun link(url: String, type: ExtractorLinkType) = ExtractorLink(
        source = "Test", name = "1080p", url = url, referer = "",
        quality = 1080, type = type
    )

    @Test fun hubCachesOnlyTorrentCandidates() {
        val key = "cache_policy_test"
        try {
            val direct = link("https://cdn.example/video.m3u8?token=expired", ExtractorLinkType.M3U8)
            val torrent = link("magnet:?xt=urn:btih:0000000000000000000000000000000000000001&index=0", ExtractorLinkType.MAGNET)
            StreamCacheManager.put(key, listOf(direct, torrent).let(::cacheableHubLinks))
            assertEquals(listOf(torrent), StreamCacheManager.get(key))
            assertFalse(StreamCacheManager.get(key).orEmpty().any { it.url.contains("expired") })
        } finally {
            StreamCacheManager.clear()
        }
    }
}
