package app.couchcommander.data.media

import app.media.AccountList
import app.media.MediaKind
import app.media.TmdbAccountRepository
import app.media.TmdbSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbAccountTest {
    private var approved = false
    private var tokens = 0
    private var offline = false
    private val asked = mutableListOf<String>()

    private class MemoryStore : SessionStore {
        var saved: TmdbSession? = null
        override suspend fun load() = saved
        override suspend fun save(session: TmdbSession) { saved = session }
        override suspend fun clear() { saved = null }
    }

    // On the test's scheduler, so its virtual clock also waits for the requests.
    private fun TestScope.repo() = TmdbAccountRepository(
        HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler) }.apply { addHandler { request ->
            val path = request.url.encodedPath.removePrefix("/")
            asked += path
            if (offline) return@addHandler respondError(HttpStatusCode.ServiceUnavailable)
            when (path) {
                "4/auth/request_token" -> {
                    // The TV has nowhere to send the browser back to.
                    assertEquals("{}", (request.body as TextContent).text)
                    respond("""{"success": true, "request_token": "tok${++tokens}"}""")
                }
                // TMDB refuses a token nobody has approved yet.
                "4/auth/access_token" -> when {
                    request.method == HttpMethod.Delete -> respond("""{"success": true}""")
                    approved -> respond("""{"success": true, "account_id": "4bc889", "access_token": "user-token"}""")
                    else -> respond("""{"success": false, "status_code": 41}""", HttpStatusCode.UnprocessableEntity)
                }
                "3/authentication/session/convert/4" -> respond("""{"success": true, "session_id": "sess"}""")
                "3/account" -> respond("""{"id": 42, "username": "me"}""")
                "3/account/42/watchlist/movies" -> respond("""{"page": 1, "total_pages": 1, "total_results": 1, "results": [{"id": 1, "title": "Metropolis", "release_date": "1927-01-10"}]}""")
                else -> respond("""{"page": 1, "total_pages": 1, "total_results": 0, "results": []}""")
            }
        } })),
        token = "app-token",
        baseUrl = "https://tmdb.test/3",
        v4Url = "https://tmdb.test/4",
    )

    private fun TestScope.account(store: SessionStore = MemoryStore()) =
        TmdbAccount(repo(), store, backgroundScope, clock = { testScheduler.currentTime })

    @Test
    fun `shows a code without a redirect and signs in once the phone approves it`() = runTest {
        val store = MemoryStore()
        val account = account(store)
        account.startSignIn()
        runCurrent()
        assertEquals(SignIn.Waiting("https://www.themoviedb.org/auth/access?request_token=tok1"), account.signIn.value)

        advanceTimeBy(TmdbAccount.POLL_MS * 3)
        assertNull(account.session.value)

        approved = true
        advanceTimeBy(TmdbAccount.POLL_MS + 1)
        assertEquals(TmdbSession("sess", 42, "me", "user-token"), account.session.value)
        assertEquals(SignIn.Idle, account.signIn.value)
        assertEquals("sess", store.saved?.sessionId)
        assertEquals(listOf("Metropolis"), account.lists(MediaKind.Movie).value[AccountList.Watchlist]?.map { it.title })
    }

    @Test
    fun `reopening the panel shows the same code until it nearly expires`() = runTest {
        val account = account()
        account.startSignIn()
        runCurrent()
        account.stopSignIn()
        account.startSignIn()
        runCurrent()
        assertEquals(SignIn.Waiting("https://www.themoviedb.org/auth/access?request_token=tok1"), account.signIn.value)

        advanceTimeBy(TmdbAccount.TOKEN_REUSE_MS + TmdbAccount.POLL_MS)
        assertEquals(SignIn.Waiting("https://www.themoviedb.org/auth/access?request_token=tok2"), account.signIn.value)
    }

    @Test
    fun `stops checking once the panel closes`() = runTest {
        val account = account()
        account.startSignIn()
        runCurrent()
        account.stopSignIn()
        val before = asked.size
        advanceTimeBy(TmdbAccount.POLL_MS * 10)
        assertEquals(before, asked.size)
    }

    @Test
    fun `offline, it says so instead of showing a code`() = runTest {
        offline = true
        val account = account()
        account.startSignIn()
        runCurrent()
        assertEquals(SignIn.Offline, account.signIn.value)
    }

    @Test
    fun `signing out forgets the session and the shelves`() = runTest {
        val store = MemoryStore().apply { saved = TmdbSession("sess", 42, "me", "user-token") }
        val account = account(store)
        account.load()
        account.refreshLists(MediaKind.Movie)
        account.signOut()
        runCurrent()
        assertNull(account.session.value)
        assertNull(store.saved)
        assertEquals(emptyMap(), account.lists(MediaKind.Movie).value)
        assertEquals(listOf("3/authentication/session", "4/auth/access_token"), asked.takeLast(2))
    }
}
