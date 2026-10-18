package app.couchcommander.data.media

import app.couchcommander.data.iptv.VodNames
import app.media.MediaKind
import app.media.MovieDetails
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a film or series was left: the version played (the panel's stream, never its URL), how far in, and for a
 * series which episode. A series that finished an episode is kept at the start of the next.
 */
@Serializable
data class Watching(
    val title: MovieDetails,
    /** The version's chip label, to offer the same one again. */
    val version: String,
    val streamId: String,
    val ext: String,
    val positionMs: Long,
    val durationMs: Long,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val atEpochMillis: Long = 0,
) {
    /** 0–1, for the bar under the poster; 0 while the length isn't known. */
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * The Continue watching shelf: films and series played from the IPTV panel, the latest first, kept on disk.
 * The player writes it as it plays; a film played to the end leaves it.
 */
class ContinueWatching(dir: File, private val max: Int = MAX) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(dir, "media-continue.json")
    private val state = MutableStateFlow(read())

    val all: StateFlow<List<Watching>> = state.asStateFlow()

    /** What's under way in [kind], the latest first. */
    fun of(kind: MediaKind): List<Watching> = state.value.filter { it.title.kind == kind }

    /** Where [title] was left, if anywhere. */
    fun get(title: MovieDetails): Watching? = state.value.firstOrNull { it.title.kind == title.kind && it.title.id == title.id }

    /** Puts [watching] first, dropping the oldest past [max]; writes the file (call off the main thread). */
    @Synchronized
    fun save(watching: Watching) {
        // Only what a poster needs: the cast, trailers and seasons come with the title's page.
        val t = watching.title
        val slim = watching.copy(title = t.copy(cast = emptyList(), videos = emptyList(), seasonList = emptyList(), nextEpisode = null))
        val next = (listOf(slim) + state.value.filterNot { it.title.kind == t.kind && it.title.id == t.id }).take(max)
        if (next == state.value) return
        state.value = next
        write(next)
    }

    /** Takes [title] off the shelf. */
    @Synchronized
    fun remove(title: MovieDetails) {
        val next = state.value.filterNot { it.title.kind == title.kind && it.title.id == title.id }
        if (next.size == state.value.size) return
        state.value = next
        write(next)
    }

    @Serializable
    private class ContinueFile(val titles: List<Watching>)

    private fun read(): List<Watching> =
        runCatching { json.decodeFromString(ContinueFile.serializer(), file.readText()).titles }.getOrDefault(emptyList())
            // Saved before the panel's file names were cleaned up ("MobLand [4K] - S02E03").
            .map { w -> w.copy(episodeTitle = w.episodeTitle?.let(VodNames::episodeTitle)?.takeIf { it.isNotEmpty() }) }

    private fun write(titles: List<Watching>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(ContinueFile.serializer(), ContinueFile(titles)))
        tmp.renameTo(file)
    }

    companion object {
        /** Films and series together. */
        const val MAX = 30
    }
}
