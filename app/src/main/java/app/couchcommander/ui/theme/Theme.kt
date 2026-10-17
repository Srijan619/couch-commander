package app.couchcommander.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.couchcommander.R
import kotlin.math.absoluteValue

/** The palette from the designs: a near-black ground, white type, bright tiles. */
object Palette {
    val Ground = Color(0xFF0B0B12)
    val Ink = Color(0xFF0B0B12)
    val Panel = Color(0xFF0E0E18)
    val White = Color(0xFFFFFFFF)
    val Muted = Color(0xD9FFFFFF)
    val Raised = Color(0x29FFFFFF)
    val Mat = Color(0xFFF4EEDF)
}

/** Fill and text colour for an app that has no TV banner. */
data class TileColors(val bg: Color, val fg: Color)

private val tileColors = listOf(
    TileColors(Color(0xFFFF5A36), Palette.Ink),
    TileColors(Color(0xFF6C4DFF), Palette.White),
    TileColors(Color(0xFFC8F031), Palette.Ink),
    TileColors(Color(0xFFFF6FB5), Palette.Ink),
    TileColors(Color(0xFF3FD3F5), Palette.Ink),
    TileColors(Color(0xFFFFB020), Palette.Ink),
    TileColors(Color(0xFF2F5BFF), Palette.White),
    TileColors(Color(0xFF6EF0C2), Palette.Ink),
)

/** The same app always gets the same colour. */
fun tileColorsFor(key: String): TileColors = tileColors[key.hashCode().absoluteValue % tileColors.size]

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int) =
    Font(R.font.bricolage_grotesque, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Bricolage Grotesque (SIL OFL), one variable font file for every weight. */
val Bricolage = FontFamily(bricolage(300), bricolage(400), bricolage(500), bricolage(600), bricolage(700), bricolage(800))

@Composable
fun CouchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.White,
            onPrimary = Palette.Ink,
            background = Palette.Ground,
            onBackground = Palette.White,
            surface = Palette.Panel,
            onSurface = Palette.White,
        ),
    ) {
        // No Surface at the root, so set the default text colour and font explicitly.
        CompositionLocalProvider(
            LocalContentColor provides Palette.White,
            LocalTextStyle provides TextStyle(fontFamily = Bricolage, color = Palette.White),
            content = content,
        )
    }
}
