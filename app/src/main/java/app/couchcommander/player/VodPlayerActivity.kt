package app.couchcommander.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.couchcommander.LauncherApp
import app.couchcommander.data.iptv.IptvHttp
import app.couchcommander.data.media.Watching
import app.media.MovieDetails
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
 * watching every few seconds and on leaving; a film played to the end leaves the shelf, an episode goes on to the
 * next one. Back returns to the title page.
 */
class VodPlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var heading: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val played by lazy { (application as LauncherApp).container.played }
    private lateinit var current: VodPlay
    private var started = false

    private val saveTick = object : Runnable {
        override fun run() {
            save()
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        current = intent.getStringExtra(EXTRA_PLAY)?.let { runCatching { json.decodeFromString<VodPlay>(it) }.getOrNull() } ?: return finish()
        view = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 4_000
            setShowPreviousButton(false)
            setShowNextButton(false)
            keepScreenOn = true
        }
        heading = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(36, 20, 36, 20)
            background = GradientDrawable().apply { cornerRadius = 28f; setColor(0xCC0B0B12.toInt()) }
        }
        // The title shows with the controls.
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { heading.visibility = it })
        setContentView(
            FrameLayout(this).apply {
                addView(view)
                addView(heading, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(96, 54, 0, 0) })
            },
        )
        showHeading()
    }

    override fun onStart() {
        super.onStart()
        player = ExoPlayer.Builder(this).setSeekBackIncrementMs(SKIP_MS).setSeekForwardIncrementMs(SKIP_MS).build().also { p ->
            view.player = p
            // Subtitles start off even when the file marks one as default (a 4K Shawshank came with Swedish on);
            // forced ones, for lines in another language, still show. The gear in the controls turns them on.
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setSelectUndeterminedTextLanguage(false)
                .setPreferredAudioLanguage("en")
                .build()
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) started = true
                    if (state == Player.STATE_ENDED) ended()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) save()
                }

                // The message names the failure kind, never the URL (it holds the login).
                override fun onPlayerError(error: PlaybackException) {
                    val why = error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
                    Log.i(TAG, "VOD ${current.version}: $why")
                    Toast.makeText(this@VodPlayerActivity, if (started) "Playback stopped ($why)." else "This version didn't play ($why). Try another one.", Toast.LENGTH_LONG).show()
                    finish()
                }
            })
        }
        load(current)
        handler.postDelayed(saveTick, SAVE_EVERY_MS)
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
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        view.player = null
        // Where it stopped, to carry on from if the activity comes back without its intent being read again.
        player?.let { current = current.copy(startMs = it.currentPosition) }
        player?.release()
        player = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // With the controls hidden, Left and Right skip at once instead of only bringing them up.
        val p = player
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
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(IptvHttp.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val source = DefaultMediaSourceFactory(this).setDataSourceFactory(http).createMediaSource(MediaItem.fromUri(play.url))
        player?.run {
            setMediaSource(source, play.startMs)
            playWhenReady = true
            prepare()
        }
    }

    private fun showHeading() {
        heading.text = listOfNotNull(current.title.title, current.episodeLine, current.version.takeIf { it.isNotBlank() }).joinToString("  ·  ")
        heading.visibility = View.VISIBLE
    }

    /** To the end: the next episode plays, or the title leaves Continue. */
    private fun ended() {
        val next = current.next
        if (next == null) {
            write(null)
            return finish()
        }
        current = next
        write(current, positionMs = 0, durationMs = 0)
        showHeading()
        view.showController()
        load(current)
    }

    /** Where it is now; near the end counts as finished. */
    private fun save() {
        val p = player ?: return
        if (!started) return
        val duration = p.duration.takeIf { it > 0 } ?: 0
        val position = p.currentPosition
        if (duration > 0 && position >= duration * DONE_AT) {
            val next = current.next
            if (next == null) write(null) else write(next, positionMs = 0, durationMs = 0)
            return
        }
        write(current, position, duration)
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
        /** Past this share of its length, a film counts as watched (the credits needn't be sat through). */
        private const val DONE_AT = 0.95
        private val json = Json { ignoreUnknownKeys = true }
        private val io = Executors.newSingleThreadExecutor()

        fun start(context: Context, play: VodPlay) = context.startActivity(intent(context, play))

        fun intent(context: Context, play: VodPlay): Intent =
            Intent(context, VodPlayerActivity::class.java).putExtra(EXTRA_PLAY, json.encodeToString(VodPlay.serializer(), play))
    }
}
