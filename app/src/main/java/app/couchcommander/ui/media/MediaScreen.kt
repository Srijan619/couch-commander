package app.couchcommander.ui.media

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.data.art.ArtFrame
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.couchcommander.data.media.MediaRepository
import app.couchcommander.data.media.ContinueWatching
import app.couchcommander.data.media.Watching
import app.couchcommander.data.media.TmdbAccount
import app.couchcommander.data.media.Shelf
import app.couchcommander.data.media.Shelves
import app.couchcommander.ui.Icons
import app.couchcommander.ui.page.ActionPill
import app.couchcommander.ui.page.RowCard
import app.couchcommander.ui.page.TabPill
import app.couchcommander.ui.page.rememberBlurred
import app.couchcommander.ui.rememberClock
import app.couchcommander.ui.theme.Palette
import app.media.AccountList
import app.media.MediaKind
import app.media.MovieDetails
import kotlinx.coroutines.delay

/**
 * Design H: the full-screen page Left on home's "Apps" handle slides in, the football page's mirror. Tabs for
 * Home, Movies and TV, each a few lists as shelves of posters under a hero. Home has films and series together:
 * Continue (what was played, as wide cards with progress), the account's lists and what's trending; Movies and TV
 * are for browsing one kind. The hero shows the focused title's still, name and plot, its trailer playing muted
 * once focus rests on it. OK on a poster opens its page over this one, and "More like this" there opens the next
 * on top, so Back walks back through them. Back from the shelves goes home. The account pill at the end of the
 * tabs signs in to TMDB from a phone; signed in, the watchlist and favourites come before TMDB's lists.
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
    // the account's lists, then TMDB's. Empty ones left out.
    val rows = buildList {
        fun pick(films: List<MovieDetails>, series: List<MovieDetails>) = when (kind) {
            null -> mix(films, series)
            MediaKind.Movie -> films
            MediaKind.Tv -> series
        }
        if (kind == null) add(ShelfRow(CONTINUE, "Continue", "Where you left off", lately.map { it.title }, lately.associateBy { it.title.ref }))
        if (session != null) TmdbAccount.SHELVES.forEach { add(ShelfRow(it.name, it.title, it.note, pick(myFilms[it].orEmpty(), mySeries[it].orEmpty()))) }
        val shelves = if (kind == null) listOf(Shelf.Trending) else Shelf.entries
        shelves.forEach { add(ShelfRow(it.name, it.title, it.note, pick(movies.rows[it].orEmpty(), tv.rows[it].orEmpty()))) }
    }.filter { it.titles.isNotEmpty() }
    var accountOpen by remember { mutableStateOf(false) }
    val accountFocus = remember { FocusRequester() }
    // Closing the panel puts focus back on the pill, not on the shelves.
    var backToAccount by remember { mutableStateOf(false) }

    // The titles opened from the shelves, the one on screen last.
    var opened by remember { mutableStateOf(listOf<MovieDetails>()) }
    // Where focus was on the shelves, to go back to when the last page closes.
    val posterFocus = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = posterFocus.getOrPut(key) { FocusRequester() }
    var lastPoster by remember { mutableStateOf<String?>(null) }
    var inTabs by remember { mutableStateOf(false) }
    // The title the hero shows: the focused poster's, a moment after focus lands, so scrolling past doesn't load
    // every still on the way.
    var hovered by remember { mutableStateOf<MovieDetails?>(null) }
    val hero by produceState(hovered, hovered) {
        delay(HERO_SETTLE_MS)
        value = hovered
    }
    val firstTitle = rows.firstOrNull()?.titles?.first()
    LaunchedEffect(tab, firstTitle?.id) { if (hovered == null || (kind != null && hovered?.kind != kind)) hovered = firstTitle }

    LaunchedEffect(active) {
        if (!active) {
            opened = emptyList()
            lastPoster = null
            accountOpen = false
            // Each visit opens on Home.
            tab = 0
        }
    }
    LaunchedEffect(accountOpen && active) { if (accountOpen && active) account.startSignIn() else account.stopSignIn() }

    val shelvesActive = active && opened.isEmpty() && !accountOpen
    BackHandler(enabled = active && opened.isEmpty(), onBack = onClose)
    BackHandler(enabled = active && opened.isNotEmpty()) { opened = opened.dropLast(1) }

    // Focus on the open tab when the page opens, and back on the poster when the last title page closes.
    LaunchedEffect(shelvesActive) {
        if (!shelvesActive) return@LaunchedEffect
        withFrameNanos { }
        if (backToAccount) {
            backToAccount = false
            if (runCatching { accountFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
        val target = lastPoster?.let { posterFocus[it] } ?: tabFocus[tab]
        runCatching { target.requestFocus() }.onFailure { runCatching { tabFocus[tab].requestFocus() } }
    }

    Box(Modifier.fillMaxSize().background(Palette.Ground)) {
        backdrop?.let { Image(it, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize()) }
        HeroBackdrop(hero, media, trailers = shelvesActive)

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
                            val first = rows.firstOrNull()?.let { key(tab, it.id, it.titles.first()) }
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
                    Icons.Person, session?.username ?: "Sign in", accent, shelvesActive, accountFocus,
                    modifier = Modifier.padding(end = 18.dp), description = "TMDB account",
                ) { accountOpen = true }
                Text(clock.time, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            HeroText(hero, accent, Modifier.padding(top = 4.dp))

            if (rows.isEmpty()) {
                Column(Modifier.padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when (kind) { null -> "Getting films and series…"; MediaKind.Movie -> "Getting films…"; MediaKind.Tv -> "Getting series…" }, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                    Text("From TMDB. The first time after a quiet spell can take up to a minute.", fontSize = 15.sp, color = Palette.Muted)
                }
            } else {
                // Moving to another shelf scrolls it to the top, so every Up or Down moves the shelves the same
                // amount; the posters' own rows scroll as usual.
                val rowScrolling = LocalBringIntoViewSpec.current
                val pivot = with(LocalDensity.current) { remember(this) { ShelfPivot(SHELF_PIVOT.toPx()) } }
                CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
                Column(Modifier.padding(top = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowScrolling) {
                    rows.forEach { row ->
                        RowCard(live = false, verticalAlignment = Alignment.Top, padding = PaddingValues(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                            Column(Modifier.width(96.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                                Text(row.note, fontSize = 11.sp, color = Palette.Muted, maxLines = 1)
                            }
                            LazyRow(
                                Modifier.weight(1f),
                                // Room for a focused poster to grow without being clipped.
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(row.titles, key = { it.ref }) { title ->
                                    val k = key(tab, row.id, title)
                                    val left = row.continued[title.ref]
                                    if (left != null) {
                                        ContinueTile(left, shelvesActive, requester(k), onFocused = { lastPoster = k; hovered = title }) { opened = listOf(title) }
                                    } else {
                                        PosterTile(title, shelvesActive, requester(k), onFocused = { lastPoster = k; hovered = title }) {
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
            if (rows.isNotEmpty()) {
                Icon(if (inTabs) Icons.ChevronDown else Icons.ArrowUp, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
                Text(
                    if (inTabs) "Down: titles" else "Up: Home, Movies, TV, account",
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

        // The open title covers the shelves; only the top one is drawn.
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
)

private val AccountList.title get() = if (this == AccountList.Favorites) "Favourites" else name
private val AccountList.note get() = if (this == AccountList.Favorites) "Yours" else "To watch"

private const val CONTINUE = "Continue"

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
