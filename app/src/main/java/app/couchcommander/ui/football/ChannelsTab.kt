package app.couchcommander.ui.football

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.iptv.ChannelHealth
import app.iptv.ChannelRecord
import app.iptv.IptvChannel
import app.iptv.Names
import app.iptv.PopularChannels
import app.iptv.WatchOption
import app.couchcommander.domain.ChannelsGuide
import app.couchcommander.domain.MatchWatch.MAX_ATTEMPTS
import app.couchcommander.player.WatchAttempt
import app.couchcommander.ui.home.confirmKeys
import app.couchcommander.ui.page.CardGround
import app.couchcommander.ui.theme.Palette
import app.football.FootballBoards
import app.football.FootballMatch
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The "Channels" tab (design K): "On now", "Most watched" and the brands down the left; the chosen one's channels on
 * the right, 4K, UHD and FHD first, HD and SD under their own heading. A dot marks a channel showing a match now, and
 * the strip under the grid says what the focused channel shows. Pressing a channel plays it, falling back to the same
 * channel's other feeds; holding OK offers to hide it. Focus landing on an entry on the left opens it.
 */
@Composable
internal fun ChannelsTab(
    brands: List<PopularChannels.Brand>,
    mostWatched: List<IptvChannel>,
    popular: List<IptvChannel>,
    /** Today's matches of the followed teams and leagues, and where each is on. */
    matches: List<FootballMatch>,
    watch: Map<Long, List<WatchOption>>,
    health: Map<String, ChannelRecord>,
    now: Instant,
    zone: ZoneId,
    is24Hour: Boolean,
    accent: Color,
    active: Boolean,
    first: FocusRequester,
    /** The channel played before the launcher was built again (channelKey): it takes focus, then [onRestored]. */
    restore: String? = null,
    onRestored: () -> Unit = {},
    onPlay: (List<WatchAttempt>) -> Unit,
    onHide: (IptvChannel) -> Unit,
    onShowHidden: () -> Unit,
) {
    // Kept composed while another tab is open (FootballScreen), and while hidden it keeps what it last showed:
    // rebuilt there every minute, it would cost a frame each time on a tab or page that doesn't show it.
    val held = remember { arrayOfNulls<Shown>(1) }
    val shown = held[0]?.takeIf { !active } ?: Shown(brands, mostWatched, matches, watch, health, now).also { held[0] = it }
    val (_, _, _, _, health, now) = shown
    // Worked out off the main thread (names, the match lookup), so the first visit's frame only builds the tiles.
    // Until it's ready, the first time, the tab stays empty under the tabs for a frame or two.
    val built by produceState<Built?>(null, shown) {
        value = withContext(Dispatchers.Default) {
            val guide = ChannelsGuide.byChannel(shown.matches, shown.watch, now, zone)
            Built(guide, sections(shown.brands, shown.mostWatched, shown.matches, shown.watch, health, now, guide))
        }
    }
    val (guide, sections) = built ?: return
    if (sections.isEmpty()) {
        Column(Modifier.padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("No sports channels yet", fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Text("Sky Sports, TNT, V Sport, Viaplay, DAZN, CBS Sports and others show here after the next playlist sync.", fontSize = 15.sp, color = Palette.Muted)
        }
        return
    }
    // Remembered, like [focusable] below, so the tiles' handlers stay the same objects and the tiles skip recomposing
    // when the tab shows again; rebuilding them all made switching to it hold a frame for 0.17 s on the TV.
    val play = remember(popular, onPlay) {
        { pick: PopularChannels.Pick ->
            val same = Names.channel(pick.channel.name)
            val others = popular.filter { it.url != pick.channel.url && Names.channel(it.name) == same }
            onPlay((listOf(pick.channel) + others).take(MAX_ATTEMPTS).map { WatchAttempt(pick.label, it, event = false) })
        }
    }
    // Read only where focus asks, not in composition: [active] flips each time the tab shows or hides.
    val focusable = rememberUpdatedState(active)

    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    // Each visit starts on the first entry: "On now" when a match is on.
    LaunchedEffect(active) { if (!active) chosen = null }
    val open = sections.firstOrNull { it.key == chosen } ?: sections.first()
    val railFocus = remember(sections.map { it.key }) { sections.associate { it.key to FocusRequester() } }
    // Read only by the strip, so moving through the grid doesn't recompose the whole tab.
    val focused = remember { mutableStateOf<PopularChannels.Pick?>(null) }
    var menu by remember { mutableStateOf<PopularChannels.Pick?>(null) }
    // Each entry opens at the top of its channels.
    val gridScroll = remember(open.key) { ScrollState(0) }
    LaunchedEffect(active) { if (!active) menu = null }
    val restorePick = restore?.let { r -> open.groups.firstNotNullOfOrNull { g -> g.picks.firstOrNull { channelKey(it.channel) == r } } }
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(restorePick) {
        if (restorePick == null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { restoreFocus.requestFocus() }
        onRestored()
    }

    Box(Modifier.padding(top = 16.dp).fillMaxSize()) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Lazy: only the entries on screen are built; the brands below the edge come as focus moves down.
            LazyColumn(
                Modifier
                    .width(RAIL_WIDTH)
                    .fillMaxHeight()
                    // Left from the grid lands on the open entry, not on whichever sits level with the tile. The open
                    // entry is always on screen: an entry opens as focus lands on it.
                    .focusProperties { onEnter = { runCatching { first.requestFocus() } } },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(sections, key = { it.key }) { s ->
                    // Down from the tabs comes here, to the open entry.
                    val requester = if (s.key == open.key) first else railFocus.getValue(s.key)
                    RailEntry(s, s.key == open.key, accent, focusable, requester) { chosen = s.key }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(CardGround)) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(gridScroll).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(GAP),
                ) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)) {
                        Text(open.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(open.subtitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, modifier = Modifier.padding(bottom = 1.dp))
                    }
                    open.groups.forEach { g ->
                        when {
                            g.match != null -> MatchHeading(g.match, accent)
                            g.label != null -> Text(
                                g.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = Palette.Muted,
                                modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                            )
                        }
                        g.picks.chunked(COLUMNS).forEach { line ->
                            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                                line.forEach { pick ->
                                    val key = ChannelHealth.key(pick.channel)
                                    GridTile(
                                        pick, live = guide[key]?.live == true, failed = ChannelHealth.rank(health[key], now) > 0,
                                        accent, focusable, Modifier.weight(1f).then(if (pick === restorePick) Modifier.focusRequester(restoreFocus) else Modifier),
                                        onFocused = { focused.value = pick },
                                        onHold = { menu = pick },
                                    ) { play(pick) }
                                }
                                repeat(COLUMNS - line.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                Strip(focused, open, guide, health, now, zone, is24Hour, accent)
            }
        }
        menu?.let { pick ->
            ChannelMenu(
                pick, hidden = health.count { it.value.hidden }, accent,
                onPlay = { menu = null; play(pick) },
                onHide = {
                    menu = null
                    focused.value = null
                    onHide(pick.channel)
                    runCatching { first.requestFocus() }
                },
                onShowHidden = { menu = null; onShowHidden() },
                onDismiss = { menu = null },
            )
        }
    }
}

/** The match on each channel, and the entries on the left with their channels. */
private data class Built(val guide: Map<String, ChannelsGuide.OnChannel>, val sections: List<Section>)

/** What the tab was last built from. */
private data class Shown(
    val brands: List<PopularChannels.Brand>,
    val mostWatched: List<IptvChannel>,
    val matches: List<FootballMatch>,
    val watch: Map<Long, List<WatchOption>>,
    val health: Map<String, ChannelRecord>,
    val now: Instant,
)

/** An entry on the left: "On now", "Most watched" or a brand, its channels and how many show a match now. */
private class Section(val key: String, val title: String, val subtitle: String, val logo: String?, val live: Int, val count: Int, val groups: List<Group>)
/** A run of tiles, under a match's heading, a label ("HD AND SD"), or none. */
private class Group(val match: FootballMatch?, val label: String?, val picks: List<PopularChannels.Pick>)

private fun sections(
    brands: List<PopularChannels.Brand>,
    mostWatched: List<IptvChannel>,
    matches: List<FootballMatch>,
    watch: Map<Long, List<WatchOption>>,
    health: Map<String, ChannelRecord>,
    now: Instant,
    guide: Map<String, ChannelsGuide.OnChannel>,
): List<Section> {
    fun live(picks: List<PopularChannels.Pick>) = picks.count { guide[ChannelHealth.key(it.channel)]?.live == true }
    fun subtitle(count: Int, live: Int) = "$count channels" + if (live > 0) " · $live showing a match now" else ""
    val result = mutableListOf<Section>()
    val onNow = ChannelsGuide.onNow(matches, watch).mapNotNull { (m, options) ->
        ChannelHealth.order(options, health, now) { it.channel }.take(PopularChannels.MAX_PER_BRAND).takeIf { it.isNotEmpty() }
            ?.let { Group(m, null, it.map(::matchPick)) }
    }
    if (onNow.isNotEmpty()) {
        val count = onNow.sumOf { it.picks.size }
        result += Section("now", "On now", "$count channels showing a match", null, onNow.size, count, onNow)
    }
    if (mostWatched.isNotEmpty()) {
        val picks = mostWatched.map(PopularChannels::pick)
        result += Section("most", "Most watched", subtitle(picks.size, live(picks)), picks.first().channel.logo, live(picks), picks.size, listOf(Group(null, null, picks)))
    }
    brands.forEach { b ->
        val all = b.sharp + b.other
        if (all.isEmpty()) return@forEach
        val groups = listOfNotNull(
            b.sharp.takeIf { it.isNotEmpty() }?.let { Group(null, null, it) },
            b.other.takeIf { it.isNotEmpty() }?.let { Group(null, "HD AND SD", it) },
        )
        result += Section("brand:${b.name}", b.name, subtitle(all.size, live(all)), all.first().channel.logo, live(all), all.size, groups)
    }
    return result
}

/**
 * A channel under a match in "On now". A slot named after the match ("ESPN LIVE | CA OSASUNA VS. RACING") goes by its
 * provider, as on the match's own chips; a regular channel as in its brand's list, with the brand.
 */
internal fun matchPick(option: WatchOption): PopularChannels.Pick {
    if (option.score < 3) return PopularChannels.pick(option.channel)
    val (name, detail) = tileText(Names.provider(option.channel, event = true), option.channel)
    return PopularChannels.Pick(option.channel, name, detail.takeIf { it != "Live" }.orEmpty())
}

/** "34′ Brentford 1–0 Liverpool · Premier League" over the match's channels in "On now". */
@Composable
private fun MatchHeading(match: FootballMatch, accent: Color) {
    Row(Modifier.padding(start = 2.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        match.liveTime?.let {
            Text(it, color = Palette.Ink, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clip(CircleShape).background(accent).padding(horizontal = 7.dp, vertical = 1.dp))
        }
        Text(
            listOfNotNull(score(match), match.tournament).joinToString(" · "),
            fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun score(m: FootballMatch) =
    if (m.started) "${m.home.name} ${m.home.score ?: 0}–${m.away.score ?: 0} ${m.away.name}" else "${m.home.name} – ${m.away.name}"

/** An entry on the left: white while focused; the open one keeps a light ground once focus moves on to its channels. */
@Composable
private fun RailEntry(section: Section, open: Boolean, accent: Color, active: State<Boolean>, focus: FocusRequester, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onOpen() }
    val shape = RoundedCornerShape(10.dp)
    val fg = if (focused) Palette.Ink else Palette.White
    Row(
        Modifier
            .focusRequester(focus)
            .scale(if (focused) 1.04f else 1f)
            .then(if (focused) Modifier.shadow(16.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (focused) Palette.White else if (open) Palette.Raised else Color.Transparent)
            .focusProperties { canFocus = active.value }
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onOpen)
            .semantics { contentDescription = "${section.title}, ${section.subtitle}" }
            .fillMaxWidth()
            .height(42.dp)
            .padding(start = 5.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (section.key == "now") {
            Box(Modifier.size(33.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Raised), contentAlignment = Alignment.Center) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
            }
        } else {
            ChannelLogo(section.logo, section.title, onWhite = focused)
        }
        Text(section.title, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (section.live > 0 && section.key != "now") {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (focused) Palette.Ink else accent))
        }
        Text("${section.count}", color = fg.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * A channel in the grid: logo, a name of up to two lines, country and quality under it. A dot when it shows a match
 * now; dimmed when it didn't play last time. OK plays it, holding OK calls [onHold].
 */
@Composable
private fun GridTile(
    pick: PopularChannels.Pick,
    live: Boolean,
    failed: Boolean,
    accent: Color,
    active: State<Boolean>,
    modifier: Modifier,
    onFocused: () -> Unit,
    onHold: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val shape = RoundedCornerShape(10.dp)
    val fg = if (focused) Palette.Ink else Palette.White
    // OK is handled here, ahead of clickable, to tell a press from a hold; clickable stays for touch and semantics.
    var longFired by remember { mutableStateOf(false) }
    Box(
        modifier
            .scale(if (focused) 1.05f else 1f)
            .then(if (focused) Modifier.shadow(16.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .alpha(if (failed && !focused) 0.5f else 1f)
            .clip(shape)
            .background(if (focused) Palette.White else Palette.Raised)
            .focusProperties { canFocus = active.value }
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.keyCode !in confirmKeys) return@onPreviewKeyEvent false
                when (k.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (k.repeatCount == 0) longFired = false
                        else if (!longFired && (k.isLongPress || k.eventTime - k.downTime >= LONG_PRESS_MS)) {
                            longFired = true
                            onHold()
                        }
                    }
                    KeyEvent.ACTION_UP -> {
                        if (!longFired) onClick()
                        longFired = false
                    }
                }
                true
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "${pick.name}, ${pick.detail}" + (if (live) ", showing a match now" else "") + (if (failed) ", didn't play last time" else "")
                onLongClick("Options") { onHold(); true }
            }
            .height(TILE_HEIGHT),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 7.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChannelLogo(pick.channel.logo, pick.name, onWhite = focused)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(pick.name, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (failed) "Didn't play last time" else pick.detail.ifEmpty { "Live" },
                    color = fg.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (live) Box(Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 6.dp).size(6.dp).clip(CircleShape).background(accent))
    }
}

/** Under the grid: what the focused channel shows now or later today, or that it failed last time. */
@Composable
private fun Strip(
    focused: MutableState<PopularChannels.Pick?>,
    open: Section,
    guide: Map<String, ChannelsGuide.OnChannel>,
    health: Map<String, ChannelRecord>,
    now: Instant,
    zone: ZoneId,
    is24Hour: Boolean,
    accent: Color,
) {
    // Only a channel of the open entry: after moving on the left, the last tile's line would describe another list.
    val pick = focused.value?.takeIf { p -> open.groups.any { g -> g.picks.any { it.channel.url == p.channel.url } } }
    Row(
        Modifier.fillMaxWidth().height(STRIP_HEIGHT).background(StripGround).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (pick == null) {
            Text("Right: channels · Hold OK on one for options", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
            return@Row
        }
        val key = ChannelHealth.key(pick.channel)
        val on = guide[key]
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(listOf(pick.name, pick.detail).filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tag = when {
                    on?.live == true -> "LIVE"
                    on != null -> FootballBoards.timeLabel(on.match.kickoff, zone, is24Hour)
                    else -> null
                }
                tag?.let {
                    Text(
                        it, color = Palette.Ink, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.clip(CircleShape).background(if (on?.live == true) accent else Palette.White).padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
                Text(
                    when {
                        on != null -> listOfNotNull(score(on.match), on.match.liveTime.takeIf { on.live }).joinToString(" · ")
                        ChannelHealth.rank(health[key], now) > 0 -> "Didn't play last time. OK tries again, hold OK to hide it."
                        else -> "No match from your teams or leagues on it today"
                    },
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Holding OK on a channel: play it, hide it from the page, or bring back the hidden ones. */
@Composable
private fun ChannelMenu(
    pick: PopularChannels.Pick,
    hidden: Int,
    accent: Color,
    onPlay: () -> Unit,
    onHide: () -> Unit,
    onShowHidden: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstItem = remember { FocusRequester() }
    // Opened by holding OK, which is still down: its repeats and release are kept from the menu, so they don't press Play.
    var okHeld by remember { mutableStateOf(true) }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { firstItem.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.Mat)
                .onPreviewKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    if (!okHeld || k.keyCode !in confirmKeys) return@onPreviewKeyEvent false
                    if (k.action == KeyEvent.ACTION_UP) okHeld = false
                    true
                }
                // Focus stays in the menu until it closes.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                listOf(pick.name, pick.detail).filter { it.isNotEmpty() }.joinToString(" · "),
                color = MenuMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp),
            )
            MenuItem("Play", firstItem, onPlay)
            MenuItem("Hide this channel", null, onHide)
            if (hidden > 0) MenuItem("Show hidden channels ($hidden)", null, onShowHidden)
        }
    }
}

@Composable
private fun MenuItem(label: String, focus: FocusRequester?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Text(
        label,
        color = if (focused) Palette.White else Palette.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Palette.Ink else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

private const val COLUMNS = 4
private val GAP = 8.dp
private const val LONG_PRESS_MS = 600L
private val RAIL_WIDTH = 176.dp
private val TILE_HEIGHT = 52.dp
private val STRIP_HEIGHT = 50.dp
private val StripGround = Color(0x14FFFFFF)
private val Scrim = Color(0x99000000)
private val MenuMuted = Color(0xFF5E5650)
