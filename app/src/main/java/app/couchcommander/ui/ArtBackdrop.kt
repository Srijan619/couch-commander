package app.couchcommander.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.couchcommander.data.art.ArtFrame
import app.couchcommander.domain.Accent
import app.couchcommander.ui.theme.Palette
import kotlin.math.abs

private const val DRIFT_MS = 24_000

/**
 * The painting behind everything, shared by home and the screensaver. Wide paintings fill the screen and drift
 * slowly (a Ken Burns pan) while [drift] is true; square ones hang in a mat on a wall coloured from the painting.
 * A new painting fades in. Saturation and the colour boost are already baked into the image (ArtRepository).
 *
 * The drift redraws the whole screen every frame, so home turns it off while the remote is in use: on a TV's GPU
 * that frame time is better spent on the tiles.
 */
@Composable
fun ArtBackdrop(
    frame: ArtFrame?,
    drift: Boolean,
    modifier: Modifier = Modifier,
    framedHeight: Float = 0.74f,
    framedOffset: Dp = 0.dp,
) {
    // Survives painting changes, so the pan carries on from where it paused.
    val position = remember { Animatable(0f) }
    LaunchedEffect(drift) {
        if (!drift) return@LaunchedEffect
        var target = if (position.value >= 1f) 0f else 1f
        while (true) {
            val remaining = (abs(target - position.value) * DRIFT_MS).toInt().coerceAtLeast(1)
            position.animateTo(target, tween(remaining, easing = LinearEasing))
            target = 1f - target
        }
    }
    Crossfade(targetState = frame, animationSpec = tween(1200), modifier = modifier.fillMaxSize(), label = "art") { f ->
        when {
            f == null -> Box(Modifier.fillMaxSize().background(Palette.Ground))
            f.framed -> FramedArt(f, framedHeight, framedOffset)
            else -> Image(
                bitmap = f.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Read here, in the layer block, so the pan only re-transforms the layer: no recomposition.
                        val t = position.value
                        val s = 1.04f + 0.12f * t
                        scaleX = s
                        scaleY = s
                        translationX = -0.025f * size.width * t
                        translationY = -0.015f * size.height * t
                    },
            )
        }
    }
}

@Composable
private fun FramedArt(frame: ArtFrame, heightFraction: Float, offset: Dp) {
    val wall = remember(frame) { Color(Accent.wall(frame.accent)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(wall)
            .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent))),
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .offset(y = offset)
                .fillMaxHeight(heightFraction)
                .aspectRatio(frame.image.width.toFloat() / frame.image.height)
                .shadow(28.dp)
                .background(Palette.Mat)
                .padding(10.dp),
        ) {
            Image(frame.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
