package app.couchcommander.domain

import java.text.Normalizer

/**
 * A team's three-letter code for a matchday poster ("Manchester City" → "MCI"). The feed has none, so the big clubs
 * are listed and the rest are made from the name: one word's first three letters, else the first word's initial and
 * the next word's first two letters, after dropping "FC" and the like.
 */
object TeamCodes {
    private val KNOWN = mapOf(
        "arsenal" to "ARS", "aston villa" to "AVL", "bournemouth" to "BOU", "brentford" to "BRE", "brighton" to "BHA",
        "brighton and hove albion" to "BHA", "brighton & hove albion" to "BHA", "burnley" to "BUR", "chelsea" to "CHE",
        "crystal palace" to "CRY", "everton" to "EVE", "fulham" to "FUL", "ipswich town" to "IPS", "leeds united" to "LEE",
        "leicester city" to "LEI", "liverpool" to "LIV", "manchester city" to "MCI", "manchester united" to "MUN",
        "newcastle united" to "NEW", "nottingham forest" to "NFO", "southampton" to "SOU", "sunderland" to "SUN",
        "tottenham hotspur" to "TOT", "tottenham" to "TOT", "west ham united" to "WHU", "west ham" to "WHU",
        "wolverhampton wanderers" to "WOL", "wolverhampton" to "WOL", "wolves" to "WOL",
        "borussia dortmund" to "BVB", "bayern munchen" to "FCB", "bayern munich" to "FCB", "bayer leverkusen" to "B04",
        "rb leipzig" to "RBL", "borussia monchengladbach" to "BMG", "eintracht frankfurt" to "SGE", "union berlin" to "FCU",
        "real madrid" to "RMA", "barcelona" to "BAR", "atletico madrid" to "ATM", "athletic bilbao" to "ATH",
        "athletic club" to "ATH", "real sociedad" to "RSO", "real betis" to "BET",
        "inter" to "INT", "internazionale" to "INT", "ac milan" to "MIL", "milan" to "MIL", "juventus" to "JUV",
        "paris saint-germain" to "PSG", "paris saint germain" to "PSG", "paris sg" to "PSG",
        "fc inter turku" to "INT", "inter turku" to "INT", "ff jaro" to "JAR", "hjk" to "HJK", "hjk helsinki" to "HJK",
    )
    private val DROP = setOf("fc", "afc", "cf", "sc", "ac", "as", "ss", "sv", "fk", "ff", "if", "bk", "cd", "ud", "club", "de", "the", "1.")

    fun code(name: String): String {
        val folded = fold(name)
        KNOWN[folded]?.let { return it }
        val words = folded.split(' ').filter { it.isNotEmpty() && it !in DROP }.ifEmpty { folded.split(' ').filter { it.isNotEmpty() } }
        val letters = words.map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        val code = when {
            letters.isEmpty() -> name.take(3)
            letters.size == 1 -> letters[0].take(3)
            else -> letters[0].take(1) + letters[1].take(2)
        }
        return code.uppercase()
    }

    private fun fold(text: String) = Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ")
}
