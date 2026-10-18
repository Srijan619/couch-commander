package app.couchcommander.ui.media

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.LauncherApp
import app.couchcommander.data.art.ArtFrame
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.couchcommander.data.media.MediaRepository
import app.couchcommander.data.media.MediaSearch
import app.couchcommander.data.media.ContinueWatching
import app.couchcommander.data.media.Watching
import app.couchcommander.data.media.TmdbAccount
import app.couchcommander.data.media.Shelf
import app.couchcommander.data.media.Shelves
import app.couchcommander.player.VodPlayerActivity
import app.couchcommander.ui.Icons
import app.couchcommander.ui.home.confirmKeys
import app.couchcommander.ui.page.ActionPill
import app.couchcommander.ui.page.RowCard
import app.couchcommander.ui.page.TabPill
import app.couchcommander.ui.page.rememberBlurred
import app.couchcommander.ui.rememberClock
import app.couchcommander.ui.theme.Palette
import app.media.AccountList
import app.media.MediaKind
import app.media.MovieDetails
import app.media.SearchScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

/**
 * Design H: the full-screen page Left on home's "Apps" handle slides in, the football page's mirror. Tabs for
 * Home, Movies and TV, each a few lists as shelves of posters under a hero. Home has films and series together:
 * Continue (what was played, as wide cards with progress), the account's lists and what's trending; Movies and TV
 * are for browsing one kind. The hero shows the focused title's still, name and plot, its trailer playing muted
 * once focus rests on it. OK on a poster opens its page over this one, and "More like this" there opens the next
 * on top, so Back walks back through them; OK on a Continue card plays it from where it was left, without the
 * page in between. Back from the shelves goes home. The account pill at the end of the
 * tabs signs in to TMDB from a phone; signed in, the watchlist and favourites come before TMDB's lists. The search
 * pill before it opens [SearchScreen] over the shelves, starting on the open tab's kind.
 */
// LocalBringIntoViewSpec: the standard way to pin focus on TV, still marked experimental.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaScreen(
    /** False while it waits off screen: kept composed so opening it is only a slide. */
    active: Boolean,
    frame: ArtFrame?,
    accent: Color,
    movies: Shelves,
    tv: Shelves,
    media: MediaRepository,
    account: TmdbAccount,
    played: ContinueWatching,
    /** The viewer's country (a code such as "FI") for where to watch; null when unknown. */
    country: String?,
    onClose: () -> Unit,
) {
    val clock = rememberClock()
    val backdrop = rememberBlurred(frame)

    var tab by remember { mutableIntStateOf(0) }
    // Home (null) has both kinds.
    val kinds = listOf(null, MediaKind.Movie, MediaKind.Tv)
    val names = listOf("Home", "Movies", "TV")
    val tabFocus = remember { List(names.size) { FocusRequester() } }
    val kind = kinds[tab]
    val session by account.session.collectAsStateWithLifecycle()
    val signIn by account.signIn.collectAsStateWithLifecycle()
    val myFilms by account.lists(MediaKind.Movie).collectAsStateWithLifecycle()
    val mySeries by account.lists(MediaKind.Tv).collectAsStateWithLifecycle()
    val lately by played.all.collectAsStateWithLifecycle()
    // Home: what was played, the account's lists and what's trending, films and series together. Movies and TV:
    // the account's lists, then TMDB's. Empty ones left out; one not fetched yet stays as a placeholder, so the
    // shelves below don't move down when it arrives.
    val rows = buildList {
        fun pick(films: List<MovieDetails>, series: List<MovieDetails>) = when (kind) {
            null -> mix(films, series)
            MediaKind.Movie -> films
            MediaKind.Tv -> series
        }
        fun pending(films: Boolean, series: Boolean) = when (kind) {
            null -> films || series
            MediaKind.Movie -> films
            MediaKind.Tv -> series
        }
        if (kind == null) add(ShelfRow(CONTINUE, "Continue", "Where you left off", lately.map { it.title }, lately.associateBy { it.title.ref }))
        if (session != null) TmdbAccount.SHELVES.forEach {
            add(ShelfRow(it.name, it.title, it.note, pick(myFilms[it].orEmpty(), mySeries[it].orEmpty()), loading = pending(it !in myFilms, it !in mySeries)))
        }
        val shelves = if (kind == null) listOf(Shelf.Trending) else Shelf.entries
        shelves.forEach {
            add(ShelfRow(it.name, it.title, it.note, pick(movies.rows[it].orEmpty(), tv.rows[it].orEmpty()), loading = pending(movies.fetchedAt == null, tv.fetchedAt == null)))
        }
    }.filter { it.titles.isNotEmpty() || it.loading }
    // Some shelf has a title to show (and to focus); until then the hero says what's coming.
    val ready = rows.any { it.titles.isNotEmpty() }
    var accountOpen by remember { mutableStateOf(false) }
    val accountFocus = remember { FocusRequester() }
    // Closing the panel puts focus back on the pill, not on the shelves.
    var backToAccount by remember { mutableStateOf(false) }
    val searchScope = rememberCoroutineScope()
    val search = remember(media) { MediaSearch(searchScope, media::search, media::person) }
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    var backToSearch by remember { mutableStateOf(false) }

    // The titles opened from the shelves, the one on screen last.
    var opened by remember { mutableStateOf(listOf<MovieDetails>()) }
    val context = LocalContext.current
    // OK on a Continue card plays it at once; a second press while it starts does nothing.
    var resuming by remember { mutableStateOf<Job?>(null) }
    fun resume(left: Watching) {
        if (resuming?.isActive == true) return
        resuming = searchScope.launch {
            val play = resumePlay((context.applicationContext as LauncherApp).container, left)
            if (play != null) VodPlayerActivity.start(context, play) else opened = listOf(left.title)
        }
    }
    // A Continue card's page was opened by holding OK, which is still down: its repeats and release are kept from
    // the page, so they don't press Play there.
    var okHeld by remember { mutableStateOf(false) }
    // Where focus was on the shelves, to go back to when the last page closes.
    val posterFocus = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = posterFocus.getOrPut(key) { FocusRequester() }
    var lastPoster by remember { mutableStateOf<String?>(null) }
    var inTabs by remember { mutableStateOf(false) }
    // The title the hero shows: the focused poster's, a moment after focus lands, so scrolling past doesn't load
    // every still on the way. Both are held as State and only read inside the hero's own composables: read here,
    // every arrow press would recompose the whole page, every shelf and poster (30 ms frames on the TV).
    val hovered = remember { mutableStateOf<MovieDetails?>(null) }
    val hero = remember { mutableStateOf<MovieDetails?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { hovered.value }.collectLatest {
            if (hero.value != null) delay(HERO_SETTLE_MS)
            hero.value = it
        }
    }
    val firstTitle = rows.firstOrNull { it.titles.isNotEmpty() }?.titles?.first()
    LaunchedEffect(tab, firstTitle?.id) {
        val now = hovered.value
        if (now == null || (kind != null && now.kind != kind)) hovered.value = firstTitle
    }

    LaunchedEffect(active) {
        if (!active) {
            opened = emptyList()
            lastPoster = null
            accountOpen = false
            searchOpen = false
            // Each visit opens on Home.
            tab = 0
        }
    }
    // Shared by the tabs, so another tab opens back at its first shelf rather than as far down as the last one was.
    val shelfScroll = rememberScrollState()
    LaunchedEffect(tab, active) { shelfScroll.scrollTo(0) }
    LaunchedEffect(accountOpen && active) { if (accountOpen && active) account.startSignIn() else account.stopSignIn() }

    val shelvesActive = active && opened.isEmpty() && !accountOpen && !searchOpen
    // The search has its own Back while it's on top.
    BackHandler(enabled = active && opened.isEmpty() && !searchOpen, onBack = onClose)
    BackHandler(enabled = active && opened.isNotEmpty()) { opened = opened.dropLast(1) }

    // Focus on the open tab when the page opens, and back on the poster when the last title page closes.
    LaunchedEffect(shelvesActive) {
        if (!shelvesActive) return@LaunchedEffect
        withFrameNanos { }
        if (backToAccount) {
            backToAccount = false
            if (runCatching { accountFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
        if (backToSearch) {
            backToSearch = false
            if (runCatching { searchFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
        val target = lastPoster?.let { posterFocus[it] } ?: tabFocus[tab]
        runCatching { target.requestFocus() }.onFailure { runCatching { tabFocus[tab].requestFocus() } }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (!okHeld || k.keyCode !in confirmKeys) return@onPreviewKeyEvent false
                if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) {
                    // A fresh press: the hold has ended without its release reaching here.
                    okHeld = false
                    false
                } else {
                    if (k.action == KeyEvent.ACTION_UP) okHeld = false
                    true
                }
            },
    ) {
        Deferred { HeroBackdrop(hero.value, media, trailers = shelvesActive, fallback = backdrop) }

        // The bottom padding keeps the shelves clear of the footer's hints.
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 30.dp, bottom = FOOTER_SPACE)) {
            // The tabs share the title's line, leaving the height below to the hero.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Movies & TV", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 18.dp))
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    // Up from anywhere on the shelves lands on the open tab.
                    .focusProperties {
                        onEnter = { tabFocus[tab].requestFocus() }
                        // Down goes to the first shelf's first title, even when the shelves are scrolled past it.
                        onExit = {
                            val first = rows.firstOrNull { it.titles.isNotEmpty() }?.let { key(tab, it.id, it.titles.first()) }
                            if (requestedFocusDirection == FocusDirection.Down && first != null) requester(first).requestFocus()
                        }
                    }
                    .onFocusChanged { inTabs = it.hasFocus }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                names.forEachIndexed { i, name ->
                    TabPill(name, i == tab, accent, shelvesActive, tabFocus[i]) {
                        if (tab != i) lastPoster = null
                        tab = i
                    }
                }
            }
                ActionPill(
                    Icons.Search, null, accent, shelvesActive, searchFocus,
                    modifier = Modifier.padding(end = 8.dp), description = "Search",
                ) {
                    search.reset(when (kind) { null -> SearchScope.All; MediaKind.Movie -> SearchScope.Movies; MediaKind.Tv -> SearchScope.Tv })
                    searchOpen = true
                }
                ActionPill(
                    Icons.Person, session?.username ?: "Sign in", accent, shelvesActive, accountFocus,
                    modifier = Modifier.padding(end = 18.dp), description = "TMDB account",
                ) { accountOpen = true }
                Text(clock.time, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            if (ready) {
                Deferred { HeroText(hero.value, accent, Modifier.padding(top = 4.dp)) }
            } else {
                // In the hero's place and height, so the shelves' placeholders sit where the shelves will.
                Column(Modifier.padding(top = 4.dp).height(HERO_TEXT_HEIGHT), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
                    Text(when (kind) { null -> "Getting films and series…"; MediaKind.Movie -> "Getting films…"; MediaKind.Tv -> "Getting series…" }, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                    Text("From TMDB. The first time after a quiet spell can take up to a minute.", fontSize = 15.sp, color = Palette.Muted)
                }
            }

            if (rows.isNotEmpty()) {
                // Moving to another shelf scrolls it to the top, so every Up or Down moves the shelves the same
                // amount; the posters' own rows scroll as usual.
                val rowScrolling = LocalBringIntoViewSpec.current
                val pivot = with(LocalDensity.current) { remember(this) { ShelfPivot(SHELF_PIVOT.toPx()) } }
                CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
                Column(Modifier.padding(top = 20.dp).verticalScroll(shelfScroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowScrolling) {
                    rows.forEach { row ->
                        RowCard(live = false, verticalAlignment = Alignment.Top, padding = PaddingValues(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                            Column(Modifier.width(96.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                                Text(row.note, fontSize = 11.sp, color = Palette.Muted, maxLines = 1)
                            }
                            if (row.titles.isEmpty()) {
                                SkeletonPosterRow(rememberPulse(), modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 8.dp))
                            } else LazyRow(
                                Modifier.weight(1f),
                                // Room for a focused poster to grow without being clipped.
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(row.titles, key = { it.ref }) { title ->
                                    val k = key(tab, row.id, title)
                                    val left = row.continued[title.ref]
                                    if (left != null) {
                                        ContinueTile(left, shelvesActive, requester(k), onFocused = { lastPoster = k; hovered.value = title }, onLongClick = {
                                            okHeld = true
                                            opened = listOf(title)
                                        }) { resume(left) }
                                    } else {
                                        PosterTile(title, shelvesActive, requester(k), onFocused = { lastPoster = k; hovered.value = title }) {
                                            opened = listOf(title)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
                }
            }
        }

        Row(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ready) {
                Icon(if (inTabs) Icons.ChevronDown else Icons.ArrowUp, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
                Text(
                    if (inTabs) "Down: titles" else "Up: Home, Movies, TV, search, account",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.padding(start = 4.dp, end = 16.dp),
                )
            }
            Text("Back: home", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.padding(end = 4.dp))
            Icon(Icons.ChevronRight, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
        }
        Text(
            "Data: TMDB", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 18.dp),
        )

        if (searchOpen && active) {
            SearchScreen(
                search = search,
                active = opened.isEmpty(),
                backdrop = backdrop,
                accent = accent,
                media = media,
                onTitleOpen = { opened = listOf(it) },
                onClose = {
                    backToSearch = true
                    searchOpen = false
                },
            )
        }

        // The open title covers the shelves and the search; only the top one is drawn.
        opened.lastOrNull()?.let { title ->
            TitleScreen(
                title = title,
                media = media,
                accent = accent,
                active = active,
                country = country,
                account = account,
                onOpen = { opened = opened + it },
            )
        }

        if (accountOpen && active) {
            AccountPanel(
                session = session,
                signIn = signIn,
                accent = accent,
                onRetry = account::startSignIn,
                onSignOut = account::signOut,
                onDismiss = {
                    backToAccount = true
                    accountOpen = false
                },
            )
        }
    }
}

/** One shelf on the page: Continue, one of the account's lists or one of TMDB's. */
private class ShelfRow(
    val id: String,
    val title: String,
    val note: String,
    val titles: List<MovieDetails>,
    /** Where each title was left, by [ref]: Continue's wide cards. */
    val continued: Map<String, Watching> = emptyMap(),
    /** Not fetched yet: placeholders hold its place while [titles] is empty. */
    val loading: Boolean = false,
)

private val AccountList.title get() = if (this == AccountList.Favorites) "Favourites" else name
private val AccountList.note get() = if (this == AccountList.Favorites) "Yours" else "To watch"

private const val CONTINUE = "Continue"

/**
 * Its own recompose scope: a State read in [content] recomposes only this, not the caller (a plain lambda passed
 * to an inline layout like Column would be part of the caller's scope).
 */
@Composable
private fun Deferred(content: @Composable () -> Unit) = content()

private fun key(tab: Int, row: String, title: MovieDetails) = "$tab/$row/${title.ref}"

/** A title's key where films and series share a shelf: their TMDB ids can be the same. */
private val MovieDetails.ref get() = "${kind.name}/$id"

/** Films and series taken in turn, each keeping its own order. */
private fun mix(films: List<MovieDetails>, series: List<MovieDetails>): List<MovieDetails> =
    (0 until maxOf(films.size, series.size)).flatMap { listOfNotNull(films.getOrNull(it), series.getOrNull(it)) }

/** How long focus stays on a poster before the hero changes to it. */
private const val HERO_SETTLE_MS = 250L
/** Room under the content for the footer's hints (at 18 dp from the bottom). */
private val FOOTER_SPACE = 52.dp
/** How far below the top of the shelves the focused poster sits: its card's padding and the room it grows into. */
private val SHELF_PIVOT = 14.dp

/** Scrolls whatever takes focus to [pivotPx] below the top, not just into view. */
private class ShelfPivot(private val pivotPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - pivotPx
}
