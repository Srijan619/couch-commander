package app.couchcommander.data.media

import app.media.Episode
import app.media.MediaKind
import app.media.MovieCache
import app.media.MovieDetails
import app.media.MoviePage
import app.media.MovieRepository
import app.media.MovieSort
import app.media.PersonDetails
import app.media.SearchPage
import app.media.SearchScope
import app.media.TitleExtras
import app.media.TmdbPeople
import app.media.TmdbSearch
import io.ktor.client.HttpClient
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The rows of the Movies and TV pages, in order: TMDB's lists, then genres. A genre has TMDB's id for films and for
 * series (they differ, as for sci-fi); null leaves it off that page.
 */
enum class Shelf(
    val title: String,
    val note: String,
    private val movieGenre: Int? = null,
    private val tvGenre: Int? = null,
    /** A genre left out, the same id for films and series. */
    val without: Int? = null,
) {
    Trending("Trending", "This week"),
    TopRated("Top rated", "All time"),
    Newest("New", "Latest releases"),
    Action("Action", "Popular", movieGenre = 28, tvGenre = 10759),
    Comedy("Comedy", "Popular", movieGenre = 35, tvGenre = 35),
    Drama("Drama", "Popular", movieGenre = 18, tvGenre = 18),
    Crime("Crime", "Popular", movieGenre = 80, tvGenre = 80),
    SciFi("Sci-fi", "Popular", movieGenre = 878, tvGenre = 10765),
    Thriller("Thriller", "Popular", movieGenre = 53),
    Mystery("Mystery", "Popular", tvGenre = 9648),
    Horror("Horror", "Popular", movieGenre = 27),
    Animation("Animation", "Popular", movieGenre = 16, tvGenre = 16),
    // Most animation is tagged Family too: without it, the two shelves would start with the same titles.
    LiveFamily("Family", "Live action", movieGenre = 10751, tvGenre = 10751, without = 16),
    Documentary("Documentary", "Popular", movieGenre = 99, tvGenre = 99);

    val isGenre: Boolean get() = movieGenre != null || tvGenre != null

    /** TMDB's genre id for [kind]; null for the lists, which aren't genres. */
    fun genre(kind: MediaKind): Int? = if (kind == MediaKind.Movie) movieGenre else tvGenre

    /** On [kind]'s page: every list, and the genres it has. */
    fun on(kind: MediaKind): Boolean = !isGenre || genre(kind) != null

    companion object {
        fun of(kind: MediaKind): List<Shelf> = entries.filter { it.on(kind) }
    }
}

/**
 * One page's shelves as of [fetchedAt]; empty until the first fetch (or a cached one) is in. A shelf grows a page at
 * a time from the first: [pages] says how many it has, [ended] the ones with no more, [busy] those getting one.
 */
data class Shelves(
    val rows: Map<Shelf, List<MovieDetails>> = emptyMap(),
    val fetchedAt: Instant? = null,
    val pages: Map<Shelf, Int> = emptyMap(),
    val ended: Set<Shelf> = emptySet(),
    val busy: Set<Shelf> = emptySet(),
) {
    val isEmpty: Boolean get() = rows.values.all { it.isEmpty() }

    /** Has more titles to fetch, and isn't fetching them already. */
    fun canGrow(shelf: Shelf): Boolean = !rows[shelf].isNullOrEmpty() && shelf !in ended && shelf !in busy
}

/**
 * Films and series from TMDB (media-core, through the garbled-store proxy) for the Movies and TV pages: each
 * page's shelves kept on disk so they show at once, refreshed when older than [MAX_AGE]. A title's details,
 * where to watch and episodes are kept in memory for the session. Search asks TMDB each time.
 */
class MediaRepository(private val dir: File, client: HttpClient, baseUrl: String) {
    // Before the states: reading the files on disk needs it.
    private val json = Json { ignoreUnknownKeys = true }
    private val repos = MediaKind.entries.associateWith { kind -> MovieRepository(client, baseUrl, ids = emptyList(), cache = NoCache, kind = kind) }
    private val states = MediaKind.entries.associateWith { kind -> MutableStateFlow(read(kind)) }
    private val refreshing = MediaKind.entries.associateWith { Mutex() }
    private val searcher = TmdbSearch(client, baseUrl)
    private val people = TmdbPeople(client, baseUrl)

    private val details = Lru<MovieDetails>(64)
    private val extras = Lru<TitleExtras>(32)
    private val episodes = Lru<List<Episode>>(32)

    fun shelves(kind: MediaKind): StateFlow<Shelves> = states.getValue(kind).asStateFlow()

    /**
     * Fetches every shelf of [kind] unless the ones on disk are recent. A shelf that fails keeps what it had; the
     * first call after the proxy has slept can take up to a minute.
     */
    suspend fun refresh(kind: MediaKind, now: Instant, force: Boolean = false) = refreshing.getValue(kind).withLock {
        val state = states.getValue(kind)
        val at = state.value.fetchedAt
        // A shelf missing from the file (one added since it was written) is fetched without waiting for the rest to age.
        val complete = Shelf.of(kind).all { it in state.value.rows }
        if (!force && at != null && Duration.between(at, now) < MAX_AGE && complete) return@withLock
        val shelves = Shelf.of(kind)
        val pages = coroutineScope {
            shelves.associateWith { shelf -> async { fetch(kind, shelf, 1) } }.mapValues { it.value.await() }
        }
        if (pages.values.all { it == null }) return@withLock // offline: keep what's there, try again next time
        // A shelf that failed keeps what it had; one that never came stays missing, to be asked for next time.
        val rows = shelves.mapNotNull { shelf -> (pages[shelf]?.movies ?: state.value.rows[shelf])?.let { shelf to it } }.toMap()
        // Back to each shelf's first page: what was paged in beyond it is dropped with the old titles.
        val next = Shelves(rows, now, ended = shelves.filter { pages[it]?.hasMore == false }.toSet())
        state.value = next
        withContext(Dispatchers.IO) { write(kind, next) }
    }

    /**
     * Adds [shelf]'s next page of [kind] to its end, leaving out titles it already has. Does nothing while one is on
     * its way or after the last; a page that fails can be asked for again.
     */
    suspend fun more(kind: MediaKind, shelf: Shelf) {
        val state = states.getValue(kind)
        val start = state.value
        if (!shelf.on(kind) || !start.canGrow(shelf)) return
        val page = (start.pages[shelf] ?: 1) + 1
        state.update { it.copy(busy = it.busy + shelf) }
        val got = try {
            fetch(kind, shelf, page)
        } finally {
            state.update { it.copy(busy = it.busy - shelf) }
        }
        got ?: return
        state.update { now ->
            // A refresh in the meantime started the shelf over; this page no longer follows on from it.
            if (now.fetchedAt != start.fetchedAt) return@update now
            val have = now.rows[shelf].orEmpty()
            val seen = have.mapTo(HashSet()) { it.id }
            now.copy(
                rows = now.rows + (shelf to have + got.movies.filter { it.id !in seen }),
                pages = now.pages + (shelf to page),
                ended = if (got.hasMore) now.ended else now.ended + shelf,
            )
        }
    }

    private suspend fun fetch(kind: MediaKind, shelf: Shelf, page: Int): MoviePage? {
        val repo = repos.getValue(kind)
        return when (shelf) {
            Shelf.Trending -> repo.trending(page)
            Shelf.TopRated -> repo.page(MovieSort.TopRated, page)
            Shelf.Newest -> repo.page(MovieSort.Newest, page)
            else -> shelf.genre(kind)?.let { repo.genre(it, page, withoutGenre = shelf.without) }
        }
    }

    /** What's already in memory for [title], without asking: lets a page open on it instead of a placeholder. */
    fun cachedDetails(title: MovieDetails): MovieDetails? = details.get("${title.kind}/${title.id}")
    fun cachedExtras(title: MovieDetails): TitleExtras? = extras.get("${title.kind}/${title.id}")
    fun cachedEpisodes(title: MovieDetails, season: Int): List<Episode>? = episodes.get("${title.id}/$season")

    /** Everything about one title (cast, runtime, seasons, trailers); null when offline. */
    suspend fun details(title: MovieDetails): MovieDetails? {
        val key = "${title.kind}/${title.id}"
        details.get(key)?.let { return it }
        return repos.getValue(title.kind).details(title.id)?.also { details.put(key, it) }
    }

    /** Where to watch, by country, and titles like it; null when offline. */
    suspend fun extras(title: MovieDetails): TitleExtras? {
        val key = "${title.kind}/${title.id}"
        extras.get(key)?.let { return it }
        return repos.getValue(title.kind).extras(title.id)?.also { extras.put(key, it) }
    }

    /** A series' episodes in [season], in order; null when offline. */
    suspend fun episodes(title: MovieDetails, season: Int): List<Episode>? {
        val key = "${title.id}/$season"
        episodes.get(key)?.let { return it }
        return repos.getValue(title.kind).episodes(title.id, season)?.also { episodes.put(key, it) }
    }

    /** Page [page] of TMDB's results for [query]: films, series and people, most relevant first; null when offline. */
    suspend fun search(query: String, scope: SearchScope, page: Int): SearchPage? = searcher.search(query, scope, page)

    /** Someone's films and series, from a person in the search results; null when offline. */
    suspend fun person(id: Int): PersonDetails? = people.details(id)

    @Serializable
    private class ShelfFile(val fetchedAt: Long, val rows: Map<String, List<MovieDetails>>)

    private fun file(kind: MediaKind) = File(dir, "media-${kind.name.lowercase()}.json")

    private fun read(kind: MediaKind): Shelves = runCatching {
        val saved = json.decodeFromString(ShelfFile.serializer(), file(kind).readText())
        Shelves(
            rows = Shelf.of(kind).mapNotNull { shelf -> saved.rows[shelf.name]?.let { shelf to it } }.toMap(),
            fetchedAt = Instant.ofEpochMilli(saved.fetchedAt),
        )
    }.getOrDefault(Shelves())

    private fun write(kind: MediaKind, shelves: Shelves) {
        val text = json.encodeToString(ShelfFile.serializer(), ShelfFile(shelves.fetchedAt?.toEpochMilli() ?: 0, shelves.rows.mapKeys { it.key.name }))
        val target = file(kind)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(text)
        tmp.renameTo(target)
    }

    /** The last [max] used, dropping the oldest; plain Kotlin so the unit tests run on the JVM. */
    private class Lru<V>(private val max: Int) {
        private val map = object : LinkedHashMap<String, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>) = size > max
        }

        @Synchronized fun get(key: String): V? = map[key]
        @Synchronized fun put(key: String, value: V) { map[key] = value }
    }

    /** The shelves are cached here instead, all of a page's rows in one file. */
    private object NoCache : MovieCache {
        override suspend fun read(): String? = null
        override suspend fun write(json: String) = Unit
    }

    companion object {
        val MAX_AGE: Duration = Duration.ofHours(6)
    }
}
