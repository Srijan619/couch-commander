package app.couchcommander.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.data.art.ArtFrame
import app.couchcommander.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The pieces the full-screen pages share, so football and movies and TV look and move the same.

/** A row card is at least a tile's height (48 dp) plus its padding. */
private val ROW_MIN_HEIGHT = 66.dp

/** A tab on a full-screen page (football, movies and TV): underlined in the accent while open, a white pill while focused. Focus opens it. */
@Composable
fun TabPill(label: String, open: Boolean, accent: Color, active: Boolean, focus: FocusRequester, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onOpen() }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .focusRequester(focus)
            .scale(if (focused) 1.06f else 1f)
            .then(if (focused) Modifier.shadow(16.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (focused) Palette.White else Color.Transparent)
            .focusProperties { canFocus = active }
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onOpen)
            .padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 4.dp)
            // As wide as the label, so the underline below runs its full length.
            .width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label, fontSize = 15.sp, maxLines = 1,
            fontWeight = if (open) FontWeight.Bold else FontWeight.SemiBold,
            color = when {
                focused -> Palette.Ink
                open -> Palette.White
                else -> Palette.Muted
            },
        )
        Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(2.dp).clip(CircleShape).background(if (open && !focused) accent else Color.Transparent))
    }
}

/** A row card's ground: brighter while something in it has focus, a touch brighter while its match is live. */
val CardGround = Color(0x0AFFFFFF)
val CardLive = Color(0x14FFFFFF)
val CardFocused = Color(0x24FFFFFF)

/**
 * A match, a brand of channels or a shelf of titles as a rounded card, so the row being browsed stands out from the others while
 * focus moves through its tiles.
 */
@Composable
fun RowCard(
    live: Boolean,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    padding: PaddingValues = PaddingValues(start = 18.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.hasFocus }
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) CardFocused else if (live) CardLive else CardGround)
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(padding),
        verticalAlignment = verticalAlignment,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        content = content,
    )
}

/** A white-when-focused pill: a provider chip, "+N", "+" for leagues, and the "add your playlist" link. */
@Composable
fun ActionPill(
    icon: ImageVector?,
    label: String?,
    accent: Color,
    active: Boolean,
    focus: FocusRequester?,
    modifier: Modifier = Modifier,
    /** Read out for an icon-only pill. */
    description: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val fg = if (focused) Palette.Ink else Palette.White
    Row(
        modifier
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .scale(if (focused) 1.06f else 1f)
            .then(if (focused) Modifier.shadow(16.dp, shape, ambientColor = accent, spotColor = accent) else Modifier)
            .clip(shape)
            .background(if (focused) Palette.White else Palette.Raised)
            // Hidden off screen, nothing here may take focus (Right on home's last app would find it).
            .focusProperties { canFocus = active }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, if (label == null) description else null, tint = fg, modifier = Modifier.size(15.dp)) }
        label?.let { Text(it, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
    }
}

/** The painting as a small, blurred and dimmed copy, made once per painting off the main thread. */
@Composable
fun rememberBlurred(frame: ArtFrame?): ImageBitmap? {
    val image = frame?.image
    val blurred by produceState<ImageBitmap?>(null, image) {
        value = image?.let { withContext(Dispatchers.Default) { Blur.small(it.asAndroidBitmap(), dim = 0.62f, ground = Palette.Ground.toArgb()).asImageBitmap() } }
    }
    return blurred
}
