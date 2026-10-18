package app.couchcommander.domain

import app.media.Episode
import app.media.MediaKind
import app.media.MovieDetails
import app.media.WatchProvider
import app.media.WatchProviders
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words on the Movies and TV pages: a title's facts line, where to watch it, an episode's line. */
object MediaText {
    /** "2 h 33 min", "47 min"; empty when unknown. */
    fun runtime(minutes: Int): String = when {
        minutes <= 0 -> ""
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    /** "1927 · 2 h 33 min · Science fiction, Drama"; a series has its seasons where a film has its length. */
    fun facts(title: MovieDetails): String {
        val length = when (title.kind) {
            MediaKind.Movie -> runtime(title.runtimeMinutes)
            MediaKind.Tv -> listOf(
                if (title.seasons > 0) plural(title.seasons, "season") else "",
                runtime(title.runtimeMinutes),
            ).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        return listOf(title.year.toString(), length, title.genres.take(2).joinToString(", "))
            .filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** "Fritz Lang · Brigitte Helm, Alfred Abel"; a series' creators stand where a film's director does. */
    fun people(title: MovieDetails): String =
        listOf(title.director, title.cast.take(3).joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · ")

    /**
     * Where to watch in [country] (a code such as "FI"), in one line: "In Finland: on Netflix and Max · rent on
     * Apple TV". Null when it isn't on any service there.
     */
    fun whereToWatch(providers: WatchProviders?, country: String): String? {
        if (providers == null || providers.isEmpty) return null
        val parts = listOfNotNull(
            names(providers.stream)?.let { "on $it" },
            names(providers.free)?.let { "free on $it" },
            names(providers.rent)?.let { "rent on $it" },
            // Buying only when it can't be rented: the same shops usually offer both.
            names(if (providers.rent.isEmpty()) providers.buy else emptyList())?.let { "buy on $it" },
        )
        return "In ${countryName(country)}: " + parts.joinToString(" · ")
    }

    /** "30 min · 12 Mar 1952", or "Upcoming · 2 Nov 2026" for one not out yet on [today]. */
    fun episodeLine(episode: Episode, today: LocalDate): String {
        val date = episode.airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val day = date?.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
        return if (episode.isUpcoming(today.toString())) {
            listOfNotNull("Upcoming", day).joinToString(" · ")
        } else {
            listOf(runtime(episode.runtimeMinutes), day.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    }

    /** The user's own TMDB rating: "8", or "7.5" for a half step. */
    fun score(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale.ROOT, value)

    fun countryName(code: String): String = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifEmpty { code }

    /** "Netflix", "Netflix and Max", "Netflix, Max and 2 more". */
    private fun names(providers: List<WatchProvider>): String? {
        val all = providers.map { it.name }
        return when {
            all.isEmpty() -> null
            all.size == 1 -> all[0]
            all.size == 2 -> "${all[0]} and ${all[1]}"
            all.size == 3 -> "${all[0]}, ${all[1]} and ${all[2]}"
            else -> "${all[0]}, ${all[1]} and ${all.size - 2} more"
        }
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}
