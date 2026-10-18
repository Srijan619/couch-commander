package app.couchcommander.ui.media

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.data.media.MediaRepository
import app.couchcommander.domain.MediaText
import app.couchcommander.ui.theme.Palette
import app.media.MovieDetails
import app.media.MovieVideo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The focused title's still behind the whole page, fading into the ground at the left, the top and the bottom so
 * the words, the clock and the shelves stay readable over it. Once focus has rested on the title for [TRAILER_DELAY_MS], its trailer
 * plays over the still with sound (when [trailers] allows and it has one); it stops as focus moves on or a
 * title's page opens. It plays once, not on a loop. [fallback] (home's blurred painting) shows until the first still has
 * loaded. With [immersion], the fades go while the page around the hero is hidden.
 *
 * The still and its fades are drawn into one cached layer: moving along the shelves then costs the TV's GPU one
 * full-screen texture, not an image and two gradients redrawn every frame (it was GPU-bound at about 30 fps).
 */
@Composable
fun HeroBackdrop(title: MovieDetails?, media: MediaRepository, trailers: Boolean, fallback: ImageBitmap? = null, immersion: Immersion? = null, modifier: Modifier = Modifier) {
    val still = rememberRemoteImage(title?.backdropWideUrl ?: title?.backdropUrl, 960.dp)
    val videos by produceState<List<MovieVideo>?>(null, title?.id, trailers) {
        value = null
        if (title == null || !trailers) return@produceState
        delay(TRAILER_DELAY_MS)
        value = media.details(title)?.videos
    }
    val autoplay = rememberAutoplayAllowed()
    val keys = videos?.map { it.key }.orEmpty()
    val playing = trailers && autoplay && keys.isNotEmpty()
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            if (still == null) fallback?.let { Image(it, null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize()) }
            // Keyed by the image, so a new title's still fades in over the last one.
            Crossfade(still, animationSpec = tween(400), label = "hero") { image: ImageBitmap? ->
                image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            if (!playing) Fades()
        }
        if (playing) {
            // Most trailers are cinema-wide with black bars inside the video: zoomed past them to fill the page.
            TrailerPlayer(keys, playing = true, muted = false, loop = false, zoom = 1.34f, onStateChange = { immersion?.trailer = it }, modifier = Modifier.fillMaxSize())
            DisposableEffect(keys) { onDispose { immersion?.trailer = null } }
            // Over the video, in their own cached layer; gone with the page while it's hidden.
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = immersion?.shown?.value ?: 1f
                },
            ) { Fades() }
        }
    }
}

/** Darkens the left, the top and the bottom so the words, the clock and the shelves stay readable. */
@Composable
private fun Fades() {
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Palette.Ground.copy(alpha = 0.92f), 0.4f to Palette.Ground.copy(alpha = 0.55f), 0.75f to Color.Transparent)))
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Palette.Ground.copy(alpha = 0.6f),
                0.2f to Color.Transparent,
                0.45f to Color.Transparent,
                0.85f to Palette.Ground.copy(alpha = 0.95f),
            ),
        ),
    )
}

/** The focused title's name, facts and the start of its plot, above the shelves; always [HERO_TEXT_HEIGHT] tall. */
@Composable
fun HeroText(title: MovieDetails?, accent: Color, modifier: Modifier = Modifier) {
    Column(modifier.height(HERO_TEXT_HEIGHT), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
        if (title == null) return@Column
        Text(title.title, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, lineHeight = 40.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(MediaText.facts(title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (title.rating > 0) {
                Text("★", fontSize = 12.sp, color = accent)
                Text("%.1f".format(title.rating), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(x = (-6).dp))
            }
        }
        title.overview?.let {
            Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = Palette.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 480.dp))
        }
    }
}

/** How long focus rests on a title before its trailer starts: browsing past doesn't load one for each. */
private const val TRAILER_DELAY_MS = 1_000L
/** With the header, about the top half of the page is the hero's, as on Netflix: the shelves start below it. */
val HERO_TEXT_HEIGHT = 200.dp

/**
 * Hides the page around the hero while its trailer plays: once it has been showing for [IMMERSE_AFTER_MS] with no key
 * pressed, everything but the video fades away. A key press brings the page back and does nothing else, so OK
 * doesn't open a title nobody can see and Back doesn't leave; so does the trailer stalling, failing or ending.
 */
@Stable
class Immersion {
    /** The hero's trailer, null while none is on screen. */
    internal var trailer by mutableStateOf<TrailerState?>(null)
    internal var hidden by mutableStateOf(false)
    private var presses by mutableIntStateOf(0)
    /** The page's opacity, 0 while hidden. Read it only while drawing (graphicsLayer), or each frame of the fade recomposes the page. */
    val shown = Animatable(1f)
    // The key that brought the page back: its repeats and release are kept from the page too.
    private var swallowing: Int? = null

    /** For the page's onPreviewKeyEvent, before anything else: true when the key only brings the page back. */
    fun onKey(event: KeyEvent): Boolean {
        val k = event.nativeKeyEvent
        if (k.keyCode == swallowing) {
            if (k.action == AndroidKeyEvent.ACTION_UP) swallowing = null
            if (k.action == AndroidKeyEvent.ACTION_UP || k.repeatCount > 0) return true
        }
        if (k.action != AndroidKeyEvent.ACTION_DOWN) return false
        presses++
        if (!hidden) return false
        hidden = false
        swallowing = k.keyCode
        return true
    }

    internal suspend fun run() {
        snapshotFlow { (trailer == TrailerState.Playing) to presses }.collectLatest { (playing, _) ->
            if (!playing) {
                hidden = false
                return@collectLatest
            }
            delay(IMMERSE_AFTER_MS)
            hidden = true
        }
    }

    internal suspend fun fade() {
        // Out slowly, so it doesn't look like a glitch; back at once, as the key press expects.
        snapshotFlow { hidden }.collectLatest { shown.animateTo(if (it) 0f else 1f, tween(if (it) 1_200 else 250)) }
    }
}

@Composable
fun rememberImmersion(): Immersion {
    val immersion = remember { Immersion() }
    LaunchedEffect(immersion) { immersion.run() }
    LaunchedEffect(immersion) { immersion.fade() }
    return immersion
}

/** How long a trailer plays with no key pressed before the page around it hides. */
private const val IMMERSE_AFTER_MS = 10_000L
