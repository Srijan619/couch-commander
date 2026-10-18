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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.graphicsLayer
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
 * pill, first on the tabs' line, opens [SearchScreen] over the shelves, starting on the open tab's kind.
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
    // Searches, Continue's plays and fetching more of a shelf.
    val searchScope = rememberCoroutineScope()
    // Each tab's shelves, kept while what they're built from stays the same: the hidden tabs' shelves below get the
    // same list after a switch, so they skip recomposing.
    val rowsByTab = kinds.map { k ->
        remember(k, lately, session != null, myFilms, mySeries, movies, tv) {
            shelfRows(k, lately, session != null, myFilms, mySeries, movies, tv) { grow, shelf -> searchScope.launch { media.more(grow, shelf) } }
        }
    }
    val rows = rowsByTab[tab]
    // Some shelf has a title to show (and to focus); until then the hero says what's coming.
    val ready = rows.any { it.titles.isNotEmpty() }
    var accountOpen by remember { mutableStateOf(false) }
    val accountFocus = remember { FocusRequester() }
    // Closing the panel puts focus back on the pill, not on the shelves.
    var backToAccount by remember { mutableStateOf(false) }
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
    val requester = remember { { key: String -> posterFocus.getOrPut(key) { FocusRequester() } } }
    var lastPoster by remember { mutableStateOf<String?>(null) }
    // Each shelf's last focused title, by tab and shelf: Up or Down back onto a shelf returns to it.
    val shelfPoster = remember { HashMap<String, String>() }
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
    // A tab opens back at its first shelf rather than as far down as it was left.
    val shelfScrolls = remember { List(names.size) { ScrollState(0) } }
    LaunchedEffect(tab, active) { shelfScrolls[tab].scrollTo(0) }
    // The tabs visited, kept composed while hidden: built again on every switch (focus landing on a tab opens it),
    // each switch held a frame while all of its shelves and posters were made.
    val visitedTabs = remember { mutableStateListOf<Int>() }
    LaunchedEffect(tab) { if (tab !in visitedTabs) visitedTabs += tab }
    LaunchedEffect(accountOpen && active) { if (accountOpen && active) account.startSignIn() else account.stopSignIn() }

    val shelvesActive = active && opened.isEmpty() && !accountOpen && !searchOpen
    // The page hides around the hero's trailer while nobody presses a key.
    val immersion = rememberImmersion()
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
                if (immersion.onKey(e)) return@onPreviewKeyEvent true
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
        Deferred { HeroBackdrop(hero.value, media, trailers = shelvesActive, fallback = backdrop, immersion = immersion) }

        // The bottom padding keeps the shelves clear of the footer's hints.
        Column(Modifier.fillMaxSize().graphicsLayer { alpha = immersion.shown.value }.padding(start = 48.dp, end = 48.dp, top = 30.dp, bottom = FOOTER_SPACE)) {
            // The tabs share the title's line, leaving the height below to the hero.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Movies & TV", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 18.dp))
                // First on the line: one Left from the tabs, where the page opens.
                ActionPill(
                    Icons.Search, null, accent, shelvesActive, searchFocus,
                    modifier = Modifier.padding(end = 8.dp), description = "Search",
                ) {
                    search.reset(when (kind) { null -> SearchScope.All; MediaKind.Movie -> SearchScope.Movies; MediaKind.Tv -> SearchScope.Tv })
                    searchOpen = true
                }
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    // Up from anywhere on the shelves lands on the open tab.
                    .focusProperties {
                        onEnter = { tabFocus[tab].requestFocus() }
                        // Down goes back to the title Up came from: the shelves stay where they were, so it's the one
                        // in view. With none yet on this tab, the first shelf's first title.
                        onExit = {
                            if (requestedFocusDirection == FocusDirection.Down) {
                                val back = lastPoster?.takeIf { it.startsWith("$tab/") }
                                if (back == null || !requester(back).requestFocus()) {
                                    rows.firstOrNull { it.titles.isNotEmpty() }?.let { requester(key(tab, it.id, it.titles.first())).requestFocus() }
                                }
                            }
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

            // Moving to another shelf scrolls it to the top, so every Up or Down moves the shelves the same
            // amount; the posters' own rows scroll as usual.
            val rowScrolling = LocalBringIntoViewSpec.current
            // Still while something covers them: opening a title moved them up a shelf behind it, and closing it
            // scrolled them back down in view.
            val covered by rememberUpdatedState(!shelvesActive)
            val pivot = with(LocalDensity.current) { remember(this) { ShelfPivot(SHELF_PIVOT.toPx()) { covered } } }
            val justInView = with(LocalDensity.current) { remember(this) { JustInView(CARD_MARGIN.toPx()) } }
            val onFocused = remember { { k: String, shelf: String, title: MovieDetails -> lastPoster = k; shelfPoster[shelf] = k; hovered.value = title } }
            val onOpen = remember { { title: MovieDetails -> opened = listOf(title) } }
            val onHold = remember { { title: MovieDetails -> okHeld = true; opened = listOf(title) } }
            val onResume = remember { { left: Watching -> resume(left) } }
            Box(Modifier.padding(top = 20.dp)) {
                (visitedTabs + tab).distinct().forEach { t ->
                    key(t) {
                        val shown = t == tab
                        val focusable = rememberUpdatedState(shelvesActive && shown)
                        Box(Modifier.graphicsLayer { alpha = if (shown) 1f else 0f }) {
                            TabShelves(
                                t, rowsByTab[t], shelfScrolls[t], focusable, pivot, rowScrolling, justInView, requester, shelfPoster,
                                onFocused, onOpen, onHold, onResume,
                            )
                        }
                    }
                }
            }
        }

        Row(Modifier.align(Alignment.BottomStart).graphicsLayer { alpha = immersion.shown.value }.padding(start = 48.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ready) {
                Icon(if (inTabs) Icons.ChevronDown else Icons.ArrowUp, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
                Text(
                    if (inTabs) "Down: titles" else "Up: search, Home, Movies, TV, account",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.padding(start = 4.dp, end = 16.dp),
                )
            }
            Text("Back: home", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.padding(end = 4.dp))
            Icon(Icons.ChevronRight, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
        }
        Text(
            "Data: TMDB", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted,
            modifier = Modifier.align(Alignment.BottomEnd).graphicsLayer { alpha = immersion.shown.value }.padding(end = 48.dp, bottom = 18.dp),
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

/**
 * One tab's shelves. Every parameter stays the same object while the tab is hidden and the lists don't change, so a
 * switch to another tab doesn't recompose these; [active] is read only when focus moves.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabShelves(
    tab: Int,
    rows: List<ShelfRow>,
    scroll: ScrollState,
    active: State<Boolean>,
    pivot: BringIntoViewSpec,
    rowScrolling: BringIntoViewSpec,
    justInView: BringIntoViewSpec,
    requester: (String) -> FocusRequester,
    /** Each shelf's last focused title, by "tab/shelf". */
    shelfPoster: Map<String, String>,
    onFocused: (key: String, shelf: String, title: MovieDetails) -> Unit,
    onOpen: (MovieDetails) -> Unit,
    onHold: (MovieDetails) -> Unit,
    onResume: (Watching) -> Unit,
) {
    if (rows.isEmpty()) return
    CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
    Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides rowScrolling) {
        rows.forEach { row ->
            key(row.id) {
            val shelf = "$tab/${row.id}"
            RowCard(live = false, verticalAlignment = Alignment.Top, padding = PaddingValues(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                // Wide enough for the longest name, "Documentary", on one line.
                Column(Modifier.width(112.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(row.note, fontSize = 11.sp, color = Palette.Muted, maxLines = 1)
                }
                if (row.titles.isEmpty()) {
                    SkeletonPosterRow(rememberPulse(), modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 8.dp))
                } else {
                val rowState = remember { LazyListState() }
                // A few titles from the end, the next page is fetched, so it's usually in before the end.
                val more by rememberUpdatedState(row.more)
                LaunchedEffect(rowState) {
                    snapshotFlow { rowState.layoutInfo.let { it.totalItemsCount - (it.visibleItemsInfo.lastOrNull()?.index ?: 0) } }
                        .collect { left -> if (left <= GROW_WHEN_LEFT) more?.invoke() }
                }
                // Continue's wide cards nearly all fit: its row moves only to show one in full, not to put
                // the focused card a third of the way in, which cut the one before it in half.
                CompositionLocalProvider(LocalBringIntoViewSpec provides if (row.continued.isEmpty()) rowScrolling else justInView) {
                LazyRow(
                    Modifier
                        .weight(1f)
                        // Up or Down onto a shelf goes back to the title last focused on it, else its
                        // leftmost title in view. Left to itself, focus goes to the title nearest the
                        // middle of the one it left, which under a wide Continue card is the second
                        // poster. Always the leftmost in view drifted: landing scrolls the title a third
                        // of the way in, bringing the one before it into view for the next time.
                        .focusProperties {
                            onEnter = {
                                if (requestedFocusDirection == FocusDirection.Down || requestedFocusDirection == FocusDirection.Up) {
                                    val back = shelfPoster[shelf]?.takeIf { k -> row.titles.any { key(tab, row.id, it) == k } }
                                    if (back == null || !requester(back).requestFocus()) {
                                        val seen = rowState.layoutInfo.visibleItemsInfo
                                        val first = seen.firstOrNull { it.offset >= 0 } ?: seen.firstOrNull()
                                        row.titles.getOrNull(first?.index ?: 0)?.let { requester(key(tab, row.id, it)).requestFocus() }
                                    }
                                }
                            }
                        }
                        .focusGroup(),
                    state = rowState,
                    // Room for a focused poster to grow without being clipped.
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(row.titles, key = { it.ref }) { title ->
                        val k = key(tab, row.id, title)
                        val left = row.continued[title.ref]
                        if (left != null) {
                            ContinueTile(left, active, requester(k), onFocused = { onFocused(k, shelf, title) }, onLongClick = { onHold(title) }) { onResume(left) }
                        } else {
                            PosterTile(title, active, requester(k), onFocused = { onFocused(k, shelf, title) }) { onOpen(title) }
                        }
                    }
                    if (row.growing) items(GROWING_PLACEHOLDERS) { SkeletonPoster(rememberPulse()) }
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

/**
 * The shelves of the tab showing [kind] (null for Home). Home: what was played, the account's lists and what's
 * trending, films and series together. Movies and TV: the account's lists, then TMDB's. Empty ones left out; one
 * not fetched yet stays as a placeholder, so the shelves below don't move down when it arrives.
 */
private fun shelfRows(
    kind: MediaKind?,
    lately: List<Watching>,
    signedIn: Boolean,
    myFilms: Map<AccountList, List<MovieDetails>>,
    mySeries: Map<AccountList, List<MovieDetails>>,
    movies: Shelves,
    tv: Shelves,
    more: (MediaKind, Shelf) -> Unit,
): List<ShelfRow> = buildList {
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
    if (signedIn) TmdbAccount.SHELVES.forEach {
        add(ShelfRow(it.name, it.title, it.note, pick(myFilms[it].orEmpty(), mySeries[it].orEmpty()), loading = pending(it !in myFilms, it !in mySeries)))
    }
    val shelves = if (kind == null) listOf(Shelf.Trending) else Shelf.of(kind)
    shelves.forEach { shelf ->
        // Home's Trending grows both kinds together, so films and series keep taking turns.
        val grow = listOfNotNull(MediaKind.Movie.takeIf { kind != MediaKind.Tv && movies.canGrow(shelf) }, MediaKind.Tv.takeIf { kind != MediaKind.Movie && tv.canGrow(shelf) })
        add(
            ShelfRow(
                shelf.name, shelf.title, shelf.note, pick(movies.rows[shelf].orEmpty(), tv.rows[shelf].orEmpty()),
                loading = pending(movies.fetchedAt == null, tv.fetchedAt == null),
                growing = pending(shelf in movies.busy, shelf in tv.busy),
                more = if (grow.isEmpty()) null else { -> grow.forEach { k -> more(k, shelf) } },
            ),
        )
    }
}.filter { it.titles.isNotEmpty() || it.loading }

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
    /** Getting its next page: placeholders follow its last title. */
    val growing: Boolean = false,
    /** Fetches its next page; null when there's no more, or one is on its way. */
    val more: (() -> Unit)? = null,
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

/** Titles still to the right of the last one in view when a shelf fetches its next page. */
private const val GROW_WHEN_LEFT = 6
/** Posters standing in for a shelf's next page while it comes. */
private const val GROWING_PLACEHOLDERS = 3

/** How long focus stays on a poster before the hero changes to it. */
private const val HERO_SETTLE_MS = 250L
/** Room under the content for the footer's hints (at 18 dp from the bottom). */
private val FOOTER_SPACE = 52.dp
/** How far below the top of the shelves the focused poster sits: its card's padding and the room it grows into. */
private val SHELF_PIVOT = 14.dp

/** Room left beside a card scrolled into view, for it to grow into when focused. */
private val CARD_MARGIN = 10.dp

/** Scrolls only as far as it takes to show what took focus in full, [marginPx] clear of the edge. */
private class JustInView(private val marginPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < marginPx -> offset - marginPx
        offset + size > containerSize - marginPx -> offset + size - containerSize + marginPx
        else -> 0f
    }
}

/** Scrolls whatever takes focus to [pivotPx] below the top, not just into view; not at all while [frozen]. */
private class ShelfPivot(private val pivotPx: Float, private val frozen: () -> Boolean) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (frozen()) 0f else offset - pivotPx
}
