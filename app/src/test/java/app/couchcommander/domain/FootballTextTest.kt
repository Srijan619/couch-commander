package app.couchcommander.domain

import app.football.FootballBoard
import app.football.FootballMatch
import app.football.MatchSide
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FootballTextTest {
    private val zone = ZoneId.of("Europe/Helsinki")
    private val now = Instant.parse("2026-10-16T09:00:00Z") // Fri 16 Oct, 12:00 in Helsinki

    private fun match(id: Long, kickoff: String, home: String = "HJK", away: String = "KuPS", hs: Int? = null, aws: Int? = null, started: Boolean = false, finished: Boolean = false, liveTime: String? = null, result: String? = null) =
        FootballMatch(
            id = id, home = MatchSide(1, home, hs), away = MatchSide(2, away, aws), kickoffEpochMillis = Instant.parse(kickoff).toEpochMilli(),
            tournament = "Veikkausliiga", started = started, finished = finished, liveTime = liveTime, result = result,
        )

    private fun lines(featured: FootballMatch?, vararg upcoming: FootballMatch) =
        FootballText.lines(FootballBoard(featured, upcoming.toList()), now, zone, is24Hour = true, locale = Locale.UK)

    @Test
    fun `nothing to show without a featured match`() {
        assertNull(lines(null))
    }

    @Test
    fun `upcoming match`() {
        val l = lines(match(1, "2026-10-18T14:00:00Z"), match(2, "2026-10-22T15:30:00Z", home = "Inter Turku", away = "HJK"))!!
        assertEquals("Next match", l.label)
        assertEquals("HJK – KuPS", l.title)
        assertEquals("Sun 18 Oct · 17:00 · Veikkausliiga", l.detail)
    }

    @Test
    fun `today and the countdown in the last hours`() {
        val l = lines(match(1, "2026-10-16T10:20:00Z"))!!
        assertEquals("Kick-off in 1 h 20 min", l.label)
        assertEquals("Today · 13:20 · Veikkausliiga", l.detail)
    }

    @Test
    fun `live shows the minute and score`() {
        val l = lines(match(1, "2026-10-16T08:00:00Z", hs = 2, aws = 1, started = true, liveTime = "63′"))!!
        assertEquals("Live · 63′", l.label)
        assertEquals(true, l.live)
        assertEquals("HJK 2 – 1 KuPS", l.title)
        assertEquals("Veikkausliiga", l.detail)
    }

    @Test
    fun `finished after penalties`() {
        val l = lines(match(1, "2026-10-15T16:00:00Z", hs = 1, aws = 1, started = true, finished = true, result = "Pen"))!!
        assertEquals("After penalties", l.label)
        assertEquals("HJK 1 – 1 KuPS", l.title)
    }
}
