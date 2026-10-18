package app.couchcommander.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.couchcommander.data.media.MediaRepository
import app.couchcommander.data.media.MediaSearch
import app.couchcommander.data.media.SearchState
import app.couchcommander.ui.Icons
import app.couchcommander.ui.page.ActionPill
import app.couchcommander.ui.page.TabPill
import app.couchcommander.ui.rememberClock
import app.couchcommander.ui.theme.Bricolage
import app.couchcommander.ui.theme.Palette
import app.media.MovieDetails
import app.media.Person
import app.media.PersonDetails
import app.media.SearchResult
import app.media.SearchScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Search on the Movies and TV page, as in Garbled: films, series and people from TMDB. The search pill opens it over
 * the shelves with the keyboard up; the tabs above the field narrow it to films, series or people. Results are
 * posters (and people as round photos) under a hero for the focused one. OK on a title opens its page on top, so
 * Back comes back here; OK on a person shows their films and series, and Back goes back to the results. Back from
 * the results closes the search.
 */
@Composable
fun SearchScreen(
    search: MediaSearch,
    /** False while a title's page covers it, or the page is off screen. */
    active: Boolean,
    backdrop: ImageBitmap?,
    accent: Color,
    media: MediaRepository,
    onTitleOpen: (MovieDetails) -> Unit,
    onClose: () -> Unit,
) {
    val state by search.state.collectAsStateWithLifecycle()
    val clock = rememberClock()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = active) { if (state.person != null) search.onPersonClose() else onClose() }

    val field = remember { FocusRequester() }
    val fieldInteraction = remember { MutableInteractionSource() }
    val fieldFocused by fieldInteraction.collectIsFocusedAsState()
    val scopeFocus = remember { SearchScope.entries.associateWith { FocusRequester() } }
    val tileFocus = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = tileFocus.getOrPut(key) { FocusRequester() }
    // The tile focus was on, to go back to when a title's page closes; the person's, when their titles close.
    var lastTile by remember { mutableStateOf<String?>(null) }
    var personTile by remember { mutableStateOf<String?>(null) }
    var inResults by remember { mutableStateOf(false) }

    // The focused title, and the hero's a moment later. Held as State and read only inside the hero's own
    // composables: read here, every focus move would recompose the whole screen and every poster.
    val hovered = remember { mutableStateOf<MovieDetails?>(null) }
    val hero = remember { mutableStateOf<MovieDetails?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { hovered.value }.collectLatest {
            if (hero.value != null) delay(HERO_SETTLE_MS)
            hero.value = it
        }
    }

    val person = state.person
    // A person's own titles in place of the results: what TMDB knows them for at once, all of them once loaded.
    val items: List<SearchResult> = if (person != null) {
        (state.personInfo?.let(::titlesOf) ?: person.knownFor).map { SearchResult.Title(it) }
    } else {
        state.shown
    }
    // A different list starts at its top, with the hero cleared until focus lands on something in it.
    val listKey = person?.id ?: state.resultsFor
    val grid = rememberLazyGridState()
    LaunchedEffect(listKey) {
        grid.scrollToItem(0)
        hovered.value = null
    }
    val nearEnd by remember(grid) {
        derivedStateOf {
            val info = grid.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_AHEAD_ITEMS
        }
    }
    LaunchedEffect(nearEnd, state.canLoadMore, person) { if (nearEnd && state.canLoadMore && person == null) search.loadMore() }

    // The field when the search opens; the tile left from when a title's page or a person's titles close.
    LaunchedEffect(active, person?.id) {
        if (!active) return@LaunchedEffect
        withFrameNanos { }
        val back = if (person == null) personTile?.also { personTile = null } ?: lastTile else null
        val target = back?.let { tileFocus[it] } ?: if (person != null) items.firstOrNull()?.let { tileFocus[it.key] } else null
        if (target == null || runCatching { target.requestFocus() }.isFailure) runCatching { field.requestFocus() }
    }

    // The page hides around the hero's trailer while nobody presses a key.
    val immersion = rememberImmersion()
    Box(Modifier.fillMaxSize().background(Palette.Ground).onPreviewKeyEvent(immersion::onKey)) {
        Deferred { HeroBackdrop(hero.value, media, trailers = active && inResults, fallback = backdrop, immersion = immersion) }

        Column(Modifier.fillMaxSize().graphicsLayer { alpha = immersion.shown.value }.padding(start = 48.dp, end = 48.dp, top = 30.dp, bottom = FOOTER_SPACE)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Search", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 18.dp))
                Row(
                    Modifier
                        .weight(1f)
                        // Up from the field lands on the open tab.
                        .focusProperties { onEnter = { scopeFocus.getValue(state.scope).requestFocus() } }
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchScope.entries.forEach { scope ->
                        TabPill(scope.label, scope == state.scope, accent, active, scopeFocus.getValue(scope)) {
                            if (scope != state.scope) lastTile = null
                            search.onScopeChange(scope)
                        }
                    }
                }
                Text(clock.time, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            BasicTextField(
                value = state.query,
                onValueChange = {
                    lastTile = null
                    search.onQueryChange(it)
                },
                singleLine = true,
                interactionSource = fieldInteraction,
                textStyle = TextStyle(fontFamily = Bricolage, fontSize = 18.sp, color = Palette.White),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Done typing: the keyboard goes and focus moves down to the results, if there are any yet.
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    if (items.isNotEmpty()) focusManager.moveFocus(FocusDirection.Down)
                }),
                modifier = Modifier
                    .padding(top = 10.dp)
                    .width(560.dp)
                    .focusRequester(field)
                    .focusProperties { canFocus = active }
                    .semantics { contentDescription = "Search films, series and people" }
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.Raised)
                    .border(2.dp, if (fieldFocused) accent else Color.Transparent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Search, null, tint = Palette.Muted, modifier = Modifier.size(18.dp))
                        Box {
                            if (state.query.isEmpty()) Text(state.scope.hint, fontSize = 18.sp, color = Palette.Muted)
                            inner()
                        }
                    }
                },
            )

            Deferred { HeroText(hero.value, accent, Modifier.height(SEARCH_HERO_HEIGHT)) }

            val message = message(state, person, items.isEmpty())
            if (message != null) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(message.first, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
                    Text(message.second, fontSize = 15.sp, color = Palette.Muted)
                    if (state.failed || state.personFailed) {
                        ActionPill(null, "Retry", accent, active, null, modifier = Modifier.padding(top = 8.dp), onClick = search::onRetry)
                    }
                }
            } else {
                // Posters still to come, as grey ones where they'll appear: the first page, the next, or the rest
                // of a person's titles.
                val more = if (person != null) state.personInfo == null && !state.personFailed else state.firstLoading || state.loading
                val pulse = if (more) rememberPulse() else null
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(POSTER_WIDTH),
                    state = grid,
                    // Room for a focused poster to grow without being clipped.
                    contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize().onFocusChanged { inResults = it.hasFocus },
                ) {
                    if (person != null) {
                        item(key = "person", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                listOfNotNull(person.name, person.department).joinToString(" · "),
                                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Muted,
                            )
                        }
                    }
                    items(items, key = { it.key }) { result ->
                        val k = result.key
                        when (result) {
                            is SearchResult.Title -> PosterTile(result.movie, active, requester(k), onFocused = { lastTile = k; hovered.value = result.movie }) {
                                keyboard?.hide()
                                onTitleOpen(result.movie)
                            }
                            is SearchResult.Person -> PersonTile(result.person, active, requester(k), onFocused = { lastTile = k; hovered.value = null }) {
                                keyboard?.hide()
                                personTile = k
                                lastTile = null
                                search.onPersonOpen(result.person)
                            }
                        }
                    }
                    if (pulse != null) {
                        items(if (items.isEmpty()) PLACEHOLDER_FIRST else PLACEHOLDER_MORE, key = { "placeholder-$it" }) { SkeletonPoster(pulse) }
                    } else if (person == null && state.failed) {
                        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                            ActionPill(null, "Retry", accent, active, null, onClick = search::onRetry)
                        }
                    }
                }
            }
        }

        Row(Modifier.align(Alignment.BottomStart).graphicsLayer { alpha = immersion.shown.value }.padding(start = 48.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (fieldFocused) Icons.ChevronDown else Icons.ArrowUp, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
            Text(
                if (fieldFocused) "Down: results · Up: All, Movies, TV, People" else "Up: search",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.padding(start = 4.dp, end = 16.dp),
            )
            Text(if (person != null) "Back: results" else "Back: Movies & TV", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
        }
        Text(
            "Data: TMDB", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted,
            modifier = Modifier.align(Alignment.BottomEnd).graphicsLayer { alpha = immersion.shown.value }.padding(end = 48.dp, bottom = 18.dp),
        )
    }
}

/** A heading and a line in place of the results, or null when there are results to show. */
private fun message(state: SearchState, person: Person?, empty: Boolean): Pair<String, String>? = when {
    person != null -> when {
        !empty -> null
        state.personFailed -> "Couldn't get ${person.name}'s titles" to "Check the connection and try again."
        state.personInfo == null -> "Getting ${person.name}'s titles…" to "From TMDB."
        else -> "Nothing to show" to "TMDB has no films or series for ${person.name} yet."
    }
    !state.searchable -> "Search TMDB" to "Type a film, a series or who's in it. Up picks films, series or people only."
    state.firstLoading -> null // placeholder posters
    !empty -> null
    state.failed -> "Couldn't search TMDB" to "Check the connection and try again."
    else -> "Nothing found" to "Try other words, or another tab above."
}

/** Everything someone is known for (acting, for an actor), most-voted first, unreleased ones left out. */
private fun titlesOf(details: PersonDetails): List<MovieDetails> {
    val department = details.department ?: details.departments.firstOrNull()
    return details.credits
        .filter { it.department == department && it.title.voteCount > 0 }
        .map { it.title }
        .distinctBy { it.kind to it.id }
        .sortedByDescending { it.voteCount }
}

/** A person in the results: a round photo over their initials, with their name and what they do below. */
@Composable
private fun PersonTile(person: Person, active: Boolean, focus: FocusRequester, onFocused: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val photo = rememberRemoteImage(person.photoUrl, PHOTO_SIZE)
    Column(
        Modifier
            .focusRequester(focus)
            .scale(if (focused) 1.07f else 1f)
            .focusProperties { canFocus = active }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = listOfNotNull(person.name, person.department).joinToString(", ") }
            .size(POSTER_WIDTH, POSTER_HEIGHT),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(PHOTO_SIZE)
                .clip(CircleShape)
                .background(Palette.Raised)
                .then(if (focused) Modifier.border(2.dp, Palette.White, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                person.name.split(' ').mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString(""),
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Muted,
            )
            photo?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(person.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        person.department?.let { Text(it, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, maxLines = 1) }
    }
}

/** Its own recompose scope, so the hero's reads of [hero] recompose only the hero. */
@Composable
private fun Deferred(content: @Composable () -> Unit) = content()

private val SearchScope.label: String
    get() = when (this) {
        SearchScope.All -> "All"
        SearchScope.Movies -> "Movies"
        SearchScope.Tv -> "TV"
        SearchScope.People -> "People"
    }

private val SearchScope.hint: String
    get() = when (this) {
        SearchScope.All -> "Films, series and people"
        SearchScope.Movies -> "Films"
        SearchScope.Tv -> "Series"
        SearchScope.People -> "Actors, directors, writers"
    }

/** Placeholder posters while the first results come, and while more do. */
private const val PLACEHOLDER_FIRST = 16
private const val PLACEHOLDER_MORE = 4
/** How many tiles before the end the next page is asked for. */
private const val LOAD_AHEAD_ITEMS = 12
private const val HERO_SETTLE_MS = 250L
private val PHOTO_SIZE = 84.dp
/** Shorter than the shelves' hero: the field takes the rest. */
private val SEARCH_HERO_HEIGHT = 150.dp
/** Room under the content for the footer's hints (at 18 dp from the bottom). */
private val FOOTER_SPACE = 52.dp
