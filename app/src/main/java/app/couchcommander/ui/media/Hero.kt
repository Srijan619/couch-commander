package app.couchcommander.ui.media

import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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

/**
 * The focused title's still behind the whole page, fading into the ground at the left, the top and the bottom so
 * the words, the clock and the shelves stay readable over it. Once focus has rested on the title for [TRAILER_DELAY_MS], its trailer
 * plays over the still with sound (when [trailers] allows and it has one); it stops as focus moves on or a
 * title's page opens.
 */
@Composable
fun HeroBackdrop(title: MovieDetails?, media: MediaRepository, trailers: Boolean, modifier: Modifier = Modifier) {
    val still = rememberRemoteImage(title?.backdropWideUrl ?: title?.backdropUrl, 960.dp)
    val videos by produceState<List<MovieVideo>?>(null, title?.id, trailers) {
        value = null
        if (title == null || !trailers) return@produceState
        delay(TRAILER_DELAY_MS)
        value = media.details(title)?.videos
    }
    val autoplay = rememberAutoplayAllowed()
    Box(modifier.fillMaxSize()) {
        // Keyed by the image, so a new title's still fades in over the last one.
        Crossfade(still, animationSpec = tween(400), label = "hero") { image: ImageBitmap? ->
            image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        val keys = videos?.map { it.key }.orEmpty()
        if (trailers && autoplay && keys.isNotEmpty()) {
            // Most trailers are cinema-wide with black bars inside the video: zoomed past them to fill the page.
            TrailerPlayer(keys, playing = true, muted = false, zoom = 1.34f, modifier = Modifier.fillMaxSize())
        }
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
private const val TRAILER_DELAY_MS = 2_500L
/** With the header, about the top half of the page is the hero's, as on Netflix: the shelves start below it. */
val HERO_TEXT_HEIGHT = 200.dp
