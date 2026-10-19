package app.couchcommander.data.media

import app.media.trakt.Trakt
import app.media.trakt.TraktItem
import app.media.trakt.TraktScrobble
import app.media.trakt.TraktTokenStore
import app.media.trakt.TraktTokens
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TraktScrobblerTest {
    private val dir: File = Files.createTempDirectory("trakt-scrobbler").toFile()
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private class MemoryStore(var tokens: TraktTokens? = null) : TraktTokenStore {
        override suspend fun load() = tokens
        override suspend fun save(tokens: TraktTokens?) { this.tokens = tokens }
    }

    private fun trakt(store: TraktTokenStore, requests: MutableList<HttpRequestData>, online: () -> Boolean) = Trakt(
        client = HttpClient(MockEngine { req ->
            requests.add(req)
            if (online()) respond("{}", HttpStatusCode.Created)
            else respond("", HttpStatusCode.InternalServerError)
        }),
        clientId = "test-cid",
        store = store,
        baseUrl = "https://trakt.test",
        userAgent = "Test/1.0",
        now = { 1_000_000_000L },
    )

    @Test
    fun `ignores reports when not signed in`() = runTest(testDispatcher) {
        val requests = mutableListOf<HttpRequestData>()
        val store = MemoryStore(tokens = null)
        val trakt = trakt(store, requests) { true }
        trakt.load()

        val scrobbler = TraktScrobbler(dir, trakt, testScope, testDispatcher)
        scrobbler.report(TraktScrobble.Start, TraktItem.Movie(603), 0f)
        testScheduler.advanceUntilIdle()

        assertEquals(0, scrobbler.queuedCount())
        assertEquals(0, requests.size)
    }

    @Test
    fun `sends scrobble and leaves queue empty when online`() = runTest(testDispatcher) {
        val requests = mutableListOf<HttpRequestData>()
        val store = MemoryStore(tokens = TraktTokens("token", "refresh", 2_000_000_000L, "alice"))
        val trakt = trakt(store, requests) { true }
        trakt.load()

        val scrobbler = TraktScrobbler(dir, trakt, testScope, testDispatcher)
        scrobbler.report(TraktScrobble.Start, TraktItem.Movie(603), 0.1f)
        testScheduler.advanceUntilIdle()

        assertEquals(0, scrobbler.queuedCount())
        assertEquals(1, requests.size)
        assertTrue(requests.first().url.encodedPath.contains("scrobble/start"))
    }

    @Test
    fun `keeps reports on disk when offline and flushes them in order when online`() = runTest(testDispatcher) {
        var isOnline = false
        val requests = mutableListOf<HttpRequestData>()
        val store = MemoryStore(tokens = TraktTokens("token", "refresh", 2_000_000_000L, "alice"))
        val trakt = trakt(store, requests) { isOnline }
        trakt.load()

        val scrobbler = TraktScrobbler(dir, trakt, testScope, testDispatcher)

        // 1. Report start while offline
        scrobbler.report(TraktScrobble.Start, TraktItem.Movie(603), 0.0f)
        testScheduler.advanceUntilIdle()
        assertEquals(1, scrobbler.queuedCount())

        // 2. Report pause while offline
        scrobbler.report(TraktScrobble.Pause, TraktItem.Movie(603), 0.5f)
        testScheduler.advanceUntilIdle()
        assertEquals(2, scrobbler.queuedCount())

        // 3. Report stop while offline
        scrobbler.report(TraktScrobble.Stop, TraktItem.Movie(603), 0.95f)
        testScheduler.advanceUntilIdle()
        assertEquals(3, scrobbler.queuedCount())

        // Create a new scrobbler instance from the same directory to verify disk persistence
        val reloadedScrobbler = TraktScrobbler(dir, trakt, testScope, testDispatcher)
        assertEquals(3, reloadedScrobbler.queuedCount())

        // 4. Come back online and flush
        isOnline = true
        requests.clear()
        reloadedScrobbler.flush()
        testScheduler.advanceUntilIdle()

        assertEquals(0, reloadedScrobbler.queuedCount())
        assertEquals(3, requests.size)
        assertTrue(requests[0].url.encodedPath.contains("scrobble/start"))
        assertTrue(requests[1].url.encodedPath.contains("scrobble/pause"))
        assertTrue(requests[2].url.encodedPath.contains("scrobble/stop"))
    }

    @Test
    fun `reports episodes with showTmdb season and number`() = runTest(testDispatcher) {
        val requests = mutableListOf<HttpRequestData>()
        val store = MemoryStore(tokens = TraktTokens("token", "refresh", 2_000_000_000L, "alice"))
        val trakt = trakt(store, requests) { true }
        trakt.load()

        val scrobbler = TraktScrobbler(dir, trakt, testScope, testDispatcher)
        scrobbler.report(TraktScrobble.Stop, TraktItem.Episode(showTmdb = 1399, season = 1, number = 2), 0.98f)
        testScheduler.advanceUntilIdle()

        assertEquals(0, scrobbler.queuedCount())
        assertEquals(1, requests.size)
        assertTrue(requests.first().url.encodedPath.contains("scrobble/stop"))
    }
}
