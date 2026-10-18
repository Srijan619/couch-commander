package app.couchcommander.ui.media

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Grey stand-ins the size of what's loading, so the page keeps its shape and nothing jumps when the real thing
// arrives. They breathe slowly; the pulse is read only while drawing, so it never recomposes anything.

/** One slow breath, shared by every placeholder on a screen so they pulse together. */
@Composable
fun rememberPulse(): State<Float> =
    rememberInfiniteTransition(label = "skeleton").animateFloat(PULSE_LOW, PULSE_HIGH, PulseSpec, label = "pulse")

/** A rounded grey block in [pulse]'s current shade. */
fun Modifier.skeleton(pulse: State<Float>, radius: Dp = 9.dp): Modifier = drawBehind {
    val r = radius.toPx()
    drawRoundRect(Color.White.copy(alpha = pulse.value), cornerRadius = CornerRadius(r, r))
}

/** Where a poster will be. */
@Composable
fun SkeletonPoster(pulse: State<Float>) {
    Box(Modifier.size(POSTER_WIDTH, POSTER_HEIGHT).skeleton(pulse))
}

/** Where an action pill will be: as tall as one, about as wide as "Play". */
@Composable
fun SkeletonPill(pulse: State<Float>, width: Dp = 92.dp) {
    Box(Modifier.size(width, PILL_HEIGHT).skeleton(pulse, 10.dp))
}

/** Where a tab will be, on the line tabs take. */
@Composable
fun SkeletonTab(pulse: State<Float>, width: Dp = 84.dp) {
    Box(Modifier.padding(horizontal = 4.dp, vertical = 6.dp).size(width, 22.dp).skeleton(pulse, 8.dp))
}

/** Where an episode's row will be: its height, with a number and a title in grey. */
@Composable
fun SkeletonEpisode(pulse: State<Float>) {
    Row(
        Modifier.fillMaxWidth().height(EPISODE_HEIGHT).skeleton(pulse, 12.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.width(28.dp).height(10.dp).skeleton(pulse, 4.dp))
        Box(Modifier.width(220.dp).height(12.dp).skeleton(pulse, 4.dp))
    }
}

/** A row of posters to come. */
@Composable
fun SkeletonPosterRow(pulse: State<Float>, count: Int = 8, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) { repeat(count) { SkeletonPoster(pulse) } }
}

/** An episode row's height (EpisodeRow), so the list keeps its length. */
val EPISODE_HEIGHT = 50.dp
/** An ActionPill's height: 15 sp text and 8 dp padding above and below. */
private val PILL_HEIGHT = 36.dp
private const val PULSE_LOW = 0.05f
private const val PULSE_HIGH = 0.11f
private val PulseSpec: InfiniteRepeatableSpec<Float> = infiniteRepeatable(tween(900), RepeatMode.Reverse)
