package app.couchcommander.domain

import app.football.FootballMatch
import app.football.MatchTimes
import java.time.Instant
import java.time.ZoneId

/**
 * One league's tab on the football page (design E): today's matches of that league, live ones first, then later
 * kickoffs, then today's results under "Earlier today". With nothing today, the next match day instead.
 */
object LeaguePage {
    class Day(
        val matches: List<FootballMatch>,
        /** Where today's results start in [matches], for the "Earlier today" heading; -1 without any. */
        val firstResult: Int,
        /** Only with nothing today. */
        val next: NextDay?,
        /** The next match day's matches, by kickoff; only with nothing today. */
        val nextMatches: List<FootballMatch> = emptyList(),
    )

    /** The next match day: its first kickoff and how many matches it has. */
    class NextDay(val kickoff: Instant, val count: Int)

    fun today(matches: List<FootballMatch>, now: Instant, zone: ZoneId): Day {
        val today = now.atZone(zone).toLocalDate()
        fun day(m: FootballMatch) = m.kickoff.atZone(zone).toLocalDate()
        val played = matches.filter { !it.cancelled }
        // A match past midnight that's still on counts as today's.
        val todays = played.filter { day(it) == today || it.live }
        // A match Footy never marked finished is over all the same, a while after kickoff.
        val (results, open) = todays.partition { it.finished || !now.isBefore(it.kickoff.plus(MatchTimes.STALE_AFTER)) }
        val ordered = open.sortedWith(compareBy<FootballMatch> { if (it.live) 0 else 1 }.thenBy { it.kickoffEpochMillis }) +
            results.sortedBy { it.kickoffEpochMillis }
        val first = if (todays.isNotEmpty()) null else played.filter { day(it).isAfter(today) }.minByOrNull { it.kickoffEpochMillis }
        val nextMatches = first?.let { f -> played.filter { day(it) == day(f) }.sortedBy { it.kickoffEpochMillis } }.orEmpty()
        val next = first?.let { NextDay(it.kickoff, nextMatches.size) }
        return Day(ordered, if (results.isEmpty()) -1 else open.size, next, nextMatches)
    }
}
