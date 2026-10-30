package app.couchcommander.ui.football

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import app.couchcommander.LauncherApp
import app.football.CrestColor
import app.football.Footy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A team's crest and the deep tone of its colour for the poster band; either null while loading or when there's none. */
class Crest(val image: ImageBitmap?, val tone: Color?)

/** Each team's tone, worked out once per run; 0 for a crest without colour. */
private val tones = ConcurrentHashMap<Int, Int>()

/** The crest through the channel logo cache (downloaded once, decoded small), its colour picked off the main thread. */
@Composable
fun rememberCrest(teamId: Int): Crest {
    val logos = (LocalContext.current.applicationContext as LauncherApp).container.logos
    val url = Footy.crestUrl(teamId, small = true)
    val crest by produceState(Crest(url?.let { logos.cached(it) }?.image, tones[teamId]?.toTone()), teamId) {
        if (url == null) return@produceState
        val logo = logos.load(url) ?: return@produceState
        val tone = tones[teamId] ?: withContext(Dispatchers.Default) {
            val bitmap = logo.image.asAndroidBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            CrestColor.dominant(pixels)?.let(CrestColor::cardTone) ?: 0
        }.also { tones[teamId] = it }
        value = Crest(logo.image, tone.toTone())
    }
    return crest
}

private fun Int.toTone(): Color? = takeIf { it != 0 }?.let(::Color)
