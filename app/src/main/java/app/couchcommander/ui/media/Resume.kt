package app.couchcommander.ui.media

import app.couchcommander.AppContainer
import app.couchcommander.data.iptv.IptvSource
import app.couchcommander.data.iptv.XtreamVod
import app.couchcommander.data.media.Watching
import app.couchcommander.player.VodPlay
import app.media.MediaKind
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What OK on a Continue card plays: the same version, from where it was left, straight away. For a series the
 * episode after it follows on when the panel's episode list comes within [NEXT_LOOKUP_MS]; otherwise the
 * episode plays alone. Null without an Xtream playlist, so the title's page opens instead.
 */
suspend fun resumePlay(container: AppContainer, watching: Watching): VodPlay? {
    val src = container.iptvSource.source.value as? IptvSource.Xtream ?: return null
    val start = watching.positionMs.takeIf { it >= RESUME_FROM_MS } ?: 0
    if (watching.title.kind == MediaKind.Movie || watching.season == null || watching.episode == null) {
        return VodPlay(
            watching.title, watching.version, XtreamVod.movieStreamUrl(src, watching.streamId, watching.ext),
            watching.streamId, watching.ext, startMs = start,
        )
    }
    val play = VodPlay(
        watching.title, watching.version, XtreamVod.episodeStreamUrl(src, watching.streamId, watching.ext),
        watching.streamId, watching.ext, watching.season, watching.episode, watching.episodeTitle, startMs = start,
    )
    // The series' versions on the panel, one at a time until the one holding this episode: usually the first.
    // Episode lists are cached, so a second press asks the panel nothing.
    val next = withTimeoutOrNull(NEXT_LOOKUP_MS) {
        for (entry in container.vod.find(watching.title)) {
            val eps = container.vod.episodes(src, entry) ?: continue
            val at = eps.indexOfFirst { it.id == watching.streamId }
            if (at >= 0) return@withTimeoutOrNull eps.getOrNull(at + 1)
        }
        null
    }
    return play.copy(next = next?.let { VodPlay(watching.title, watching.version, XtreamVod.episodeStreamUrl(src, it), it.id, it.ext, it.season, it.number, it.title) })
}

/** Under this far in, a film or episode starts from the beginning, as Play does on the title page. */
private const val RESUME_FROM_MS = 30_000L
/** How long OK waits for the next episode before playing without it. */
private const val NEXT_LOOKUP_MS = 3_000L
