package app.couchcommander.ui.home

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couchcommander.data.apps.TvApp
import app.couchcommander.ui.PanelRow
import app.couchcommander.ui.theme.Palette

/** Opened by holding OK on a tile: open, move along the row, or remove. Back closes it. */
@Composable
fun AppMenu(
    app: TvApp,
    accent: Color,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // The OK that opened this menu is still held. Ignore it until it is released, so it doesn't press "Open".
    val held = remember { booleanArrayOf(true) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C000000))
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent
                if (!held[0] || k.keyCode !in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)) {
                    return@onPreviewKeyEvent false
                }
                if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) {
                    held[0] = false
                    false
                } else {
                    if (k.action == KeyEvent.ACTION_UP) held[0] = false
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(340.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Palette.Panel)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                app.label, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, bottom = 10.dp),
            )
            PanelRow("Open", accent, onOpen, Modifier.focusRequester(first))
            PanelRow("Move left", accent, { onMove(-1) }, enabled = canMoveLeft)
            PanelRow("Move right", accent, { onMove(1) }, enabled = canMoveRight)
            PanelRow("Remove from home", accent, onHide)
        }
    }
}
