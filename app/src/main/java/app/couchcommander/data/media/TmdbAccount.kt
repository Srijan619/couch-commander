package app.couchcommander.data.media

import app.media.AccountList
import app.media.MediaKind
import app.media.MovieAccountState
import app.media.MovieDetails
import app.media.TmdbAccountRepository
import app.media.TmdbSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where signing in stands while the account panel is open. */
sealed interface SignIn {
    data object Idle : SignIn
    /** Asking the proxy for a code; the first time after a quiet spell takes up to a minute. */
    data object Starting : SignIn
    /** [url] is TMDB's approval page, shown as a QR code; this checks every few seconds whether it was approved. */
    data class Waiting(val url: String) : SignIn
    data object Offline : SignIn
}

/** Keeps the signed-in session between runs; [KeystoreSessionStore] on the TV, a plain one in the tests. */
interface SessionStore {
    suspend fun load(): TmdbSession?
    suspend fun save(session: TmdbSession)
    suspend fun clear()
}

/**
 * The user's TMDB account for the Movies and TV page: signing in from a phone, the watchlist and favourites
 * shelves, and the title page's Watchlist, Favourite and Rate.
 *
 * The TV has no browser to come back from, so it never uses TMDB's redirect: the account panel shows the approval
 * page as a QR code, the user approves it on their phone, and this swaps the token for a session as soon as TMDB
 * says it's approved. A token is kept until it nearly expires, so closing the panel and opening it again shows the
 * same code; past that, a new one.
 */
class TmdbAccount(
    private val repo: TmdbAccountRepository,
    private val store: SessionStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = POLL_MS,
) {
    private val _session = MutableStateFlow<TmdbSession?>(null)
    val session: StateFlow<TmdbSession?> = _session.asStateFlow()

    private val _signIn = MutableStateFlow<SignIn>(SignIn.Idle)
    val signIn: StateFlow<SignIn> = _signIn.asStateFlow()

    private val lists = MediaKind.entries.associateWith { MutableStateFlow<Map<AccountList, List<MovieDetails>>>(emptyMap()) }

    private var polling: Job? = null
    private var pending: String? = null
    private var pendingSince = 0L

    /** The saved session, if any; call once at start. */
    suspend fun load() {
        if (_session.value == null) _session.value = store.load()
    }

    /** The watchlist and favourites of [kind], most recently added first; empty when signed out. */
    fun lists(kind: MediaKind): StateFlow<Map<AccountList, List<MovieDetails>>> = lists.getValue(kind).asStateFlow()

    /** Shows a code and waits for it to be approved; does nothing while signed in or already waiting. */
    fun startSignIn() {
        if (_session.value != null || polling?.isActive == true) return
        polling = scope.launch {
            if (_signIn.value !is SignIn.Waiting) _signIn.value = SignIn.Starting
            while (true) {
                val token = pending?.takeIf { clock() - pendingSince < TOKEN_REUSE_MS }
                    ?: repo.requestToken()?.also {
                        pending = it
                        pendingSince = clock()
                    }
                if (token == null) {
                    _signIn.value = SignIn.Offline
                    return@launch
                }
                _signIn.value = SignIn.Waiting(repo.approvalUrl(token, redirectTo = null))
                // Not yet approved reads the same as offline (no session), so keep asking until the token runs out.
                while (clock() - pendingSince < TOKEN_REUSE_MS) {
                    repo.exchange(token)?.let {
                        signedIn(it)
                        return@launch
                    }
                    delay(pollMillis)
                }
                pending = null
            }
        }
    }

    /** Stops checking when the panel closes; the code stays good for the next time it opens. */
    fun stopSignIn() {
        polling?.cancel()
        polling = null
        if (_signIn.value !is SignIn.Waiting) _signIn.value = SignIn.Idle
    }

    /** Forgets the session here and ends it on TMDB (best effort). */
    fun signOut() {
        val ended = _session.value ?: return
        _session.value = null
        lists.values.forEach { it.value = emptyMap() }
        scope.launch {
            store.clear()
            repo.signOut(ended)
        }
    }

    /** Fetches the watchlist and favourites of [kind]; a list that fails keeps what it had. */
    suspend fun refreshLists(kind: MediaKind) {
        val session = _session.value ?: return
        val pages = coroutineScope {
            SHELVES.associateWith { list -> async { repo.page(session, list, 1, kind) } }.mapValues { it.value.await() }
        }
        if (_session.value != session) return // signed out meanwhile
        val state = lists.getValue(kind)
        state.value = SHELVES.associateWith { pages[it]?.movies ?: state.value[it].orEmpty() }
    }

    /** Whether [title] is on the watchlist, a favourite, and the user's rating; null when signed out or offline. */
    suspend fun state(title: MovieDetails): MovieAccountState? =
        _session.value?.let { repo.state(it, title.id, title.kind) }

    /** True once TMDB has saved it; the shelves follow. */
    suspend fun setWatchlist(title: MovieDetails, on: Boolean): Boolean =
        change(title) { repo.setWatchlist(it, title.id, on, title.kind) }

    suspend fun setFavorite(title: MovieDetails, on: Boolean): Boolean =
        change(title) { repo.setFavorite(it, title.id, on, title.kind) }

    /** Rates [title] 0.5–10, or removes the rating when [value] is null. */
    suspend fun rate(title: MovieDetails, value: Double?): Boolean =
        change(title, refresh = false) { repo.rate(it, title.id, value, title.kind) }

    private suspend fun change(title: MovieDetails, refresh: Boolean = true, save: suspend (TmdbSession) -> Boolean): Boolean {
        val session = _session.value ?: return false
        return save(session).also { if (it && refresh) refreshLists(title.kind) }
    }

    private suspend fun signedIn(session: TmdbSession) {
        pending = null
        _session.value = session
        _signIn.value = SignIn.Idle
        store.save(session)
        MediaKind.entries.forEach { refreshLists(it) }
    }

    companion object {
        /** The shelves the account adds, in order. */
        val SHELVES = listOf(AccountList.Watchlist, AccountList.Favorites)
        const val POLL_MS = 3_000L
        /** A little under TMDB's 60-minute token lifetime. */
        const val TOKEN_REUSE_MS = 50 * 60 * 1000L
    }
}
