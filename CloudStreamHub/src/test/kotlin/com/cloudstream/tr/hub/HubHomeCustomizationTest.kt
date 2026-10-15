package com.cloudstream.tr.hub

import com.lagradost.cloudstream3.MainPageData
import org.junit.Assert.*
import org.junit.Test

class HubHomeCustomizationTest {
    @Test fun defaultContainsAllTenHomeSectionsInExistingOrder() {
        val pages = HubHomePreferences.sections.map { MainPageData(it.label, "/fixture/" + it.id) }
        val actual = HubHomePreferences.filtered(pages, HubHomePreferences.normalized(emptyList(), emptySet()))
        assertEquals(pages, actual)
        assertEquals(10, actual.size)
    }

    @Test fun popularMoviesCanBeHiddenWithoutTouchingOtherSections() {
        val pages = HubHomePreferences.sections.map { MainPageData(it.label, "/fixture/" + it.id) }
        val actual = HubHomePreferences.filtered(pages, HubHomePreferences.normalized(
            emptyList(), setOf("popular_movies")
        ))
        assertEquals(9, actual.size)
        assertFalse(actual.any { it.name == "Popüler Filmler" })
        assertTrue(actual.any { it.name == "Popüler Diziler" })
    }

    @Test fun reorderIsStableEvenWithUnknownOrRepeatedIdentifiers() {
        val config = HubHomePreferences.normalized(
            listOf("anime", "popular_movies", "anime", "UNRECOGNIZED"),
            setOf("popular_series", "MISSING")
        )
        assertEquals("anime", config.order.first())
        assertEquals("popular_movies", config.order[1])
        assertEquals(10, config.order.distinct().size)
        assertEquals(setOf("popular_series"), config.hidden)
        val pages = HubHomePreferences.sections.map { MainPageData(it.label, "/fixture/" + it.id) }
        val ordered = HubHomePreferences.filtered(pages, config)
        assertEquals("Anime ve Animasyon", ordered.first().name)
        assertEquals("Popüler Filmler", ordered[1].name)
        assertEquals(9, ordered.size)
    }

    @Test fun hidingAllAutomaticallyKeepsOneCategoryAvailable() {
        val all = HubHomePreferences.sections.map { it.id }.toSet()
        val config = HubHomePreferences.normalized(emptyList(), all)
        assertEquals(9, config.hidden.size)
        assertTrue(config.order.first() !in config.hidden)
    }

    @Test fun themeChoicesAreRestrictedToKnownModeAndAccent() {
        assertEquals(HubAppearancePreferences.Config(), HubAppearancePreferences.normalized("unknown", "bad"))
        assertEquals(HubAppearancePreferences.Config("DARK", "TEAL"),
            HubAppearancePreferences.normalized("DARK", "TEAL"))
        assertEquals(6, HubAppearancePreferences.accents.size)
    }
}
