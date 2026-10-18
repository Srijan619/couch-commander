package app.couchcommander.data.media

import app.media.Episode
import app.media.MediaKind
import app.media.MovieCache
import app.media.MovieDetails
import app.media.MovieRepository
import app.media.MovieSort
import app.media.TitleExtras
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The rows of the Movies and TV pages, in order. */
enum class Shelf(val title: String, val note: String) {
    Trending("Trending", "This week"),
    TopRated("Top rated", "All time"),
    Newest("New", "Latest releases"),
}

/** One page's shelves as of [fetchedAt]; empty until the first fetch (or a cached one) is in. */
data class Shelves(val rows: Map<Shelf, List<MovieDetails>> = emptyMap(), val fetchedAt: Instant? = null) {
    val isEmpty: Boolean get() = rows.values.all { it.isEmpty() }
}

/**
 * Films and series from TMDB (media-core, through the garbled-store proxy) for the Movies and TV pages: each
 * page's shelves kept on disk so they show at once, refreshed when older than [MAX_AGE]. A title's details,
 * where to watch and episodes are kept in memory for the session.
 */
class MediaRepository(private val dir: File, client: HttpClient, baseUrl: String) {
    // Before the states: reading the files on disk needs it.
    private val json = Json { ignoreUnknownKeys = true }
    private val repos = MediaKind.entries.associateWith { kind -> MovieRepository(client, baseUrl, ids = emptyList(), cache = NoCache, kind = kind) }
    private val states = MediaKind.entries.associateWith { kind -> MutableStateFlow(read(kind)) }
    private val refreshing = MediaKind.entries.associateWith { Mutex() }

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
        if (!force && at != null && Duration.between(at, now) < MAX_AGE && !state.value.isEmpty) return@withLock
        val repo = repos.getValue(kind)
        val pages = coroutineScope {
            Shelf.entries.associateWith { shelf ->
                async {
                    when (shelf) {
                        Shelf.Trending -> repo.trending(1)
                        Shelf.TopRated -> repo.page(MovieSort.TopRated, 1)
                        Shelf.Newest -> repo.page(MovieSort.Newest, 1)
                    }
                }
            }.mapValues { it.value.await() }
        }
        if (pages.values.all { it == null }) return@withLock // offline: keep what's there, try again next time
        val rows = Shelf.entries.associateWith { shelf -> pages[shelf]?.movies ?: state.value.rows[shelf].orEmpty() }
        val next = Shelves(rows, now)
        state.value = next
        withContext(Dispatchers.IO) { write(kind, next) }
    }

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

    @Serializable
    private class ShelfFile(val fetchedAt: Long, val rows: Map<String, List<MovieDetails>>)

    private fun file(kind: MediaKind) = File(dir, "media-${kind.name.lowercase()}.json")

    private fun read(kind: MediaKind): Shelves = runCatching {
        val saved = json.decodeFromString(ShelfFile.serializer(), file(kind).readText())
        Shelves(
            rows = Shelf.entries.associateWith { saved.rows[it.name].orEmpty() },
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
