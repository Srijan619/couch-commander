package app.couchcommander.data.iptv

/**
 * Which playlist channels to keep: live sports channels, by group or name. A full playlist is mostly films,
 * series and general TV, so this drops well over 90% of it while it streams in.
 */
object Sports {
    private val KEYWORDS = listOf(
        "sport", "desporto", "deporte", "esporte", "football", "soccer", "futbol", "futebol", "fussball", "fotboll", "jalkapallo",
        "voetbal", "calcio", "nogomet", "fudbal", "pilka", "campeones", "campeoes",
        "premier league", "epl", "laliga", "la liga", "bundesliga", "serie a", "ligue 1", "eredivisie", "veikkausliiga", "allsvenskan",
        "champions league", "europa league", "conference league", "uefa", "fifa", "world cup", "mundial", "euro 20", "copa",
        "dazn", "espn", "bein", "sky sport", "tnt sport", "viaplay", "v sport", "eurosport", "cmore", "c more", "ziggo",
        "canal sport", "canal+ sport", "arena sport", "supersport", "setanta", "fox sports", "star sports", "ruutu", "elisa viihde sport",
        "match tv", "movistar", "nova sport", "polsat sport", "digi sport", "ppv", "live event", "events",
    )
    private val FOLDED = KEYWORDS.map { " " + Names.fold(it) }
    /** VOD groups: a "Sports documentaries" film collection isn't a live channel. Matched as word starts. */
    private val NOT_LIVE = listOf(" vod", " movie", " film", " series", " documentar", " replay", " catch up")
    private val VOD_PATHS = listOf("/movie/", "/series/")

    fun isLiveSports(channel: IptvChannel): Boolean {
        val group = " " + Names.fold(channel.group)
        if (VOD_PATHS.any { channel.url.contains(it, ignoreCase = true) } || NOT_LIVE.any { group.contains(it) }) return false
        return isSportsName(group) || isSportsName(" " + Names.fold(channel.name))
    }

    /** For Xtream category names. */
    fun isSportsCategory(name: String): Boolean {
        val folded = " " + Names.fold(name)
        return NOT_LIVE.none { folded.contains(it) } && isSportsName(folded)
    }

    /** [padded] starts with a space; keywords match at word starts ("sport" also matches "sports"). */
    private fun isSportsName(padded: String): Boolean = FOLDED.any { padded.contains(it) }
}
