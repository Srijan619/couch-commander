package app.couchcommander.ui.football

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import app.couchcommander.LauncherApp
import app.couchcommander.data.art.ArtFrame
import app.couchcommander.data.iptv.ChannelHealth
import app.couchcommander.data.iptv.ChannelRecord
import app.couchcommander.data.iptv.Names
import app.couchcommander.data.iptv.PopularChannels
import app.couchcommander.data.iptv.IptvChannel
import app.couchcommander.data.iptv.WatchOption
import app.couchcommander.domain.FootballLines
import app.couchcommander.player.WatchAttempt
import app.couchcommander.domain.FootballPage
import app.couchcommander.domain.FootballText
import app.couchcommander.domain.LeaguePage
import app.couchcommander.ui.Icons
import app.couchcommander.ui.page.ActionPill
import app.couchcommander.ui.page.RowCard
import app.couchcommander.ui.page.TabPill
import app.couchcommander.ui.page.rememberBlurred
import app.couchcommander.ui.rememberClock
import app.couchcommander.ui.theme.Palette
import app.football.FootballBoard
import app.football.FootballBoards
import app.football.FootballLeague
import app.football.FootballMatch
import app.football.FootballTeam
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Design D, as a place to watch from: the full-screen page Right on home's "Apps" handle slides in. It lists the
 * followed teams' matches on now and later today, one row each, with a chip per provider the IPTV sync found it on.
 * Later days and results stay on home. Without a playlist it points to the setting. Back goes home.
 *
 * Design E adds a tab per followed league after "My teams", and "+" to choose leagues; a league's matches today show
 * as matchday posters (design L, MatchShelf), and so do the followed teams' on "My teams".
 * With a playlist, "Channels" sits left of "My teams": the big sports brands' channels in 4K, UHD or FHD, to watch
 * whatever is on when no match was found. A tab opens as focus lands on it; Up from the list returns to the open tab.
 */
@Composable
fun FootballScreen(
    /** False while it waits off screen: kept composed so opening it is only a slide (building it took ~95 ms). */
    active: Boolean,
    frame: ArtFrame?,
    accent: Color,
    matches: List<FootballMatch>,
    teams: List<FootballTeam>,
    leagues: List<FootballLeague>,
    /** Each followed league's matches, by league id. */
    leagueMatches: Map<Int, List<FootballMatch>>,
    /** Where each match is on, by match id (from the IPTV sync). */
    watch: Map<Long, List<WatchOption>>,
    hasPlaylist: Boolean,
    /** The big sports brands' channels from the playlist, for the "Channels" tab (see PopularChannels). */
    popular: List<IptvChannel>,
    /** The channels watched most, most first (ChannelHealth.mostWatched); a row of their own above the brands. */
    mostWatched: List<IptvChannel> = emptyList(),
    /** How each channel played lately (see ChannelHealth): failing ones go last, hidden ones aren't offered. */
    health: Map<String, ChannelRecord>,
    /** The match and what to try, in order: the pressed chip, then the others as fallbacks. */
    onWatch: (FootballMatch, List<WatchAttempt>) -> Unit,
    /** A channel from the "Channels" tab, then the same channel's other feeds as fallbacks. */
    onPlayChannel: (List<WatchAttempt>) -> Unit,
    /** Holding OK on a channel there: hide it from the football page, or bring back every hidden one. */
    onHideChannel: (IptvChannel) -> Unit = {},
    onShowHiddenChannels: () -> Unit = {},
    onAddPlaylist: () -> Unit,
    onChooseLeagues: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clock = rememberClock()
    val is24Hour = remember { DateFormat.is24HourFormat(context) }
    // Re-worded each minute, like home's block: countdowns, and today's list moving on.
    val minute = remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            minute.value = Instant.now()
        }
    }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember(matches, teams) {
        derivedStateOf<FootballPage.Today> { FootballPage.today(matches, teams, minute.value, zone) }
    }
    val backdrop = rememberBlurred(frame)
    val first = remember { FocusRequester() }

    // "Channels" (with a playlist), "My teams", then one per league. Each visit starts on "My teams" (-1).
    // Saved, like what was played last: on the TV, low on memory, the launcher is destroyed while the player is up
    // and built again on Back, which came back to "My teams" instead of the channel that was playing.
    val names = buildList { if (hasPlaylist) add("Channels"); add("My teams"); leagues.forEach { add(it.name) } }
    val mine = if (hasPlaylist) 1 else 0
    var tab by rememberSaveable { mutableIntStateOf(-1) }
    var played by rememberSaveable { mutableStateOf<String?>(null) }
    // Set only when built again with something played: the channel (or match's channel) it names takes focus.
    var restore by remember { mutableStateOf(played) }
    LaunchedEffect(active) { if (!active) { tab = -1; played = null } }
    val selected = if (tab < 0) mine else tab.coerceAtMost(names.lastIndex)
    val tabFocus = remember(names.size) { List(names.size) { FocusRequester() } }
    val showChannels = hasPlaylist && selected == 0
    var channelsBuilt by remember { mutableStateOf(false) }
    LaunchedEffect(showChannels) { if (showChannels) channelsBuilt = true }
    val league = leagues.getOrNull(selected - mine - 1)
    // The match tab on screen ("mine" or "league-<id>"), and the ones visited this time, kept composed while hidden.
    val shelfKey = when { showChannels -> null; league == null -> "mine"; else -> "league-${league.id}" }
    val visitedShelves = remember { mutableStateListOf<String>() }
    LaunchedEffect(shelfKey) { if (shelfKey != null && shelfKey !in visitedShelves) visitedShelves += shelfKey }
    val day = remember(league, leagueMatches) {
        derivedStateOf<LeaguePage.Day?> { league?.let { LeaguePage.today(leagueMatches[it.id].orEmpty(), minute.value, zone) } }
    }
    // The Channels tab marks the channels showing any of these, and lists them under "On now" in the tabs' order:
    // the followed teams', then each league's as its tab comes, by kickoff within each.
    val allMatches = remember(matches, leagues, leagueMatches) {
        (matches.sortedBy { it.kickoffEpochMillis } + leagues.flatMap { l -> leagueMatches[l.id].orEmpty().sortedBy { it.kickoffEpochMillis } }).distinctBy { it.id }
    }
    val followed = remember(teams) { teams.mapTo(mutableSetOf()) { it.id } }
    val home = remember { Names.homeCountry(ZoneId.systemDefault().id, Locale.getDefault().country) }
    // Off the main thread: the player updates the health records, and grouping the playlist's ~1,200 channels again
    // inside composition held the launcher's first frame for two seconds on the TV when coming back from it.
    val brands by produceState(emptyList<PopularChannels.Brand>(), popular, health, home) {
        value = withContext(Dispatchers.Default) { PopularChannels.group(ChannelHealth.order(popular, health, Instant.now()) { it }, home) }
    }
    // Likewise each match's chips. A match whose chips came out the same keeps the same object, so its card skips
    // recomposing; handing every card the health records redrew them all whenever the player updated them.
    val chips by produceState(remember { chipsFor(watch, health, home, emptyMap()) }, watch, health, home) {
        val previous = value
        value = withContext(Dispatchers.Default) { chipsFor(watch, health, home, previous) }
    }

    val hasTarget = remember(hasPlaylist, brands, mostWatched, day) {
        derivedStateOf {
            when {
                !hasPlaylist -> true
                showChannels -> brands.isNotEmpty() || mostWatched.isNotEmpty()
                day.value == null -> today.value.matches.isNotEmpty() || today.value.upcoming.isNotEmpty()
                else -> day.value?.matches?.isNotEmpty() == true || day.value?.nextMatches?.isNotEmpty() == true
            }
        }
    }
    // The footer names the way to the other half: Down to the matches from the tabs, Up to the tabs from the matches.
    var inTabs by remember { mutableStateOf(false) }


    BackHandler(enabled = active, onBack = onClose)
    LaunchedEffect(active) {
        if (!active) { restore = null; return@LaunchedEffect }
        withFrameNanos { }
        if (restore != null) {
            // The played channel takes focus itself once its tab is built (off the main thread); the open tab if it
            // doesn't show by then (hidden, or gone from the playlist).
            delay(1_500)
            if (restore == null) return@LaunchedEffect
            restore = null
        }
        // On the open tab ("My teams"), not on a match's first channel: one OK too many would start a stream.
        runCatching { tabFocus[selected].requestFocus() }
    }
    val playChannel = remember(onPlayChannel) {
        { attempts: List<WatchAttempt> -> played = attempts.firstOrNull()?.let { channelKey(it.channel) }; onPlayChannel(attempts) }
    }
    val watchMatch = remember(onWatch) {
        { match: FootballMatch, attempts: List<WatchAttempt> -> played = attempts.firstOrNull()?.let { matchKey(match, it.channel) }; onWatch(match, attempts) }
    }
    val restored = remember { { restore = null } }

    Box(Modifier.fillMaxSize().background(Palette.Ground)) {
        // The painting, blurred and dimmed once into a small bitmap and stretched: a live full-screen blur, and a
        // translucent dim over it, cost the TV's GPU a full redraw every frame.
        backdrop?.let { Image(it, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize()) }

        // The bottom padding keeps a long matchday's rows clear of the footer's hints.
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 30.dp, bottom = 52.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("Football", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Today", fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp).weight(1f),
                )
                Text(clock.time, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Row(
                Modifier
                    .padding(top = 14.dp)
                    .offset(x = (-12).dp)
                    // Many leagues run past the edge; focusing a tab scrolls it into view.
                    .horizontalScroll(rememberScrollState())
                    // Up from anywhere in the list lands on the open tab, not on whichever tab sits above.
                    .focusProperties { onEnter = { tabFocus[selected].requestFocus() } }
                    .onFocusChanged { inTabs = it.hasFocus }
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                names.forEachIndexed { i, name ->
                    key(i) {
                        val isSelected = remember(selected) { i == selected }
                        TabPill(name, isSelected, accent, active, tabFocus[i]) {
                            if (tab != i) tab = i
                        }
                    }
                }
                ActionPill(Icons.Plus, null, accent, active, focus = null, modifier = Modifier.padding(start = 6.dp), description = "Choose leagues", onClick = onChooseLeagues)
            }

            // Built on the first visit and then kept, hidden, while another tab is open: built again on every switch
            // from "My teams", it held one frame for 0.3 s on the TV.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Always keep ChannelsTab composed if it's been built, just hide it with alpha.
                if (hasPlaylist && channelsBuilt) {
                    Box(Modifier.graphicsLayer { alpha = if (showChannels) 1f else 0f }) {
                        ChannelsTab(
                            brands, mostWatched, popular, allMatches, watch, health, minute.value, zone, is24Hour, accent, active && showChannels, first,
                            restore = restore.takeIf { showChannels }, onRestored = restored,
                            onPlay = playChannel, onHide = onHideChannel, onShowHidden = onShowHiddenChannels,
                        )
                    }
                }
                (visitedShelves + listOfNotNull(shelfKey)).distinct().forEach { k ->
                    key(k) {
                        val shown = k == shelfKey
                        val focusable = rememberUpdatedState(active && shown)
                        Box(Modifier.graphicsLayer { alpha = if (shown) 1f else 0f }) {
                            Column {
                                val shelfLeague = leagues.firstOrNull { "league-${it.id}" == k }
                                if (shelfLeague == null) {
                                    MatchShelf(
                                        k, today.value.matches, firstResult = -1, today.value.upcoming, "No matches today",
                                        today.value.next?.let { "Next: ${dayName(it.kickoff, minute.value, zone)}" },
                                        followed = emptySet(), chips, hasPlaylist, minute.value, zone, is24Hour, accent, active, focusable,
                                        restore.takeIf { shown }, restored, watchMatch,
                                )
                                } else {
                                    val leagueDay = remember(shelfLeague, leagueMatches) {
                                        derivedStateOf<LeaguePage.Day?> { league?.let { LeaguePage.today(leagueMatches[it.id].orEmpty(), minute.value, zone) } }
                                    }
                                    val next = leagueDay.value?.next?.let { n ->
                                        val time = FootballBoards.timeLabel(n.kickoff, zone, is24Hour)
                                        "Next: ${dayName(n.kickoff, minute.value, zone)} · " + if (n.count == 1) "1 match at $time" else "${n.count} matches from $time"
                                    }
                                    MatchShelf(
                                        k, leagueDay.value?.matches.orEmpty(), leagueDay.value?.firstResult ?: -1, leagueDay.value?.nextMatches.orEmpty(), "No ${shelfLeague.name} today", next,
                                        followed, chips, hasPlaylist, minute.value, zone, is24Hour, accent, active, focusable,
                                        restore.takeIf { shown }, restored, watchMatch,
                                    )
                                }
                                if (shown && !hasPlaylist) {
                                    ActionPill(
                                        Icons.ChevronRight, "Add your IPTV playlist to watch here", accent, active,
                                        focus = first, modifier = Modifier.padding(top = 18.dp), onClick = onAddPlaylist,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            key(inTabs, hasTarget.value, showChannels, names.size) {
                FootballFooterHints(inTabs, hasTarget.value, showChannels, names.size)
            }
            Icon(Icons.ChevronLeft, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
            Text("Back: home", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun FootballFooterHints(inTabs: Boolean, hasTarget: Boolean, showChannels: Boolean, namesSize: Int) {
    Box(Modifier.graphicsLayer { alpha = if (inTabs || !hasTarget) 0f else 1f }) {
        when {
            inTabs && hasTarget -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.ChevronDown, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
                    Text(
                        if (showChannels) "Down: channels" else "Down: matches",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.padding(end = 16.dp),
                    )
                }
            }
            !inTabs && namesSize > 1 -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.ArrowUp, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
                    Text(
                        if (namesSize > 1) "Up: leagues" else "Up: channels", // Simplified for this logic
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.padding(end = 16.dp),
                    )
                }
            }
        }
    }
}

/** What was played, saved across the launcher being built again: a channel from "Channels", or a match's channel. */
internal fun channelKey(channel: IptvChannel) = "channel:" + ChannelHealth.key(channel)
internal fun matchKey(match: FootballMatch, channel: IptvChannel) = "match:${match.id}:${channel.url}"

/** A match's ways to watch: [usable] feeds in the order to try them, and [providers], one per chip. */
internal data class MatchChips(val usable: List<WatchOption>, val providers: List<Pair<String, WatchOption>>)

/**
 * Hidden channels drop out, ones that played lately come first and recent failures last (ChannelHealth); then one
 * chip per provider, the viewer's country first. Health counts more than country: a Finnish channel that just failed
 * still goes after one that works. Chips equal to [previous]'s are its very objects.
 */
private fun chipsFor(
    watch: Map<Long, List<WatchOption>>,
    health: Map<String, ChannelRecord>,
    home: String?,
    previous: Map<Long, MatchChips>,
): Map<Long, MatchChips> {
    val now = Instant.now()
    return watch.filterValues { it.isNotEmpty() }.mapValues { (id, options) ->
        val usable = ChannelHealth.order(options, health, now) { it.channel }
        val byLabel = usable.groupBy { Names.provider(it.channel, it.score == 3) }.mapValues { it.value.first() }
        val byCountry = Names.homeFirst(byLabel.keys.toList(), home).map { it to byLabel.getValue(it) }
        val chips = MatchChips(usable, ChannelHealth.order(byCountry, health, now) { it.second.channel })
        previous[id]?.takeIf { it == chips } ?: chips
    }
}

/** A provider label as a tile's two lines: "Viaplay FI" → "Viaplay" over "FI · FHD". */
internal fun tileText(label: String, channel: IptvChannel): Pair<String, String> {
    val words = label.split(' ')
    val code = words.lastOrNull()?.takeIf { words.size > 1 && it.length in 2..3 && it.all { c -> c.isUpperCase() } }
    val name = if (code != null) words.dropLast(1).joinToString(" ") else label
    val quality = when (PopularChannels.quality(channel)) { 2 -> "UHD"; 1 -> "FHD"; else -> null }
    return name to (listOfNotNull(code, quality).joinToString(" · ").ifEmpty { "Live" })
}

/** Initials' grounds for channels without a logo: muted, so a row of them doesn't shout. */
private val MonogramGrounds = listOf(0xFF5B3FA8, 0xFF2C3A8C, 0xFF1F6F5C, 0xFF8A4B1E, 0xFF7A2E4E, 0xFF35506B).map { Color(it) }

/**
 * The logo on a square that suits it, or the channel's initials. [onWhite] while its tile is focused (white): the
 * light square turns grey with an edge so it doesn't melt into the tile.
 */
@Composable
internal fun ChannelLogo(url: String?, name: String, onWhite: Boolean) {
    val logos = (LocalContext.current.applicationContext as LauncherApp).container.logos
    val logo by produceState(url?.let { logos.cached(it) }, url) { if (url != null && value == null) value = logos.load(url) }
    val shape = RoundedCornerShape(8.dp)
    val loaded = logo
    if (loaded != null) {
        // Logos drawn for dark grounds (white lettering) get a dark square, the rest a light one.
        val ground = when {
            loaded.light -> LogoGroundDark
            onWhite -> LogoGroundOnWhite
            else -> LogoGround
        }
        Box(
            Modifier
                .size(33.dp)
                .clip(shape)
                .background(ground)
                .then(if (onWhite && !loaded.light) Modifier.border(1.dp, LogoEdge, shape) else Modifier)
                .padding(3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(loaded.image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    } else {
        val initials = name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()
        Box(
            Modifier.size(33.dp).clip(shape).background(MonogramGrounds[Math.floorMod(name.hashCode(), MonogramGrounds.size)]),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, color = Palette.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

private val LogoGround = Color(0xF2FFFFFF)
private val LogoGroundDark = Color(0xFF1B1B26)
private val LogoGroundOnWhite = Color(0xFFE8E8EE)
private val LogoEdge = Color(0x290B0B12)
/** How many channels the player works through before giving up (15 s each at worst). */
internal const val MAX_ATTEMPTS = 6
/** "+N" opens up to this many over two lines; more than that is noise. */
internal const val MAX_EXPANDED = 8
