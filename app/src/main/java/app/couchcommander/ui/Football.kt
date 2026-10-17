package app.couchcommander.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.domain.FootballLines
import app.couchcommander.domain.FootballText
import app.couchcommander.ui.theme.Palette
import app.football.FootballBoards
import app.football.FootballMatch
import app.football.FootballTeam
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

/** The followed teams' featured match, re-worded each minute (countdowns, "Today" turning over). Null: nothing to show. */
@Composable
fun rememberFootballLines(matches: List<FootballMatch>, teams: List<FootballTeam>): FootballLines? {
    val context = LocalContext.current
    val minute by produceState(Instant.now()) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = Instant.now()
        }
    }
    val is24Hour = remember { DateFormat.is24HourFormat(context) }
    return remember(matches, teams, minute) {
        val ids = teams.map { it.id }.toSet()
        if (ids.isEmpty()) null
        else FootballText.lines(
            FootballBoards.from(matches.filter { it.home.id in ids || it.away.id in ids }, minute),
            minute, ZoneId.systemDefault(), is24Hour,
        )
    }
}

/** The featured match in the quiet right-aligned style of the old painting caption; on home and in the screensaver. */
@Composable
fun FootballBlock(lines: FootballLines, accent: Color, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.widthIn(max = 380.dp).semantics(mergeDescendants = true) { },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lines.live) Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            Text(lines.label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = Palette.Muted)
        }
        Text(
            lines.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, textAlign = TextAlign.End,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (lines.detail.isNotEmpty()) Text(lines.detail, fontSize = 14.sp, color = Palette.Muted, textAlign = TextAlign.End)
    }
}
