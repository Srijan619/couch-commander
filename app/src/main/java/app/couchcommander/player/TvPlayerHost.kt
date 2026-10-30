package app.couchcommander.player

import app.couchcommander.AppContainer
import app.iptv.ContinueWatching
import app.iptv.IptvSource
import app.iptv.VodPlay
import app.iptv.VodRepository
import app.media.MovieDetails
import app.media.trakt.TraktScrobble
import app.player.NowPlaying
import app.player.PlayerHost

/** The shared player's view of the TV: the playlist and Continue, TMDB's episode names, Trakt, and `/now`. */
class TvPlayerHost(private val container: AppContainer) : PlayerHost {
    override val source: IptvSource.Xtream? get() = container.iptvSource.source.value as? IptvSource.Xtream
    override val vod: VodRepository get() = container.vod
    override val played: ContinueWatching get() = container.played

    override suspend fun episodeNames(title: MovieDetails, season: Int): Map<Int, String> =
        container.media.episodes(title, season)?.associate { it.number to it.title }.orEmpty()

    override fun onScrobble(moment: TraktScrobble, play: VodPlay, progress: Float) =
        container.traktScrobbler.report(moment, play.traktItem, progress)

    override fun onNowPlaying(owner: Any, now: NowPlaying?) {
        if (now == null) return LivePlayback.clear(owner)
        LivePlayback.publish(LivePlayback.Snapshot(owner, now.play, now.positionMs, now.durationMs, now.playing, now.advancing, now.atElapsed))
    }
}
