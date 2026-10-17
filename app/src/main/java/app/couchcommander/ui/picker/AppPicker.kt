package app.couchcommander.ui.picker

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.data.apps.TvApp
import app.couchcommander.ui.PanelRow
import app.couchcommander.ui.home.AppTile
import app.couchcommander.ui.home.TileWidth
import app.couchcommander.ui.theme.Palette

/**
 * Scrolls the grid as soon as focus nears an edge (the outer fifth of the grid), not when it reaches it, so the next
 * row is already on screen and drawn when focus gets there, and with a short glide rather than the default spring.
 * On a TV's slow CPU the default (scroll late, animate long) was most of the picker's dropped frames.
 */
@OptIn(ExperimentalFoundationApi::class)
private val EarlyScroll = object : BringIntoViewSpec {
    @Deprecated("Kept for the scroll animation")
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(180, easing = FastOutSlowInEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val top = containerSize * 0.2f
        val bottom = containerSize * 0.8f
        return when {
            offset < top -> offset - top
            offset + size > bottom -> offset + size - bottom
            else -> 0f
        }
    }
}

/**
 * Chooses the apps on the home row. Shown on first run (nothing is on home until the user picks) and from
 * Launcher settings. OK adds or removes an app; picked apps are numbered in the order they will appear.
 * Each press is saved straight away, so Back and Done both just close it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppPicker(
    apps: List<TvApp>,
    picked: List<String>,
    accent: Color,
    firstRun: Boolean,
    onToggle: (TvApp) -> Unit,
    onDone: () -> Unit,
) {
    BackHandler(onBack = onDone)
    val tv = remember(apps) { apps.filter { it.isTv }.sortedBy { it.label.lowercase() } }
    val other = remember(apps) { apps.filter { !it.isTv }.sortedBy { it.label.lowercase() } }
    val first = remember { FocusRequester() }
    val done = remember { FocusRequester() }

    LaunchedEffect(apps.isEmpty()) {
        withFrameNanos { }
        runCatching { if (apps.isEmpty()) done.requestFocus() else first.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Palette.Ground)) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 36.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (firstRun) "Pick your apps" else "Apps on home",
                        fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp,
                    )
                    Text(
                        if (firstRun) "Choose what goes on your home row. They appear in the order you pick them; you can change this later in Launcher settings."
                        else "OK adds or removes an app. New picks go to the end of the row.",
                        fontSize = 15.sp, color = Palette.Muted,
                    )
                }
                Box(Modifier.width(220.dp).padding(start = 24.dp)) {
                    PanelRow(
                        label = "Done",
                        accent = accent,
                        onClick = onDone,
                        value = if (picked.isEmpty()) "None yet" else "${picked.size} picked",
                        modifier = Modifier.focusRequester(done),
                    )
                }
            }

            if (apps.isEmpty()) {
                Text("Looking for apps…", fontSize = 16.sp, color = Palette.Muted, modifier = Modifier.padding(top = 32.dp))
            }

            CompositionLocalProvider(LocalBringIntoViewSpec provides EarlyScroll) {
                LazyVerticalGrid(
                    columns = GridCells.FixedSize(TileWidth),
                    // Room for the focused tile to grow and glow without being clipped by the grid.
                    contentPadding = PaddingValues(vertical = 20.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    section("TV apps", tv, picked, accent, onToggle, firstItem = first)
                    section("Other apps", other, picked, accent, onToggle, firstItem = if (tv.isEmpty()) first else null)
                }
            }
        }
    }
}

private fun LazyGridScope.section(
    title: String,
    apps: List<TvApp>,
    picked: List<String>,
    accent: Color,
    onToggle: (TvApp) -> Unit,
    firstItem: FocusRequester?,
) {
    if (apps.isEmpty()) return
    item(key = "header:$title", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            title.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = accent,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    items(apps, key = { it.key }) { app ->
        val place = picked.indexOf(app.key)
        AppTile(
            app = app,
            accent = accent,
            onClick = { onToggle(app) },
            onFocused = { },
            badge = if (place >= 0) place + 1 else null,
            dimmed = place < 0,
            modifier = if (app === apps.first() && firstItem != null) Modifier.focusRequester(firstItem) else Modifier,
        )
    }
}
