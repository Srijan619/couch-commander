package app.couchcommander.domain

import app.football.FootballBoard
import app.football.FootballBoards
import app.football.FootballMatch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The football block on home, as text: a small label, the match, and when and where. Only the next match shows. */
data class FootballLines(val label: String, val live: Boolean, val title: String, val detail: String)

/** Turns football-core's board into home's wording. Kept free of Android types so it can be unit tested. */
object FootballText {
    fun lines(board: FootballBoard, now: Instant, zone: ZoneId, is24Hour: Boolean, locale: Locale = Locale.getDefault()): FootballLines? {
        val m = board.featured ?: return null
        val when_ = "${day(m.kickoff, now, zone, locale)} · ${FootballBoards.timeLabel(m.kickoff, zone, is24Hour, locale)}"
        return when {
            m.live -> FootballLines(
                label = listOfNotNull("Live", m.liveTime).joinToString(" · "),
                live = true,
                title = scored(m),
                detail = m.tournament.orEmpty(),
            )
            m.finished -> FootballLines(
                label = when (m.result?.uppercase(locale)) {
                    "AET" -> "After extra time"
                    "PEN" -> "After penalties"
                    else -> "Full time"
                },
                live = false,
                title = scored(m),
                detail = m.tournament.orEmpty(),
            )
            else -> FootballLines(
                label = FootballBoards.kickoffIn(m.kickoff, now)?.let { (h, min) ->
                    if (h > 0) "Kick-off in $h h $min min" else "Kick-off in $min min"
                } ?: "Next match",
                live = false,
                title = "${m.home.name} – ${m.away.name}",
                detail = listOfNotNull(when_, m.tournament).joinToString(" · "),
            )
        }
    }

    private fun scored(m: FootballMatch) = "${m.home.name} ${m.home.score ?: 0} – ${m.away.score ?: 0} ${m.away.name}"

    /** "Today", "Tomorrow", else "Sat 18 Oct". */
    internal fun day(kickoff: Instant, now: Instant, zone: ZoneId, locale: Locale): String {
        val at = kickoff.atZone(zone)
        return when (ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), at.toLocalDate())) {
            0L -> "Today"
            1L -> "Tomorrow"
            else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
        }
    }
}
