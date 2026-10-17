package app.couchcommander.domain

import app.football.FootballMatch
import app.football.MatchSide
import java.time.Instant
import java.time.ZoneId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LeaguePageTest {
    private val zone = ZoneId.of("Europe/Helsinki")
    private val now = Instant.parse("2026-10-17T14:30:00Z") // Sat 17 Oct, 17:30 in Helsinki

    private fun m(id: Long, kickoff: String, score: Pair<Int, Int>? = null, finished: Boolean = false, cancelled: Boolean = false) = FootballMatch(
        id, MatchSide(id.toInt() * 2, "Home $id", score?.first), MatchSide(id.toInt() * 2 + 1, "Away $id", score?.second),
        Instant.parse(kickoff).toEpochMilli(), started = score != null, finished = finished, cancelled = cancelled,
    )

    @Test
    fun `live first, then later kickoffs, then results`() {
        val result = m(1, "2026-10-17T11:30:00Z", 0 to 2, finished = true)
        val live = m(2, "2026-10-17T13:30:00Z", 1 to 0)
        val later = m(3, "2026-10-17T16:30:00Z")
        val tomorrow = m(4, "2026-10-18T13:30:00Z")
        val yesterday = m(5, "2026-10-16T18:30:00Z", 1 to 0, finished = true)
        val off = m(6, "2026-10-17T16:30:00Z", cancelled = true)
        val day = LeaguePage.today(listOf(yesterday, result, later, live, tomorrow, off), now, zone)
        assertEquals(listOf(2L, 3L, 1L), day.matches.map { it.id })
        assertEquals(2, day.firstResult)
        assertNull(day.next, "something on today")
    }

    @Test
    fun `no results, no heading`() {
        assertEquals(-1, LeaguePage.today(listOf(m(3, "2026-10-17T16:30:00Z")), now, zone).firstResult)
    }

    @Test
    fun `a match Footy never finished counts as a result`() {
        val stuck = m(1, "2026-10-17T09:00:00Z", 1 to 1)
        val day = LeaguePage.today(listOf(stuck), now, zone)
        assertEquals(0, day.firstResult)
    }

    @Test
    fun `still on past midnight stays on the list`() {
        val late = m(1, "2026-10-17T20:00:00Z", 2 to 2)
        val day = LeaguePage.today(listOf(late), Instant.parse("2026-10-17T21:15:00Z"), zone) // 00:15 Sunday
        assertEquals(listOf(1L), day.matches.map { it.id })
    }

    @Test
    fun `nothing today names the next match day and its size`() {
        val tue1 = m(1, "2026-10-20T16:45:00Z")
        val tue2 = m(2, "2026-10-20T19:00:00Z")
        val wed = m(3, "2026-10-21T19:00:00Z")
        val day = LeaguePage.today(listOf(wed, tue2, tue1), now, zone)
        assertEquals(emptyList(), day.matches)
        assertEquals(Instant.parse("2026-10-20T16:45:00Z"), day.next?.kickoff)
        assertEquals(2, day.next?.count)
        assertNull(LeaguePage.today(emptyList(), now, zone).next)
    }
}
