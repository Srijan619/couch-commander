package app.couchcommander.domain

import app.couchcommander.player.WatchAttempt
import app.iptv.ChannelHealth
import app.iptv.ChannelRecord
import app.iptv.Names
import app.iptv.WatchOption
import java.time.Instant

/** A match's ways to watch: [usable] feeds in the order to try them, and [providers], one per chip. */
data class MatchChips(val usable: List<WatchOption>, val providers: List<Pair<String, WatchOption>>)

/**
 * Which channels to offer for a match and in what order, for the football page's chips and for a phone's
 * "Watch on TV" alike, so both try the same channels the same way.
 */
object MatchWatch {
    /** How many channels the player works through before giving up (15 s each at worst). */
    const val MAX_ATTEMPTS = 6

    /**
     * Hidden channels drop out, ones that played lately come first and recent failures last (ChannelHealth); then one
     * chip per provider, the viewer's country first. Health counts more than country: a Finnish channel that just failed
     * still goes after one that works.
     */
    fun chips(options: List<WatchOption>, health: Map<String, ChannelRecord>, home: String?, now: Instant): MatchChips {
        val usable = ChannelHealth.order(options, health, now) { it.channel }
        val byLabel = usable.groupBy { Names.provider(it.channel, it.score == 3) }.mapValues { it.value.first() }
        val byCountry = Names.homeFirst(byLabel.keys.toList(), home).map { it to byLabel.getValue(it) }
        return MatchChips(usable, ChannelHealth.order(byCountry, health, now) { it.second.channel })
    }

    /** Pressing a channel tries it first, then the other providers, then every other feed for the match. */
    fun attempts(chips: MatchChips, first: WatchOption): List<WatchAttempt> =
        (listOf(first) + chips.providers.map { it.second } + chips.usable).distinctBy { it.channel.url }.take(MAX_ATTEMPTS)
            .map { WatchAttempt(Names.provider(it.channel, it.score == 3), it.channel, event = it.score == 3) }
}
