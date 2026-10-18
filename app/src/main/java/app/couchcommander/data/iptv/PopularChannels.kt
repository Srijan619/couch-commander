package app.couchcommander.data.iptv

import java.text.Normalizer

/**
 * The big sports brands' channels for the football page's "Channels" tab: something to watch, or to browse, when no
 * match was found in the guide. 4K, UHD and FHD first; HD and SD after them, apart. Matched by name only, so it works
 * without a guide.
 */
object PopularChannels {
    /** In the tab's order; each brand's names as [Names.fold] writes them, matched at word starts. */
    private val BRANDS = listOf(
        "Sky Sports" to listOf("sky sport"),
        "TNT Sports" to listOf("tnt sport", "tnt ultimate"),
        "V Sport" to listOf("v sport"),
        "Viaplay" to listOf("viaplay"),
        "DAZN" to listOf("dazn"),
        "ESPN" to listOf("espn"),
        "beIN Sports" to listOf("bein"),
        // Where the Champions League is in the US. Not "paramount": in playlists that's one slot per match, no use for browsing.
        "CBS Sports" to listOf("cbs sport", "cbs golazo", "golazo"),
        "Eurosport" to listOf("eurosport"),
        "Canal+ Sport" to listOf("canal sport", "canal plus sport"),
        "Movistar" to listOf("movistar"),
        "Sport TV" to listOf("sport tv"),
        "Ziggo Sport" to listOf("ziggo sport"),
        "Premier Sports" to listOf("premier sport"),
        "Eleven Sports" to listOf("eleven sport"),
        "SuperSport" to listOf("supersport"),
        "TV 2 Sport" to listOf("tv 2 sport", "tv2 sport"),
    ).map { (brand, names) -> brand to names.map { " $it" } }

    private val UHD = setOf("uhd", "4k", "2160p", "3840p")
    private val FHD = setOf("fhd", "1080p")
    /** Enough to cover a few countries per brand; the tab shows far fewer. */
    const val MAX_KEPT = 1_200
    /** Per brand, for each of the two quality groups. */
    const val MAX_PER_BRAND = 8

    /** [sharp]: 4K, UHD and FHD. [other]: HD, SD and untagged, shown after them under their own heading. */
    class Brand(val name: String, val sharp: List<Pick>, val other: List<Pick>)
    class Pick(val channel: IptvChannel, /** "Main Event" */ val name: String, /** "UK · UHD" */ val detail: String) {
        val label: String get() = if (detail.isEmpty()) name else "$name $detail"
    }

    /** Superscript and modifier letters, including the "ᶠᵖˢ" of "60ᶠᵖˢ". */
    private val SUPERSCRIPTS = Regex("[\\u00B2\\u00B3\\u00B9\\u02B0-\\u02FF\\u1D2C-\\u1DBF\\u2070-\\u209F\\u2C7D]+")

    /** Superscript tags ("1ᵁᴴᴰ", "²") as a plain word of their own ("1 UHD"), so they're read and dropped like any other tag. */
    private fun plain(text: String) = SUPERSCRIPTS.replace(text) { " " + Normalizer.normalize(it.value, Normalizer.Form.NFKC) }

    fun brand(channel: IptvChannel): String? {
        val name = " " + Names.fold(plain(channel.name))
        return BRANDS.firstOrNull { (_, names) -> names.any { name.contains(it) } }?.first
    }

    /** 2 for 4K/UHD, 1 for FHD, 0 otherwise; the group counts too ("UK| SPORTS UHD"). */
    fun quality(channel: IptvChannel): Int {
        val words = (Names.fold(plain(channel.name)) + " " + Names.fold(channel.group)).split(' ').toSet()
        return when {
            words.any { it in UHD } -> 2
            words.any { it in FHD } -> 1
            else -> 0
        }
    }

    /** Pay-per-view and event slots show nothing between events, so they're no use for browsing. */
    private val EVENT_SLOTS = setOf("ppv", "events") // not "event": Sky Sports Main Event is a regular channel

    /** What a sync keeps: the brands' regular channels, sharpest first so the cap drops SD before 4K. */
    fun keep(channels: List<IptvChannel>): List<IptvChannel> =
        channels.filter { brand(it) != null && !isEventSlot(it) }.distinctBy { it.url }.sortedByDescending { quality(it) }.take(MAX_KEPT)

    /** "vs" or "v." between teams; not a bare "v", which is also V Sport ("Elisa V Sport 1"). */
    private val VERSUS = Regex("""\s(vs\.?|v\.)\s""", RegexOption.IGNORE_CASE)

    /** The brands' film and entertainment channels ("beIN Entertainment") aren't sport. */
    private val NOT_SPORT = setOf("entertainment", "movies", "movie", "drama", "series", "kids")
    /** Nor, for the football page, are their channels for another sport ("Sky Sport MotoGP 4K"). */
    private val OTHER_SPORT = setOf(
        "tennis", "motogp", "f1", "formula", "nba", "nfl", "nhl", "mlb", "golf", "cricket", "darts", "racing", "cycling",
        "rugby", "ufc", "boxing", "wwe", "snooker", "basket", "basketball", "hockey", "baseball",
    )

    /**
     * Not a channel to browse: an event or PPV slot, a playlist's section divider ("##### MOVISTAR #####"), or one of a
     * brand's non-sport or non-football channels.
     */
    private fun isEventSlot(channel: IptvChannel): Boolean {
        if ("##" in channel.name) return true
        // A slot named after one match carries its own "|" or " vs " after the provider prefix ("US| ESPN+ US | Macarthur vs …").
        val rest = plain(channel.name).replace(PREFIX, "")
        if ('|' in rest || VERSUS.containsMatchIn(rest)) return true
        // Bracketed tags label a regular channel too: "UK| TNT ULTIMATE 4K+ [LIVE-EVENT]" is TNT's 4K channel.
        val words = Names.fold(rest.replace(BRACKETS, " ")).split(' ')
        // "Only Event 3" is a slot; "Main Event" is Sky's main channel.
        val slot = words.withIndex().any { (i, w) -> w == "event" && words.getOrNull(i - 1) != "main" }
        return slot || words.any { it in EVENT_SLOTS || it in NOT_SPORT || it in OTHER_SPORT }
    }

    /**
     * [channels] by brand, in the brands' order, each split by quality. Within a group, [home]'s channels first, then
     * English-language ones, then the sharper feed; the same channel in UHD and FHD shows once, as the sharper one.
     */
    fun group(channels: List<IptvChannel>, home: String?): List<Brand> =
        // Filtered again here: a list stored by an older sync can still hold event slots. Each channel's name is read
        // once up front: the reading (folding, superscripts, regexes) is what costs, and sorting would repeat it.
        channels.filterNot(::isEventSlot)
            .mapNotNull { c -> brand(c)?.let { b -> quality(c).let { q -> Facts(c, b, q, name(c, b), country(c), qualityTag(c, q)) } } }
            .groupBy { it.brand }
            .map { (brand, list) ->
                val picks = list
                    .sortedWith(compareByDescending<Facts> { it.quality }.thenBy { it.channel.number ?: Int.MAX_VALUE })
                    // The same channel from one country once, as its sharpest feed.
                    .distinctBy { "${it.name.lowercase()}|${it.country}" }
                    .sortedBy { if (home != null && it.country == home) 0 else if (it.country in ENGLISH) 1 else 2 }
                val (sharp, other) = picks.partition { it.quality > 0 }
                Brand(brand, sharp.take(MAX_PER_BRAND).map(Facts::pick), other.take(MAX_PER_BRAND).map(Facts::pick))
            }
            .sortedBy { b -> BRANDS.indexOfFirst { it.first == b.name } }

    private class Facts(val channel: IptvChannel, val brand: String, val quality: Int, val name: String, val country: String?, val tag: String?) {
        fun pick() = Pick(channel, name, listOfNotNull(country, tag).joinToString(" · "))
    }

    /**
     * Any channel as a tile, for "Most watched": a brand's channel as in its brand's row ("Main Event", "UK · UHD"),
     * anything else by its provider name ("Telia Play", "FI").
     */
    fun pick(channel: IptvChannel): Pick {
        val brand = brand(channel)
        if (brand != null) return Pick(channel, withBrand(name(channel, brand), brand), listOfNotNull(country(channel), qualityTag(channel)).joinToString(" · "))
        val label = Names.provider(channel, event = false)
        val words = label.split(' ')
        val code = words.lastOrNull()?.takeIf { words.size > 1 && it.length in 2..3 && it.all { c -> c.isUpperCase() } }
        val name = if (code != null) words.dropLast(1).joinToString(" ") else label
        return Pick(channel, name, listOfNotNull(code, qualityTag(channel)).joinToString(" · "))
    }

    /** A tile's name where no brand row names it ("On now", "Most watched"): "Main Event" → "Sky Sports Main Event". */
    fun withBrand(name: String, brand: String): String = if (name.startsWith(brand, ignoreCase = true)) name else "$brand $name"

    /** Countries whose feeds have English commentary. */
    private val ENGLISH = setOf("UK", "GB", "IE", "US", "CA", "AU")

    private val PREFIX = Regex("""^\s*([A-Za-z]{2,3})\s*[|:]\s*""")
    private val BRACKETS = Regex("""[\[(][^\])]*[\])]""")
    /** "50 fps", "60fps": a feed's frame rate, not part of its name. */
    private val FRAME_RATE = Regex("""\b(25|30|50|60)\s*fps\b""", RegexOption.IGNORE_CASE)
    private val DROP = UHD + FHD + setOf("hd", "sd", "lq", "hq", "720p", "720", "1080", "2160", "hevc", "h265", "h264", "vip", "backup", "raw", "multi", "hdr", "50fps", "60fps")

    private fun country(channel: IptvChannel) = PREFIX.find(channel.name)?.groupValues?.get(1)?.uppercase()

    private fun qualityTag(channel: IptvChannel, quality: Int = quality(channel)): String? = when (quality) {
        2 -> "UHD"
        1 -> "FHD"
        else -> (Names.fold(plain(channel.name)) + " " + Names.fold(channel.group)).split(' ').let { w ->
            when {
                "hd" in w -> "HD"
                "sd" in w -> "SD"
                else -> null
            }
        }
    }

    /**
     * The channel as its tile shows it: without the brand the row already names, the provider prefix, quality
     * tags and country (the tile's second line has those). "UK| Sky Sports Main Event UHD" → "Main Event".
     */
    private fun name(channel: IptvChannel, brand: String): String {
        val words = plain(channel.name).replace(PREFIX, "").replace(BRACKETS, " ").replace(FRAME_RATE, " ")
            .split(' ', '/').filter { it.isNotBlank() && Names.fold(it) !in DROP }
        val patterns = BRANDS.first { it.first == brand }.second.map { it.trim() }
        // The fewest leading words that spell the brand ("Sky Sports" for "sky sport").
        val brandWords = (1..words.size).firstOrNull { n -> Names.fold(words.take(n).joinToString(" ")).let { f -> patterns.any { f.startsWith(it) } } } ?: 0
        val kept = words.drop(brandWords).ifEmpty { words }
        // Playlists often shout ("MAIN EVENT"); short codes ("UK", "F1", "PL") and acronyms stay as they are.
        val shouting = kept.none { w -> w.any { it.isLowerCase() } }
        val rest = kept.joinToString(" ") { w ->
            when {
                // "PL" beside "Premier League" read as two channels; spelled out, the two are one and dedupe.
                w.equals("PL", ignoreCase = true) -> "Premier League"
                !shouting || w.length <= 3 || w.uppercase() in ACRONYMS -> w
                else -> SPELLED[w.lowercase()] ?: w.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
        // A bare number or sign says nothing on its own: "251" is "Sky Sports 251", "+" is "Sky Sports+".
        return when {
            rest.any { it.isLetter() } -> rest
            rest.any { it.isDigit() } -> "$brand $rest"
            else -> "$brand$rest"
        }
    }

    private val ACRONYMS = setOf("DAZN", "ESPN", "UEFA", "NFL", "NBA", "NHL", "MLB", "UFC", "WWE")
    private val SPELLED = mapOf("laliga" to "LaLiga", "bein" to "beIN")
}
