package app.couchcommander.domain

import app.football.FootballMatch
import app.iptv.ChannelHealth
import app.iptv.WatchOption
import java.time.Instant
import java.time.ZoneId

/**
 * What the Channels tab says about each channel: the match it shows now or later today. The only guide kept is for
 * the followed teams and leagues, so these come from where the IPTV sync found their matches (WatchFinder), not from
 * a full programme guide.
 */
object ChannelsGuide {
    /** [match] on a channel: [live] now, or later today. */
    data class OnChannel(val match: FootballMatch, val live: Boolean)

    /** By [ChannelHealth.key]: the live match if there is one, else the next one today. */
    fun byChannel(matches: List<FootballMatch>, watch: Map<Long, List<WatchOption>>, now: Instant, zone: ZoneId): Map<String, OnChannel> {
        val today = now.atZone(zone).toLocalDate()
        val result = HashMap<String, OnChannel>()
        // Live ones first, then the rest by kickoff, so the first match a channel gets is the one to show.
        val relevant = matches
            .filter { !it.finished && !it.cancelled && (it.liveAt(now) || it.kickoff.isAfter(now) && it.kickoff.atZone(zone).toLocalDate() == today) }
            .sortedWith(compareByDescending<FootballMatch> { it.liveAt(now) }.thenBy { it.kickoffEpochMillis })
        for (m in relevant) {
            val on = OnChannel(m, m.liveAt(now))
            watch[m.id].orEmpty().forEach { result.putIfAbsent(ChannelHealth.key(it.channel), on) }
        }
        return result
    }

    /**
     * The matches live now that are on at least one channel, in the order given (the page's tab order: the followed
     * teams', then each league's), each with where it's on. Live as of [now] (FootballMatch.liveAt): a match whose
     * final whistle Footy never reported isn't on now a day later.
     */
    fun onNow(matches: List<FootballMatch>, watch: Map<Long, List<WatchOption>>, now: Instant): List<Pair<FootballMatch, List<WatchOption>>> =
        matches.filter { it.liveAt(now) }
            .mapNotNull { m -> watch[m.id]?.distinctBy { it.channel.url }?.takeIf { it.isNotEmpty() }?.let { m to it } }
}
