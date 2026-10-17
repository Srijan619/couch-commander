package app.couchcommander.domain

import app.football.FootballLeague
import app.football.FootballTeam
import kotlinx.serialization.Serializable

/** Everything the user can change, stored as one JSON file. */
@Serializable
data class LauncherConfig(
    /** Keys of the apps on the home row, in order. Nothing is shown until the user picks. */
    val apps: List<String> = emptyList(),
    /** False until the first-run app picker has been finished. */
    val onboarded: Boolean = false,
    /** How long each painting stays up, on home and in the screensaver. */
    val artMinutes: Int = 10,
    /** Tints the painting with its own accent colour, which is what makes home look vibrant. */
    val colourBoost: Boolean = true,
    /** Clock and painting title in the screensaver. */
    val ambientInfo: Boolean = true,
    /** The followed teams' next match or live score in the screensaver, as on home. */
    val ambientFootball: Boolean = true,
    /** Seconds without a key press before the app dock slides back below the screen; 0 keeps it up. */
    val dockHideSeconds: Int = 60,
    /** The apps stay on screen at the bottom of home instead of waiting below it behind the "Apps" handle. */
    val alwaysShowApps: Boolean = false,
    /** Teams whose next match or live score shows on home. None: the football block is hidden. */
    val footballTeams: List<FootballTeam> = emptyList(),
    /** Leagues with a tab on the football page, in tab order. Only the football page reads them, never home or the screensaver. */
    val footballLeagues: List<FootballLeague> = emptyList(),
) {
    companion object {
        val ART_MINUTE_CHOICES = listOf(1, 5, 10, 30, 60, 180)
        val DOCK_HIDE_CHOICES = listOf(15, 30, 60, 120, 300, 0)
    }
}
