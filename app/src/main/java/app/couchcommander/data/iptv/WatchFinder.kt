package app.couchcommander.data.iptv

import app.football.FootballMatch
import java.time.Duration

/**
 * Finds where a followed team's match is on: a channel named after the match ("EPL 03: Arsenal v Chelsea"),
 * or a guide entry that names the teams around kickoff. Cheap: works on the few hundred kept channels and the
 * handful of kept programmes, so it runs whenever the matches change.
 */
object WatchFinder {
    /** A guide entry counts if it starts at most this long before kickoff (pre-match shows)… */
    private val EARLIEST_START = Duration.ofHours(3).toMillis()
    /** …and no later than this after it. */
    private val LATEST_START = Duration.ofMinutes(30).toMillis()
    private val NOT_LIVE = listOf("highlights", "replay", "repeat", "rewind", "classic", "review", "best of", "re run", "rerun", "uusinta", "kooste")
    /** Enough for a few different providers once duplicates (the same feed in FHD and HD) share a chip. */
    const val MAX_OPTIONS = 12

    fun find(matches: List<FootballMatch>, channels: List<IptvChannel>, programmes: List<IptvProgramme>): Map<Long, List<WatchOption>> {
        if (channels.isEmpty()) return emptyMap()
        val byEpg = channels.filter { it.epgId != null }.groupBy { it.epgId!! }
        val folded = channels.map { it to Names.fold(it.name) }
        val titles = programmes.map { it to Names.fold(it.title) }.filter { (_, t) -> NOT_LIVE.none { " $t ".contains(" $it ") } }

        return matches.filter { !it.finished && !it.cancelled }.associate { match ->
            val home = Names.teamForms(match.home.name)
            val away = Names.teamForms(match.away.name)
            val kickoff = match.kickoffEpochMillis
            val options = mutableListOf<WatchOption>()

            for ((channel, name) in folded) {
                if (Names.mentions(name, home) && Names.mentions(name, away)) options += WatchOption(channel, score = 3)
            }
            for ((p, title) in titles) {
                if (p.startEpochMillis < kickoff - EARLIEST_START || p.startEpochMillis > kickoff + LATEST_START || p.stopEpochMillis <= kickoff) continue
                val both = Names.mentions(title, home) && Names.mentions(title, away)
                val one = both || Names.mentions(title, home) || Names.mentions(title, away)
                if (!one) continue
                byEpg[p.epgId].orEmpty().forEach { options += WatchOption(it, p.title, p.startEpochMillis, if (both) 2 else 1) }
            }

            match.id to options
                .sortedWith(compareByDescending<WatchOption> { it.score }.thenBy { it.channel.number ?: Int.MAX_VALUE })
                .distinctBy { it.channel.url }
                .take(MAX_OPTIONS)
        }.filterValues { it.isNotEmpty() }
    }
}
