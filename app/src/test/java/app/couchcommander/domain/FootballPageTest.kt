package app.couchcommander.domain

import app.football.FootballMatch
import app.football.FootballTeam
import app.football.MatchSide
import java.time.Instant
import java.time.ZoneId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FootballPageTest {
    private val zone = ZoneId.of("Europe/Helsinki")
    private val now = Instant.parse("2026-10-17T09:00:00Z") // Sat 17 Oct, 12:00 in Helsinki
    private val jaro = FootballTeam(1, "FF Jaro")
    private val arsenal = FootballTeam(10, "Arsenal")

    private fun m(id: Long, kickoff: String, home: Pair<Int, String>, away: Pair<Int, String>, score: Pair<Int, Int>? = null, finished: Boolean = score != null) = FootballMatch(
        id, MatchSide(home.first, home.second, score?.first), MatchSide(away.first, away.second, score?.second),
        Instant.parse(kickoff).toEpochMilli(), started = score != null, finished = finished,
    )

    @Test
    fun `live first, then today's kickoffs, not later days or results`() {
        val live = m(1, "2026-10-17T08:30:00Z", 10 to "Arsenal", 11 to "Everton", 1 to 0, finished = false)
        val jaroToday = m(2, "2026-10-17T15:00:00Z", 1 to "FF Jaro", 2 to "KuPS")
        val arsenalLateToday = m(3, "2026-10-17T20:30:00Z", 10 to "Arsenal", 12 to "Fulham") // 23:30 in Helsinki: still today
        val tomorrow = m(4, "2026-10-17T21:30:00Z", 1 to "FF Jaro", 4 to "HJK") // 00:30 Sunday in Helsinki
        val result = m(5, "2026-10-17T06:00:00Z", 1 to "FF Jaro", 5 to "SJK", 2 to 1)
        val page = FootballPage.today(listOf(tomorrow, arsenalLateToday, result, jaroToday, live), listOf(jaro, arsenal), now, zone)
        assertEquals(listOf(1L, 2L, 3L), page.matches.map { it.id })
        assertEquals(4L, page.next?.id)
    }

    @Test
    fun `same kickoff keeps the order the teams were followed in`() {
        val a = m(1, "2026-10-17T15:00:00Z", 10 to "Arsenal", 11 to "Everton")
        val j = m(2, "2026-10-17T15:00:00Z", 1 to "FF Jaro", 2 to "KuPS")
        assertEquals(listOf(2L, 1L), FootballPage.today(listOf(a, j), listOf(jaro, arsenal), now, zone).matches.map { it.id })
    }

    @Test
    fun `nothing today names the next match`() {
        val sunday = m(1, "2026-10-18T12:00:00Z", 1 to "FF Jaro", 2 to "KuPS")
        val page = FootballPage.today(listOf(sunday), listOf(jaro), now, zone)
        assertEquals(emptyList(), page.matches)
        assertEquals(1L, page.next?.id)
        assertNull(FootballPage.today(emptyList(), listOf(jaro), now, zone).next)
    }
}
