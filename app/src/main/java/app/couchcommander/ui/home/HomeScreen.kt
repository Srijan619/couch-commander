package app.couchcommander.ui.home

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.focusable
import androidx.compose.material3.Icon
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.data.apps.TvApp
import app.couchcommander.data.art.ArtFrame
import app.couchcommander.domain.Accent
import app.couchcommander.domain.FootballLines
import app.couchcommander.ui.ArtBackdrop
import app.couchcommander.ui.FootballBlock
import app.couchcommander.ui.Icons
import app.couchcommander.ui.rememberClock
import app.couchcommander.ui.theme.Palette

/**
 * Asks home to focus the tile with [key], sliding the dock up; null rests home with the dock hidden.
 * [nonce] makes a repeat request count.
 */
data class FocusRequest(val key: String?, val nonce: Int)

private const val HANDLE_KEY = "couch:handle"
private val DockHeight = 230.dp
/** How far the painting's caption rises to clear the open dock (and a focused tile's grown edge). */
private val CaptionLift = 166.dp
/** The caption's widest: it stays in the right-hand part of the screen, clear of the centred "Apps" handle. */
private val CaptionMaxWidth = 380.dp

/**
 * Home, direction L from the designs: the painting fills the screen, the clock sits top left with the followed
 * teams' next match (or live score) opposite, and the apps wait in a dock below the bottom edge. Down or OK on the
 * "Apps" handle slides the dock up; Up or Back slides it away, and it hides by itself after [dockHideSeconds]
 * without a key press (0: never). Up while it is hidden opens settings, Right the football page, Left Movies and TV.
 */
@Composable
fun HomeScreen(
    apps: List<TvApp>,
    frame: ArtFrame?,
    football: FootballLines?,
    drift: Boolean,
    interactive: Boolean,
    focusRequest: FocusRequest,
    onLaunch: (TvApp) -> Unit,
    onAppMenu: (TvApp) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFootball: () -> Unit,
    onOpenMovies: () -> Unit,
    dockHideSeconds: Int,
    /**
     * The dock stays up: Up opens settings, a Movies & TV tile starts the row and a Football tile ends it, since
     * there's no handle to press Left or Right on.
     */
    alwaysShowApps: Boolean = false,
) {
    val accent = Color(frame?.accent ?: Accent.FALLBACK)
    val clock = rememberClock()
    val listState = rememberLazyListState()
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    var dockOpen by remember { mutableStateOf(false) }
    var lastAppKey by remember { mutableStateOf<String?>(null) }
    // Bumped on every key press while home has focus; restarts the auto-hide countdown.
    var keyTick by remember { mutableIntStateOf(0) }
    // Asks for focus on a tile (dock up) or the handle (dock down); done in an effect so the tiles can take focus.
    var target by remember { mutableStateOf(FocusRequest(null, 0)) }
    fun showDock(key: String? = null) {
        if (apps.isEmpty()) return onOpenSettings()
        dockOpen = true
        target = FocusRequest(key?.takeIf { k -> apps.any { it.key == k } } ?: lastAppKey?.takeIf { k -> apps.any { it.key == k } } ?: apps.first().key, target.nonce + 1)
    }
    fun hideDock() {
        // Always shown: "rest" means the dock with the last app focused, not the handle.
        if (alwaysShowApps && apps.isNotEmpty()) return showDock()
        dockOpen = false
        target = FocusRequest(HANDLE_KEY, target.nonce + 1)
    }

    // Switching the setting while home is up moves to its resting state at once.
    LaunchedEffect(alwaysShowApps) { if (alwaysShowApps) showDock() }
    LaunchedEffect(focusRequest) {
        val key = focusRequest.key
        if (key != null && apps.any { it.key == key }) showDock(key) else hideDock()
    }
    LaunchedEffect(target) {
        val key = target.key ?: return@LaunchedEffect
        val index = apps.indexOfFirst { it.key == key }
        // The tile has to be composed before it can take focus; off-screen tiles aren't.
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        withFrameNanos { }
        runCatching { requester(key).requestFocus() }
    }
    LaunchedEffect(dockOpen, keyTick, interactive, dockHideSeconds) {
        if (!dockOpen || !interactive || dockHideSeconds <= 0 || alwaysShowApps) return@LaunchedEffect
        delay(dockHideSeconds * 1000L)
        hideDock()
    }
    val dock by animateFloatAsState(if (dockOpen) 1f else 0f, tween(350, easing = FastOutSlowInEasing), label = "dock")

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .onPreviewKeyEvent {
                keyTick++
                false
            },
    ) {
        // A soft shadow keeps white type readable over busy paintings.
        val overArt = LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 14f))
        // Everything behind the tiles, drawn into one cached layer. While the remote is in use none of it changes, so
        // each frame only copies that layer and draws the tiles; without this the TV's GPU repaints the painting,
        // both gradients and the shadowed text on every frame of a focus animation.
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        ArtBackdrop(frame, drift, framedHeight = 0.6f, framedOffset = (-40).dp)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Palette.Ground.copy(alpha = 0.75f),
                    0.34f to Color.Transparent,
                    0.42f to Color.Transparent,
                    1f to Palette.Ground.copy(alpha = 0.5f),
                ),
            ),
        )

        CompositionLocalProvider(LocalTextStyle provides overArt) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 36.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text(clock.time, fontSize = 58.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.6).sp, lineHeight = 60.sp)
                Text(clock.date, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Palette.Muted)
                // What Left does from the handle, mirroring "More football" opposite; gone while the dock is up.
                Row(Modifier.padding(top = 10.dp).graphicsLayer { alpha = 0.85f * (1f - dock) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.ChevronLeft, contentDescription = null, tint = Palette.White, modifier = Modifier.size(15.dp))
                    Text("Movies & TV", style = overArt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            football?.let {
                Column(horizontalAlignment = Alignment.End) {
                    FootballBlock(it, accent)
                    // What Right does from the handle; gone while the dock is up, as Right then moves along the apps.
                    Row(Modifier.padding(top = 8.dp).graphicsLayer { alpha = 0.85f * (1f - dock) }, verticalAlignment = Alignment.CenterVertically) {
                        Text("More football", style = overArt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.ChevronRight, contentDescription = null, tint = Palette.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
        }
        }

        // The painting dims a little while the dock is up.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.25f * dock }.background(Palette.Ground))

        // The painting's caption, like a gallery label, bottom right; it rises above the dock when that slides up.
        frame?.artwork?.let { art ->
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(start = 48.dp, end = 48.dp, bottom = 28.dp)
                    .widthIn(max = CaptionMaxWidth)
                    .graphicsLayer { translationY = -CaptionLift.toPx() * dock },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    art.title, style = overArt, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Text("${art.artist} · ${art.year}", style = overArt, fontSize = 14.sp, color = Palette.Muted, maxLines = 1)
            }
        }

        // "Apps ⌄" at the bottom centre while the dock is hidden; it holds focus then. Down or OK shows the dock,
        // Up opens settings, Right the football page, Left Movies and TV. With no apps on home it leads to settings instead.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
                .graphicsLayer { alpha = 0.9f * (1f - dock) }
                .focusRequester(requester(HANDLE_KEY))
                .focusProperties { canFocus = interactive && !dockOpen }
                .onKeyEvent { e ->
                    val k = e.nativeKeyEvent
                    val action = when (k.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN, in confirmKeys -> { -> showDock() }
                        KeyEvent.KEYCODE_DPAD_UP -> onOpenSettings
                        KeyEvent.KEYCODE_DPAD_RIGHT -> onOpenFootball
                        KeyEvent.KEYCODE_DPAD_LEFT -> onOpenMovies
                        else -> return@onKeyEvent false
                    }
                    if (k.action == KeyEvent.ACTION_UP) action()
                    true
                }
                .focusable()
                .semantics { contentDescription = if (apps.isEmpty()) "Launcher settings" else "Show apps" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (apps.isEmpty()) "No apps on home yet · Settings" else "Apps",
                style = overArt, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp,
            )
            Icon(Icons.ChevronDown, contentDescription = null, tint = Palette.White, modifier = Modifier.size(22.dp))
        }

        // What Up does while the dock is hidden; the dock's own hint says what it does while the dock is up.
        if (apps.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 28.dp).graphicsLayer { alpha = 0.8f * (1f - dock) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.ArrowUp, contentDescription = null, tint = Palette.White, modifier = Modifier.size(15.dp))
                Text("Settings", style = overArt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // The dock: hidden just below the bottom edge, sliding up over its own fade.
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(DockHeight)
                .graphicsLayer { translationY = size.height * (1f - dock) }
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.35f to Palette.Ground.copy(alpha = 0.78f),
                        1f to Palette.Ground.copy(alpha = 0.94f),
                    ),
                ),
        ) {
            Column(Modifier.align(Alignment.BottomStart).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(
                    // Up or Back slides the dock away: nothing sits above the row to move to. Always shown, Up opens
                    // settings (as Up on the handle does) and Back is left to home, which ignores it.
                    modifier = Modifier.onPreviewKeyEvent { e ->
                        val code = e.nativeKeyEvent.keyCode
                        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                        if (alwaysShowApps && code == KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                        if (e.nativeKeyEvent.action == KeyEvent.ACTION_UP) if (alwaysShowApps) onOpenSettings() else hideDock()
                        true
                    },
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (alwaysShowApps) item(key = "couch:movies") {
                        MoviesTile(accent, onOpenMovies, Modifier.focusProperties { canFocus = interactive && dockOpen })
                    }
                    itemsIndexed(apps, key = { _, app -> app.key }) { _, app ->
                        AppTile(
                            app = app,
                            accent = accent,
                            onClick = { onLaunch(app) },
                            onLongClick = { onAppMenu(app) },
                            onFocused = { lastAppKey = app.key },
                            modifier = Modifier.focusRequester(requester(app.key)).focusProperties { canFocus = interactive && dockOpen },
                        )
                    }
                    if (alwaysShowApps && football != null) item(key = "couch:football") {
                        FootballTile(accent, onOpenFootball, Modifier.focusProperties { canFocus = interactive && dockOpen })
                    }
                }
                Row(
                    Modifier.padding(start = 48.dp).graphicsLayer { alpha = 0.8f },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.ArrowUp, contentDescription = null, tint = Palette.White, modifier = Modifier.size(15.dp))
                    Text(if (alwaysShowApps) "Settings" else "Hide apps", style = overArt, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
