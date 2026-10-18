package app.couchcommander.data.iptv

/**
 * Panel film and series names against TMDB titles. Panels put language and quality tags around the name
 * ("EN - Dune: Part Two (2024) [4K]", "|NL| Dune", "4K-EN - Dune"); these come off for matching and make up the
 * version's label instead.
 */
object VodNames {
    /** A leading tag: `[EN]`, `|EN|`, `EN - `, `EN| `, `4K-EN - `, `NF: ` (capitals only before a colon, so "Alien: Romulus" stays whole). */
    private val PREFIX = Regex("""^\s*(?:\[([^\]]{1,12})]|\|([^|]{1,12})\||([A-Za-z0-9+-]{2,8})\s*(?:\||\s-\s|-\s)|([A-Z0-9]{2,3}):\s)\s*""")
    private val BRACKETED = Regex("""\s*[\[(]([^\])]{1,20})[\])]""")
    private val YEAR = Regex("""(?:^|[\s(\[-])((?:19|20)\d{2})[)\]]?\s*$""")
    private val TRAILING_SEPARATOR = Regex("""[\s\-|:.]+$""")
    private val LATEST_YEAR = java.time.Year.now().value + 3
    private val QUALITY = setOf("4k", "uhd", "fhd", "hd", "sd", "hdr", "hdr10", "dv", "hevc", "h265", "x265", "1080p", "720p", "2160p", "multi", "multisub", "multi sub", "sub", "subs", "dub", "dubbed", "vostfr", "vf", "vo")

    /** A panel name taken apart: what to match on, the year in it, and its tags ("EN", "4K"). */
    class Parsed(val title: String, val year: Int?, val tags: List<String>)

    fun parse(name: String): Parsed {
        var rest = name.trim()
        val tags = mutableListOf<String>()
        repeat(3) {
            val m = PREFIX.find(rest) ?: return@repeat
            val tag = m.groupValues.drop(1).first { it.isNotEmpty() }.trim()
            // "4K-EN" is two tags.
            tags += tag.split('-').filter { it.isNotBlank() }
            rest = rest.substring(m.range.last + 1)
        }
        // Bracketed quality tags anywhere ("(4K)", "[MULTI-SUB]"); a bracketed year is read below.
        rest = BRACKETED.replace(rest) { m ->
            val inside = m.groupValues[1].trim()
            if (Names.fold(inside) in QUALITY || Names.fold(inside).replace(" ", "") in QUALITY) {
                tags += inside.uppercase()
                ""
            } else m.value
        }
        var words = rest.trim().split(Regex("\\s+"))
        while (words.size > 1 && Names.fold(words.last()) in QUALITY) {
            tags += words.last().uppercase()
            words = words.dropLast(1)
        }
        rest = words.joinToString(" ")
        // A year still to come is part of the title ("Blade Runner 2049").
        val year = YEAR.find(rest)?.takeIf { it.range.first > 0 && it.groupValues[1].toInt() <= LATEST_YEAR }?.let {
            rest = rest.substring(0, it.range.first)
            it.groupValues[1].toInt()
        }
        rest = TRAILING_SEPARATOR.replace(rest, "")
        return Parsed(rest.trim(), year, tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
    }

    /** The year in a name, for panels that don't send one. */
    fun year(name: String): Int? = parse(name).year

    /** Forms of a panel name to look it up by: without its tags, and whole (in case a tag was really the title). */
    fun keys(name: String): Set<String> =
        setOf(key(parse(name).title), key(name)).filter { it.isNotEmpty() }.toSet()

    /** The lookup form of a title: folded, without a leading "the". */
    fun key(title: String): String = Names.fold(title).removePrefix("the ").trim()

    /** What a version is called on its chip: its tags ("EN 4K"), else its category. */
    fun label(entry: VodEntry): String =
        parse(entry.name).tags.joinToString(" ").ifEmpty { entry.category.trim() }.ifEmpty { "Play" }

    /**
     * An episode's name from the panel's title for it, which is often the file's name: "MobLand [4K] - S02E03"
     * has none, "Friends - S01E01 - Pilot" is "Pilot". A title without an "S01E01" is kept as it is.
     */
    fun episodeTitle(raw: String): String {
        val m = EPISODE_CODE.find(raw) ?: return raw.trim()
        return raw.substring(m.range.last + 1).trim { it.isWhitespace() || it in "-–—:|." }
    }

    private val EPISODE_CODE = Regex("""(?i)\bS\d{1,2}\s*E\d{1,3}\b""")

    /** Each version's chip label, numbered where two would read the same. */
    fun labels(versions: List<VodEntry>): List<String> {
        val plain = versions.map { label(it) }
        val counts = plain.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        return plain.map { l -> if ((counts[l] ?: 0) > 1) "$l ${seen.merge(l, 1, Int::plus)}" else l }
    }

    /**
     * The order versions are offered in: English (or untagged) before other languages, then the best picture
     * (4K, FHD, untagged, HD, SD). Read from the name's tags, else its category ("Czech and Slovakia", "SE Movies").
     */
    fun rank(entry: VodEntry): Int {
        val tags = parse(entry.name).tags.map { Names.fold(it).replace(" ", "") }
        val language = tags.filter { it !in QUALITY && it.length in 2..3 && it.all(Char::isLetter) }
        val category = Names.fold(entry.category).split(' ')
        val foreign = when {
            language.isNotEmpty() -> language.none { it in ENGLISH }
            else -> category.any { it in FOREIGN_WORDS || it in FOREIGN_CODES } && category.none { it in ENGLISH || it == "english" }
        }
        val quality = when {
            tags.any { it in setOf("4k", "uhd", "2160p") } -> 0
            tags.any { it in setOf("fhd", "1080p") } -> 1
            tags.any { it in setOf("hd", "720p") } -> 3
            tags.any { it == "sd" } -> 4
            else -> 2
        }
        return (if (foreign) 10 else 0) + quality
    }

    private val ENGLISH = setOf("en", "eng", "uk", "us", "gb")
    /** Country and language codes panels tag foreign versions with, in their categories. */
    private val FOREIGN_CODES = setOf("se", "sv", "swe", "no", "nor", "dk", "dan", "de", "ger", "fr", "fre", "es", "esp", "it", "ita", "nl", "pl", "pt", "br", "tr", "ar", "ru", "gr", "hu", "ro", "bg", "hr", "rs", "al", "cz", "sk", "in", "fi", "fin", "ex", "yu")
    private val FOREIGN_WORDS = setOf(
        "czech", "slovakia", "slovak", "swedish", "sverige", "svenska", "norsk", "norwegian", "dansk", "danish", "deutsch", "german", "germany",
        "french", "france", "francais", "spanish", "espanol", "latino", "italian", "italia", "arabic", "turkish", "turkce", "polish", "polska",
        "dutch", "nederlands", "portuguese", "brasil", "brazil", "hindi", "indian", "russian", "greek", "hungarian", "romanian", "bulgarian",
        "croatian", "serbian", "albanian", "persian", "nordic", "scandinavian", "finnish", "suomi", "kurdish", "ex",
    )

    /** Close enough: panels and TMDB often disagree by a year (festival vs release). */
    fun yearMatches(panel: Int?, tmdb: Int): Boolean = panel == null || tmdb <= 0 || kotlin.math.abs(panel - tmdb) <= 1
}
