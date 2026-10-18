package app.couchcommander.data.iptv

import app.media.MediaKind
import app.media.MovieDetails
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The panel's films and series as of the last sync; kept as `vod.json`, never with a URL in it. */
@Serializable
data class VodIndex(
    val movies: List<VodEntry> = emptyList(),
    val series: List<VodEntry> = emptyList(),
    val syncedAtEpochMillis: Long? = null,
    /** Hash of the source, so a new playlist syncs again straight away. */
    val key: String? = null,
    val error: String? = null,
)

/** How the film and series catalogue stands, for settings and the logs. */
data class VodStatus(val movies: Int = 0, val series: Int = 0, val syncedAtEpochMillis: Long? = null, val error: String? = null, val syncing: Boolean = false)

/**
 * The films and series on the user's Xtream panel, for Play on a TMDB title. The catalogue is fetched in one
 * request per kind on a low-priority thread once a day and kept on disk; it is read into memory the first time a title
 * is looked up. A title is found by the panel's TMDB id when it has one, else by its name and year. An M3U
 * playlist has no catalogue to ask, so it finds nothing for now.
 */
class VodRepository(
    dir: File,
    private val fetch: IptvFetch = IptvHttp(),
    private val dispatcher: CoroutineDispatcher = VOD_THREAD,
    /** Lookups: not the sync thread, which can be busy for minutes. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file = File(dir, "vod.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var triedAt: Instant? = null
    private var triedKey: String? = null

    /** Null until first needed: the catalogue can be tens of thousands of titles. */
    @Volatile private var lookup: Lookup? = null
    private val loading = Mutex()

    private val _status = MutableStateFlow(VodStatus())
    val status: StateFlow<VodStatus> = _status.asStateFlow()

    private val episodes = object : LinkedHashMap<String, List<VodEpisode>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<VodEpisode>>) = size > 16
    }

    /** Syncs when the source changed, the last sync is older than [MAX_AGE], or [force]. */
    suspend fun refresh(source: IptvSource?, now: Instant, force: Boolean = false) = mutex.withLock {
        // Reads the catalogue on disk once, which also fills in the status for settings.
        val current = withContext(io) { loading.withLock { lookup() }?.index ?: VodIndex() }
        if (source !is IptvSource.Xtream) {
            if (current.key != null) save(VodIndex())
            return@withLock
        }
        val key = key(source)
        val fresh = current.key == key && current.syncedAtEpochMillis?.let { Duration.between(Instant.ofEpochMilli(it), now) < MAX_AGE } == true
        val tried = triedKey == key && triedAt?.let { Duration.between(it, now) < RETRY } == true
        // A forced sync queued behind one that just finished (at start, or from settings) would fetch it all again.
        val justSynced = current.key == key && current.syncedAtEpochMillis?.let { Duration.between(Instant.ofEpochMilli(it), now) < JUST_SYNCED } == true
        if (justSynced || (!force && (fresh || tried))) return@withLock
        triedAt = now
        triedKey = key
        _status.value = _status.value.copy(syncing = true)
        val next = try {
            withContext(dispatcher) {
                runCatching { sync(source, now).copy(key = key) }
                    .getOrElse { e -> current.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        } finally {
            _status.value = _status.value.copy(syncing = false)
        }
        save(next)
    }

    /** The panel's versions of [title], the surest first; empty when it has none or there's no catalogue yet. */
    suspend fun find(title: MovieDetails): List<VodEntry> = withContext(io) {
        val l = loading.withLock { lookup() } ?: return@withContext emptyList()
        val (byTmdb, byKey) = if (title.kind == MediaKind.Movie) l.movieTmdb to l.movieKeys else l.seriesTmdb to l.seriesKeys
        val sure = byTmdb[title.id].orEmpty()
        // A same-named title the panel says is another TMDB id is another film.
        val named = byKey[VodNames.key(title.title)].orEmpty()
            .filter { (it.tmdb == null || it.tmdb == title.id) && VodNames.yearMatches(it.year, title.year) }
        // English and the best picture first; the surest match first among equals (the sort is stable).
        (sure + named).distinctBy { it.id }.sortedBy(VodNames::rank)
    }

    /** Films and series whose name holds [text], for the adb report; names only, never a URL. */
    suspend fun search(text: String, max: Int = 40): List<VodEntry> = withContext(io) {
        val l = loading.withLock { lookup() } ?: return@withContext emptyList()
        val q = Names.fold(text)
        (l.index.movies + l.index.series).filter { Names.fold(it.name).contains(q) }.take(max)
    }

    /** A series' episodes on the panel, in order; null when the panel can't be reached. */
    suspend fun episodes(source: IptvSource?, series: VodEntry): List<VodEpisode>? {
        if (source !is IptvSource.Xtream) return null
        synchronized(episodes) { episodes[series.id] }?.let { return it }
        return withContext(io) {
            runCatching { fetch.get(XtreamVod.seriesInfoUrl(source, series.id), API_MAX) { XtreamVod.parseEpisodes(it.reader().readText()) } }.getOrNull()
        }?.also { synchronized(episodes) { episodes[series.id] = it } }
    }

    /** Four requests in all: each kind's categories (for their names) and its whole list, read as it streams in. */
    private fun sync(source: IptvSource.Xtream, now: Instant): VodIndex {
        fun entries(categoriesUrl: String, listUrl: String, series: Boolean): List<VodEntry> {
            // Only names the entries' categories; a panel that won't list them still has its titles.
            val categories = runCatching { fetch.get(categoriesUrl, API_MAX) { Xtream.parseCategories(it.reader().readText()) } }
                .getOrDefault(emptyList()).associate { it.id to it.name }
            return fetch.get(listUrl, LIST_MAX) { XtreamVod.parseEntries(it, categories, series, MAX_ENTRIES) }.distinctBy { it.id }
        }
        val movies = runCatching { entries(XtreamVod.movieCategoriesUrl(source), XtreamVod.moviesUrl(source), series = false) }
        val series = runCatching { entries(XtreamVod.seriesCategoriesUrl(source), XtreamVod.seriesUrl(source), series = true) }
        if (movies.isFailure && series.isFailure) throw movies.exceptionOrNull()!!
        return VodIndex(movies.getOrDefault(emptyList()), series.getOrDefault(emptyList()), now.toEpochMilli())
    }

    /** The HTTP answer to each of the sync's requests, for the adb report: "get_vod_streams: HTTP 403". */
    suspend fun probe(source: IptvSource?): List<String> = withContext(io) {
        if (source !is IptvSource.Xtream) return@withContext listOf("no Xtream panel set")
        listOf(
            "get_live_categories" to Xtream.categoriesUrl(source),
            "get_vod_categories" to XtreamVod.movieCategoriesUrl(source),
            "get_series_categories" to XtreamVod.seriesCategoriesUrl(source),
        ).map { (name, url) ->
            name + ": " + runCatching { fetch.get(url, API_MAX) { "OK, ${it.readBytes().size} bytes" } }.getOrElse { it.message ?: it.javaClass.simpleName }
        }
    }

    private fun lookup(): Lookup? = lookup ?: runCatching { json.decodeFromString<VodIndex>(file.readText()) }.getOrNull()?.let(::Lookup)?.also {
        lookup = it
        _status.value = it.index.status()
    }

    private suspend fun save(next: VodIndex) = withContext(io) {
        lookup = Lookup(next)
        _status.value = next.status()
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(VodIndex.serializer(), next))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun VodIndex.status() = VodStatus(movies.size, series.size, syncedAtEpochMillis, error, _status.value.syncing)

    private fun key(source: IptvSource): String {
        val text = json.encodeToString(IptvSource.serializer(), source) + KEY_VERSION
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    /** The catalogue by TMDB id and by name. */
    private class Lookup(val index: VodIndex) {
        val movieTmdb = index.movies.filter { it.tmdb != null }.groupBy { it.tmdb!! }
        val seriesTmdb = index.series.filter { it.tmdb != null }.groupBy { it.tmdb!! }
        val movieKeys = byKey(index.movies)
        val seriesKeys = byKey(index.series)

        private fun byKey(entries: List<VodEntry>): Map<String, List<VodEntry>> {
            val map = HashMap<String, MutableList<VodEntry>>()
            entries.forEach { e -> VodNames.keys(e.name).forEach { map.getOrPut(it) { mutableListOf() } += e } }
            return map
        }
    }

    companion object {
        val MAX_AGE: Duration = Duration.ofHours(24)
        val RETRY: Duration = Duration.ofHours(2)
        val JUST_SYNCED: Duration = Duration.ofMinutes(10)
        const val API_MAX = 30_000_000L
        /** A whole catalogue; it is read as it streams in, so this guards the download, not memory. */
        const val LIST_MAX = 1_000_000_000L
        /** A safety net for memory, films and series each. */
        const val MAX_ENTRIES = 200_000
        private const val KEY_VERSION = 1

        /** Its own low-priority thread, so the catalogue never waits behind (or delays) the channel sync. */
        private val VOD_THREAD: CoroutineDispatcher by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "vod-sync").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
                .asCoroutineDispatcher()
        }
    }
}
