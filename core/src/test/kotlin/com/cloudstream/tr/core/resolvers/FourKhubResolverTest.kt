package com.cloudstream.tr.core.resolvers

import com.lagradost.cloudstream3.utils.AppUtils
import org.junit.Assert.*
import org.junit.Test

class FourKhubResolverTest {

    @Test
    fun testParseHdHubResponse() {
        val sampleJson = """
        {
          "streams": [
            {
              "name": "4KHDHub\n4K",
              "title": "The Nice Guys 2016 2160p UHD BluRay x265\nFile size: 18.2 GB",
              "url": "https://pixeldrain.dev/api/file/giigZuSc?download"
            },
            {
              "name": "4KHDHub\n1080p",
              "title": "The Nice Guys 2016 1080p BluRay x264\nFile size: 3.8 GB",
              "url": "https://gpdl.hubcloud.ist/?id=abc12345"
            }
          ]
        }
        """.trimIndent()

        val parsed = AppUtils.tryParseJson<HdHubResponse>(sampleJson)
        assertNotNull("HdHubResponse should parse successfully", parsed)
        assertEquals(2, parsed?.streams?.size)
        val s1 = parsed?.streams?.get(0)
        assertEquals("4KHDHub\n4K", s1?.name)
        assertTrue(s1?.url?.contains("pixeldrain") == true)
    }
}
