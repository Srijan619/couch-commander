package app.couchcommander.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.LauncherApp
import app.couchcommander.data.media.Watching
import app.couchcommander.ui.theme.Palette
import app.media.MovieDetails

val POSTER_WIDTH = 100.dp
val POSTER_HEIGHT = 150.dp
/** Continue's cards: a 16:9 still, about as tall as a poster. */
val CONTINUE_WIDTH = 256.dp
val CONTINUE_HEIGHT = 144.dp

/**
 * An image from TMDB's CDN, decoded about [width] wide; null until the first one loads, or without a [url]. A new
 * [url] keeps showing the last image until its own has loaded.
 */
@Composable
fun rememberRemoteImage(url: String?, width: Dp): ImageBitmap? {
    val images = (LocalContext.current.applicationContext as LauncherApp).container.images
    val px = with(LocalDensity.current) { width.roundToPx() }
    val image by produceState(url?.let { images.cached(it, px) }, url, px) {
        value = url?.let { images.cached(it, px) ?: images.load(it, px) }
    }
    return image
}

private val ProgressTrack = Color(0x4DFFFFFF)

/** Muted grounds for posters still loading or missing, picked by title so a shelf isn't all one colour. */
private val PosterGrounds = listOf(0xFF3A3F5C, 0xFF5B4A2E, 0xFF2E4A47, 0xFF6B3A2A, 0xFF44506B, 0xFF5C2E4A).map { Color(it) }

/**
 * A title's poster; its name and year on a plain ground until the poster loads (or when TMDB has none). Grows and
 * takes a white edge while focused. No glow, unlike the football page's tiles: the row clips it into a hard edge.
 */
@Composable
fun PosterTile(
    title: MovieDetails,
    active: Boolean,
    focus: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val shape = RoundedCornerShape(9.dp)
    val poster = rememberRemoteImage(title.posterUrl, POSTER_WIDTH)
    Box(
        Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .scale(if (focused) 1.07f else 1f)
            .clip(shape)
            .background(PosterGrounds[Math.floorMod(title.title.hashCode(), PosterGrounds.size)])
            .then(if (focused) Modifier.border(2.dp, Palette.White, shape) else Modifier)
            .focusProperties { canFocus = active }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${title.title}, ${title.year}" }
            .size(POSTER_WIDTH, POSTER_HEIGHT),
    ) {
        if (poster != null) {
            Image(poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.align(Alignment.BottomStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title.title, color = Palette.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(title.year.toString(), color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * A title on Continue: its still, wide, with its name, what's left ("S1 E3 · 42 min left") and a bar showing how
 * far in it was left. Grows and takes a white edge while focused, like a poster.
 */
@Composable
fun ContinueTile(
    watching: Watching,
    active: Boolean,
    focus: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val title = watching.title
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    val shape = RoundedCornerShape(9.dp)
    val still = rememberRemoteImage(title.backdropUrl, CONTINUE_WIDTH)
    val line = listOfNotNull(
        if (watching.season != null && watching.episode != null) "S${watching.season} E${watching.episode}" else null,
        timeLeft(watching),
    ).joinToString(" · ")
    Box(
        Modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .scale(if (focused) 1.05f else 1f)
            .clip(shape)
            .background(PosterGrounds[Math.floorMod(title.title.hashCode(), PosterGrounds.size)])
            .then(if (focused) Modifier.border(2.dp, Palette.White, shape) else Modifier)
            .focusProperties { canFocus = active }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${title.title}, $line" }
            .size(CONTINUE_WIDTH, CONTINUE_HEIGHT),
    ) {
        still?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        // Keeps the name readable over a bright still.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xD9000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title.title, color = Palette.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotEmpty()) Text(line, color = Palette.Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            // Inside the card, clear of its rounded corners; a few minutes in still shows as a dot.
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(ProgressTrack)) {
                Box(Modifier.fillMaxWidth(watching.progress.coerceAtLeast(0.02f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Palette.White))
            }
        }
    }
}

/** "1 h 12 min left", "42 min left"; null before the length is known. */
private fun timeLeft(w: Watching): String? {
    if (w.durationMs <= 0) return null
    val minutes = ((w.durationMs - w.positionMs).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> "Almost done"
        minutes < 60 -> "$minutes min left"
        else -> "${minutes / 60} h ${minutes % 60} min left"
    }
}
