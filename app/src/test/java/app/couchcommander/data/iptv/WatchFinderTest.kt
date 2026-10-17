package app.couchcommander.data.iptv

import app.football.FootballMatch
import app.football.MatchSide
import java.time.Instant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchFinderTest {
    private val kickoff = Instant.parse("2026-10-18T14:00:00Z")
    private val match = FootballMatch(7, MatchSide(1, "Arsenal"), MatchSide(2, "Chelsea"), kickoff.toEpochMilli())

    private fun ch(name: String, n: Int, epg: String? = null) = IptvChannel(name, "Sport", "http://h/$n", n, epg)
    private fun prog(epg: String, title: String, start: Instant, minutes: Long = 120) =
        IptvProgramme(epg, title, start.toEpochMilli(), start.plusSeconds(minutes * 60).toEpochMilli())

    @Test
    fun `event channel first, then guide matches by strength and number`() {
        val channels = listOf(ch("V Sport 1", 42, "vs1"), ch("V Sport 1 HD", 43, "vs1"), ch("Sky Sports PL", 10, "sky"), ch("EPL 03: Arsenal v Chelsea", 900), ch("Golf", 5, "golf"))
        val programmes = listOf(
            prog("vs1", "Premier League: Arsenal v Chelsea", kickoff.minusSeconds(1800)),
            prog("sky", "Arsenal build-up", kickoff.minusSeconds(3600), 75),
            prog("golf", "Arsenal v Chelsea highlights", kickoff),
        )
        val options = WatchFinder.find(listOf(match), channels, programmes).getValue(7)
        assertEquals(listOf("EPL 03: Arsenal v Chelsea", "V Sport 1", "V Sport 1 HD", "Sky Sports PL"), options.map { it.channel.name })
        assertEquals(listOf(3, 2, 2, 1), options.map { it.score })
        assertEquals("Premier League: Arsenal v Chelsea", options[1].title)
    }

    @Test
    fun `guide entries far from kickoff and finished matches are ignored`() {
        val channels = listOf(ch("V Sport 1", 42, "vs1"))
        val programmes = listOf(prog("vs1", "Arsenal v Chelsea", kickoff.plusSeconds(86_400)), prog("vs1", "Arsenal v Chelsea", kickoff.minusSeconds(5 * 3600)))
        assertTrue(WatchFinder.find(listOf(match), channels, programmes).isEmpty())
        val done = match.copy(finished = true)
        assertTrue(WatchFinder.find(listOf(done), listOf(ch("Arsenal v Chelsea", 1)), emptyList()).isEmpty())
    }
}
