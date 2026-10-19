package app.couchcommander.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.couchcommander.ui.PanelRow
import app.couchcommander.ui.PanelSection
import app.couchcommander.ui.theme.Palette
import app.media.trakt.Trakt
import app.media.trakt.TraktDeviceCode
import app.media.trakt.TraktPoll
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

/**
 * Connect Trakt account to automatically record playback and sync watch history.
 * Signed out: shows the device activation code and QR code pointing to trakt.tv/activate on the TV,
 * polling until approved. Signed in: shows the username with an option to disconnect.
 */
@Composable
fun TraktPanel(
    trakt: Trakt,
    accent: Color,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val username by trakt.username.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    var deviceCode by remember { mutableStateOf<TraktDeviceCode?>(null) }
    var loading by remember { mutableStateOf(username == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(username, retryCount) {
        if (username != null) return@LaunchedEffect
        loading = true
        error = null
        val code = trakt.startSignIn()
        if (code == null) {
            loading = false
            error = "Couldn't reach Trakt. Check network connection."
            return@LaunchedEffect
        }
        deviceCode = code
        loading = false

        // Polls until approved or expired
        val poll = trakt.finishSignIn(code)
        when (poll) {
            is TraktPoll.SignedIn -> {
                // trakt.username automatically updates
            }
            TraktPoll.Expired -> {
                error = "Code expired. Please try again."
                deviceCode = null
            }
            TraktPoll.Denied -> {
                error = "Sign in denied on Trakt."
                deviceCode = null
            }
            else -> {
                error = "Couldn't reach Trakt."
                deviceCode = null
            }
        }
    }

    LaunchedEffect(username == null, loading, error) {
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Trakt",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
            )

            if (username != null) {
                Text(
                    "Your TV automatically reports what you watch and where you left off to your Trakt account.",
                    fontSize = 14.sp, color = Palette.Muted,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                )
                PanelSection("Signed in", accent)
                Text(
                    username ?: "",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 12.dp),
                )
                PanelRow("Done", accent, onDismiss, modifier = Modifier.focusRequester(first))
                PanelRow("Sign out", accent, onClick = { scope.launch { trakt.signOut() } })
                return@Column
            }

            Text(
                "Connect your Trakt account to scrobble plays, keep resume points, and sync watch history.",
                fontSize = 14.sp, color = Palette.Muted,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 6.dp),
            )

            val code = deviceCode
            if (code != null) {
                PanelSection("Enter code", accent)
                Text(
                    code.userCode,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    color = Palette.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(vertical = 12.dp),
                )
                Text(
                    "On your phone or computer, visit:\n${code.verificationUrl}",
                    fontSize = 14.sp,
                    color = Palette.White,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 6.dp),
                )
                Box(
                    Modifier
                        .padding(start = 14.dp, top = 4.dp, bottom = 12.dp)
                        .size(QR_SIZE)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.White),
                    contentAlignment = Alignment.Center,
                ) {
                    QrCode(code.verificationUrl, Modifier.padding(12.dp).fillMaxSize())
                }
                Text(
                    "Waiting for approval…",
                    fontSize = 13.sp,
                    color = Palette.Muted,
                    modifier = Modifier.padding(start = 14.dp, bottom = 10.dp),
                )
            } else if (loading) {
                Text(
                    "Getting a code…",
                    fontSize = 14.sp,
                    color = Palette.Muted,
                    modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
                )
            } else if (error != null) {
                Text(
                    error ?: "",
                    fontSize = 14.sp,
                    color = Palette.Ink,
                    modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 10.dp),
                )
                PanelRow("Try again", accent, { retryCount++ }, modifier = Modifier.focusRequester(first))
            }

            PanelRow("Cancel", accent, onDismiss, modifier = if (code != null || loading) Modifier.focusRequester(first) else Modifier)
        }
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix: BitMatrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Canvas(modifier.semantics { contentDescription = "QR code for Trakt activation" }) {
        val cell = size.minDimension / matrix.width
        val module = Size(cell + 0.5f, cell + 0.5f)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), module)
        }
    }
}

private val QR_SIZE = 190.dp
