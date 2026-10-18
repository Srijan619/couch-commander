package app.couchcommander.domain

import app.football.FootballMatch
import app.football.FootballTeam
import app.football.MatchTimes
import java.time.Instant
import java.time.ZoneId

/**
 * The full-screen football page (design D) is a place to watch from, so it lists only what you could stream
 * today: the followed teams' matches on now, then today's later kickoffs. Results and later days stay on home.
 */
object FootballPage {
    const val MAX_MATCHES = 3

    class Today(
        val matches: List<FootballMatch>,
        /** The first match after today, for the "nothing today" line. */
        val next: FootballMatch?,
        /** With nothing today, each followed team's next match, soonest first. */
        val upcoming: List<FootballMatch> = emptyList(),
    )

    fun today(matches: List<FootballMatch>, teams: List<FootballTeam>, now: Instant, zone: ZoneId): Today {
        val ids = teams.map { it.id }.toSet()
        // Same kickoff: the team followed first goes first.
        fun rank(m: FootballMatch) = teams.indexOfFirst { it.id == m.home.id || it.id == m.away.id }
        val own = matches
            .filter { !it.cancelled && !it.finished && (it.home.id in ids || it.away.id in ids) && now.isBefore(it.kickoff.plus(MatchTimes.STALE_AFTER)) }
            .sortedWith(compareBy<FootballMatch> { if (it.live) 0 else 1 }.thenBy { it.kickoffEpochMillis }.thenBy { rank(it) })
        val today = now.atZone(zone).toLocalDate()
        val (watchable, later) = own.partition { it.live || !it.kickoff.atZone(zone).toLocalDate().isAfter(today) }
        val upcoming = if (watchable.isNotEmpty()) emptyList()
        else teams.mapNotNull { t -> later.firstOrNull { it.home.id == t.id || it.away.id == t.id } }.distinctBy { it.id }.sortedBy { it.kickoffEpochMillis }
        return Today(watchable.take(MAX_MATCHES), later.firstOrNull(), upcoming)
    }
}
