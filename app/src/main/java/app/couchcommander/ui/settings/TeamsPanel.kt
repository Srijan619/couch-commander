package app.couchcommander.ui.settings

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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.ui.PanelRow
import app.couchcommander.ui.PanelSection
import app.couchcommander.ui.theme.Bricolage
import app.couchcommander.ui.theme.Palette
import app.football.FootballTeam
import kotlinx.coroutines.delay

private const val SEARCH_DEBOUNCE_MS = 400L

/**
 * Follow or stop following football teams. Search by club name with the TV's on-screen keyboard; results come from
 * Footy through football-core. Back returns to settings.
 */
@Composable
fun TeamsPanel(
    followed: List<FootballTeam>,
    accent: Color,
    search: suspend (String) -> List<FootballTeam>,
    onFollow: (FootballTeam) -> Unit,
    onUnfollow: (FootballTeam) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FootballTeam>?>(null) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldInteraction = remember { MutableInteractionSource() }
    val fieldFocused by fieldInteraction.collectIsFocusedAsState()

    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        results = search(query)
    }

    Box(Modifier.fillMaxSize().background(Color(0x59000000))) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(380.dp)
                .background(Palette.Panel)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Football", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
            Text(
                "Your teams' next match and live scores show on home.",
                fontSize = 14.sp, color = Palette.Muted, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            )

            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                interactionSource = fieldInteraction,
                textStyle = TextStyle(fontFamily = Bricolage, fontSize = 17.sp, color = Palette.White),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(field)
                    .semantics { contentDescription = "Search teams" }
                    .border(2.dp, if (fieldFocused) accent else Color(0x66FFFFFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Search a club, e.g. HJK", fontSize = 17.sp, color = Palette.Muted)
                        inner()
                    }
                },
            )

            val found = results
            if (found != null) {
                PanelSection("Results", accent)
                if (found.isEmpty()) {
                    Text("No teams found.", fontSize = 14.sp, color = Palette.Muted, modifier = Modifier.padding(14.dp))
                }
                found.take(MAX_RESULTS).forEach { team ->
                    val isFollowed = followed.any { it.id == team.id }
                    PanelRow(
                        team.displayName, accent,
                        onClick = { if (isFollowed) onUnfollow(team) else onFollow(team) },
                        value = if (isFollowed) "Following" else "Follow",
                    )
                }
            }

            PanelSection("Following", accent)
            if (followed.isEmpty()) {
                Text("No teams yet.", fontSize = 14.sp, color = Palette.Muted, modifier = Modifier.padding(14.dp))
            }
            followed.forEach { team ->
                PanelRow(team.displayName, accent, onClick = { onUnfollow(team) }, value = "Stop following")
            }
        }
    }
}

private const val MAX_RESULTS = 8
