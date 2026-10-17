package app.couchcommander.data.iptv

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a channel did when it was last played, and whether the viewer hid it. */
@Serializable
data class ChannelRecord(
    /** Recent failures (epoch millis), newest last; only the last few are kept. */
    val failures: List<Long> = emptyList(),
    val lastSuccess: Long? = null,
    val hidden: Boolean = false,
    /** Time spent watching it, all told: what "Most watched" ranks by. */
    val watchedSeconds: Long = 0,
    val plays: Int = 0,
)

/**
 * Which channels play. The player records each attempt; the football page's chips put channels that failed
 * lately at the back and leave hidden ones out. Failures fade after [FORGET]: an event channel that's dead in the
 * morning can be the one showing the match in the evening.
 */
object ChannelHealth {
    val FORGET: Duration = Duration.ofHours(24)
    private const val KEEP = 5

    /** Stable across syncs and free of the login: the stream URL isn't (it holds it), the name and group are. */
    fun key(channel: IptvChannel) = "${channel.group}|${channel.name}"

    fun failed(r: ChannelRecord?, now: Instant) = (r ?: ChannelRecord()).let { it.copy(failures = (it.failures + now.toEpochMilli()).takeLast(KEEP)) }
    fun played(r: ChannelRecord?, now: Instant) = (r ?: ChannelRecord()).let { it.copy(failures = emptyList(), lastSuccess = now.toEpochMilli(), plays = it.plays + 1) }
    fun watched(r: ChannelRecord?, seconds: Long) = (r ?: ChannelRecord()).let { it.copy(watchedSeconds = it.watchedSeconds + seconds.coerceAtLeast(0)) }

    /** Under this in total, a channel was only tried, not watched. */
    const val MOST_WATCHED_MIN_SECONDS = 120L
    /** Not "event": Sky Sports Main Event is a regular channel. */
    private val EVENT_SLOT = setOf("ppv", "events")

    /**
     * The channels watched the longest in total (not the latest), looked up in [channels] by [key] since the health
     * file holds no URLs. Hidden channels and pay-per-view or event slots (their names change with every event) are
     * left out.
     */
    fun mostWatched(records: Map<String, ChannelRecord>, channels: List<IptvChannel>, limit: Int = 6): List<IptvChannel> {
        val byKey = channels.associateBy { key(it) }
        return records.entries
            .filter { (_, r) -> !r.hidden && r.watchedSeconds >= MOST_WATCHED_MIN_SECONDS }
            .sortedByDescending { it.value.watchedSeconds }
            .mapNotNull { byKey[it.key] }
            .filterNot { c -> Names.fold(c.name).split(' ').any { it in EVENT_SLOT } }
            .take(limit)
    }

    /** Lower plays first: played lately -1, unknown 0, then one more per recent failure. */
    fun rank(r: ChannelRecord?, now: Instant): Int {
        if (r == null) return 0
        val since = now.minus(FORGET).toEpochMilli()
        if (r.lastSuccess != null && r.lastSuccess >= since && r.failures.none { it > r.lastSuccess }) return -1
        return r.failures.count { it >= since }
    }

    /** [items] without hidden channels, the ones that played lately first and recent failures last; otherwise in order. */
    fun <T> order(items: List<T>, records: Map<String, ChannelRecord>, now: Instant, channel: (T) -> IptvChannel): List<T> =
        items.filterNot { records[key(channel(it))]?.hidden == true }.sortedBy { rank(records[key(channel(it))], now) }

    /**
     * An event channel (named after the match) usually shows nothing until shortly before kickoff, so failing then
     * says nothing about the evening; a regular channel's failure always counts.
     */
    fun counts(event: Boolean, kickoff: Instant?, now: Instant) = !event || kickoff == null || !now.isBefore(kickoff.minus(Duration.ofMinutes(10)))
}

/** [ChannelHealth] records, as one small JSON file shared by the launcher and the player (same process). */
class ChannelHealthStore(dir: File) {
    private val file = File(dir, "channel-health.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _records = MutableStateFlow(runCatching { json.decodeFromString<Map<String, ChannelRecord>>(file.readText()) }.getOrDefault(emptyMap()))
    val records: StateFlow<Map<String, ChannelRecord>> = _records.asStateFlow()

    fun failed(channel: IptvChannel, now: Instant = Instant.now()) = change(channel) { ChannelHealth.failed(it, now) }
    fun played(channel: IptvChannel, now: Instant = Instant.now()) = change(channel) { ChannelHealth.played(it, now) }
    fun hide(channel: IptvChannel) = change(channel) { (it ?: ChannelRecord()).copy(hidden = true) }
    fun watched(channel: IptvChannel, seconds: Long) { if (seconds > 0) change(channel) { ChannelHealth.watched(it, seconds) } }

    val hiddenCount: Int get() = _records.value.count { it.value.hidden }

    fun showHidden() {
        _records.update { all -> all.mapValues { (_, r) -> r.copy(hidden = false) } }
        save()
    }

    private fun change(channel: IptvChannel, transform: (ChannelRecord?) -> ChannelRecord) {
        _records.update { it + (ChannelHealth.key(channel) to transform(it[ChannelHealth.key(channel)])) }
        save()
    }

    /** Tiny file, written on the main thread after a playback attempt; atomic so a crash can't leave half of it. */
    @Synchronized
    private fun save() {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(_records.value))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}
