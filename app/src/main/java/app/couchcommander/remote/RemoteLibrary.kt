package app.couchcommander.remote

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.couchcommander.AppContainer
import app.couchcommander.player.LivePlayback
import app.player.VodPlayerActivity
import app.iptv.IptvSource
import app.iptv.VodNames
import app.iptv.VodPlays
import app.media.MediaKind
import app.media.MovieDetails

/**
 * The phone's requests against the TV's own catalogue and Continue shelf: the same matching, version order and
 * resume rules as Play on a title's page, so a title started from the phone carries on the same either way.
 */
class RemoteLibrary(private val context: Context, private val container: AppContainer) : Library {

    override suspend fun has(ref: TitleRef, withEpisodes: Boolean): Availability {
        val title = details(ref, needFull = false)
        val versions = container.vod.find(title)
        if (versions.isEmpty()) return Availability(false)
        val episodes = if (withEpisodes && title.kind == MediaKind.Tv) {
            container.vod.episodes(container.iptvSource.source.value, versions.first())?.map { listOf(it.season, it.number) }
        } else null
        return Availability(true, VodNames.labels(versions), episodes)
    }

    override suspend fun play(request: PlayRequest): Result<Played> = runCatching {
        val src = container.iptvSource.source.value as? IptvSource.Xtream ?: throw RemoteFailure(503, "No Xtream playlist on the TV")
        val title = details(request.ref, needFull = true)
        val watching = container.played.get(title)
        // Carrying on from Continue, as OK on its card does: the very stream that played, not a version looked up
        // again by its label, which can read differently after a catalogue sync and land on a broken one.
        watching?.takeIf { w ->
            request.startMs == null && (request.version == null || request.version == w.version) &&
                (request.season == null || (request.season == w.season && request.episode == w.episode))
        }?.let { VodPlays.resume(src, it) }?.let { play ->
            context.startActivity(VodPlayerActivity.intent(context, play).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            return@runCatching Played(title.title, play.version, play.season, play.episode, play.startMs)
        }
        val versions = container.vod.find(title)
        if (versions.isEmpty()) throw RemoteFailure(404, "Not on the panel")
        val labels = VodNames.labels(versions)
        val at = VodPlays.pick(labels, request.version, watching)
        val entry = versions[at]
        val label = labels[at]

        val play = if (title.kind == MediaKind.Movie) {
            VodPlays.film(src, title, label, entry, request.startMs ?: VodPlays.startAt(watching, null))
        } else {
            val eps = container.vod.episodes(src, entry) ?: throw RemoteFailure(503, "The panel didn't answer")
            val episode = if (request.season != null && request.episode != null) {
                eps.firstOrNull { it.season == request.season && it.number == request.episode } ?: throw RemoteFailure(404, "That episode isn't on the panel")
            } else {
                VodPlays.upNext(eps, watching) ?: throw RemoteFailure(404, "No episodes on the panel")
            }
            val start = request.startMs ?: VodPlays.startAt(watching, episode)
            // TMDB's episode names, as on the title page; the panel's are often just its file name.
            val names = container.media.episodes(title, episode.season)?.associate { it.number to it.title }.orEmpty()
            VodPlays.episode(src, title, label, eps, episode, start, names)
        }
        context.startActivity(VodPlayerActivity.intent(context, play).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        Played(title.title, label, play.season, play.episode, play.startMs)
    }

    override fun continueWatching(): List<ContinueItem> = container.played.all.value.map { w ->
        ContinueItem(
            w.title.id, typeOf(w.title.kind), w.title.title, w.title.posterPath, w.title.backdropPath, w.version,
            w.positionMs, w.durationMs, w.season, w.episode, w.episodeTitle, w.atEpochMillis,
        )
    }

    override fun now(): Now {
        val live = LivePlayback.current ?: return Now()
        val p = live.play
        return Now(
            live.playing,
            ContinueItem(
                p.title.id, typeOf(p.title.kind), p.title.title, p.title.posterPath, p.title.backdropPath, p.version,
                live.positionAt(SystemClock.elapsedRealtime()), live.durationMs, p.season, p.episode, p.episodeTitle, System.currentTimeMillis(),
            ),
        )
    }

    override fun removeContinue(ref: TitleRef): Boolean {
        val kind = ref.kind ?: return false
        val w = container.played.all.value.firstOrNull { it.title.id == ref.tmdb && it.title.kind == kind } ?: return false
        container.played.remove(w.title)
        return true
    }

    /**
     * The title from TMDB (as on the Movies and TV page). To check availability, the phone's
     * name and year are enough; playing wants the poster and plot for Continue, but falls back to them offline.
     */
    private suspend fun details(ref: TitleRef, needFull: Boolean): MovieDetails {
        val kind = ref.kind ?: throw RemoteFailure(400, "type must be movie or tv")
        val stub = MovieDetails(ref.tmdb, ref.title.orEmpty(), ref.year ?: 0, 0.0, kind = kind)
        if (!needFull && !ref.title.isNullOrBlank()) return stub
        return container.media.details(stub)
            ?: stub.takeIf { !ref.title.isNullOrBlank() }
            ?: throw RemoteFailure(503, "TMDB didn't answer; send title and year")
    }
}
