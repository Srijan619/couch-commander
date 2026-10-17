package app.couchcommander.data.iptv

import java.text.Normalizer

/** Text normalising for matching playlist channels to the guide and guide titles to Footy team names. */
object Names {
    private val MARKS = Regex("""\p{M}+""")
    private val NON_WORD = Regex("""[^a-z0-9]+""")
    private val BRACKETS = Regex("""[\[(][^\])]*[\])]""")
    /** `WC|`, `US:`, `UK -` style prefixes that providers put before every channel name. */
    private val PREFIX = Regex("""^\s*[A-Za-z0-9]{1,4}\s*[|:]\s*""")
    private val QUALITY = setOf("fhd", "uhd", "hd", "sd", "4k", "8k", "hevc", "h265", "h264", "50fps", "60fps", "raw", "vip", "backup", "multi", "hdr", "lq", "hq")

    /** Lowercase ASCII words separated by single spaces: "Bayern München" → "bayern munchen". */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase().replace("ß", "ss").replace("ø", "o").replace("æ", "ae").replace("&", " and "), Normalizer.Form.NFD)
            .replace(MARKS, "").replace(NON_WORD, " ").trim()

    /** A channel name without provider prefixes and quality tags: "WC| ZDF FHD" and "ZDF HD" both give "zdf". */
    fun channel(name: String): String =
        fold(name.replace(PREFIX, "").replace(BRACKETS, " ")).split(' ').filter { it.isNotEmpty() && it !in QUALITY }.joinToString(" ")

    private val AFFIXES = setOf(
        "fc", "afc", "cf", "sc", "fk", "if", "ac", "as", "ss", "sv", "vfb", "vfl", "tsg", "rc", "cd", "ud", "sd", "bk", "ik", "ff", "ifk",
        "sk", "nk", "hnk", "gnk", "rcd", "ca", "club", "de", "futbol", "football", "calcio", "1", "jk", "ps", "kf", "fbc",
    )
    private val SUFFIXES = setOf("united", "town", "athletic", "rovers", "albion", "county", "wanderers", "hotspur")
    /** Places with two big clubs, where dropping "United"/"City" would mix them up. */
    private val SHARED = setOf("manchester", "sheffield", "bristol", "nottingham", "birmingham")
    private val ALIASES = mapOf(
        "manchester united" to listOf("man utd", "man united"),
        "manchester city" to listOf("man city"),
        "tottenham hotspur" to listOf("spurs"),
        "wolverhampton wanderers" to listOf("wolves"),
        "brighton and hove albion" to listOf("brighton"),
        "nottingham forest" to listOf("nottm forest", "notts forest"),
        "sheffield united" to listOf("sheff utd"),
        "paris saint germain" to listOf("psg", "paris sg"),
        "internazionale" to listOf("inter", "inter milan"),
        "inter" to listOf("internazionale", "inter milan"),
        "bayern munchen" to listOf("bayern", "bayern munich"),
        "barcelona" to listOf("barca"),
        "atletico madrid" to listOf("atletico", "atleti"),
        "borussia dortmund" to listOf("dortmund", "bvb"),
        "borussia monchengladbach" to listOf("gladbach", "monchengladbach"),
        "bayer leverkusen" to listOf("leverkusen"),
        "rb leipzig" to listOf("leipzig"),
        "ac milan" to listOf("milan"),
        "sporting cp" to listOf("sporting", "sporting lisbon"),
    )

    /**
     * The ways a guide title may write a team: "Wolverhampton Wanderers" → "wolverhampton wanderers",
     * "wolverhampton", "wolves". "FF Jaro" → "ff jaro", "jaro".
     */
    fun teamForms(name: String): Set<String> {
        val full = fold(name)
        if (full.isEmpty()) return emptySet()
        val forms = linkedSetOf(full)
        val words = full.split(' ')
        val core = words.filter { it !in AFFIXES }.ifEmpty { words }
        forms += core.joinToString(" ")
        if (core.size > 1 && core.last() in SUFFIXES && core.first() !in SHARED) forms += core.dropLast(1).joinToString(" ")
        forms.toList().forEach { f -> ALIASES[f]?.let { forms += it } }
        // Single short words ("as", "ik") would match anywhere.
        return forms.filterTo(linkedSetOf()) { it.length >= 3 }
    }

    /** True when [title] (already [fold]ed) contains one of [forms] as whole words. */
    fun mentions(title: String, forms: Set<String>): Boolean {
        val padded = " $title "
        return forms.any { padded.contains(" $it ") }
    }

    private val COUNTRY_PREFIX = Regex("""^\s*([A-Za-z]{2,3})\s*[|:]""")
    private val COUNTRY_WORDS = mapOf(
        "finland" to "FI", "sweden" to "SE", "denmark" to "DK", "norway" to "NO", "iceland" to "IS", "germany" to "DE",
        "spain" to "ES", "portugal" to "PT", "france" to "FR", "italy" to "IT", "netherlands" to "NL", "poland" to "PL",
        "uk" to "UK", "usa" to "US", "canada" to "CA", "australia" to "AU",
    )
    private val TAGS = QUALITY + setOf("ppv", "exclusive", "bk", "live", "event", "events", "next", "channel", "ch")
    private val UPPER = setOf("dazn", "tv", "bt", "tnt", "espn", "rtl", "zdf", "ard", "orf", "srf", "nos", "rai", "nbc", "cbs", "dyn", "mtv", "tv2")

    /**
     * A short provider name for a Watch chip. Event channels ("Union Berlin vs Dortmund @ … :Viaplay NL 17") are
     * named after their group ("NL| VIAPLAY PPV BK" → "Viaplay NL"); others after the channel ("SI| SPORT TV 1 ⱽᴵᴾ" →
     * "Sport TV 1 SI").
     */
    fun provider(channel: IptvChannel, event: Boolean): String {
        val base = if (event && channel.group.isNotBlank()) channel.group else channel.name
        var code = COUNTRY_PREFIX.find(base)?.groupValues?.get(1)?.uppercase()
        var words = fold(base.replace(COUNTRY_PREFIX, " ")).split(' ').filter { it.isNotEmpty() && it !in TAGS }
        if (code == null) COUNTRY_WORDS[words.firstOrNull()]?.let { code = it; words = words.drop(1) }
        val name = words.joinToString(" ") { w ->
            when {
                w in UPPER -> w.uppercase()
                w == "bein" -> "beIN"
                else -> w.replaceFirstChar { it.uppercase() }
            }
        }.ifEmpty { channel.name.take(20) }
        return if (code == null || name.endsWith(" $code", ignoreCase = true)) name else "$name $code"
    }

    private val ZONE_COUNTRIES = mapOf(
        "Helsinki" to "FI", "Mariehamn" to "FI", "Stockholm" to "SE", "Oslo" to "NO", "Copenhagen" to "DK", "Reykjavik" to "IS",
        "Berlin" to "DE", "Vienna" to "AT", "Zurich" to "CH", "Madrid" to "ES", "Lisbon" to "PT", "Paris" to "FR", "Rome" to "IT",
        "Amsterdam" to "NL", "Brussels" to "BE", "London" to "UK", "Dublin" to "IE", "Warsaw" to "PL", "Tallinn" to "EE",
    )

    /**
     * Where the TV is, for putting the viewer's own country's channels first. TVs are often left on en-US, so the
     * time zone ("Europe/Helsinki") counts before the locale.
     */
    fun homeCountry(zoneId: String, localeCountry: String): String? =
        ZONE_COUNTRIES[zoneId.substringAfterLast('/')] ?: localeCountry.uppercase().takeIf { it.length == 2 && it != "US" }

    /** Puts [home]'s providers first, keeping the finder's order otherwise (the sort is stable). */
    fun homeFirst(labels: List<String>, home: String?): List<String> =
        if (home == null) labels else labels.sortedBy { if (it.endsWith(" $home")) 0 else 1 }
}
