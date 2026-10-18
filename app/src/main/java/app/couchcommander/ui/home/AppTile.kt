package app.couchcommander.ui.home

import android.view.KeyEvent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.data.apps.TvApp
import app.couchcommander.ui.theme.Palette
import app.couchcommander.ui.theme.tileColorsFor

val TileWidth = 168.dp
val TileHeight = 94.dp
private val TileShape = RoundedCornerShape(14.dp)

/** Keys that act as "OK" on remotes, keyboards and game pads. */
internal val confirmKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)
private const val LONG_PRESS_MS = 600L

/**
 * A focusable tile that grows and glows in the painting's [accent] when focused. OK opens, holding OK calls
 * [onLongClick]. Long press is handled here rather than with combinedClickable so it works on every remote,
 * including ones that don't repeat the key while it is held.
 */
/** The last tile on an always-shown dock: opens the football page, which Right on the "Apps" handle opens otherwise. */
@Composable
fun FootballTile(accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(
        label = "Football", accent = accent, onClick = onClick, onLongClick = null, onFocused = {}, modifier = modifier,
        background = Modifier.background(Palette.Raised),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Spacer(Modifier.weight(1f))
            Text("Football", color = Palette.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("Today's matches", color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** The first tile on an always-shown dock: opens Movies and TV, which Left on the "Apps" handle opens otherwise. */
@Composable
fun MoviesTile(accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(
        label = "Movies & TV", accent = accent, onClick = onClick, onLongClick = null, onFocused = {}, modifier = modifier,
        background = Modifier.background(Palette.Raised),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Spacer(Modifier.weight(1f))
            Text("Movies & TV", color = Palette.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("Trending this week", color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Tile(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onFocused: () -> Unit,
    modifier: Modifier,
    background: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    // Short and decisive: focus should land as fast as the remote is pressed.
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, tween(120, easing = FastOutSlowInEasing), label = "tile")
    Box(
        modifier
            .size(TileWidth, TileHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // Only the focused tile casts a shadow (the accent glow); a shadow per tile costs every frame.
            .then(if (focused) Modifier.shadow(22.dp, TileShape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(TileShape)
            .then(background)
            .then(if (focused) Modifier.border(3.dp, Palette.White, TileShape) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (k.keyCode !in confirmKeys) return@onKeyEvent false
                when (k.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (k.repeatCount == 0) longFired = false
                        else if (!longFired && onLongClick != null && (k.isLongPress || k.eventTime - k.downTime >= LONG_PRESS_MS)) {
                            longFired = true
                            onLongClick()
                        }
                        true
                    }
                    KeyEvent.ACTION_UP -> {
                        if (!longFired) {
                            if (onLongClick != null && k.eventTime - k.downTime >= LONG_PRESS_MS) onLongClick() else onClick()
                        }
                        longFired = false
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(onTap = { onClick() }, onLongPress = onLongClick?.let { { _ -> it() } })
            }
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick { onClick(); true }
                if (onLongClick != null) onLongClick { onLongClick(); true }
            },
    ) { content() }
}

/**
 * An app's banner, or a coloured tile with its icon and name. In the app picker, [badge] is the app's place on the
 * home row and unpicked apps are [dimmed].
 */
@Composable
fun AppTile(
    app: TvApp,
    accent: Color,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    badge: Int? = null,
    dimmed: Boolean = false,
) {
    val colors = tileColorsFor(app.key)
    Tile(
        label = if (badge != null) "${app.label}, on home" else app.label,
        accent = accent,
        onClick = onClick,
        onLongClick = onLongClick,
        onFocused = onFocused,
        modifier = modifier,
        background = Modifier.background(if (app.banner != null) Palette.Ground else colors.bg),
    ) {
        val banner = app.banner
        if (banner != null) {
            Image(banner, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.weight(1f))
                Text(app.label, color = colors.fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (dimmed) Box(Modifier.fillMaxSize().background(Color(0x73000000)))
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("$badge", color = Palette.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
