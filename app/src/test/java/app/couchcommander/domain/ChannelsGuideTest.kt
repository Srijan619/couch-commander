package app.couchcommander.domain

import app.iptv.ChannelHealth
import app.iptv.IptvChannel
import app.iptv.WatchOption
import app.football.FootballMatch
import app.football.MatchSide
import java.time.Instant
import java.time.ZoneId
import org.junit.Test
import kotlin.test.assertEquals

class ChannelsGuideTest {
    private val zone = ZoneId.of("Europe/Helsinki")
    private val now = Instant.parse("2026-10-17T14:30:00Z") // Sat 17 Oct, 17:30 in Helsinki

    private fun m(id: Long, kickoff: String, started: Boolean = false, finished: Boolean = false) = FootballMatch(
        id, MatchSide(id.toInt() * 2, "Home $id", if (started) 1 else null), MatchSide(id.toInt() * 2 + 1, "Away $id", if (started) 0 else null),
        Instant.parse(kickoff).toEpochMilli(), started = started, finished = finished,
    )
    private fun ch(name: String) = IptvChannel(name, "Sports", "http://x/$name")
    private fun on(vararg channels: IptvChannel) = channels.map { WatchOption(it, score = 2) }

    @Test
    fun `a channel shows its live match, else its next one today`() {
        val main = ch("Main Event"); val football = ch("Football"); val news = ch("News"); val old = ch("Old")
        val live = m(1, "2026-10-17T14:00:00Z", started = true)
        val later = m(2, "2026-10-17T17:00:00Z")
        val tomorrow = m(3, "2026-10-18T14:00:00Z")
        val done = m(4, "2026-10-17T11:00:00Z", started = true, finished = true)
        val watch = mapOf(1L to on(main), 2L to on(main, football), 3L to on(news), 4L to on(old))
        val guide = ChannelsGuide.byChannel(listOf(later, live, tomorrow, done), watch, now, zone)
        assertEquals(ChannelsGuide.OnChannel(live, true), guide[ChannelHealth.key(main)])
        assertEquals(ChannelsGuide.OnChannel(later, false), guide[ChannelHealth.key(football)])
        assertEquals(null, guide[ChannelHealth.key(news)], "not today")
        assertEquals(null, guide[ChannelHealth.key(old)], "finished")
    }

    @Test
    fun `on now lists live matches that are on somewhere, in tab order`() {
        val a = ch("A"); val b = ch("B")
        val early = m(1, "2026-10-17T13:30:00Z", started = true)
        val late = m(2, "2026-10-17T14:00:00Z", started = true)
        val nowhere = m(3, "2026-10-17T14:00:00Z", started = true)
        val result = ChannelsGuide.onNow(listOf(late, nowhere, early), mapOf(1L to on(a, a), 2L to on(b)))
        assertEquals(listOf(2L to listOf(b), 1L to listOf(a)), result.map { (m, o) -> m.id to o.map { it.channel } }, "in the order given, once per channel")
    }
}
