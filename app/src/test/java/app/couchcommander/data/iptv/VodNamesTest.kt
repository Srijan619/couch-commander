package app.couchcommander.data.iptv

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VodNamesTest {
    private fun check(name: String, title: String, year: Int?, tags: List<String>) {
        val p = VodNames.parse(name)
        assertEquals(title, p.title, name)
        assertEquals(year, p.year, name)
        assertEquals(tags, p.tags, name)
    }

    @Test
    fun `takes the tags off`() {
        check("EN - Dune: Part Two (2024)", "Dune: Part Two", 2024, listOf("EN"))
        check("|NL| Oppenheimer [4K]", "Oppenheimer", null, listOf("NL", "4K"))
        check("[FI] Tuntematon sotilas - 2017", "Tuntematon sotilas", 2017, listOf("FI"))
        check("4K-EN - The Batman 2022", "The Batman", 2022, listOf("4K", "EN"))
        check("EN| Blade Runner 2049 (2017) FHD", "Blade Runner 2049", 2017, listOf("EN", "FHD"))
    }

    @Test
    fun `leaves titles that only look like tags`() {
        check("Alien: Romulus (2024)", "Alien: Romulus", 2024, emptyList())
        check("1917 (2019)", "1917", 2019, emptyList())
        check("Blade Runner 2049", "Blade Runner 2049", null, emptyList())
    }

    @Test
    fun `keys match TMDB titles`() {
        assertTrue(VodNames.key("Dune: Part Two") in VodNames.keys("EN - Dune Part Two (2024)"))
        assertTrue(VodNames.key("Amélie") in VodNames.keys("FR - Amelie"))
        assertTrue(VodNames.key("The Office") in VodNames.keys("US - Office"))
        // "IT" is a tag on most panels, but also a film.
        assertTrue(VodNames.key("IT") in VodNames.keys("IT"))
    }

    @Test
    fun `English and the best picture first, other languages last`() {
        val versions = listOf(
            VodEntry("1", "SE - Dune (2021)"),
            VodEntry("2", "Dune (2021)", category = "Czech and Slovakia"),
            VodEntry("3", "EN - Dune (2021) HD"),
            VodEntry("4", "Dune (2021)", category = "IMDB Top Movies [Multi-Sub]"),
            VodEntry("5", "EN - Dune (2021) [4K]"),
            VodEntry("6", "|NL| Dune [4K]"),
        )
        assertEquals(listOf("5", "4", "3", "6", "1", "2"), versions.sortedBy(VodNames::rank).map { it.id })
    }

    @Test
    fun `labels a version by its tags, else its category`() {
        assertEquals("EN 4K", VodNames.label(VodEntry("1", "EN - Dune (2021) [4K]")))
        assertEquals("Movies 2024", VodNames.label(VodEntry("1", "Dune", category = "Movies 2024")))
    }

    @Test
    fun episodeTitleDropsThePanelsFileName() {
        assertEquals("", VodNames.episodeTitle("MobLand [4K] - S02E03"))
        assertEquals("Pilot", VodNames.episodeTitle("Friends - S01E01 - Pilot"))
        assertEquals("The One Where", VodNames.episodeTitle("Friends S01 E02: The One Where"))
        assertEquals("Pilot", VodNames.episodeTitle(" Pilot "))
        assertEquals("", VodNames.episodeTitle("Épisode 1"))
        assertEquals("", VodNames.episodeTitle("MobLand - S01E01 - Episode 1"))
        assertEquals("Episode of the Year", VodNames.episodeTitle("Episode of the Year"))
    }
}
