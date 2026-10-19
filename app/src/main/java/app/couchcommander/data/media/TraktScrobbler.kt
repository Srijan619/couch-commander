package app.couchcommander.data.media

import android.util.Log
import app.media.trakt.Trakt
import app.media.trakt.TraktItem
import app.media.trakt.TraktScrobble
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One report waiting to be sent to Trakt. */
@Serializable
data class QueuedScrobble(
    val action: String,
    val isMovie: Boolean,
    val tmdbId: Int,
    val season: Int? = null,
    val episode: Int? = null,
    val progress: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
) {
    fun moment(): TraktScrobble = runCatching { TraktScrobble.valueOf(action) }.getOrDefault(TraktScrobble.Stop)
    fun toItem(): TraktItem = if (isMovie) {
        TraktItem.Movie(tmdbId)
    } else {
        TraktItem.Episode(showTmdb = tmdbId, season = season ?: 1, number = episode ?: 1)
    }
}

@Serializable
private class QueueFile(val items: List<QueuedScrobble> = emptyList())

/**
 * Sends what's playing to Trakt as it starts, pauses and stops.
 * When the TV is offline or Trakt doesn't answer, reports wait in a queue on disk and flush
 * when connectivity returns.
 */
class TraktScrobbler(
    dir: File,
    private val trakt: Trakt,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file = File(dir, "trakt-scrobbles.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Queues [moment] and attempts to send it straight away. */
    fun report(moment: TraktScrobble, item: TraktItem, progress: Float) {
        if (trakt.username.value == null) return
        val scrobble = QueuedScrobble(
            action = moment.name,
            isMovie = item is TraktItem.Movie,
            tmdbId = when (item) {
                is TraktItem.Movie -> item.tmdb
                is TraktItem.Episode -> item.showTmdb
            },
            season = (item as? TraktItem.Episode)?.season,
            episode = (item as? TraktItem.Episode)?.number,
            progress = progress.coerceIn(0f, 1f),
            timestamp = System.currentTimeMillis(),
        )
        scope.launch(dispatcher) {
            enqueue(scrobble)
            flush()
        }
    }

    /** Sends any pending reports in order until sent or offline. */
    suspend fun flush(): Unit = withContext(dispatcher) {
        if (trakt.username.value == null) return@withContext
        mutex.withLock {
            val list = read().toMutableList()
            if (list.isEmpty()) return@withLock
            var index = 0
            while (index < list.size) {
                val item = list[index]
                val ok = runCatching { trakt.scrobble(item.moment(), item.toItem(), item.progress) }.getOrDefault(false)
                if (ok) {
                    list.removeAt(index)
                    write(list)
                } else {
                    val nextAttempts = item.attempts + 1
                    if (nextAttempts >= MAX_ATTEMPTS) {
                        Log.w(TAG, "Dropping report after $MAX_ATTEMPTS failures: ${item.action} tmdb=${item.tmdbId}")
                        list.removeAt(index)
                        write(list)
                    } else {
                        list[index] = item.copy(attempts = nextAttempts)
                        write(list)
                        // Offline or unreachable: stop flushing the rest until next time.
                        break
                    }
                }
            }
        }
    }

    /** Empties the queue on disk (e.g. on sign out). */
    suspend fun clear() = withContext(dispatcher) {
        mutex.withLock {
            if (file.exists()) file.delete()
        }
    }

    /** How many reports are currently waiting in the queue on disk. */
    suspend fun queuedCount(): Int = withContext(dispatcher) {
        mutex.withLock { read().size }
    }

    private fun enqueue(scrobble: QueuedScrobble) {
        val current = read()
        val next = (current + scrobble).takeLast(MAX_QUEUE_SIZE)
        write(next)
    }

    private fun read(): List<QueuedScrobble> =
        if (!file.exists()) emptyList()
        else runCatching { json.decodeFromString(QueueFile.serializer(), file.readText()).items }.getOrDefault(emptyList())

    private fun write(items: List<QueuedScrobble>) {
        val text = json.encodeToString(QueueFile.serializer(), QueueFile(items))
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    companion object {
        private const val TAG = "TraktScrobbler"
        private const val MAX_ATTEMPTS = 5
        private const val MAX_QUEUE_SIZE = 100
    }
}
