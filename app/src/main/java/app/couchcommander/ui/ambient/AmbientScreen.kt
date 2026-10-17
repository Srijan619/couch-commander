package app.couchcommander.ui.ambient

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.data.art.ArtFrame
import app.couchcommander.domain.Accent
import app.couchcommander.domain.FootballLines
import app.couchcommander.ui.ArtBackdrop
import app.couchcommander.ui.FootballBlock
import app.couchcommander.ui.PanelRow
import app.couchcommander.ui.rememberClock
import app.couchcommander.ui.theme.Palette
import kotlinx.coroutines.delay

private const val INFO_TIMEOUT_MS = 8_000L

/**
 * The screensaver: the painting, a quiet clock and its name, and the followed teams' match top-right as on home. Any key shows details with "Next painting" and
 * "Go home"; they hide again after a few seconds. Back leaves the screensaver (DreamService handles it).
 */
@Composable
fun AmbientScreen(frame: ArtFrame?, showClock: Boolean, football: FootballLines?, onNext: () -> Unit, onHome: () -> Unit) {
    val clock = rememberClock()
    val accent = Color(frame?.accent ?: Accent.FALLBACK)
    var info by remember { mutableStateOf(false) }
    var keyCount by remember { mutableIntStateOf(0) }
    val root = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { root.requestFocus() } }
    // Focus the first button only when the details open; later keys move between the buttons.
    LaunchedEffect(info) {
        if (info) runCatching { firstButton.requestFocus() }
    }
    // Each key press restarts the countdown to hiding the details.
    LaunchedEffect(info, keyCount) {
        if (info) {
            delay(INFO_TIMEOUT_MS)
            info = false
            runCatching { root.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .onPreviewKeyEvent { e ->
                keyCount++
                if (info) return@onPreviewKeyEvent false
                // The first key only wakes the details; it must not also press a button.
                if (e.type == KeyEventType.KeyDown) info = true
                true
            }
            .focusRequester(root)
            .focusable(),
    ) {
        // A framed painting hangs smaller and lower under the football block, so the two don't overlap.
        if (football != null) ArtBackdrop(frame, drift = true, framedHeight = 0.6f, framedOffset = 50.dp)
        else ArtBackdrop(frame, drift = true)

        AnimatedVisibility(visible = !info && showClock && frame != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Ground.copy(alpha = 0.72f)))),
                )
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 48.dp, vertical = 40.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column {
                        Text(clock.time, fontSize = 72.sp, fontWeight = FontWeight.Light, letterSpacing = (-2.5).sp, lineHeight = 72.sp)
                        Text(clock.date, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Palette.Muted)
                    }
                    frame?.let { f ->
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 380.dp)) {
                            Text(
                                f.artwork.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic,
                                textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Text("${f.artwork.artist} · ${f.artwork.year}", fontSize = 13.sp, color = Palette.Muted)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = !info && football != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(200.dp)
                        .background(Brush.verticalGradient(listOf(Palette.Ground.copy(alpha = 0.6f), Color.Transparent))),
                )
                football?.let { FootballBlock(it, accent, Modifier.align(Alignment.TopEnd).padding(horizontal = 48.dp, vertical = 40.dp)) }
            }
        }

        AnimatedVisibility(visible = info, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0f to Palette.Ground.copy(alpha = 0.88f),
                            0.45f to Palette.Ground.copy(alpha = 0.6f),
                            0.75f to Palette.Ground.copy(alpha = 0.1f),
                        ),
                    ),
                )
                Text(clock.time, fontSize = 30.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.TopEnd).padding(48.dp))
                Column(
                    Modifier.padding(start = 54.dp).width(420.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    Text("NOW SHOWING", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = accent)
                    Text(frame?.artwork?.title.orEmpty(), fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp, letterSpacing = (-1).sp)
                    frame?.let { Text("${it.artwork.artist} · ${it.artwork.year}", fontSize = 17.sp, fontWeight = FontWeight.Medium) }
                    Text("Public domain, via Wikimedia Commons.", fontSize = 14.sp, color = Palette.Muted, modifier = Modifier.padding(bottom = 12.dp))
                    PanelRow("Next painting", accent, onNext, Modifier.focusRequester(firstButton))
                    PanelRow("Go home", accent, onHome)
                }
            }
        }
    }
}
