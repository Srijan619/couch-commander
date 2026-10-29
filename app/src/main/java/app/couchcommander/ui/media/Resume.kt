package app.couchcommander.ui.media

import app.couchcommander.AppContainer
import app.couchcommander.data.media.Watching
import app.couchcommander.player.VodPlay
import app.iptv.IptvSource
import app.iptv.XtreamVod
import app.media.MediaKind

/**
 * What OK on a Continue card plays: the same version, from where it was left, straight away. A series' next
 * episode isn't looked up here (that took the panel up to 3 s before anything played); the player finds it while
 * this one plays. Null without an Xtream playlist, so the title's page opens instead.
 */
fun resumePlay(container: AppContainer, watching: Watching): VodPlay? {
    val src = container.iptvSource.source.value as? IptvSource.Xtream ?: return null
    val start = watching.positionMs.takeIf { it >= RESUME_FROM_MS } ?: 0
    if (watching.title.kind == MediaKind.Movie || watching.season == null || watching.episode == null) {
        return VodPlay(
            watching.title, watching.version, XtreamVod.movieStreamUrl(src, watching.streamId, watching.ext),
            watching.streamId, watching.ext, startMs = start,
        )
    }
    return VodPlay(
        watching.title, watching.version, XtreamVod.episodeStreamUrl(src, watching.streamId, watching.ext),
        watching.streamId, watching.ext, watching.season, watching.episode, watching.episodeTitle, startMs = start,
    )
}

/** Under this far in, a film or episode starts from the beginning, as Play does on the title page. */
private const val RESUME_FROM_MS = 30_000L
