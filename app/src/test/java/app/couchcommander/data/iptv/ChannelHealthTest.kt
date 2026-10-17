package app.couchcommander.data.iptv

import java.time.Duration
import java.time.Instant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelHealthTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private fun ch(name: String) = IptvChannel(name, "Sport", "http://h/$name")

    @Test
    fun `played lately first, failures last, hidden left out, otherwise in order`() {
        val a = ch("A"); val b = ch("B"); val c = ch("C"); val d = ch("D"); val e = ch("E")
        val records = mapOf(
            ChannelHealth.key(a) to ChannelHealth.failed(null, now.minusSeconds(60)),
            ChannelHealth.key(c) to ChannelHealth.played(null, now.minusSeconds(3600)),
            ChannelHealth.key(d) to ChannelRecord(hidden = true),
        )
        assertEquals(listOf(c, b, e, a), ChannelHealth.order(listOf(a, b, c, d, e), records, now) { it })
    }

    @Test
    fun `failures fade after a day, and a failure after a success counts`() {
        val old = ChannelHealth.failed(null, now.minus(Duration.ofHours(25)))
        assertEquals(0, ChannelHealth.rank(old, now))
        val playedThenFailed = ChannelHealth.failed(ChannelHealth.played(null, now.minusSeconds(7200)), now.minusSeconds(60))
        assertEquals(1, ChannelHealth.rank(playedThenFailed, now))
        assertEquals(-1, ChannelHealth.rank(ChannelHealth.played(playedThenFailed, now), now))
    }

    @Test
    fun `most watched ranks by total time, not recency`() {
        val a = ch("UK| Sky Sports Main Event"); val b = ch("TNT Sports 1"); val c = ch("V Sport 1"); val d = ch("Eurosport 1")
        val ppv = ch("US| PPV 4 Event")
        var records = mapOf(
            ChannelHealth.key(a) to ChannelHealth.watched(ChannelHealth.played(null, now.minusSeconds(86_400 * 3)), 5_400),
            ChannelHealth.key(b) to ChannelHealth.watched(ChannelHealth.played(null, now), 600), // watched today, but less
            ChannelHealth.key(c) to ChannelHealth.watched(null, 60), // only tried
            ChannelHealth.key(d) to ChannelRecord(watchedSeconds = 9_000, hidden = true),
            ChannelHealth.key(ppv) to ChannelRecord(watchedSeconds = 20_000),
            "Gone|Old channel" to ChannelRecord(watchedSeconds = 30_000), // no longer in the playlist
        )
        assertEquals(listOf(a, b), ChannelHealth.mostWatched(records, listOf(a, b, c, d, ppv)))
        records = records + (ChannelHealth.key(b) to ChannelHealth.watched(records[ChannelHealth.key(b)], 6_000))
        assertEquals(listOf(b, a), ChannelHealth.mostWatched(records, listOf(a, b, c, d, ppv)))
    }

    @Test
    fun `event channels failing well before kickoff don't count`() {
        val kickoff = now.plus(Duration.ofHours(4))
        assertFalse(ChannelHealth.counts(event = true, kickoff = kickoff, now = now))
        assertTrue(ChannelHealth.counts(event = true, kickoff = kickoff, now = kickoff.minusSeconds(300)))
        assertTrue(ChannelHealth.counts(event = false, kickoff = kickoff, now = now))
    }
}
