package app.couchcommander.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.couchcommander.LauncherApp
import app.couchcommander.ui.theme.CouchTheme
import app.couchcommander.ui.theme.Palette
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/** The code a pairing phone asks for, over whatever is on screen; closes once paired, on expiry, or with Back. */
class PairActivity : ComponentActivity() {
    private val pairing by lazy { (application as LauncherApp).container.pairing }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CouchTheme {
                val request by pairing.request.collectAsStateWithLifecycle()
                val r = request
                BackHandler { pairing.cancel() }
                LaunchedEffect(r) {
                    if (r == null) finish()
                    else {
                        delay(Duration.between(Instant.now(), r.expiresAt).toMillis().coerceAtLeast(0))
                        pairing.cancel()
                    }
                }
                Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                    if (r != null) {
                        Column(
                            Modifier.background(Palette.Panel, RoundedCornerShape(28.dp)).padding(horizontal = 56.dp, vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Pair ${r.name}", fontSize = 22.sp, color = Palette.Muted)
                            Text(r.code, fontSize = 72.sp, fontWeight = FontWeight.Bold, letterSpacing = 12.sp, color = Palette.White)
                            Text("Enter this code on your phone", fontSize = 16.sp, color = Palette.Muted)
                        }
                    }
                }
            }
        }
    }
}
