package app.couchcommander.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import app.couchcommander.LauncherApp
import app.iptv.IptvHttp
import app.iptv.IptvSource
import app.iptv.NextEpisode
import app.iptv.VodEpisode
import app.iptv.XtreamVod
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.couchcommander.data.media.Watching
import app.media.MovieDetails
import app.media.trakt.TraktItem
import app.media.trakt.TraktScrobble
import java.util.concurrent.Executors
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One film or episode to play from the panel; [next] is the episode after it, if the panel has one. */
@Serializable
data class VodPlay(
    val title: MovieDetails,
    val version: String,
    val url: String,
    val streamId: String,
    val ext: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val startMs: Long = 0,
    val next: VodPlay? = null,
) {
    /** "S1 E2 · One for the Angels", or null for a film. */
    val episodeLine: String? get() = if (season != null && episode != null) "S$season E$episode" + episodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() else null
}

/**
 * Plays a film or an episode from the IPTV panel full screen, from where it was left. Media3's controls come up
 * with OK or any arrow; Left and Right with them hidden skip back and ahead. The position goes to Continue
 * watching every few seconds and on leaving; a film played to the end leaves the shelf. Back brings up the
 * controls; Back again, with them up, returns to the title page.
 *
 * A series' next episode is looked up while this one plays (when whoever started this didn't know it): the
 * controls' ⏭ plays it at any time, the end of this one goes straight on, and during the credits a "Next episode"
 * button comes up that OK presses. Each episode gets a fresh player, as a start from Continue does: queued in one
 * Media3 playlist, the second episode played on with a black picture and no sound on the Bravia, and preloading it
 * would open a second connection, which panels limited to one stream refuse.
 */
class VodPlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var heading: TextView
    /** "Next episode" during the credits; OK presses it while the controls are hidden. */
    private lateinit var nextButton: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val container by lazy { (application as LauncherApp).container }
    private val played get() = container.played
    /** When this was opened, for the "ready in" log line. */
    private var openedAt = 0L
    private lateinit var current: VodPlay
    private var started = false
    private var stoppedReported = false
    /** Network failures retried for the stream being started; back to 0 once one plays. */
    private var retries = 0
    /** Whether a next episode is known to exist for [current]: until it is, a series isn't taken off Continue. */
    private var next: NextEpisode = NextEpisode.Unknown

    private fun traktItem(play: VodPlay): TraktItem =
        if (play.season != null && play.episode != null) {
            TraktItem.Episode(showTmdb = play.title.id, season = play.season, number = play.episode)
        } else {
            TraktItem.Movie(tmdb = play.title.id)
        }

    private fun scrobble(moment: TraktScrobble, overrideProgress: Float? = null) {
        val p = player
        val dur = p?.duration?.takeIf { it > 0 } ?: 0L
        val prog = overrideProgress ?: if (dur > 0) (p!!.currentPosition.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
        container.traktScrobbler.report(moment, traktItem(current), prog)
    }

    private val creditsTick = object : Runnable {
        override fun run() {
            updateNextButton()
            handler.postDelayed(this, CREDITS_CHECK_MS)
        }
    }

    private val saveTick = object : Runnable {
        override fun run() {
            save()
            publish()
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedAt = SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        current = intent.getStringExtra(EXTRA_PLAY)?.let { runCatching { json.decodeFromString<VodPlay>(it) }.getOrNull() } ?: return finish()
        view = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 4_000
            setShowPreviousButton(false)
            // Media3's ⏭, for a series: it plays the next episode once it's known (see NextAware).
            setShowNextButton(current.season != null)
            keepScreenOn = true
        }
        heading = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(36, 20, 36, 20)
            background = GradientDrawable().apply { cornerRadius = 28f; setColor(0xCC0B0B12.toInt()) }
        }
        nextButton = TextView(this).apply {
            setTextColor(0xFF0B0B12.toInt())
            textSize = 20f
            setPadding(44, 22, 44, 22)
            background = GradientDrawable().apply { cornerRadius = 32f; setColor(Color.WHITE) }
            visibility = View.GONE
        }
        // The title shows with the controls; the Next button gives way to them (their ⏭ does the same).
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener {
            heading.visibility = it
            updateNextButton()
        })
        setContentView(
            FrameLayout(this).apply {
                addView(view)
                addView(heading, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(96, 54, 0, 0) })
                addView(nextButton, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 96, 96) })
            },
        )
        showHeading()
    }

    override fun onStart() {
        super.onStart()
        startPlayer()
        load(current)
        handler.postDelayed(saveTick, SAVE_EVERY_MS)
        handler.post(creditsTick)
    }

    private fun startPlayer() {
        // Starts after 1 s of video rather than Media3's 2.5 s: a panel's VOD streams faster than it plays.
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            START_BUFFER_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        ).build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl).setSeekBackIncrementMs(SKIP_MS).setSeekForwardIncrementMs(SKIP_MS).build().also { p ->
            view.player = NextAware(p)
            // Subtitles start off even when the file marks one as default (a 4K Shawshank came with Swedish on);
            // forced ones, for lines in another language, still show. The gear in the controls turns them on.
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setSelectUndeterminedTextLanguage(false)
                .setPreferredAudioLanguage("en")
                .build()
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && !started && openedAt > 0) {
                        Log.i(TAG, "VOD ready in ${SystemClock.elapsedRealtime() - openedAt} ms")
                        openedAt = 0
                    }
                    if (state == Player.STATE_READY) {
                        started = true
                        retries = 0
                    }
                    // Not from inside the player's own callback: playing the next one releases it.
                    if (state == Player.STATE_ENDED) handler.post { if (current.next != null) playNext() else ended() }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) save()
                    if (isPlaying) {
                        stoppedReported = false
                        scrobble(TraktScrobble.Start)
                    } else if (started && !stoppedReported && player?.playWhenReady == false && player?.playbackState != Player.STATE_ENDED) {
                        scrobble(TraktScrobble.Pause)
                    }
                }

                // Play, pause, seeks, buffering: whatever a phone asking `/now` should see.
                override fun onEvents(player: Player, events: Player.Events) = publish()

                // The message names the failure kind, never the URL (it holds the login).
                override fun onPlayerError(error: PlaybackException) {
                    val why = error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
                    Log.i(TAG, "VOD ${current.version}: $why")
                    // A panel that allows one stream refuses the next for a moment after the last one closed (the
                    // next episode, another title straight after): a network failure before it plays is tried again.
                    if (!started && error.errorCode in IO_ERRORS && retries < MAX_RETRIES) {
                        retries++
                        heading.text = "Starting…"
                        heading.visibility = View.VISIBLE
                        handler.postDelayed({ load(current); showHeading() }, RETRY_MS * retries)
                        return
                    }
                    Toast.makeText(this@VodPlayerActivity, if (started) "Playback stopped ($why)." else "This version didn't play ($why). Try another one.", Toast.LENGTH_LONG).show()
                    finish()
                }
            })
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onStop() {
        save()
        if (started && !stoppedReported) {
            stoppedReported = true
            scrobble(TraktScrobble.Stop)
        }
        LivePlayback.clear(this)
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        view.player = null
        // Where it stopped, to carry on from if the activity comes back without its intent being read again.
        player?.let { current = current.copy(startMs = it.currentPosition) }
        player?.release()
        player = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        // Back in two steps, so one press by accident doesn't end the film: the first brings up the controls (and
        // puts the Next button away) while it keeps playing, the second, with them up, closes the player.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !view.isControllerFullyVisible) {
            if (event.action == KeyEvent.ACTION_UP) view.showController()
            return true
        }
        // OK on the Next button during the credits.
        if (p != null && nextButton.visibility == View.VISIBLE && event.keyCode in CONFIRM_KEYS) {
            if (event.action == KeyEvent.ACTION_UP) playNext()
            return true
        }
        // With the controls hidden, Left and Right skip at once instead of only bringing them up.
        if (p != null && !view.isControllerFullyVisible && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { p.seekBack(); view.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { p.seekForward(); view.showController(); return true }
            }
        }
        // Media3's own pattern: the view gets every key first, so its controls work without holding focus.
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun load(play: VodPlay) {
        started = false
        stoppedReported = false
        player?.run {
            setMediaSource(source(play), play.startMs)
            playWhenReady = true
            prepare()
        }
        queueNext(play)
        nameEpisode(play)
    }

    /**
     * TMDB's name for [play]'s episode, for the heading and Continue: the panel's is often generic ("Épisode 1")
     * or its file's name, and only the title page's episode rows knew TMDB's. Cached, so asking again is free.
     */
    private fun nameEpisode(play: VodPlay) {
        val season = play.season ?: return
        val number = play.episode ?: return
        lifecycleScope.launch {
            val name = container.media.episodes(play.title, season)?.firstOrNull { it.number == number }?.title?.takeIf { it.isNotBlank() } ?: return@launch
            if (current.streamId != play.streamId || current.episodeTitle == name) return@launch
            current = current.copy(episodeTitle = name)
            heading.text = headingText()
        }
    }

    private fun source(play: VodPlay): MediaSource {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(IptvHttp.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        return DefaultMediaSourceFactory(this).setDataSourceFactory(http).createMediaSource(MediaItem.fromUri(play.url))
    }

    /** [play]'s next episode: the one whoever started this knew, else looked up on the panel while [play] plays. */
    private fun queueNext(play: VodPlay) {
        next = if (play.season == null) NextEpisode.Last else NextEpisode.Unknown
        play.next?.let { return queue(it) }
        if (play.season == null) return
        val src = container.iptvSource.source.value as? IptvSource.Xtream ?: return
        lifecycleScope.launch {
            val askedAt = SystemClock.elapsedRealtime()
            val found = container.vod.nextEpisode(src, play.title, play.streamId)
            Log.i(TAG, "VOD next after ${play.episodeLine}: ${found.javaClass.simpleName} in ${SystemClock.elapsedRealtime() - askedAt} ms")
            // Only if it's still the same episode on screen.
            if (current.streamId != play.streamId) return@launch
            when (found) {
                is NextEpisode.Found -> {
                    val ep = found.episode
                    queue(VodPlay(play.title, play.version, XtreamVod.episodeStreamUrl(src, ep), ep.id, ep.ext, ep.season, ep.number, ep.title))
                }
                else -> next = found
            }
        }
    }

    private fun queue(after: VodPlay) {
        current = current.copy(next = after)
        next = NextEpisode.Found(VodEpisode(after.streamId, after.season ?: 0, after.episode ?: 0, after.episodeTitle.orEmpty(), after.ext))
        // Bound again, so the controls see that ⏭ can be used now.
        player?.let { view.player = NextAware(it) }
    }

    /** On to the next episode, in a fresh player: from ⏭, the Next button, or the end of this one. */
    private fun playNext() {
        val after = current.next ?: return
        if (!stoppedReported) {
            stoppedReported = true
            scrobble(TraktScrobble.Stop, overrideProgress = 1f)
        }
        write(after, positionMs = 0, durationMs = 0)
        current = after
        stoppedReported = false
        openedAt = SystemClock.elapsedRealtime()
        view.player = null
        player?.release()
        startPlayer()
        load(current)
        showHeading()
        view.showController()
    }

    /** The player as the controls see it: with ⏭ while a next episode is known, which plays it. */
    private inner class NextAware(player: Player) : ForwardingPlayer(player) {
        private val hasNext get() = current.next != null

        override fun getAvailableCommands(): Player.Commands =
            if (hasNext) super.getAvailableCommands().buildUpon().addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
            else super.getAvailableCommands()

        override fun isCommandAvailable(command: Int): Boolean =
            (hasNext && (command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) || super.isCommandAvailable(command)

        override fun hasNextMediaItem() = hasNext || super.hasNextMediaItem()
        override fun seekToNext() = if (hasNext) playNext() else super.seekToNext()
        override fun seekToNextMediaItem() = if (hasNext) playNext() else super.seekToNextMediaItem()
    }

    /** Shows the Next button in the credits: the last minute, or 5% for long episodes, at most three minutes. */
    private fun updateNextButton() {
        val p = player
        val after = current.next
        val duration = p?.duration?.takeIf { it > 0 } ?: 0
        val credits = (duration * CREDITS_SHARE).toLong().coerceIn(CREDITS_MIN_MS, CREDITS_MAX_MS)
        val show = p != null && after != null && duration > 0 && p.currentPosition >= duration - credits && !view.isControllerFullyVisible
        nextButton.text = "▶  Next episode"
        nextButton.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showHeading() {
        heading.text = headingText()
        heading.visibility = View.VISIBLE
    }

    private fun headingText() = listOfNotNull(current.title.title, current.episodeLine, current.version.takeIf { it.isNotBlank() }).joinToString("  ·  ")

    /** The end with no next episode: the title leaves Continue, unless it's a series whose next couldn't be looked up. */
    private fun ended() {
        if (!stoppedReported) {
            stoppedReported = true
            scrobble(TraktScrobble.Stop, overrideProgress = 1f)
        }
        if (next != NextEpisode.Unknown) write(null)
        finish()
    }

    /** Where it is now; near the end counts as finished. */
    private fun save() {
        val p = player ?: return
        if (!started) return
        val duration = p.duration.takeIf { it > 0 } ?: 0
        val position = p.currentPosition
        if (duration > 0 && position >= duration * DONE_AT) {
            val after = current.next
            when {
                after != null -> write(after, positionMs = 0, durationMs = 0)
                // Not known whether there's more: keep it where it is.
                next == NextEpisode.Unknown -> write(current, position, duration)
                else -> write(null)
            }
            return
        }
        write(current, position, duration)
    }

    /** The player as it is now, for the phone's `/now` (see [LivePlayback]). */
    private fun publish() {
        val p = player ?: return
        LivePlayback.publish(
            LivePlayback.Snapshot(
                owner = this, play = current, positionMs = p.currentPosition, durationMs = p.duration.takeIf { it > 0 } ?: 0,
                playing = p.playWhenReady, advancing = p.isPlaying, atElapsed = SystemClock.elapsedRealtime(),
            ),
        )
    }

    /** [play] at [positionMs], or with null off the shelf; on a background thread, it writes a file. */
    private fun write(play: VodPlay?, positionMs: Long = 0, durationMs: Long = 0) {
        val title = current.title
        val watching = play?.let {
            Watching(it.title, it.version, it.streamId, it.ext, positionMs, durationMs, it.season, it.episode, it.episodeTitle, System.currentTimeMillis())
        }
        io.execute { if (watching == null) played.remove(title) else played.save(watching) }
    }

    companion object {
        private const val TAG = "Iptv"
        private const val EXTRA_PLAY = "play"
        private const val SAVE_EVERY_MS = 15_000L
        private const val SKIP_MS = 10_000L
        private const val START_BUFFER_MS = 1_000
        private const val CREDITS_CHECK_MS = 500L
        private const val MAX_RETRIES = 3
        private const val RETRY_MS = 2_000L
        /** Media3's network error codes (2000-2999): failed to connect, bad HTTP status, timed out… */
        private val IO_ERRORS = 2000..2999
        private const val CREDITS_SHARE = 0.05
        private const val CREDITS_MIN_MS = 60_000L
        private const val CREDITS_MAX_MS = 180_000L
        private val CONFIRM_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)
        /** Past this share of its length, a film counts as watched (the credits needn't be sat through). */
        private const val DONE_AT = 0.95
        private val json = Json { ignoreUnknownKeys = true }
        private val io = Executors.newSingleThreadExecutor()

        fun start(context: Context, play: VodPlay) = context.startActivity(intent(context, play))

        fun intent(context: Context, play: VodPlay): Intent =
            Intent(context, VodPlayerActivity::class.java).putExtra(EXTRA_PLAY, json.encodeToString(VodPlay.serializer(), play))
    }
}
