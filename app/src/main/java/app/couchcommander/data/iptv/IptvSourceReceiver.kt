package app.couchcommander.data.iptv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.Xml
import app.couchcommander.LauncherApp
import app.couchcommander.player.PlayerActivity
import app.couchcommander.player.WatchAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sets the playlist over adb, so a long URL with a login doesn't have to be typed with the remote. Only the
 * shell can send it (the manifest requires the DUMP permission, which apps can't hold):
 *
 *     adb shell am broadcast -n app.couchcommander/.data.iptv.IptvSourceReceiver -a app.couchcommander.SET_IPTV --es source '<url>' [--es epg '<url>']
 *     adb shell am broadcast -n app.couchcommander/.data.iptv.IptvSourceReceiver -a app.couchcommander.SYNC_IPTV
 *     adb shell am broadcast -n app.couchcommander/.data.iptv.IptvSourceReceiver -a app.couchcommander.CLEAR_IPTV
 *     adb shell am broadcast -n app.couchcommander/.data.iptv.IptvSourceReceiver -a app.couchcommander.DEBUG_IPTV --es q dortmund [--ez probe true]
 *     adb shell am broadcast -n app.couchcommander/.data.iptv.IptvSourceReceiver -a app.couchcommander.DEBUG_IPTV --es play sportklub
 *     adb shell am broadcast -n app.couchcommander/.data.iptv.IptvSourceReceiver -a app.couchcommander.DEBUG_IPTV --es vod dune [--ez sync true]
 *
 * Results go to logcat under the `Iptv` tag, without URLs.
 */
class IptvSourceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as LauncherApp).container
        when (intent.action) {
            ACTION_SET -> {
                val source = intent.getStringExtra("source")?.let { IptvSource.parse(it, intent.getStringExtra("epg")) }
                if (source == null) { Log.w(TAG, "No valid http(s) URL in the 'source' extra"); return }
                container.iptvSource.set(source)
                Log.i(TAG, "Source set: ${if (source is IptvSource.Xtream) "Xtream panel" else "M3U playlist"}; syncing")
            }
            ACTION_CLEAR -> { container.iptvSource.set(null); Log.i(TAG, "Source cleared") }
            ACTION_SYNC -> Log.i(TAG, "Syncing")
            ACTION_DEBUG -> {
                // --es play <name>: plays the first kept channel with that in its name, to test playback directly.
                intent.getStringExtra("play")?.let { name ->
                    val channel = container.iptv.state.value.channels.firstOrNull { Names.fold(it.name).contains(Names.fold(name)) }
                    if (channel == null) Log.w(TAG, "No kept channel named '$name'")
                    else {
                        Log.i(TAG, "Playing ${channel.name}")
                        val attempt = WatchAttempt(Names.provider(channel, event = false), channel, event = false)
                        context.startActivity(PlayerActivity.intent(context, listOf(attempt), kickoff = null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    return
                }
                // --es vod <name>: the film and series catalogue, and what in it has that in its name; --ez sync true fetches it first.
                intent.getStringExtra("vod")?.let { name ->
                    container.scope.launch {
                        if (intent.getBooleanExtra("sync", false)) container.vod.refresh(container.iptvSource.source.value, java.time.Instant.now(), force = true)
                        container.vod.probe(container.iptvSource.source.value).forEach { Log.i(TAG, "vod probe $it") }
                        val found = container.vod.search(name)
                        val s = container.vod.status.value
                        Log.i(TAG, "vod: ${s.movies} films, ${s.series} series, synced ${s.syncedAtEpochMillis?.let { java.time.Instant.ofEpochMilli(it) } ?: "never"}${s.error?.let { "; failed: $it" }.orEmpty()}")
                        found.forEach { Log.i(TAG, "${it.name} | ${it.category} | tmdb=${it.tmdb} | year=${it.year} | label=${VodNames.label(it)}") }
                        Log.i(TAG, "-- end of vod report")
                    }
                    return
                }
                val query = intent.getStringExtra("q").orEmpty()
                val probe = intent.getBooleanExtra("probe", false)
                container.scope.launch {
                    val lines = withContext(Dispatchers.IO) {
                        IptvDiagnostics.report(
                            query, container.iptv.state.value, container.football.matches.value, container.config.config.value.footballTeams,
                            container.watch.value, container.iptvSource.source.value, probe, IptvHttp(), { Xml.newPullParser() },
                        )
                    }
                    lines.forEach { Log.i(TAG, it) }
                    Log.i(TAG, "-- end of report")
                }
                return
            }
            else -> return
        }
        // A sync can outlast a receiver's time limit, so it runs in the app's scope; the launcher process stays up as Home.
        container.scope.launch { Log.i(TAG, summary(container.syncPlaylist())) }
    }

    companion object {
        const val TAG = "Iptv"
        const val ACTION_SET = "app.couchcommander.SET_IPTV"
        const val ACTION_SYNC = "app.couchcommander.SYNC_IPTV"
        const val ACTION_CLEAR = "app.couchcommander.CLEAR_IPTV"
        const val ACTION_DEBUG = "app.couchcommander.DEBUG_IPTV"

        fun summary(s: IptvState): String = buildString {
            append("${s.sportsChannels} sports channels of ${s.playlistSize}, ${s.channels.size} kept for the followed teams, ")
            append("${s.guideChannels} guide channels matched, ${s.programmes.size} programmes naming followed teams")
            s.guideError?.let { append("; $it") }
            s.error?.let { append("; failed: $it") }
        }
    }
}
