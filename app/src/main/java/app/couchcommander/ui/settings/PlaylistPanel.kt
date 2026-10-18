package app.couchcommander.ui.settings

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.data.iptv.IptvSource
import app.couchcommander.data.iptv.IptvState
import app.couchcommander.data.iptv.VodStatus
import app.couchcommander.ui.PanelRow
import app.couchcommander.ui.PanelSection
import app.couchcommander.ui.theme.Bricolage
import app.couchcommander.ui.theme.Palette
import java.net.URI
import java.util.Date

/**
 * The IPTV playlist behind Watch on the football page and Play on the Movies & TV page. Paste or type the link
 * with the TV's keyboard (an M3U URL, or an Xtream `get.php?username=…&password=…` link). Of its live channels
 * only sports ones are kept; films and series come from an Xtream panel's catalogue. The saved link is never
 * shown again, only its server's name: it holds the login.
 */
@Composable
fun PlaylistPanel(
    source: IptvSource?,
    state: IptvState,
    syncing: Boolean,
    /** The panel's film and series catalogue. */
    vod: VodStatus,
    accent: Color,
    onSave: (IptvSource) -> Unit,
    onSync: () -> Unit,
    onRemove: () -> Unit,
    /** Channels hidden from the player (they played but showed nothing useful). */
    hiddenChannels: Int = 0,
    onShowHidden: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    var guide by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    fun save() {
        val parsed = IptvSource.parse(link, guide.trim().ifEmpty { null })
        if (parsed == null) {
            problem = "That isn't an http(s) link."
            return
        }
        problem = null
        keyboard?.hide()
        onSave(parsed)
        link = ""
        guide = ""
    }

    Box(Modifier.fillMaxSize().background(Color(0x59000000))) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp)
                .background(Palette.Panel)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("IPTV playlist", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
            Text(
                "Add your playlist to watch your teams' matches on the football page, and films and series on the Movies & TV page. Of the live channels, only sports ones are kept.",
                fontSize = 14.sp, color = Palette.Muted, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            )

            PanelSection("Status", accent)
            Text(
                status(source, state, vod, syncing, DateFormat.getTimeFormat(context)::format),
                fontSize = 14.sp, color = if ((state.error != null || vod.error != null) && !syncing && !vod.syncing) accent else Palette.White,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 6.dp),
            )
            if (source != null) {
                PanelRow(if (syncing) "Syncing…" else "Sync now", accent, onSync, enabled = !syncing)
                if (hiddenChannels > 0) {
                    PanelRow("Hidden channels", accent, onShowHidden, value = "$hiddenChannels · Show again")
                }
                PanelRow("Remove playlist", accent, onRemove)
            }

            PanelSection(if (source == null) "Add a playlist" else "Replace it", accent)
            LinkField(link, { link = it; problem = null }, "Playlist link (M3U or Xtream get.php)", accent, Modifier.focusRequester(field), ImeAction.Next) {}
            LinkField(guide, { guide = it }, "Guide (EPG) link, optional", accent, Modifier.padding(top = 8.dp), ImeAction.Done) { save() }
            problem?.let { Text(it, fontSize = 13.sp, color = accent, modifier = Modifier.padding(start = 14.dp, top = 6.dp)) }
            PanelRow("Save and sync", accent, ::save, enabled = link.isNotBlank(), modifier = Modifier.padding(top = 8.dp))
            Text(
                "Long link? Set it from a computer instead: see \"Where to watch\" in the README.",
                fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp),
            )
        }
    }
}

@Composable
private fun LinkField(value: String, onChange: (String) -> Unit, hint: String, accent: Color, modifier: Modifier, ime: ImeAction, onDone: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        interactionSource = interaction,
        textStyle = TextStyle(fontFamily = Bricolage, fontSize = 15.sp, color = Palette.White),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ime, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = hint }
            .border(2.dp, if (focused) accent else Color(0x66FFFFFF), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, fontSize = 15.sp, color = Palette.Muted)
                inner()
            }
        },
    )
}

/**
 * "Xtream · panel.example", then "214 sports channels, 12 showing your teams, synced 03:12" and "8 400 films,
 * 1 200 series, synced 03:15"; the server name only, never the login.
 */
private fun status(source: IptvSource?, s: IptvState, vod: VodStatus, syncing: Boolean, time: (Date) -> String): String {
    if (source == null) return "No playlist yet. Watch buttons appear once one is added."
    val kind = when (source) {
        is IptvSource.Xtream -> "Xtream · ${host(source.server)}"
        is IptvSource.M3u -> "M3U · ${host(source.url)}"
    }
    if (syncing) return "$kind\nSyncing…"
    val synced = s.syncedAtEpochMillis?.let { "synced ${time(Date(it))}" } ?: "not synced yet"
    val lines = listOfNotNull(
        kind,
        "${s.sportsChannels} sports channels, ${s.channels.size} showing your teams, $synced",
        s.guideError,
        s.error?.let { "Last sync failed: $it" },
        when {
            source !is IptvSource.Xtream -> "Films and series need an Xtream playlist."
            vod.syncing -> "Films and series: syncing…"
            vod.syncedAtEpochMillis != null -> "%,d films, %,d series, synced %s".format(vod.movies, vod.series, time(Date(vod.syncedAtEpochMillis)))
            vod.error == null -> "Films and series: not synced yet"
            else -> null
        },
        vod.error?.takeIf { source is IptvSource.Xtream && !vod.syncing }?.let { "Films and series sync failed: $it" },
    )
    return lines.joinToString("\n")
}

private fun host(url: String) = runCatching { URI(url).host }.getOrNull() ?: "server"
