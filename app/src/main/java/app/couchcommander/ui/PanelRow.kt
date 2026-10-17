package app.couchcommander.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.ui.theme.Palette

/**
 * One row in a side panel or menu: a label, and a value or switch on the right. The focused row turns white with
 * the painting's accent glowing under it, like the focused app tile.
 */
@Composable
fun PanelRow(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    toggle: Boolean? = null,
    enabled: Boolean = true,
    /** Inside a [PanelGroup] rows are square and don't glow: the group's rounded edge frames them. */
    grouped: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = if (grouped) RectangleShape else RoundedCornerShape(12.dp)
    val fg = if (focused) Palette.Ink else Palette.White
    Row(
        modifier
            .fillMaxWidth()
            .then(if (focused && !grouped) Modifier.shadow(16.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (focused) Palette.White else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = if (toggle != null) Role.Switch else Role.Button, onClick = onClick)
            .semantics { if (toggle != null) stateDescription = if (toggle) "On" else "Off" }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 14.dp, vertical = if (grouped) 12.dp else 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = fg, fontSize = 16.sp, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.weight(1f))
        when {
            toggle != null -> Switch(toggle, accent, fg)
            value != null -> Text(value, color = fg.copy(alpha = 0.8f), fontSize = 15.sp)
        }
    }
}

/** On: the accent with a white knob. Off: drawn in the row's text colour [fg], so it shows on a focused (white) row too. */
@Composable
private fun Switch(on: Boolean, accent: Color, fg: Color) {
    Box(
        Modifier
            .size(width = 38.dp, height = 22.dp)
            .clip(CircleShape)
            .background(if (on) accent else fg.copy(alpha = 0.22f))
            .then(if (on) Modifier else Modifier.border(1.5.dp, fg.copy(alpha = 0.55f), CircleShape)),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = if (on) 19.dp else 3.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(if (on) Palette.White else fg),
        )
    }
}

/** A small upper-case heading over a group of rows. */
@Composable
fun PanelSection(title: String, accent: Color, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(),
        color = accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = modifier.padding(start = 14.dp, top = 14.dp, bottom = 2.dp),
    )
}

/**
 * A titled, rounded group of [PanelRow]s (pass `grouped = true` to each), with hairlines between them: the grouped
 * settings style from the designs.
 */
@Composable
fun PanelGroup(title: String, modifier: Modifier = Modifier, rows: List<@Composable () -> Unit>) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
            color = Palette.Muted, modifier = Modifier.padding(start = 4.dp),
        )
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f))) {
            rows.forEachIndexed { i, row ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                row()
            }
        }
    }
}
