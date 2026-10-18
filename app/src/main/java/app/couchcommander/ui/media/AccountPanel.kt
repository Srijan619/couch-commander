package app.couchcommander.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import app.couchcommander.data.media.SignIn
import app.couchcommander.ui.PanelRow
import app.couchcommander.ui.PanelSection
import app.couchcommander.ui.theme.Palette
import app.media.TmdbSession
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/**
 * The TMDB account, from the account pill on the Movies and TV page. Signed out, it shows TMDB's approval page as a
 * QR code to scan with a phone; the TV signs in by itself once the user approves there. Signed in, who it is and
 * Sign out. Back or Cancel closes it.
 */
@Composable
fun AccountPanel(
    session: TmdbSession?,
    signIn: SignIn,
    accent: Color,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    // Again when signing in finishes: the row that had focus is gone.
    LaunchedEffect(session == null, signIn is SignIn.Offline) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Color(0x59000000))) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(400.dp)
                .background(Palette.Panel)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("TMDB account", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
            if (session != null) {
                Text(
                    "Your watchlist and favourites are the first shelves on Movies and TV. Add to them from a title's page.",
                    fontSize = 14.sp, color = Palette.Muted, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                )
                PanelSection("Signed in", accent)
                Text(session.username, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 8.dp))
                PanelRow("Done", accent, onDismiss, modifier = Modifier.focusRequester(first))
                PanelRow("Sign out", accent, onSignOut)
                return@Column
            }

            Text(
                "Sign in with your phone to see your watchlist and favourites here, and to add titles and rate them.",
                fontSize = 14.sp, color = Palette.Muted, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            )
            Box(
                Modifier.padding(start = 14.dp, top = 6.dp, bottom = 12.dp).size(QR_SIZE).clip(RoundedCornerShape(12.dp)).background(Palette.White),
                contentAlignment = Alignment.Center,
            ) {
                when (signIn) {
                    is SignIn.Waiting -> QrCode(signIn.url, Modifier.padding(12.dp).fillMaxSize())
                    SignIn.Offline -> Text("Couldn't reach TMDB", fontSize = 14.sp, color = Palette.Ink)
                    else -> Text("Getting a code…", fontSize = 14.sp, color = Palette.Ink)
                }
            }
            Text(
                "1. Scan the code with your phone's camera.\n2. Log in to TMDB and choose Approve.\n3. The TV signs in by itself.",
                fontSize = 15.sp, lineHeight = 24.sp, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            )
            if (signIn == SignIn.Offline) {
                PanelRow("Try again", accent, onRetry, modifier = Modifier.focusRequester(first))
                PanelRow("Cancel", accent, onDismiss)
            } else {
                PanelRow("Cancel", accent, onDismiss, modifier = Modifier.focusRequester(first))
            }
            if (signIn == SignIn.Starting) {
                Text(
                    "The first time after a quiet spell can take up to a minute.",
                    fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp),
                )
            }
        }
    }
}

/** [text] as a QR code, dark modules on whatever is behind it (white, with its own margin round it). */
@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix: BitMatrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Canvas(modifier.semantics { contentDescription = "QR code for signing in to TMDB" }) {
        val cell = size.minDimension / matrix.width
        // A hair over a cell, so neighbouring modules meet without seams.
        val module = Size(cell + 0.5f, cell + 0.5f)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), module)
        }
    }
}

private val QR_SIZE = 220.dp
