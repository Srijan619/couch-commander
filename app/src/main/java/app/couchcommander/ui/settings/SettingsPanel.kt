package app.couchcommander.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.data.art.ArtFrame
import app.couchcommander.data.inputs.TvInput
import app.couchcommander.domain.LauncherConfig
import app.couchcommander.ui.Icons
import app.couchcommander.ui.PanelGroup
import app.couchcommander.ui.PanelRow
import app.couchcommander.ui.theme.Palette

/**
 * The settings side panel, design A ("grouped panel"): shortcuts to the plugged-in inputs and the TV's own settings
 * as cards, the painting now showing with Next, then the options in two rounded groups. Back closes it.
 */
@Composable
fun SettingsPanel(
    config: LauncherConfig,
    inputs: List<TvInput>,
    frame: ArtFrame?,
    accent: Color,
    appCount: Int,
    homeButton: Boolean,
    onOpenInput: (TvInput) -> Unit,
    onAndroidSettings: () -> Unit,
    onNextPainting: () -> Unit,
    onUpdate: ((LauncherConfig) -> LauncherConfig) -> Unit,
    onChooseApps: () -> Unit,
    onFootball: () -> Unit,
    onLeagues: () -> Unit,
    /** "Not set", or the playlist's kind and channel count. */
    playlist: String,
    onPlaylist: () -> Unit,
    screensaverOurs: Boolean,
    onScreensaver: () -> Unit,
    onHomeButton: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0x59000000))) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp)
                .background(Palette.Panel)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 26.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, modifier = Modifier.weight(1f))
                Text("Back to close", fontSize = 12.sp, color = Palette.Muted)
            }

            // Inputs with something plugged in, then the TV's own settings, two to a row.
            val shortcuts = inputs.map { input ->
                Shortcut(Icons.Hdmi, input.label, if (input.standby) "Standby" else "Connected") { onOpenInput(input) }
            } + Shortcut(Icons.Tv, "Android settings", "Network, picture, sound", onAndroidSettings)
            shortcuts.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEachIndexed { i, s ->
                        ShortcutCard(s, accent, Modifier.weight(1f).then(if (row == 0 && i == 0) Modifier.focusRequester(first) else Modifier))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            NowShowing(frame, accent, config.artMinutes, onNextPainting)

            PanelGroup(
                "Home",
                // "Hide after" only shows while hiding is on.
                rows = listOf<@Composable () -> Unit>(
                    { PanelRow("Apps on home", accent, onChooseApps, value = if (appCount == 1) "1 app" else "$appCount apps", grouped = true) },
                    {
                        PanelRow("Change painting", accent, grouped = true, value = everyLabel(config.artMinutes), onClick = {
                            onUpdate { c ->
                                val choices = LauncherConfig.ART_MINUTE_CHOICES
                                c.copy(artMinutes = choices[(choices.indexOf(c.artMinutes) + 1) % choices.size])
                            }
                        })
                    },
                    { PanelRow("Colour boost", accent, { onUpdate { it.copy(colourBoost = !it.colourBoost) } }, toggle = config.colourBoost, grouped = true) },
                    {
                        PanelRow(
                            "Hide apps automatically", accent, { onUpdate { it.copy(alwaysShowApps = !it.alwaysShowApps) } },
                            toggle = !config.alwaysShowApps, grouped = true,
                        )
                    },
                ) + if (config.alwaysShowApps) emptyList() else listOf<@Composable () -> Unit>(
                    {
                        PanelRow("Hide after", accent, grouped = true, value = hideLabel(config.dockHideSeconds), onClick = {
                            onUpdate { c ->
                                val choices = LauncherConfig.DOCK_HIDE_CHOICES
                                c.copy(dockHideSeconds = choices[(choices.indexOf(c.dockHideSeconds) + 1) % choices.size])
                            }
                        })
                    },
                ),
            )

            // The football page's inputs together: which teams and leagues, and where to watch them.
            PanelGroup(
                "Football",
                rows = listOf(
                    {
                        PanelRow(
                            "Teams", accent, onFootball, grouped = true,
                            value = config.footballTeams.singleOrNull()?.name ?: config.footballTeams.size.takeIf { it > 0 }?.let { "$it teams" } ?: "None",
                        )
                    },
                    {
                        PanelRow(
                            "Leagues", accent, onLeagues, grouped = true,
                            value = config.footballLeagues.singleOrNull()?.name ?: config.footballLeagues.size.takeIf { it > 0 }?.let { "$it leagues" } ?: "None",
                        )
                    },
                    { PanelRow("IPTV playlist", accent, onPlaylist, value = playlist, grouped = true) },
                    { PanelRow("Football in screensaver", accent, { onUpdate { it.copy(ambientFootball = !it.ambientFootball) } }, toggle = config.ambientFootball, grouped = true) },
                ),
            )

            PanelGroup(
                "More",
                rows = listOf(
                    { PanelRow("Screensaver clock and name", accent, { onUpdate { it.copy(ambientInfo = !it.ambientInfo) } }, toggle = config.ambientInfo, grouped = true) },
                    { PanelRow("Screensaver", accent, onScreensaver, value = if (screensaverOurs) "Art" else "Use art", grouped = true) },
                    { PanelRow("Open Couch Commander on Home", accent, onHomeButton, value = if (homeButton) "On" else "Off", grouped = true) },
                ),
            )
        }
    }
}

private class Shortcut(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

/** A big card for something you jump to (an input, the TV's settings); white with the accent glow when focused. */
@Composable
private fun ShortcutCard(s: Shortcut, accent: Color, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    val fg = if (focused) Palette.Ink else Palette.White
    Column(
        modifier
            .height(80.dp)
            .then(if (focused) Modifier.shadow(16.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (focused) Palette.White else Color.White.copy(alpha = 0.08f))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = s.onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(s.icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        Column {
            Text(s.title, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.subtitle, color = fg.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The painting on screen now, with a button to skip to the next one. */
@Composable
private fun NowShowing(frame: ArtFrame?, accent: Color, minutes: Int, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        frame?.let {
            Image(
                it.image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        Column(Modifier.weight(1f)) {
            Text("NOW SHOWING", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = accent)
            Text(frame?.artwork?.title.orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(frame?.artwork?.artist, "changes ${everyLabel(minutes).lowercase()}").joinToString(" · "),
                fontSize = 11.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        NextButton(accent, onNext)
    }
}

@Composable
private fun NextButton(accent: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Text(
        "Next",
        color = if (focused) Palette.Ink else Palette.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .then(if (focused) Modifier.shadow(12.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (focused) Palette.White else Color.White.copy(alpha = 0.14f))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun hideLabel(seconds: Int): String = when {
    seconds == 0 -> "Never"
    seconds < 60 -> "After $seconds s"
    seconds == 60 -> "After a minute"
    else -> "After ${seconds / 60} min"
}

private fun everyLabel(minutes: Int): String = when {
    minutes == 1 -> "Every minute"
    minutes < 60 -> "Every $minutes min"
    minutes == 60 -> "Every hour"
    else -> "Every ${minutes / 60} hours"
}
