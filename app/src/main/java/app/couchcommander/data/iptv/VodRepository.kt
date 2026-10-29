package app.couchcommander.data.iptv

import app.media.MediaKind
import app.media.MovieDetails
import java.io.File
import java.io.OutputStream
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

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

/** What [VodRepository.refresh] needs to decide on a sync, kept apart from the catalogue as `vod-meta.json`. */
@Serializable
private data class VodMeta(
    val key: String? = null,
    val syncedAtEpochMillis: Long? = null,
    val movies: Int = 0,
    val series: Int = 0,
    val error: String? = null,
)

/** What follows an episode: the next one, nothing (the series' last on the panel), or not known (offline). */
sealed interface NextEpisode {
    data class Found(val episode: VodEpisode) : NextEpisode
    data object Last : NextEpisode
    data object Unknown : NextEpisode
}

/** How the film and series catalogue stands, for settings and the logs. */
data class VodStatus(val movies: Int = 0, val series: Int = 0, val syncedAtEpochMillis: Long? = null, val error: String? = null, val syncing: Boolean = false)

/**
 * The films and series on the user's Xtream panel, for Play on a TMDB title. The catalogue is fetched in one
 * request per kind on a low-priority thread once a day and kept on disk; it is read into memory the first time a title
 * is looked up. Deciding whether to sync reads only `vod-meta.json`: the catalogue can take a TV a minute to read. A title is found by the panel's TMDB id when it has one, else by its name and year. An M3U
 * playlist has no catalogue to ask, so it finds nothing for now.
 */
class VodRepository(
    dir: File,
    private val fetch: IptvFetch = IptvHttp(),
    private val dispatcher: CoroutineDispatcher = VOD_THREAD,
    /** Lookups: not the sync thread, which can be busy for minutes. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Told how long reading the catalogue from disk and indexing it took, in ms, for the log. */
    private val onLoaded: (Long) -> Unit = {},
) {
    private val file = File(dir, "vod.json")
    private val metaFile = File(dir, "vod-meta.json")
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
        // Reads what was last synced once, which also fills in the status for settings.
        val current = withContext(io) { meta() }
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
                runCatching { sync(source, now).copy(key = key) }.getOrElse { e -> e }
            }
        } finally {
            _status.value = _status.value.copy(syncing = false)
        }
        when (next) {
            is VodIndex -> save(next)
            // Keeps the catalogue it had, without reading it just to note the failure.
            is Throwable -> saveMeta(current.copy(error = next.message ?: next.javaClass.simpleName))
        }
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

    /**
     * What comes after [streamId] in [series], from the first of the panel's versions that lists it. The episode
     * lists are cached, so asking again costs nothing.
     */
    suspend fun nextEpisode(source: IptvSource?, series: MovieDetails, streamId: String): NextEpisode {
        for (entry in find(series)) {
            val eps = episodes(source, entry) ?: continue
            val at = eps.indexOfFirst { it.id == streamId }
            if (at >= 0) return eps.getOrNull(at + 1)?.let(NextEpisode::Found) ?: NextEpisode.Last
        }
        // Offline, or no version lists this episode any more.
        return NextEpisode.Unknown
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

    @OptIn(ExperimentalSerializationApi::class)
    private fun lookup(): Lookup? = lookup ?: run {
        val start = System.nanoTime()
        // Streamed: the file can be tens of megabytes, too much to hold as one string on a TV as well.
        runCatching { file.inputStream().buffered().use { json.decodeFromStream(VodIndex.serializer(), it) } }.getOrNull()?.let(::Lookup)?.also {
            lookup = it
            onLoaded((System.nanoTime() - start) / 1_000_000)
        }
    }

    /** What was last synced. A catalogue saved before there was a meta file is read once to make one. */
    private suspend fun meta(): VodMeta {
        runCatching { json.decodeFromString(VodMeta.serializer(), metaFile.readText()) }.getOrNull()?.let {
            _status.value = it.status()
            return it
        }
        val index = loading.withLock { lookup() }?.index ?: return VodMeta()
        return index.meta().also { saveMeta(it) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun save(next: VodIndex) = withContext(io) {
        lookup = Lookup(next)
        // Streamed too: as one string, a whole catalogue doesn't fit in the app's memory on a TV.
        val saved = runCatching { write(file) { json.encodeToStream(VodIndex.serializer(), next, it) } }.isSuccess
        // Unsaved, the next start has only the old catalogue on disk: not synced, so it's fetched again.
        saveMeta(if (saved) next.meta() else next.meta().copy(syncedAtEpochMillis = null, error = "Couldn't save the catalogue"))
    }

    private suspend fun saveMeta(meta: VodMeta) = withContext(io) {
        _status.value = meta.status()
        runCatching { write(metaFile) { it.write(json.encodeToString(VodMeta.serializer(), meta).encodeToByteArray()) } }
    }

    private fun write(target: File, encode: (OutputStream) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.outputStream().buffered().use(encode)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun VodIndex.meta() = VodMeta(key, syncedAtEpochMillis, movies.size, series.size, error)

    private fun VodMeta.status() = VodStatus(movies, series, syncedAtEpochMillis, error, _status.value.syncing)

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
            // A catalogue synced before the keys were saved with it works them out here.
            entries.forEach { e -> e.keys.ifEmpty { VodNames.keys(e.name) }.forEach { map.getOrPut(it) { mutableListOf() } += e } }
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
