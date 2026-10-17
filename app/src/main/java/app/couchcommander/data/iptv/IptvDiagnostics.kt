package app.couchcommander.data.iptv

import app.football.FootballMatch
import app.football.FootballTeam
import java.time.Instant
import org.xmlpull.v1.XmlPullParser

/**
 * "Why isn't this match found?": what the last sync kept for a search term, and with [probe] what the playlist
 * and guide themselves contain for it (downloaded again). Names, groups, ids and times only, never a URL.
 */
object IptvDiagnostics {
    private const val MAX_LINES = 40

    fun report(
        query: String,
        state: IptvState,
        matches: List<FootballMatch>,
        teams: List<FootballTeam>,
        watch: Map<Long, List<WatchOption>>,
        source: IptvSource?,
        probe: Boolean,
        fetch: IptvFetch,
        newParser: () -> XmlPullParser,
    ): List<String> {
        val q = Names.fold(query)
        fun hit(text: String) = q.isEmpty() || Names.fold(text).contains(q)
        val out = mutableListOf<String>()
        out += "source: ${when (source) { null -> "none"; is IptvSource.Xtream -> "Xtream"; is IptvSource.M3u -> "M3U, guide ${if (source.epgUrl != null) "set" else "from playlist header"}" }}"
        out += "followed: ${teams.joinToString { "${it.name} → ${Names.teamForms(it.name)}" }}"

        out += "-- matches named '$query' and what was found"
        matches.filter { hit(it.home.name) || hit(it.away.name) }.forEach { m ->
            out += "${m.home.name} – ${m.away.name} @ ${Instant.ofEpochMilli(m.kickoffEpochMillis)}: " +
                (watch[m.id]?.joinToString { "${it.channel.name} (score ${it.score})" } ?: "nothing")
        }
        out += "-- kept channels named '$query' (of ${state.channels.size})"
        state.channels.filter { hit(it.name) }.take(MAX_LINES).forEach { out += "${it.name} | ${it.group} | #${it.number} | epg=${it.epgId}" }
        out += "-- Channels tab: kept channels named '$query' (of ${state.popular.size})"
        state.popular.filter { hit(it.name) }.take(MAX_LINES).forEach { out += "${it.name} | ${it.group} | brand=${PopularChannels.brand(it)} | quality=${PopularChannels.quality(it)}" }
        out += "-- kept guide entries naming '$query' (of ${state.programmes.size})"
        state.programmes.filter { hit(it.title) }.take(MAX_LINES).forEach { out += "${it.epgId} | ${it.title} | ${Instant.ofEpochMilli(it.startEpochMillis)}" }

        if (probe && source != null) out += runCatching { probe(source, state, ::hit, fetch, newParser) }.getOrElse { listOf("probe failed: ${it.message}") }
        return out
    }

    private fun probe(source: IptvSource, state: IptvState, hit: (String) -> Boolean, fetch: IptvFetch, newParser: () -> XmlPullParser): List<String> {
        val out = mutableListOf<String>()
        val epgUrl = when (source) {
            is IptvSource.Xtream -> {
                val categories = fetch.get(Xtream.categoriesUrl(source), IptvRepository.API_MAX) { Xtream.parseCategories(it.reader().readText()) }
                out += "-- playlist: ${categories.size} live categories, ${categories.count { Sports.isSportsCategory(it.name) }} kept as sports"
                categories.filter { hit(it.name) || !Sports.isSportsCategory(it.name) && Names.fold(it.name).let { n -> "sport" in n || "liga" in n } }
                    .take(MAX_LINES).forEach { out += "category ${it.name} | kept=${Sports.isSportsCategory(it.name)}" }
                // Every sports stream with the term in its name, and what the Channels tab makes of it.
                val sports = categories.filter { Sports.isSportsCategory(it.name) }
                val streams = sports.flatMap { c ->
                    runCatching { fetch.get(Xtream.streamsUrl(source, c.id), IptvRepository.API_MAX) { Xtream.parseStreams(it.reader().readText(), source, c) } }.getOrDefault(emptyList())
                }
                val named = streams.filter { hit(it.name) }
                val popularAll = PopularChannels.keep(streams)
                out += "-- sports streams named it: ${named.size} (of ${streams.size}); Channels tab would keep ${popularAll.size} in all"
                named.take(MAX_LINES).forEach { ch ->
                    out += "stream ${ch.name} | ${ch.group} | brand=${PopularChannels.brand(ch)} | quality=${PopularChannels.quality(ch)} | kept=${popularAll.any { it.url == ch.url }}"
                }
                Xtream.xmltvUrl(source)
            }
            is IptvSource.M3u -> {
                val seen = mutableListOf<IptvChannel>()
                val parsed = fetch.get(source.url, IptvRepository.PLAYLIST_MAX) { input ->
                    M3u.parse(input.bufferedReader().lineSequence()) { c -> if (hit(c.name) && seen.size < MAX_LINES) seen += c; false }
                }
                out += "-- playlist: ${parsed.total} entries, header guide ${if (parsed.epgUrl != null) "present" else "missing"}"
                seen.forEach { out += "${it.name} | ${it.group} | kept=${Sports.isLiveSports(it)} | epg=${it.epgId}" }
                source.epgUrl ?: parsed.epgUrl
            }
        }
        if (epgUrl == null) return out + "-- no guide to check"
        val guide = fetch.get(epgUrl, IptvRepository.GUIDE_MAX) { input ->
            val parser = newParser()
            runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
            parser.setInput(input, null)
            Xmltv.parse(
                parser,
                // Every channel, so titles naming the term show up wherever they are.
                wantChannel = { _, _ -> true },
                from = Instant.now().minusSeconds(6 * 3600), to = Instant.now().plusSeconds(2 * 86_400),
                keepTitle = hit,
            )
        }
        val keptIds = state.channels.mapNotNullTo(mutableSetOf()) { it.epgId }
        val named = guide.channels.filter { (id, names) -> hit(id) || names.any(hit) }
        out += "-- guide: ${guide.channels.size} channels, ${named.size} named it; entries naming it in the next 2 days on any channel:"
        guide.programmes.take(MAX_LINES).forEach { p ->
            val names = guide.channels[p.epgId].orEmpty().joinToString(" / ")
            out += "guide entry ${p.epgId} ($names) | ${p.title} | ${Instant.ofEpochMilli(p.startEpochMillis)} | linked to a kept channel=${p.epgId in keptIds}"
        }
        named.entries.take(MAX_LINES).forEach { (id, names) -> out += "guide channel $id | ${names.joinToString(" / ")}" }
        return out
    }
}
