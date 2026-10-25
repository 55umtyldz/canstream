package com.cloudstream.tr.fourkhdhub

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FourKHDHubParserTest {
    @Test
    fun parsesCardFixtureAndSkipsBlankTitles() {
        val document = Jsoup.parse(
            """
            <article>
              <h2 class="entry-title"><a href="https://4khdhub.one/movie/arrival/">Arrival</a></h2>
              <img src="https://4khdhub.one/posters/arrival.jpg">
            </article>
            <div class="post-item">
              <a rel="bookmark" href="https://4khdhub.one/movie/blank/"></a>
            </div>
            """.trimIndent()
        )

        val results = FourKHDHub().parseCards(document)

        assertEquals(1, results.size)
        assertEquals("Arrival", results.single().name)
        assertEquals("https://4khdhub.one/movie/arrival/", results.single().url)
        assertEquals("https://4khdhub.one/posters/arrival.jpg", results.single().posterUrl)
    }

    @Test
    fun parsesFallbackAnchorWhenExpectedHeadingIsAbsent() {
        val document = Jsoup.parse("<article><a href='/movie/fallback/'>Fallback</a></article>")

        val result = FourKHDHub().parseCards(document)

        assertTrue(result.single().name == "Fallback")
        assertEquals("https://4khdhub.one/movie/fallback/", result.single().url)
    }

    @Test
    fun parsesModernCardGridStructure() {
        val document = Jsoup.parse(
            """
            <div class="card-grid">
              <a href="/marvels-the-punisher-series-2288/">
                <div class="movie-card-image"><img src="https://catimages.org/poster.jpg"></div>
                <div class="movie-card-content">
                  <h3>Marvel's The Punisher</h3>
                  <span>2017</span>
                </div>
              </a>
              <a href="/la-bola-negra-movie-8406/">
                <div class="movie-card-image"><img src="https://image.tmdb.org/poster2.jpg"></div>
                <div class="movie-card-content">
                  <h3>La bola negra</h3>
                  <span>2026</span>
                </div>
              </a>
            </div>
            """.trimIndent()
        )

        val results = FourKHDHub().parseCards(document)

        assertEquals(2, results.size)
        assertEquals("Marvel's The Punisher", results[0].name)
        assertEquals("https://4khdhub.one/marvels-the-punisher-series-2288/", results[0].url)
        assertEquals("https://catimages.org/poster.jpg", results[0].posterUrl)

        assertEquals("La bola negra", results[1].name)
        assertEquals("https://4khdhub.one/la-bola-negra-movie-8406/", results[1].url)
        assertEquals("https://image.tmdb.org/poster2.jpg", results[1].posterUrl)
    }
}
