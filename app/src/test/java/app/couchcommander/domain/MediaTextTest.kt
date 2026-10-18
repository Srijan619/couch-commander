package app.couchcommander.domain

import app.media.Episode
import app.media.MediaKind
import app.media.MovieDetails
import app.media.WatchProvider
import app.media.WatchProviders
import java.time.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaTextTest {
    private fun p(vararg names: String) = names.mapIndexed { i, n -> WatchProvider(i, n, null) }

    @Test
    fun `lengths read as hours and minutes`() {
        assertEquals("2 h 33 min", MediaText.runtime(153))
        assertEquals("2 h", MediaText.runtime(120))
        assertEquals("47 min", MediaText.runtime(47))
        assertEquals("", MediaText.runtime(0))
    }

    @Test
    fun `a film's facts, and a series' seasons in place of its length`() {
        val film = MovieDetails(1, "Metropolis", 1927, 8.1, genres = listOf("Science Fiction", "Drama", "Silent"), runtimeMinutes = 153)
        assertEquals("1927 · 2 h 33 min · Science Fiction, Drama", MediaText.facts(film))
        val show = MovieDetails(2, "Dragnet", 1951, 7.6, genres = listOf("Crime"), runtimeMinutes = 30, kind = MediaKind.Tv, seasons = 8)
        assertEquals("1951 · 8 seasons · 30 min · Crime", MediaText.facts(show))
    }

    @Test
    fun `where to watch in one line, buying only when it can't be rented`() {
        val stream = WatchProviders(link = null, stream = p("Netflix", "Max"), rent = p("Apple TV"), buy = p("Google Play"))
        assertEquals("In Finland: on Netflix and Max · rent on Apple TV", MediaText.whereToWatch(stream, "FI"))
        val shops = WatchProviders(link = null, buy = p("Apple TV", "Google Play", "Rakuten", "SF Anytime"))
        assertEquals("In Sweden: buy on Apple TV, Google Play and 2 more", MediaText.whereToWatch(shops, "SE"))
        assertNull(MediaText.whereToWatch(null, "FI"))
    }

    @Test
    fun `episodes show their length and day, or that they're coming`() {
        val today = LocalDate.parse("2026-10-17")
        assertEquals("30 min · 12 Mar 1952", MediaText.episodeLine(Episode(2, 5, "The Big Cast", "1952-03-12", 30), today))
        assertEquals("Upcoming · 2 Nov 2026", MediaText.episodeLine(Episode(9, 1, "New", "2026-11-02", 45), today))
        assertEquals("Upcoming", MediaText.episodeLine(Episode(9, 2, "Later", null), today))
    }

    @Test
    fun `the user's rating drops a whole number's decimals`() {
        assertEquals("8", MediaText.score(8.0))
        assertEquals("7.5", MediaText.score(7.5))
    }
}
