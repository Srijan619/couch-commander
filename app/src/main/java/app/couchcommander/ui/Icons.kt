package app.couchcommander.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The few line icons the settings panel uses, drawn on a 24-unit grid with a 2-unit stroke. Tint them when drawn. */
object Icons {
    /** An HDMI plug, seen end on. */
    val Hdmi: ImageVector = stroke("hdmi", "M3 8h18v6l-3 3H6l-3-3z", "M8 11h8")

    /** A TV on a stand: the TV's own settings. */
    val Tv: ImageVector = stroke("tv", "M3 5h18v12H3z", "M8 21h8", "M12 17v4")

    /** A plain up arrow: "press Up" for the launcher's settings. */
    val ArrowUp: ImageVector = stroke("arrowUp", "M12 19V5", "M6 11l6-6 6 6")

    /** A plain down chevron: "press Down" for the app dock. */
    val ChevronDown: ImageVector = stroke("chevronDown", "M6 9l6 6 6-6")

    /** "Press Right" for the football page, and its "Left: home" hint. */
    val ChevronRight: ImageVector = stroke("chevronRight", "M9 6l6 6-6 6")
    val ChevronLeft: ImageVector = stroke("chevronLeft", "M15 6l-6 6 6 6")

    /** Watch. */
    val Play: ImageVector = stroke("play", "M8 5.5v13l11-6.5z")

    /** The football page's last tab: choose leagues. */
    val Plus: ImageVector = stroke("plus", "M12 5v14", "M5 12h14")

    private fun stroke(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
