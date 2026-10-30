package app.couchcommander.ui.football

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.domain.TeamCodes
import app.couchcommander.player.WatchAttempt
import app.couchcommander.ui.theme.Palette
import app.football.FootballBoards
import app.football.FootballMatch
import app.iptv.Names
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Design L: a league's matches, or the followed teams', as matchday posters on a sideways shelf. The focused match opens to its channels
 * (up to three, then "All N channels"); a match no channel shows opens to say so. Today's results follow under
 * "Earlier today", grey and dimmed, and open to nothing. With nothing today, [quietTitle] and [quietLine] over
 * [upcoming]'s posters, which name their day.
 */
@Composable
internal fun MatchShelf(
    /** Which tab: each keeps its own opened match. */
    key: String,
    matches: List<FootballMatch>,
    /** Where today's results start in [matches]; -1 without any. */
    firstResult: Int,
    upcoming: List<FootballMatch>,
    quietTitle: String,
    quietLine: String?,
    /** Teams that get a dot; none on "My teams", where every match is one. */
    followed: Set<Int>,
    chips: Map<Long, MatchChips>,
    hasPlaylist: Boolean,
    now: Instant,
    zone: ZoneId,
    is24Hour: Boolean,
    accent: Color,
    /** The page is on screen; it folds back to the first match when it goes. */
    pageActive: Boolean,
    /**
     * Whether this shelf takes focus: the page is on screen and this tab is open. A State read only where focus is
     * decided, so a kept, hidden shelf doesn't recompose as tabs switch.
     */
    active: State<Boolean>,
    /** The match's channel played before the launcher was built again (matchKey): it takes focus, then [onRestored]. */
    restore: String?,
    onRestored: () -> Unit,
    onWatch: (FootballMatch, List<WatchAttempt>) -> Unit,
) {
    val quiet = matches.isEmpty()
    val shown = if (quiet) upcoming else matches
    fun result(i: Int) = !quiet && firstResult >= 0 && i >= firstResult
    // Opens on the first match there is a channel for, else the first one still to come.
    val initial = shown.indices.firstOrNull { !result(it) && chips[shown[it].id] != null } ?: shown.indices.firstOrNull { !result(it) }
    var open by rememberSaveable(key) { mutableStateOf(initial?.let { shown[it].id }) }
    // Only a match opened by moving onto it takes focus; the one open as the tab opens leaves it on the tab.
    var moved by remember(key) { mutableStateOf(false) }
    LaunchedEffect(pageActive) { if (!pageActive) { open = initial?.let { shown[it].id }; moved = false } }
    // The opened match's first channel (or the card itself, with none): where Down from the tabs lands.
    val target = remember { FocusRequester() }

    Column(Modifier.padding(top = 18.dp)) {
        if (quiet) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                Text(quietTitle, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp, maxLines = 1)
                if (quietLine != null) {
                    Text(quietLine, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
        if (shown.isEmpty()) return@Column
        // The shelf runs past the right edge; focusing a poster scrolls it into view.
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(POSTER_GAP),
            verticalAlignment = Alignment.Top,
        ) {
            shown.forEachIndexed { i, m ->
                if (i == firstResult && !quiet) EarlierDivider()
                val isResult = result(i)
                val matchChips = if (isResult) null else chips[m.id]
                val poster = Poster(
                    m, isResult, m.home.id in followed || m.away.id in followed, POSTER_COLORS[i % POSTER_COLORS.size],
                    matchChips, showDay = quiet, now, zone, is24Hour,
                )
                if (open == m.id && !isResult) {
                    OpenCard(
                        poster, m, matchChips, hasPlaylist, accent, active, target, grab = moved,
                        restoreUrl = restore?.takeIf { it.startsWith("match:${m.id}:") }?.substringAfter("match:${m.id}:"), onRestored, onWatch,
                    )
                } else {
                    ClosedPoster(poster, accent, active) { open = if (isResult) null else m.id; moved = true }
                }
            }
        }
    }
}

/** The day a match day falls on, for a quiet tab's line: "Tomorrow", else "Saturday 25 October". */
internal fun dayName(kickoff: Instant, now: Instant, zone: ZoneId): String {
    val at = kickoff.atZone(zone)
    return if (at.toLocalDate() == now.atZone(zone).toLocalDate().plusDays(1)) "Tomorrow"
    else at.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
}

/** What a poster shows, worked out once for both its closed and opened forms. */
private class PosterText(
    val home: String, val away: String, val mid: String, val color: Color, val live: Boolean, val result: Boolean,
    val mine: Boolean, val time: String, val sub: String, val progress: Float?, val kickLabel: String, val aria: String,
)

private fun Poster(
    m: FootballMatch, result: Boolean, mine: Boolean, color: Color, chips: MatchChips?, showDay: Boolean,
    now: Instant, zone: ZoneId, is24Hour: Boolean,
): PosterText {
    val score = "${m.home.score ?: 0}–${m.away.score ?: 0}"
    val kickoff = FootballBoards.timeLabel(m.kickoff, zone, is24Hour)
    val dayName = if (showDay) m.kickoff.atZone(zone).format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) else null
    val channels = chips?.providers?.size ?: 0
    val live = m.live && !result
    val time = when {
        result -> m.result ?: score
        live -> m.liveTime ?: "Live"
        else -> kickoff
    }
    val sub = when {
        result -> "Full time · $kickoff"
        else -> listOfNotNull(dayName, if (channels == 0) "No channels" else if (channels == 1) "1 channel" else "$channels channels").joinToString(" · ")
    }
    // "34'" → a bar a third full; half-time and extra time just read as live.
    val minute = m.liveTime?.takeWhile { it.isDigit() }?.toIntOrNull()
    val aria = "${m.home.name} against ${m.away.name}, " + when {
        result -> "full time, $score"
        live -> "live, $score, ${m.liveTime ?: ""}"
        else -> listOfNotNull(dayName, kickoff).joinToString(" ")
    } + if (mine) ", your team" else ""
    return PosterText(
        TeamCodes.code(m.home.name), TeamCodes.code(m.away.name), if (result || m.started) score else "v",
        if (result) ResultColor else color, live, result, mine, time, sub,
        progress = if (live && minute != null) (minute / 90f).coerceIn(0f, 1f) else null,
        kickLabel = if (dayName != null) "$dayName kick-off" else "Kick-off", aria = aria,
    )
}

/** The coloured top of a poster: LIVE or FT, a dot for a followed team, and the two codes over the score. */
@Composable
private fun PosterBand(p: PosterText, accent: Color) {
    Column(
        Modifier.width(POSTER_WIDTH).height(BAND_HEIGHT).background(p.color).padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (p.live) {
                Row(
                    Modifier.height(20.dp).clip(RoundedCornerShape(10.dp)).background(accent).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Ink))
                    Text("LIVE", color = Palette.Ink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                }
            }
            if (p.result) Text("FT", color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            if (p.mine) Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
        }
        Column {
            Text(p.home, fontSize = 34.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, maxLines = 1)
            Text(p.mid, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(vertical = 2.dp))
            Text(p.away, fontSize = 34.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, maxLines = 1)
        }
    }
}

/** A poster on the shelf. Focusing a match still to come opens it (the shelf swaps in its [OpenCard]). */
@Composable
private fun ClosedPoster(p: PosterText, accent: Color, active: State<Boolean>, onFocus: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(POSTER_RADIUS)
    Column(
        Modifier
            .scale(if (focused) 1.04f else 1f)
            .alpha(if (p.result && !focused) 0.62f else 1f)
            .then(if (focused) Modifier.border(3.dp, accent, shape) else Modifier)
            .clip(shape)
            .background(CardBottom)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusProperties { canFocus = active.value }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onFocus() }
            .semantics { contentDescription = p.aria }
            .width(POSTER_WIDTH).height(POSTER_HEIGHT),
    ) {
        PosterBand(p, accent)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.time, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(p.sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The focused match, opened: its poster, then the teams and its channels, up to three and "All N channels". Focus
 * moves to the first channel as it opens. With no channel, the card itself takes focus and says why.
 */
@Composable
private fun OpenCard(
    p: PosterText,
    match: FootballMatch,
    chips: MatchChips?,
    hasPlaylist: Boolean,
    accent: Color,
    active: State<Boolean>,
    target: FocusRequester,
    grab: Boolean,
    /** The channel played from this card before the launcher was built again: it takes focus, then [onRestored]. */
    restoreUrl: String?,
    onRestored: () -> Unit,
    onWatch: (FootballMatch, List<WatchAttempt>) -> Unit,
) {
    val shape = RoundedCornerShape(POSTER_RADIUS)
    val providers = chips?.providers.orEmpty()
    // Opened to all of them when the channel played was one past the first few.
    var all by remember(match.id) { mutableStateOf(restoreUrl != null && providers.take(OPEN_CHIPS).none { it.second.channel.url == restoreUrl }) }
    // The poster that had focus is gone; its first channel takes over. Likewise after "All N channels". At once, in
    // the frame that swaps them: a frame later, Android has already handed focus to the open tab and that frame shows it.
    DisposableEffect(match.id, all) {
        if ((grab || all) && restoreUrl == null) runCatching { target.requestFocus() }
        onDispose { }
    }
    val back = remember { FocusRequester() }
    val empty = providers.isEmpty()
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .clip(shape)
            .background(OpenGround)
            .border(2.dp, OpenEdge, shape)
            .then(
                if (empty) Modifier.focusRequester(target).focusProperties { canFocus = active.value }
                    .clickable(interactionSource = interaction, indication = null) { }.semantics { contentDescription = p.aria }
                else Modifier,
            )
            .width(OPEN_WIDTH).height(POSTER_HEIGHT),
    ) {
        Column(Modifier.width(POSTER_WIDTH).fillMaxHeight()) {
            PosterBand(p, accent)
            Column(Modifier.fillMaxWidth().weight(1f).background(CardBottom).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.time, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (p.progress != null) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Raised)) {
                        Box(Modifier.fillMaxWidth(p.progress).height(4.dp).clip(RoundedCornerShape(2.dp)).background(accent))
                    }
                } else if (!p.live) {
                    Text(p.kickLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, maxLines = 1)
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(14.dp).then(if (all) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${match.home.name} – ${match.away.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(match.tournament ?: "", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
            if (empty) {
                Column(
                    Modifier.fillMaxWidth().border(1.5.dp, DashEdge, RoundedCornerShape(10.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(if (hasPlaylist) "Not in your playlist" else "No playlist yet", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (hasPlaylist) "No channel you have shows this match yet." else "Add your IPTV playlist to watch here.",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted, lineHeight = 16.sp,
                    )
                }
            } else {
                val shown = if (all) providers.take(MAX_EXPANDED) else providers.take(OPEN_CHIPS)
                val restoreAt = if (restoreUrl == null) -1 else shown.indexOfFirst { it.second.channel.url == restoreUrl }
                LaunchedEffect(restoreAt) {
                    if (restoreAt < 0) return@LaunchedEffect
                    withFrameNanos { }
                    runCatching { (if (restoreAt == 0) target else back).requestFocus() }
                    onRestored()
                }
                shown.forEachIndexed { i, (label, option) ->
                    ChannelRow(label, option.channel, accent, active, focus = if (i == 0) target else if (i == restoreAt) back else null) {
                        onWatch(match, watchAttempts(chips!!, option))
                    }
                }
                val more = providers.size - shown.size
                if (!all && more > 0) MoreRow("All ${providers.size} channels (+$more)", accent, active) { all = true }
            }
        }
    }
}

/** A channel in an opened card: logo, name, quality and country, the width of the card. White while focused. */
@Composable
private fun ChannelRow(label: String, channel: app.iptv.IptvChannel, accent: Color, active: State<Boolean>, focus: FocusRequester?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    // From the focus itself, not the interaction: the first channel takes focus in the frame it's built, before an
    // interaction collector would be listening.
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val fg = if (focused) Palette.Ink else Palette.White
    val (name, detail) = tileText(label, channel)
    Row(
        Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .scale(if (focused) 1.03f else 1f)
            .then(if (focused) Modifier.border(3.dp, accent, shape) else Modifier)
            .clip(shape)
            .background(if (focused) Palette.White else Palette.Raised)
            .onFocusChanged { focused = it.isFocused }
            .focusProperties { canFocus = active.value }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .fillMaxWidth().height(40.dp)
            .padding(start = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        ChannelLogo(channel.logo, name, onWhite = focused)
        Text(name, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(detail, color = fg.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun MoreRow(label: String, accent: Color, active: State<Boolean>, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .scale(if (focused) 1.03f else 1f)
            .then(if (focused) Modifier.border(3.dp, accent, shape) else Modifier)
            .clip(shape)
            .background(if (focused) Palette.White else Palette.Raised)
            .focusProperties { canFocus = active.value }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .fillMaxWidth().height(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Palette.Ink else Palette.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** "Earlier today" between the matches still to come and today's results, written up the shelf. */
@Composable
private fun EarlierDivider() {
    Column(Modifier.width(30.dp).height(POSTER_HEIGHT), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(2.dp).weight(1f).background(Palette.Raised))
        Box(Modifier.height(120.dp), contentAlignment = Alignment.Center) {
            Text(
                "EARLIER TODAY", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp, color = Palette.Muted, maxLines = 1,
                modifier = Modifier.wrapContentSize(unbounded = true).rotate(-90f),
            )
        }
        Box(Modifier.width(2.dp).weight(1f).background(Palette.Raised))
    }
}

/** Pressing a channel tries it first, then the other providers, then every other feed for the match. */
internal fun watchAttempts(chips: MatchChips, first: app.iptv.WatchOption): List<WatchAttempt> =
    (listOf(first) + chips.providers.map { it.second } + chips.usable).distinctBy { it.channel.url }.take(MAX_ATTEMPTS)
        .map { WatchAttempt(Names.provider(it.channel, it.score == 3), it.channel, event = it.score == 3) }

/** Poster colours rotate along the shelf; they aren't club colours. */
private val POSTER_COLORS = listOf(0xFF8C2F39, 0xFF2A3590, 0xFF1E6B52, 0xFF5A3C8F, 0xFF8A5410).map { Color(it) }
private val ResultColor = Color(0xFF2A2A34)
private val CardBottom = Color(0xFF17171F)
private val OpenGround = Color(0xFF20202C)
private val OpenEdge = Color(0x80FFFFFF)
private val DashEdge = Color(0x59FFFFFF)
private val POSTER_WIDTH = 112.dp
private val POSTER_HEIGHT = 300.dp
private val BAND_HEIGHT = 210.dp
private val OPEN_WIDTH = 375.dp
private val POSTER_GAP = 14.dp
private val POSTER_RADIUS = 18.dp
private const val OPEN_CHIPS = 3
