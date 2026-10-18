package app.couchcommander.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.LauncherApp
import app.couchcommander.data.iptv.IptvSource
import app.couchcommander.data.iptv.VodEntry
import app.couchcommander.data.iptv.VodEpisode
import app.couchcommander.data.iptv.VodNames
import app.couchcommander.data.iptv.XtreamVod
import app.couchcommander.domain.MediaText
import app.couchcommander.player.VodPlay
import app.couchcommander.player.VodPlayerActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.couchcommander.data.media.MediaRepository
import app.couchcommander.data.media.TmdbAccount
import app.couchcommander.data.media.Watching
import app.couchcommander.ui.Icons
import app.couchcommander.ui.home.confirmKeys
import app.couchcommander.ui.page.ActionPill
import app.couchcommander.ui.page.TabPill
import app.couchcommander.ui.rememberClock
import app.couchcommander.ui.theme.Palette
import app.media.Episode
import app.media.MediaKind
import app.media.MovieAccountState
import app.media.MovieDetails
import app.media.Seasons
import app.media.TitleExtras
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * One film or series, over the shelves: its backdrop, facts, plot, where to watch it in the viewer's country and
 * the trailer, which plays full screen with sound. A series lists its episodes season by season; both end with
 * "More like this". Opens with what the shelf already had and fills in as the details arrive. Signed in to TMDB,
 * Watchlist, Favourite and Rate sit beside the trailer; a change shows at once and is put back if TMDB refuses it.
 * When the IPTV panel has the title, Play comes first (Resume where it was left), with a chip for each version
 * when there are several, and a series' episodes play from their rows.
 */
@Composable
fun TitleScreen(
    title: MovieDetails,
    media: MediaRepository,
    accent: Color,
    active: Boolean,
    country: String?,
    account: TmdbAccount,
    onOpen: (MovieDetails) -> Unit,
) {
    var trailerOpen by remember(title.id) { mutableStateOf(false) }
    val clock = rememberClock()
    // Until each answer is in (or has failed), placeholders hold its place; one already in memory shows at once.
    var detailsDone by remember(title) { mutableStateOf(media.cachedDetails(title) != null) }
    var extrasDone by remember(title) { mutableStateOf(media.cachedExtras(title) != null) }
    val full by produceState(media.cachedDetails(title) ?: title, title) {
        media.details(title)?.let { value = it }
        detailsDone = true
    }
    val extras by produceState(media.cachedExtras(title), title) {
        value = media.extras(title)
        extrasDone = true
    }
    val backdrop = rememberRemoteImage(full.backdropUrl, 960.dp)
    val poster = rememberRemoteImage(full.posterUrl, POSTER_LARGE_WIDTH)
    val trailer = full.videos.firstOrNull()
    val related = extras?.related.orEmpty()

    // Play from the IPTV panel: its versions of this title, the one last played (or picked) first.
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as LauncherApp).container }
    val source by container.iptvSource.source.collectAsStateWithLifecycle()
    val catalogue by container.vod.status.collectAsStateWithLifecycle()
    val versions by produceState<List<VodEntry>?>(null, title.id, catalogue.syncedAtEpochMillis) { value = container.vod.find(title) }
    val continued by container.played.all.collectAsStateWithLifecycle()
    val watching = continued.firstOrNull { it.title.kind == title.kind && it.title.id == title.id }
    val labels = remember(versions) { versionLabels(versions.orEmpty()) }
    var picked by remember(title.id) { mutableStateOf<Int?>(null) }
    val version = versions?.takeIf { it.isNotEmpty() }?.let { v -> (picked ?: v.indices.firstOrNull { labels[it] == watching?.version } ?: 0).coerceIn(v.indices) }
    val chosen = version?.let { versions?.getOrNull(it) }
    var panelDone by remember(chosen?.id) { mutableStateOf(false) }
    val panelEpisodes by produceState<List<VodEpisode>?>(null, chosen?.id) {
        value = null
        if (title.kind == MediaKind.Tv && chosen != null) value = container.vod.episodes(source, chosen)
        panelDone = true
    }
    val byNumber = remember(panelEpisodes) { panelEpisodes?.associateBy { it.season to it.number }.orEmpty() }
    // A series carries on from the episode it was left at, else starts at the first (specials last).
    val upNext = panelEpisodes?.let { eps ->
        watching?.let { w -> eps.firstOrNull { it.season == w.season && it.number == w.episode } } ?: eps.firstOrNull { it.season >= 1 } ?: eps.firstOrNull()
    }
    val canPlay = source is IptvSource.Xtream && chosen != null && (title.kind == MediaKind.Movie || upNext != null)
    // Play is still being worked out: the panel's versions, or a series' episodes on it.
    val playPending = source is IptvSource.Xtream && (versions == null || (title.kind == MediaKind.Tv && chosen != null && !panelDone))
    val resumeAt = watching?.positionMs?.takeIf { it >= RESUME_FROM_MS && (title.kind == MediaKind.Movie || (watching.season == upNext?.season && watching.episode == upNext?.number)) }

    /** Starts [episode] (or the film) at [startMs] in the chosen version; a series' next episode follows it. */
    fun play(episode: VodEpisode?, episodeTitle: String?, startMs: Long) {
        val src = source as? IptvSource.Xtream ?: return
        val entry = chosen ?: return
        val label = labels[version ?: return]
        val p = if (episode == null) {
            VodPlay(full, label, XtreamVod.movieStreamUrl(src, entry), entry.id, entry.ext, startMs = startMs)
        } else {
            fun of(e: VodEpisode, name: String?) = VodPlay(full, label, XtreamVod.episodeStreamUrl(src, e), e.id, e.ext, e.season, e.number, name ?: e.title)
            val after = panelEpisodes?.let { eps -> eps.getOrNull(eps.indexOf(episode) + 1) }
            of(episode, episodeTitle).copy(startMs = startMs, next = after?.let { of(it, null) })
        }
        VodPlayerActivity.start(context, p)
    }

    /** What Play does: carries on where it was left, in the chosen version. */
    fun playNow() {
        val name = watching?.episodeTitle?.takeIf { upNext != null && watching.season == upNext.season && watching.episode == upNext.number }
        play(upNext, name, resumeAt ?: 0)
    }

    val session by account.session.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Where this title stands in the account; null until it's known, so the buttons don't show a guess.
    var mine by remember(title.id, session) { mutableStateOf<MovieAccountState?>(null) }
    var mineDone by remember(title.id, session) { mutableStateOf(false) }
    LaunchedEffect(title.id, session) {
        mine = account.state(title)
        mineDone = true
    }
    var rating by remember(title.id) { mutableStateOf(false) }
    val rateFocus = remember(title.id) { FocusRequester() }
    val firstRating = remember(title.id) { FocusRequester() }
    BackHandler(enabled = active && rating) { rating = false }
    LaunchedEffect(rating) {
        withFrameNanos { }
        runCatching { if (rating) firstRating.requestFocus() else if (mine != null) rateFocus.requestFocus() }
    }
    /** Shows [next] at once and saves it; puts the old state back if TMDB doesn't take it. */
    fun change(next: MovieAccountState, save: suspend () -> Boolean) {
        val before = mine ?: return
        mine = next
        scope.launch { if (!save()) mine = before }
    }

    val seasons = remember(full.seasonList) { Seasons.ordered(full.seasonList) }
    var season by remember(title.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(seasons) { if (season == null) season = Seasons.initial(full.seasonList, full.nextEpisode)?.number }
    // True once the viewer has moved to another season; the first one opened doesn't wait.
    var seasonPicked by remember(title.id) { mutableStateOf(false) }
    var episodesFailed by remember(title.id, season) { mutableStateOf(false) }
    val episodes by produceState(season?.let { media.cachedEpisodes(title, it) }, title.id, season) {
        val s = season
        value = s?.let { media.cachedEpisodes(title, it) }
        if (s == null || value != null) return@produceState
        // Focus opens a season, so moving across the tabs asks only for the one it stops on.
        if (seasonPicked) delay(SEASON_SETTLE_MS)
        value = media.episodes(title, s)
        episodesFailed = value == null
    }
    val seasonFocus = remember(seasons.size) { List(seasons.size) { FocusRequester() } }
    val ownTrailerFocus = remember(title.id) { FocusRequester() }

    // Focus goes to the first thing there is to press, once it has loaded: the trailer, a season, a related title.
    val first = remember(title.id) { FocusRequester() }
    val fallback = remember(title.id) { FocusRequester() }
    var holdsFocus by remember(title.id) { mutableStateOf(false) }
    var restingOnFacts by remember(title.id) { mutableStateOf(false) }
    val firstTarget = when {
        canPlay -> "play"
        trailer != null -> "trailer"
        session != null -> "account"
        seasons.isNotEmpty() -> "season"
        related.isNotEmpty() -> "related"
        else -> null
    }
    // The first season's tab is the first thing to press when there's no trailer.
    fun seasonRequester(i: Int) = if (firstTarget == "season" && i == 0) first else seasonFocus[i]
    // Closing the trailer puts focus back on its pill.
    val trailerFocus = if (firstTarget == "trailer") first else ownTrailerFocus
    LaunchedEffect(title.id, firstTarget, active) {
        if (!active) return@LaunchedEffect
        withFrameNanos { }
        // Only while nothing here has focus yet, or it rests on the facts for want of a button: never away from a pick.
        if (!holdsFocus || restingOnFacts) runCatching { if (firstTarget != null) first.requestFocus() else fallback.requestFocus() }
    }

    val today = remember { LocalDate.now() }
    Box(Modifier.fillMaxSize().background(Palette.Ground).onFocusChanged { holdsFocus = it.hasFocus }) {
        // One cached layer: scrolling the page then redraws a single texture, not the still and two fades.
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            backdrop?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.55f)) }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Palette.Ground.copy(alpha = 0.95f), 0.55f to Palette.Ground.copy(alpha = 0.7f), 1f to Palette.Ground.copy(alpha = 0.3f)),
                ),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Palette.Ground.copy(alpha = 0.9f))))
        }

        val scroll = rememberScrollState()
        // Back on the buttons or the version chips, the whole top shows again: the title, the header. After the
        // focus system's own bring-into-view scroll, which would cancel one started at the same time.
        var topFocused by remember(title.id) { mutableStateOf(false) }
        LaunchedEffect(topFocused) {
            if (!topFocused) return@LaunchedEffect
            withFrameNanos { }
            snapshotFlow { scroll.isScrollInProgress }.first { !it }
            if (scroll.value > 0) scroll.animateScrollTo(0)
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 48.dp, vertical = 30.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(if (title.kind == MediaKind.Movie) "Movies" else "TV", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    full.trendingRank?.let { "Trending #$it this week" } ?: "From TMDB",
                    fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp).weight(1f),
                )
                Text(clock.time, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Row(
                Modifier
                    .padding(top = 22.dp)
                    .onFocusChanged { topFocused = it.hasFocus },
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .focusRequester(fallback)
                        .onFocusChanged { restingOnFacts = it.isFocused }
                        // With nothing to press, focus rests here so Back and the remote still reach this page.
                        .focusProperties { canFocus = active && firstTarget == null }
                        .focusable(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        (if (title.kind == MediaKind.Movie) "FILM" else "SERIES") + full.genres.firstOrNull()?.let { " · ${it.uppercase()}" }.orEmpty(),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = Palette.Muted,
                    )
                    Text(full.title, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, lineHeight = 42.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(MediaText.facts(full), fontSize = 15.sp)
                        if (full.rating > 0) {
                            Text("★", fontSize = 14.sp, color = accent)
                            Text("%.1f".format(full.rating), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(x = (-6).dp))
                        }
                    }
                    MediaText.people(full).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 13.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    full.overview?.let {
                        Text(it, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 520.dp).padding(top = 4.dp))
                    }
                    val accountPending = session != null && !mineDone
                    if (canPlay || trailer != null || session != null || playPending || !detailsDone) {
                        val pills = active && !trailerOpen && !rating
                        val pulse = if (playPending || !detailsDone || accountPending) rememberPulse() else null
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!canPlay && playPending && pulse != null) SkeletonPill(pulse)
                            if (canPlay) {
                                val label = when {
                                    upNext != null -> (if (resumeAt != null) "Resume" else "Play") + " S${upNext.season} E${upNext.number}"
                                    resumeAt != null -> "Resume ${playTime(resumeAt)}"
                                    else -> "Play"
                                }
                                ActionPill(Icons.Play, label, accent, pills, focus = first) { playNow() }
                            }
                            if (trailer != null) ActionPill(if (canPlay) Icons.Film else Icons.Play, "Trailer", accent, pills, focus = trailerFocus) { trailerOpen = true }
                            else if (!detailsDone && pulse != null) SkeletonPill(pulse, 112.dp)
                            if (mine == null && accountPending && pulse != null) {
                                SkeletonPill(pulse, 128.dp)
                                SkeletonPill(pulse, 128.dp)
                                SkeletonPill(pulse, 84.dp)
                            }
                            mine?.let { m ->
                                ActionPill(
                                    if (m.watchlist) Icons.Check else Icons.Plus, "Watchlist", accent, pills,
                                    focus = if (firstTarget == "account") first else null,
                                ) { change(m.copy(watchlist = !m.watchlist)) { account.setWatchlist(title, !m.watchlist) } }
                                ActionPill(if (m.favorite) Icons.Check else Icons.Heart, "Favourite", accent, pills, focus = null) {
                                    change(m.copy(favorite = !m.favorite)) { account.setFavorite(title, !m.favorite) }
                                }
                                ActionPill(Icons.Star, m.rating?.let { "Rated ${MediaText.score(it)}" } ?: "Rate", accent, pills, focus = rateFocus) {
                                    rating = true
                                }
                            }
                        }
                        val m = mine
                        if (rating && m != null) {
                            // Back closes it without changing anything.
                            Row(Modifier.padding(top = 10.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val current = m.rating?.toInt()?.coerceIn(1, 10) ?: 7
                                (1..10).forEach { n ->
                                    ActionPill(null, "$n", accent, active, focus = if (n == current) firstRating else null) {
                                        rating = false
                                        change(m.copy(rating = n.toDouble())) { account.rate(title, n.toDouble()) }
                                    }
                                }
                                if (m.rating != null) {
                                    ActionPill(null, "Remove", accent, active, focus = null) {
                                        rating = false
                                        change(m.copy(rating = null)) { account.rate(title, null) }
                                    }
                                }
                            }
                        }
                    }
                    if (watching != null) {
                        // Both take it off Continue for now: nothing records what was watched yet. Focus goes
                        // to the first button first, as these two leave with it.
                        fun forget() {
                            runCatching { first.requestFocus() }
                            scope.launch(Dispatchers.IO) { container.played.remove(title) }
                        }
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionPill(null, "Reset progress", accent, active && !trailerOpen && !rating, focus = null) { forget() }
                            ActionPill(Icons.Check, "Mark watched", accent, active && !trailerOpen && !rating, focus = null) { forget() }
                        }
                    }
                    if (canPlay && labels.size > 1) {
                        // One chip per version; moving onto one picks it, as with tabs, and OK plays it.
                        val chipFocus = remember(labels) { List(labels.size) { FocusRequester() } }
                        Row(
                            Modifier
                                .padding(top = 10.dp)
                                .offset(x = (-12).dp)
                                .horizontalScroll(rememberScrollState())
                                .focusProperties { onEnter = { chipFocus[version ?: 0].requestFocus() } }
                                .focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            labels.forEachIndexed { i, l ->
                                Box(
                                    Modifier.onPreviewKeyEvent { e ->
                                        if (e.nativeKeyEvent.keyCode !in confirmKeys) return@onPreviewKeyEvent false
                                        // Focus has already picked this chip's version.
                                        if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP && version == i) playNow()
                                        true
                                    },
                                ) {
                                    TabPill(l, i == version, accent, active && !trailerOpen && !rating, chipFocus[i]) { picked = i }
                                }
                            }
                        }
                    }
                    if (source != null && versions?.isEmpty() == true) {
                        Text("Not on your IPTV playlist", fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                    val where = country?.let { MediaText.whereToWatch(extras?.providersIn(it), it) }
                    Text(
                        where ?: if (extras != null && country != null) "Not on a streaming service in ${MediaText.countryName(country)}" else "",
                        fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Box(
                    Modifier.size(POSTER_LARGE_WIDTH, POSTER_LARGE_WIDTH * 1.5f).clip(RoundedCornerShape(14.dp)).background(Palette.Raised),
                ) {
                    poster?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }

            if (seasons.isEmpty() && title.kind == MediaKind.Tv && !detailsDone) {
                // The seasons come with the details: their tabs and a list's worth of rows until then.
                val pulse = rememberPulse()
                Row(Modifier.padding(top = 26.dp).offset(x = (-12).dp)) { repeat(3) { SkeletonTab(pulse) } }
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(PLACEHOLDER_EPISODES) { SkeletonEpisode(pulse) }
                }
            }
            if (seasons.isNotEmpty()) {
                Row(
                    Modifier
                        .padding(top = 26.dp)
                        .offset(x = (-12).dp)
                        .horizontalScroll(rememberScrollState())
                        // Up from the episodes lands on the open season.
                        .focusProperties { onEnter = { seasonRequester(seasons.indexOfFirst { it.number == season }.coerceAtLeast(0)).requestFocus() } }
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    seasons.forEachIndexed { i, s ->
                        TabPill(s.name, s.number == season, accent, active, seasonRequester(i)) {
                            if (s.number != season && season != null) seasonPicked = true
                            season = s.number
                        }
                    }
                }
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val list = episodes
                    when {
                        list == null && episodesFailed -> Text("Couldn't get this season's episodes.", fontSize = 14.sp, color = Palette.Muted)
                        list == null -> {
                            // As many rows as TMDB says the season has, so the page doesn't jump when they come.
                            val pulse = rememberPulse()
                            val count = seasons.firstOrNull { it.number == season }?.episodeCount?.takeIf { it > 0 } ?: PLACEHOLDER_EPISODES
                            repeat(count.coerceAtMost(MAX_PLACEHOLDER_EPISODES)) { SkeletonEpisode(pulse) }
                        }
                        list.isEmpty() -> Text("No episodes listed for this season.", fontSize = 14.sp, color = Palette.Muted)
                        else -> list.forEach { e ->
                            val onPanel = byNumber[e.season to e.number]?.takeIf { canPlay }
                            val left = watching?.takeIf { it.season == e.season && it.episode == e.number && it.positionMs >= RESUME_FROM_MS }
                            EpisodeRow(
                                e, next = full.nextEpisode?.let { it.season == e.season && it.number == e.number } == true, today, accent, active,
                                playable = onPanel != null,
                                left = left,
                            ) { onPanel?.let { play(it, e.title, left?.positionMs ?: 0) } }
                        }
                    }
                }
            }

            if (extras == null && !extrasDone) {
                Text("More like this", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 26.dp, bottom = 4.dp))
                SkeletonPosterRow(rememberPulse(), modifier = Modifier.padding(vertical = 8.dp))
            }
            if (related.isNotEmpty()) {
                Text("More like this", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 26.dp, bottom = 4.dp))
                val relatedState = remember(title.id) { LazyListState() }
                val relatedFocus = remember(related) { List(related.size) { FocusRequester() } }
                fun relatedRequester(i: Int) = if (firstTarget == "related" && i == 0) first else relatedFocus[i]
                LazyRow(
                    Modifier
                        .offset(x = (-6).dp)
                        // Down from an episode lands on the leftmost title in view. Left to itself, focus goes to the
                        // title nearest the middle of the wide card it left.
                        .focusProperties {
                            onEnter = {
                                if (requestedFocusDirection == FocusDirection.Down || requestedFocusDirection == FocusDirection.Up) {
                                    val seen = relatedState.layoutInfo.visibleItemsInfo
                                    val leftmost = seen.firstOrNull { it.offset >= 0 } ?: seen.firstOrNull()
                                    runCatching { relatedRequester(leftmost?.index ?: 0).requestFocus() }
                                }
                            }
                        }
                        .focusGroup(),
                    state = relatedState,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(related, key = { _, t -> t.id }) { i, t ->
                        PosterTile(t, active, relatedRequester(i), onFocused = {}) { onOpen(t) }
                    }
                }
            }
            Text(
                "Data: TMDB · Where to watch: JustWatch", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted,
                modifier = Modifier.padding(top = 24.dp),
            )
        }

        if (trailerOpen && active) {
            TrailerScreen(full.videos.map { it.key }) {
                trailerOpen = false
                runCatching { trailerFocus.requestFocus() }
            }
        }
    }
}

/**
 * A trailer full screen with sound, in the launcher rather than the YouTube app (garbled's player). OK pauses and
 * plays; Back, or the trailer ending, closes it. The hints fade once it has played a few seconds.
 */
@Composable
private fun TrailerScreen(keys: List<String>, onClose: () -> Unit) {
    var playing by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf(TrailerState.Loading) }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    LaunchedEffect(state) { if (state == TrailerState.Ended) onClose() }
    var hints by remember { mutableStateOf(true) }
    LaunchedEffect(state, playing) {
        hints = true
        if (state == TrailerState.Playing && playing) {
            delay(3_000)
            hints = false
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.nativeKeyEvent.keyCode !in confirmKeys) return@onKeyEvent false
                if (e.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) playing = !playing
                true
            }
            .focusable(),
    ) {
        TrailerPlayer(keys, playing = playing, muted = false, loop = false, onStateChange = { state = it }, modifier = Modifier.fillMaxSize())
        val message = when (state) {
            TrailerState.Failed -> "This trailer can't play here."
            TrailerState.Loading -> "Loading the trailer…"
            else -> null
        }
        message?.let { Text(it, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center)) }
        Text(
            if (playing) "OK: pause · Back: close" else "Paused · OK: play · Back: close",
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 18.dp).alpha(if (hints || state != TrailerState.Playing) 1f else 0f),
        )
    }
}

private val POSTER_LARGE_WIDTH = 170.dp
/** How long focus rests on a season tab before its episodes are asked for. */
private const val SEASON_SETTLE_MS = 250L
/** Rows shown while a season's length isn't known yet, and at most while it is. */
private const val PLACEHOLDER_EPISODES = 6
private const val MAX_PLACEHOLDER_EPISODES = 20
/** Less than this in, a title starts over instead of offering Resume. */
private const val RESUME_FROM_MS = 30_000L

/** "1:02:03" or "42:10". */
private fun playTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Each version's chip label, numbered where two would read the same. */
private fun versionLabels(versions: List<VodEntry>): List<String> {
    val plain = versions.map { VodNames.label(it) }
    val counts = plain.groupingBy { it }.eachCount()
    val seen = HashMap<String, Int>()
    return plain.map { l -> if ((counts[l] ?: 0) > 1) "$l ${seen.merge(l, 1, Int::plus)}" else l }
}

/**
 * One episode as a card: its still, a "Next episode" label, number and title, air date, length and
 * score, and the start of its plot; dimmed until it's out. The one Continue stopped at shows how far in it was left,
 * with Continue's bar. One on the IPTV panel shows a play mark and plays with OK.
 */
@Composable
private fun EpisodeRow(
    episode: Episode,
    next: Boolean,
    today: LocalDate,
    accent: Color,
    active: Boolean,
    playable: Boolean,
    /** Continue's entry when it stopped part way into this episode. */
    left: Watching?,
    onPlay: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val upcoming = episode.isUpcoming(today.toString())
    val still = rememberRemoteImage(episode.stillUrl, EPISODE_STILL_WIDTH)
    val line = listOfNotNull(
        left?.let(::timeLeft),
        MediaText.episodeLine(episode, today).takeIf { it.isNotEmpty() },
        "★ ${"%.1f".format(Locale.ROOT, episode.rating)}".takeIf { episode.rating > 0 && !upcoming },
    ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) EpisodeFocused else EpisodeGround)
            .focusProperties { canFocus = active }
            // A row the panel doesn't have still takes focus, so the remote can scroll the list.
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { if (playable) onPlay() }
            .height(EPISODE_HEIGHT)
            .padding(EPISODE_PADDING)
            .alpha(if (upcoming) 0.6f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(EPISODE_STILL_WIDTH, EPISODE_STILL_HEIGHT).clip(RoundedCornerShape(8.dp)).background(EpisodeGround)) {
            still?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            left?.let {
                // Over a dark foot, as on Continue's cards, so the bar shows on a bright still.
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xB3000000))))
                WatchedBar(it.progress, Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (next) Text("NEXT EPISODE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = accent)
            Text("E${episode.number} · ${episode.title}", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, maxLines = 1)
            episode.overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 13.sp, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
            }
        }
        if (playable) Icon(Icons.Play, "Play", tint = if (focused) Palette.White else Palette.Muted, modifier = Modifier.size(14.dp))
    }
}

private val EpisodeGround = Color(0x0AFFFFFF)
private val EpisodeFocused = Color(0x24FFFFFF)
