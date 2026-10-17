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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * Design E adds a tab per followed league after "My teams": that league's matches today, and "+" to choose leagues.
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
    onAddPlaylist: () -> Unit,
    onChooseLeagues: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clock = rememberClock()
    val is24Hour = remember { DateFormat.is24HourFormat(context) }
    // Re-worded each minute, like home's block: countdowns, and today's list moving on.
    val minute by produceState(Instant.now()) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = Instant.now()
        }
    }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember(matches, teams, minute) { FootballPage.today(matches, teams, minute, zone) }
    val cards = remember(today, minute) {
        today.matches.mapNotNull { m -> FootballText.lines(FootballBoard(m, emptyList()), minute, zone, is24Hour)?.let { m to it } }
    }
    val backdrop = rememberBlurred(frame)
    val first = remember { FocusRequester() }

    // "Channels" (with a playlist), "My teams", then one per league. Each visit starts on "My teams" (-1).
    val names = buildList { if (hasPlaylist) add("Channels"); add("My teams"); leagues.forEach { add(it.name) } }
    val mine = if (hasPlaylist) 1 else 0
    var tab by remember { mutableIntStateOf(-1) }
    LaunchedEffect(active) { if (!active) tab = -1 }
    val selected = if (tab < 0) mine else tab.coerceAtMost(names.lastIndex)
    val tabFocus = remember(names.size) { List(names.size) { FocusRequester() } }
    val showChannels = hasPlaylist && selected == 0
    val league = leagues.getOrNull(selected - mine - 1)
    val day = remember(league, leagueMatches, minute) { league?.let { LeaguePage.today(leagueMatches[it.id].orEmpty(), minute, zone) } }
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

    val firstWatch = cards.indexOfFirst { (m, _) -> !watch[m.id].isNullOrEmpty() }
    val firstLeagueWatch = day?.matches?.indexOfFirst { !it.finished && !watch[it.id].isNullOrEmpty() } ?: -1
    // Whether the open tab has something to press below the tabs; the footer offers Down only then.
    val hasTarget = when {
        !hasPlaylist -> true
        showChannels -> brands.isNotEmpty() || mostWatched.isNotEmpty()
        day == null -> firstWatch >= 0
        else -> firstLeagueWatch >= 0
    }
    // The footer names the way to the other half: Down to the matches from the tabs, Up to the tabs from the matches.
    var inTabs by remember { mutableStateOf(false) }

    BackHandler(enabled = active, onBack = onClose)
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        withFrameNanos { }
        // On the open tab ("My teams"), not on a match's first channel: one OK too many would start a stream.
        runCatching { tabFocus[selected].requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Palette.Ground)) {
        // The painting, blurred and dimmed once into a small bitmap and stretched: a live full-screen blur, and a
        // translucent dim over it, cost the TV's GPU a full redraw every frame.
        backdrop?.let { Image(it, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize()) }

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 30.dp)) {
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
                    TabPill(name, i == selected, accent, active, tabFocus[i]) { tab = i }
                }
                ActionPill(Icons.Plus, null, accent, active, focus = null, modifier = Modifier.padding(start = 6.dp), onClick = onChooseLeagues)
            }

            if (showChannels) {
                ChannelsTab(brands, mostWatched, popular, accent, active, first, onPlayChannel)
            } else if (day == null) {
                Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (cards.isEmpty()) NothingToday(today.next, minute, zone, is24Hour)
                    cards.forEachIndexed { i, (m, lines) ->
                        MatchCard(
                            m, lines, chips[m.id], hasPlaylist, accent, active,
                            focus = if (i == firstWatch) first else null,
                            large = cards.size == 1,
                            onWatch = onWatch,
                        )
                    }
                    if (!hasPlaylist) {
                        ActionPill(
                            Icons.ChevronRight, "Add your IPTV playlist to watch here", accent, active,
                            focus = if (firstWatch < 0) first else null, onClick = onAddPlaylist,
                        )
                    }
                }
            } else {
                // A full matchday can run past the bottom; focusing a chip scrolls it into view.
                Column(Modifier.padding(top = 18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (day.matches.isEmpty()) NothingInLeague(day.next, minute, zone, is24Hour)
                    day.matches.forEachIndexed { i, m ->
                        if (i == day.firstResult) {
                            Text(
                                "EARLIER TODAY", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = Palette.Muted,
                                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                            )
                        }
                        LeagueRow(
                            m, result = i >= day.firstResult && day.firstResult >= 0, followed, chips[m.id], hasPlaylist,
                            minute, zone, is24Hour, accent, active,
                            focus = if (i == firstLeagueWatch) first else null,
                            onWatch = onWatch,
                        )
                    }
                    if (!hasPlaylist) {
                        ActionPill(
                            Icons.ChevronRight, "Add your IPTV playlist to watch here", accent, active,
                            focus = first, modifier = Modifier.padding(top = 18.dp), onClick = onAddPlaylist,
                        )
                    }
                }
            }
        }

        Row(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                inTabs && hasTarget -> {
                    Icon(Icons.ChevronDown, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
                    Text(
                        if (showChannels) "Down: channels" else "Down: matches",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.padding(start = 4.dp, end = 16.dp),
                    )
                }
                !inTabs && names.size > 1 -> {
                    Icon(Icons.ArrowUp, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
                    Text(
                        if (leagues.isNotEmpty()) "Up: leagues" else "Up: channels",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.padding(start = 4.dp, end = 16.dp),
                    )
                }
            }
            Icon(Icons.ChevronLeft, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
            Text("Back: home", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/**
 * The "Channels" tab: one row per brand, its channels as chips, the viewer's country first. Pressing one plays it,
 * falling back to the same channel's other feeds (another country's, or the FHD one).
 */
@Composable
private fun ChannelsTab(
    brands: List<PopularChannels.Brand>,
    mostWatched: List<IptvChannel>,
    popular: List<IptvChannel>,
    accent: Color,
    active: Boolean,
    first: FocusRequester,
    onPlay: (List<WatchAttempt>) -> Unit,
) {
    if (brands.isEmpty() && mostWatched.isEmpty()) {
        Column(Modifier.padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("No sports channels yet", fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Text("Sky Sports, TNT, V Sport, Viaplay, DAZN, CBS Sports and others show here after the next playlist sync.", fontSize = 15.sp, color = Palette.Muted)
        }
        return
    }
    fun play(pick: PopularChannels.Pick) {
        val same = Names.channel(pick.channel.name)
        val others = popular.filter { it.url != pick.channel.url && Names.channel(it.name) == same }
        onPlay((listOf(pick.channel) + others).take(MAX_ATTEMPTS).map { WatchAttempt(pick.label, it, event = false) })
    }
    Column(Modifier.padding(top = 18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mostWatched.isNotEmpty()) {
            RowCard(live = false, verticalAlignment = Alignment.Top) {
                Text("Most watched", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.width(150.dp).padding(top = 13.dp))
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(TILE_GAP), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    mostWatched.map(PopularChannels::pick).forEachIndexed { i, pick ->
                        ChannelTile(pick.channel.logo, pick.name, pick.detail.ifEmpty { "Live" }, accent, active, focus = if (i == 0) first else null) { play(pick) }
                    }
                }
            }
        }
        brands.forEachIndexed { b, brand ->
            // The brand sits level with its first line of tiles, however many lines they wrap to.
            RowCard(live = false, verticalAlignment = Alignment.Top) {
                Text(brand.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.width(150.dp).padding(top = 13.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    @Composable
                    fun tiles(picks: List<PopularChannels.Pick>, takesFirstFocus: Boolean) =
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(TILE_GAP), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                            picks.forEachIndexed { i, pick ->
                                ChannelTile(pick.channel.logo, pick.name, pick.detail.ifEmpty { "Live" }, accent, active, focus = if (takesFirstFocus && i == 0) first else null) { play(pick) }
                            }
                        }
                    // 4K, UHD and FHD first; HD and SD under their own heading, so the sharp feeds are what you see first.
                    // First focus goes to "Most watched" when it's there, else to the first brand's first tile.
                    val firstBrand = b == 0 && mostWatched.isEmpty()
                    if (brand.sharp.isNotEmpty()) tiles(brand.sharp, takesFirstFocus = firstBrand)
                    if (brand.other.isNotEmpty()) {
                        Text(
                            "HD AND SD", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = Palette.Muted,
                            modifier = Modifier.padding(top = if (brand.sharp.isEmpty()) 4.dp else 2.dp),
                        )
                        tiles(brand.other, takesFirstFocus = firstBrand && brand.sharp.isEmpty())
                    }
                }
            }
        }
    }
}

/** A league with nothing today: when its next match day is, and how big. */
@Composable
private fun NothingInLeague(next: LeaguePage.NextDay?, now: Instant, zone: ZoneId, is24Hour: Boolean) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("No matches today", fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        if (next != null) {
            val at = next.kickoff.atZone(zone)
            val day = if (at.toLocalDate() == now.atZone(zone).toLocalDate().plusDays(1)) "Tomorrow"
            else at.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
            val time = FootballBoards.timeLabel(next.kickoff, zone, is24Hour)
            val count = if (next.count == 1) "1 match at $time" else "${next.count} matches from $time"
            Text("Next: $day · $count", fontSize = 15.sp, color = Palette.Muted)
        }
    }
}

/**
 * One of a league's matches today, on one line: minute or kickoff, the teams (and score once started), a dot when
 * a followed team plays, and the provider chips. Results are dimmed and have no chips.
 */
@Composable
private fun LeagueRow(
    match: FootballMatch,
    result: Boolean,
    followed: Set<Int>,
    chips: MatchChips?,
    hasPlaylist: Boolean,
    now: Instant,
    zone: ZoneId,
    is24Hour: Boolean,
    accent: Color,
    active: Boolean,
    focus: FocusRequester?,
    onWatch: (FootballMatch, List<WatchAttempt>) -> Unit,
) {
    RowCard(live = match.live && !result, modifier = Modifier.alpha(if (result) 0.6f else 1f)) {
        Row(Modifier.width(76.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (match.live) Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            val status = when {
                result -> match.result ?: "FT"
                match.live -> match.liveTime ?: "Live"
                else -> FootballBoards.timeLabel(match.kickoff, zone, is24Hour)
            }
            Text(status.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, maxLines = 1)
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = if (match.started) "${match.home.name} ${match.home.score ?: 0}–${match.away.score ?: 0} ${match.away.name}"
            else "${match.home.name} – ${match.away.name}"
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (match.home.id in followed || match.away.id in followed) Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
        }
        when {
            result -> Box(Modifier.width(CHIPS_WIDTH))
            chips != null -> ProviderChips(match, chips, accent, active, focus, maxChips = MAX_CHIPS, onWatch = onWatch)
            hasPlaylist -> Box(Modifier.width(CHIPS_WIDTH)) { Text("Not in your playlist", fontSize = 13.sp, color = Palette.Muted) }
        }
    }
}

/** A tab on the football page: underlined in the accent while open, a white pill while focused. Focus opens it. */
@Composable
private fun TabPill(label: String, open: Boolean, accent: Color, active: Boolean, focus: FocusRequester, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onOpen() }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .focusRequester(focus)
            .scale(if (focused) 1.06f else 1f)
            .then(if (focused) Modifier.shadow(16.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (focused) Palette.White else Color.Transparent)
            .focusProperties { canFocus = active }
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onOpen)
            .padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 4.dp)
            // As wide as the label, so the underline below runs its full length.
            .width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label, fontSize = 15.sp, maxLines = 1,
            fontWeight = if (open) FontWeight.Bold else FontWeight.SemiBold,
            color = when {
                focused -> Palette.Ink
                open -> Palette.White
                else -> Palette.Muted
            },
        )
        Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(2.dp).clip(CircleShape).background(if (open && !focused) accent else Color.Transparent))
    }
}

@Composable
private fun NothingToday(next: FootballMatch?, now: Instant, zone: ZoneId, is24Hour: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("No matches today", fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        if (next != null) {
            val day = FootballBoards.dayLabel(next.kickoff, now, zone, short = false).lowercase().replaceFirstChar { it.uppercase() }
            Text(
                "Next: ${next.home.name} – ${next.away.name} · $day · ${FootballBoards.timeLabel(next.kickoff, zone, is24Hour)}",
                fontSize = 15.sp, color = Palette.Muted,
            )
        }
    }
}

/** One of today's matches: what and when on the left, where to watch and Watch on the right. */
@Composable
private fun MatchCard(
    match: FootballMatch,
    lines: FootballLines,
    chips: MatchChips?,
    hasPlaylist: Boolean,
    accent: Color,
    active: Boolean,
    focus: FocusRequester?,
    large: Boolean,
    onWatch: (FootballMatch, List<WatchAttempt>) -> Unit,
) {
    RowCard(live = lines.live, padding = PaddingValues(start = 22.dp, end = 12.dp, top = 14.dp, bottom = 14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lines.live) Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
                Text(lines.label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = Palette.Muted)
            }
            if (match.started) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val name = if (large) 28.sp else 22.sp
                    Text(match.home.name, fontSize = name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("${match.home.score ?: 0}–${match.away.score ?: 0}", fontSize = if (large) 48.sp else 34.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp)
                    Text(match.away.name, fontSize = name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            } else {
                Text(lines.title, fontSize = if (large) 34.sp else 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (lines.detail.isNotEmpty()) Text(lines.detail, fontSize = 14.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        when {
            chips != null -> ProviderChips(match, chips, accent, active, focus, maxChips = MAX_CHIPS, onWatch = onWatch)
            hasPlaylist -> Box(Modifier.width(CHIPS_WIDTH)) { Text("Not in your playlist", fontSize = 13.sp, color = Palette.Muted) }
        }
    }
}

/** A match's ways to watch: [usable] feeds in the order to try them, and [providers], one per chip. */
private data class MatchChips(val usable: List<WatchOption>, val providers: List<Pair<String, WatchOption>>)

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

/**
 * One chip per provider (the same feed in FHD and HD shares one), the viewer's country first; "+N" shows the rest
 * on a second line and moves focus to the first of them.
 */
@Composable
private fun ProviderChips(
    match: FootballMatch,
    chips: MatchChips,
    accent: Color,
    active: Boolean,
    focus: FocusRequester?,
    maxChips: Int,
    onWatch: (FootballMatch, List<WatchAttempt>) -> Unit,
) {
    val (usable, providers) = chips
    // Pressing a chip tries it first, then the other chips, then every other feed for the match.
    fun attempts(first: WatchOption): List<WatchAttempt> =
        (listOf(first) + providers.map { it.second } + usable).distinctBy { it.channel.url }.take(MAX_ATTEMPTS)
            .map { WatchAttempt(Names.provider(it.channel, it.score == 3), it.channel, event = it.score == 3) }
    var expanded by remember(match.id) { mutableStateOf(false) }
    // The page stays composed between visits, so it folds back up when you leave.
    LaunchedEffect(active) { if (!active) expanded = false }
    val firstExtra = remember { FocusRequester() }
    LaunchedEffect(expanded) { if (expanded) { withFrameNanos { }; runCatching { firstExtra.requestFocus() } } }
    FlowRow(
        Modifier.width(CHIPS_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        val shown = if (expanded) providers.take(MAX_EXPANDED) else providers.take(maxChips)
        shown.forEachIndexed { i, (label, option) ->
            val (name, detail) = tileText(label, option.channel)
            ChannelTile(
                option.channel.logo, name, detail, accent, active,
                focus = when {
                    i == 0 -> focus
                    i == maxChips -> firstExtra
                    else -> null
                },
            ) { onWatch(match, attempts(option)) }
        }
        val more = providers.size - shown.size
        if (!expanded && more > 0) MoreTile("+$more", "Show $more more channels", accent, active) { expanded = true }
    }
}

private const val MAX_CHIPS = 3
private val TILE_WIDTH = 128.dp
private val TILE_HEIGHT = 48.dp
private val TILE_GAP = 8.dp
/** The tiles' column: three tiles and "+N", left-aligned and the same on every row, so each row's first tile starts on one line. */
private val CHIPS_WIDTH = TILE_WIDTH * 3 + TILE_GAP * 3 + TILE_HEIGHT

/** A row card's ground: brighter while something in it has focus, a touch brighter while its match is live. */
private val CardGround = Color(0x0AFFFFFF)
private val CardLive = Color(0x14FFFFFF)
private val CardFocused = Color(0x24FFFFFF)

/**
 * A match (or a brand of channels) as a rounded card, so the row being browsed stands out from the others while
 * focus moves through its tiles.
 */
@Composable
private fun RowCard(
    live: Boolean,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    padding: PaddingValues = PaddingValues(start = 18.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) CardFocused else if (live) CardLive else CardGround)
            .heightIn(min = TILE_HEIGHT + 18.dp)
            .padding(padding),
        verticalAlignment = verticalAlignment,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        content = content,
    )
}

/** A provider label as a tile's two lines: "Viaplay FI" → "Viaplay" over "FI · FHD". */
private fun tileText(label: String, channel: IptvChannel): Pair<String, String> {
    val words = label.split(' ')
    val code = words.lastOrNull()?.takeIf { words.size > 1 && it.length in 2..3 && it.all { c -> c.isUpperCase() } }
    val name = if (code != null) words.dropLast(1).joinToString(" ") else label
    val quality = when (PopularChannels.quality(channel)) { 2 -> "UHD"; 1 -> "FHD"; else -> null }
    return name to (listOfNotNull(code, quality).joinToString(" · ").ifEmpty { "Live" })
}

/**
 * A channel to play: its logo from the playlist (the initials until it loads, or without one), the name, and
 * country and quality underneath. White while focused, like every other control on the page.
 */
@Composable
private fun ChannelTile(logo: String?, name: String, detail: String, accent: Color, active: Boolean, focus: FocusRequester?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val fg = if (focused) Palette.Ink else Palette.White
    Row(
        Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .scale(if (focused) 1.06f else 1f)
            .then(if (focused) Modifier.shadow(16.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (focused) Palette.White else Palette.Raised)
            .focusProperties { canFocus = active }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .size(TILE_WIDTH, TILE_HEIGHT)
            .padding(start = 7.dp, end = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ChannelLogo(logo, name, onWhite = focused)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = fg.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** "+N", the size of a logo tile's height, square. */
@Composable
private fun MoreTile(label: String, description: String, accent: Color, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .scale(if (focused) 1.06f else 1f)
            .then(if (focused) Modifier.shadow(16.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (focused) Palette.White else Palette.Raised)
            .focusProperties { canFocus = active }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .size(TILE_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Palette.Ink else Palette.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Initials' grounds for channels without a logo: muted, so a row of them doesn't shout. */
private val MonogramGrounds = listOf(0xFF5B3FA8, 0xFF2C3A8C, 0xFF1F6F5C, 0xFF8A4B1E, 0xFF7A2E4E, 0xFF35506B).map { Color(it) }

/**
 * The logo on a square that suits it, or the channel's initials. [onWhite] while its tile is focused (white): the
 * light square turns grey with an edge so it doesn't melt into the tile.
 */
@Composable
private fun ChannelLogo(url: String?, name: String, onWhite: Boolean) {
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
private const val MAX_ATTEMPTS = 6
/** "+N" opens up to this many over two lines; more than that is noise. */
private const val MAX_EXPANDED = 8

/** A white-when-focused pill: a provider chip, "+N", "+" for leagues, and the "add your playlist" link. */
@Composable
private fun ActionPill(
    icon: ImageVector?,
    label: String?,
    accent: Color,
    active: Boolean,
    focus: FocusRequester?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val fg = if (focused) Palette.Ink else Palette.White
    Row(
        modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .scale(if (focused) 1.06f else 1f)
            .then(if (focused) Modifier.shadow(16.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (focused) Palette.White else Palette.Raised)
            // Hidden off screen, nothing here may take focus (Right on home's last app would find it).
            .focusProperties { canFocus = active }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, if (label == null) "Choose leagues" else null, tint = fg, modifier = Modifier.size(15.dp)) }
        label?.let { Text(it, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}

/** The painting as a small, blurred and dimmed copy, made once per painting off the main thread. */
@Composable
private fun rememberBlurred(frame: ArtFrame?): ImageBitmap? {
    val image = frame?.image
    val blurred by produceState<ImageBitmap?>(null, image) {
        value = image?.let { withContext(Dispatchers.Default) { Blur.small(it.asAndroidBitmap(), dim = 0.62f, ground = Palette.Ground.toArgb()).asImageBitmap() } }
    }
    return blurred
}
