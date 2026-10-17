package app.couchcommander.data.iptv

import app.football.FootballTeam
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser

/** What the last sync kept; the football panel reads only this, never the playlist. */
@Serializable
data class IptvState(
    val channels: List<IptvChannel> = emptyList(),
    val programmes: List<IptvProgramme> = emptyList(),
    val syncedAtEpochMillis: Long? = null,
    /** Entries in the whole playlist (or sports streams on an Xtream panel), for the "214 of 48 000" status line. */
    val playlistSize: Int = 0,
    /** Live sports channels found in it; [channels] keeps only those that matter for the followed teams. */
    val sportsChannels: Int = 0,
    /** Guide channels that matched a kept channel. 0 with a guide URL usually means ids and names didn't line up. */
    val guideChannels: Int = 0,
    val guideError: String? = null,
    /** Last failure, cleared by the next successful sync. */
    val error: String? = null,
    /** Hash of the source and the followed team ids, so a change of either syncs again straight away. */
    val key: String? = null,
    /** The big sports brands' channels in FHD or better, for the "Channels" tab; found by name, not by match. */
    val popular: List<IptvChannel> = emptyList(),
)

/**
 * Keeps the user's live sports channels and the guide entries naming their teams, synced in the background on
 * a low-priority thread. The playlist and guide are streamed and filtered as they download; only the result
 * (a few hundred channels, a handful of programmes) is stored, as `iptv.json`. Never throws.
 */
class IptvRepository(
    dir: File,
    private val fetch: IptvFetch = IptvHttp(),
    private val newParser: () -> XmlPullParser,
    private val dispatcher: CoroutineDispatcher = SYNC_THREAD,
) {
    private val file = File(dir, "iptv.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var triedAt: Instant? = null
    private var triedKey: String? = null

    private val _state = MutableStateFlow(runCatching { json.decodeFromString<IptvState>(file.readText()) }.getOrDefault(IptvState()))
    val state: StateFlow<IptvState> = _state.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    /** True while a sync downloads, for the "Syncing…" line in settings. */
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** Syncs when the source or teams changed, the last sync is older than [MAX_AGE], or [force]. */
    suspend fun refresh(source: IptvSource?, teams: List<FootballTeam>, now: Instant, force: Boolean = false): IptvState = mutex.withLock {
        if (source == null) {
            if (_state.value != IptvState()) { _state.value = IptvState(); withContext(dispatcher) { file.delete() } }
            return@withLock _state.value
        }
        val key = key(source, teams)
        val current = _state.value
        // A sync whose guide download failed is tried again soon; the playlist part of it is fine meanwhile.
        val guideFailed = current.key == key && current.guideError != null
        val fresh = current.key == key && current.syncedAtEpochMillis?.let { Duration.between(Instant.ofEpochMilli(it), now) < if (guideFailed) GUIDE_RETRY else MAX_AGE } == true
        val tried = triedKey == key && triedAt?.let { Duration.between(it, now) < if (guideFailed) GUIDE_RETRY else RETRY } == true
        // A forced sync right behind an automatic one for the same source (both start when the source is set) is skipped.
        val justSynced = current.key == key && current.syncedAtEpochMillis?.let { Duration.between(Instant.ofEpochMilli(it), now) < JUST_SYNCED } == true
        if (justSynced || (!force && (fresh || tried))) return@withLock current
        triedAt = now
        triedKey = key

        _syncing.value = true
        val next = try {
            withContext(dispatcher) {
                runCatching { sync(source, teams, now, current).copy(key = key) }
                    .getOrElse { e -> current.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        } finally {
            _syncing.value = false
        }
        _state.value = next
        if (next.error == null) withContext(dispatcher) { runCatching { writeAtomically(json.encodeToString(IptvState.serializer(), next)) } }
        next
    }

    /** [previous] fills in for the guide when its download fails, so one bad response doesn't wipe the matches. */
    private fun sync(source: IptvSource, teams: List<FootballTeam>, now: Instant, previous: IptvState): IptvState {
        val (channels, epgUrl, size) = when (source) {
            is IptvSource.Xtream -> {
                val categories = fetch.get(Xtream.categoriesUrl(source), API_MAX) { Xtream.parseCategories(it.reader().readText()) }
                val sports = categories.filter { Sports.isSportsCategory(it.name) }.take(MAX_CATEGORIES)
                // Big panels have hundreds of sports categories; one that fails or times out is skipped, not the sync.
                val streams = sports.flatMap { c ->
                    runCatching { fetch.get(Xtream.streamsUrl(source, c.id), API_MAX) { Xtream.parseStreams(it.reader().readText(), source, c) } }.getOrDefault(emptyList())
                }
                if (sports.isNotEmpty() && streams.isEmpty()) throw java.io.IOException("No streams in ${sports.size} sports categories")
                Triple(streams, Xtream.xmltvUrl(source), streams.size)
            }
            is IptvSource.M3u -> {
                val parsed = fetch.get(source.url, PLAYLIST_MAX) { M3u.parse(it.bufferedReader().lineSequence(), Sports::isLiveSports) }
                Triple(parsed.channels, source.epgUrl ?: parsed.epgUrl, parsed.total)
            }
        }
        // Every sports channel takes part in matching (a 4 000 cap here once cut the German channels off); only
        // the ones that matter for the followed teams are stored, below.
        var kept = channels.distinctBy { it.url }.take(MAX_CHANNELS)
        val sportsChannels = kept.size

        val forms = teams.flatMap { Names.teamForms(it.name) }.toSet()
        var programmes = emptyList<IptvProgramme>()
        var guideChannels = 0
        var guideError: String? = null
        if (epgUrl != null && forms.isNotEmpty() && kept.isNotEmpty()) {
            val ids = kept.mapNotNullTo(mutableSetOf()) { it.epgId }
            val names = kept.mapTo(mutableSetOf()) { Names.channel(it.name) }
            runCatching {
                fetch.get(epgUrl, GUIDE_MAX) { input ->
                    val parser = newParser()
                    runCatching { parser.setFeature(RELAXED, true) }
                    parser.setInput(input, null)
                    Xmltv.parse(
                        parser,
                        wantChannel = { id, display -> id in ids || display.any { Names.channel(it) in names } },
                        from = now.minus(GUIDE_BEFORE), to = now.plus(GUIDE_AFTER),
                        keepTitle = { Names.mentions(Names.fold(it), forms) },
                    )
                }
            }.onSuccess { guide ->
                // Most playlists leave tvg-id empty: link those channels to the guide by their cleaned-up name.
                val idByName = guide.channels.flatMap { (id, display) -> display.map { Names.channel(it) to id } }.toMap()
                kept = kept.map { if (it.epgId != null) it else it.copy(epgId = idByName[Names.channel(it.name)]) }
                // Soonest first: with whole leagues followed the cap can bite, and next week matters least.
                programmes = guide.programmes.distinct().sortedBy { it.startEpochMillis }.take(MAX_PROGRAMMES)
                guideChannels = guide.channels.size
            }.onFailure {
                guideError = "Guide: ${it.message ?: it.javaClass.simpleName}"
                // The last good guide, minus what has ended, and the channel links it made.
                programmes = previous.programmes.filter { p -> p.stopEpochMillis >= now.minus(GUIDE_BEFORE).toEpochMilli() }
                val linkedBefore = previous.channels.filter { c -> c.epgId != null }.associate { c -> c.url to c.epgId }
                kept = kept.map { c -> if (c.epgId != null) c else c.copy(epgId = linkedBefore[c.url]) }
                guideChannels = previous.guideChannels
            }
        }

        // Stored: channels the guide shows a followed team on, and channels named after one of their matches.
        val linked = programmes.mapTo(mutableSetOf()) { it.epgId }
        val relevant = kept.filter { it.epgId in linked || Names.mentions(Names.fold(it.name), forms) }
        return IptvState(relevant, programmes, now.toEpochMilli(), size, sportsChannels, guideChannels, guideError, popular = PopularChannels.keep(kept))
    }

    private fun key(source: IptvSource, teams: List<FootballTeam>): String {
        // KEY_VERSION: bumped when a sync starts keeping something new, so existing installs sync once more.
        val text = json.encodeToString(IptvSource.serializer(), source) + teams.map { it.id }.sorted() + KEY_VERSION
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun writeAtomically(text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        val MAX_AGE: Duration = Duration.ofHours(12)
        val RETRY: Duration = Duration.ofHours(1)
        /** After a sync whose guide download failed (panels return 502 now and then). */
        val GUIDE_RETRY: Duration = Duration.ofMinutes(20)
        val JUST_SYNCED: Duration = Duration.ofMinutes(1)
        val GUIDE_BEFORE: Duration = Duration.ofHours(6)
        val GUIDE_AFTER: Duration = Duration.ofDays(8)
        const val PLAYLIST_MAX = 300_000_000L
        const val GUIDE_MAX = 1_500_000_000L
        const val API_MAX = 30_000_000L
        const val MAX_CATEGORIES = 1_000
        /** A safety net for memory during a sync, not a filter: big panels have 20 000+ sports streams. */
        const val MAX_CHANNELS = 100_000
        const val MAX_PROGRAMMES = 2_000
        /** 2: [IptvState.popular]. 3: it keeps HD and SD channels too. */
        private const val KEY_VERSION = 3
        private const val RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"

        /** One background thread at the lowest priority, so a sync never takes time from the launcher's UI. */
        private val SYNC_THREAD: CoroutineDispatcher by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "iptv-sync").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
                .asCoroutineDispatcher()
        }
    }
}
