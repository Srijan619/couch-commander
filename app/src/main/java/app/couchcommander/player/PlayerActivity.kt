package app.couchcommander.player

import android.app.AlertDialog
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
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.couchcommander.LauncherApp
import app.iptv.ChannelHealth
import app.iptv.IptvChannel
import app.iptv.IptvHttp
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One channel to try, with the chip's short name for the "trying…" line. */
@Serializable
data class WatchAttempt(val label: String, val channel: IptvChannel, val event: Boolean)

/**
 * Plays a match full screen. Given every channel showing it, best first, it falls back by itself: a channel that
 * errors, or hasn't started within [START_TIMEOUT_MS], gives way to the next ("Viaplay FI didn't start, trying
 * Viaplay NL…"). Each result goes to the channel health record, which orders the chips next time. Hold OK, or press
 * Menu, to hide a channel that plays but shows nothing useful. No controls otherwise (it's live); Back returns.
 */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val health by lazy { (application as LauncherApp).container.channelHealth }

    private var attempts: List<WatchAttempt> = emptyList()
    private var kickoff: Instant? = null
    private var index = 0
    /** True once the current channel has played, so a later drop retries it once before moving on. */
    private var started = false
    private var retried = false
    /** When the current channel last went to "playing" (elapsed realtime), or 0 while it isn't. */
    private var playingSince = 0L

    private val startTimeout = Runnable { fail("didn't start") }
    private val hideStatus = Runnable { status.animate().alpha(0f).setDuration(300).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        attempts = intent.getStringExtra(EXTRA_ATTEMPTS)?.let { runCatching { json.decodeFromString<List<WatchAttempt>>(it) }.getOrNull() }.orEmpty()
        kickoff = intent.getLongExtra(EXTRA_KICKOFF, -1L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        view = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(36, 20, 36, 20)
            background = GradientDrawable().apply { cornerRadius = 28f; setColor(0xCC0B0B12.toInt()) }
            alpha = 0f
        }
        setContentView(
            FrameLayout(this).apply {
                addView(view)
                addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(96, 0, 0, 72) })
            },
        )
    }

    override fun onStart() {
        super.onStart()
        if (attempts.isEmpty()) return finish()
        player = ExoPlayer.Builder(this).build().also { p ->
            view.player = p
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) playingSince = SystemClock.elapsedRealtime() else flushWatched()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    Log.i(TAG, "Player ${current.label}: ${when (state) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "playing"; Player.STATE_ENDED -> "ended"; else -> "idle" }}")
                    if (state == Player.STATE_READY && !started) {
                        started = true
                        handler.removeCallbacks(startTimeout)
                        health.played(current.channel)
                    }
                    if (state == Player.STATE_ENDED) fail("stopped")
                }

                // The message names the failure kind, never the URL (it holds the playlist login).
                override fun onPlayerError(error: PlaybackException) = fail(error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' '))
            })
        }
        play(index)
    }

    // Back or Home: silence the stream at once, holding its last frame (detaching the view would show black until
    // the launcher is up). The release, which waits on the decoder, stays in onStop, after the launcher has drawn.
    // Something that only covers the player for a moment (the assistant) gets it back on resume.
    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onStop() {
        flushWatched()
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        view.player = null
        player?.release()
        player = null
    }

    private val current get() = attempts[index]

    private fun play(i: Int) {
        flushWatched()
        index = i
        started = false
        handler.removeCallbacks(startTimeout)
        val a = current
        // Each channel may need its own user agent and referrer, so each gets a fresh media source.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(a.channel.userAgent ?: IptvHttp.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        a.channel.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val source = DefaultMediaSourceFactory(this).setDataSourceFactory(http).createMediaSource(MediaItem.fromUri(a.channel.url))
        player?.run {
            setMediaSource(source)
            playWhenReady = true
            prepare()
        }
        handler.postDelayed(startTimeout, START_TIMEOUT_MS)
    }

    /** This channel didn't work: retry once if it had been playing (live streams drop), else record it and move on. */
    private fun fail(why: String) {
        handler.removeCallbacks(startTimeout)
        val a = current
        if (started && !retried) {
            retried = true
            say("${a.label} dropped, reconnecting…")
            return play(index)
        }
        retried = false
        if (ChannelHealth.counts(a.event, kickoff, Instant.now())) health.failed(a.channel)
        next("${a.label} $why")
    }

    private fun next(reason: String) {
        Log.i(TAG, "Player: $reason")
        val i = index + 1
        if (i >= attempts.size) {
            Toast.makeText(this, if (attempts.size == 1) "$reason." else "None of the ${attempts.size} channels started.", Toast.LENGTH_LONG).show()
            return finish()
        }
        say("$reason, trying ${attempts[i].label}…")
        play(i)
    }

    /** Adds the time the current channel has been playing to its "Most watched" total. */
    private fun flushWatched() {
        if (playingSince == 0L || attempts.isEmpty()) return
        health.watched(current.channel, (SystemClock.elapsedRealtime() - playingSince) / 1000)
        playingSince = 0L
    }

    private fun say(text: String) {
        handler.removeCallbacks(hideStatus)
        status.text = text
        status.animate().alpha(1f).setDuration(200).start()
        handler.postDelayed(hideStatus, 6_000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            event.startTracking()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            askToHide()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            askToHide()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    /** For a channel that plays but is useless (a slate, the wrong match): it's left off the chips from then on. */
    private fun askToHide() {
        val a = current
        AlertDialog.Builder(this)
            .setTitle("Hide ${a.label}?")
            .setMessage("It won't be offered again. Settings › Football › IPTV playlist can show hidden channels again.")
            .setPositiveButton("Hide") { _, _ ->
                health.hide(a.channel)
                next("${a.label} hidden")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val TAG = "Iptv"
        private const val EXTRA_ATTEMPTS = "attempts"
        private const val EXTRA_KICKOFF = "kickoff"
        /** A live channel that hasn't produced a picture by now usually won't. */
        const val START_TIMEOUT_MS = 15_000L
        private val json = Json { ignoreUnknownKeys = true }

        /** [attempts] in the order to try them: the chip that was pressed first, then the rest. */
        fun start(context: Context, attempts: List<WatchAttempt>, kickoff: Instant?) = context.startActivity(intent(context, attempts, kickoff))

        fun intent(context: Context, attempts: List<WatchAttempt>, kickoff: Instant?): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ATTEMPTS, json.encodeToString(attempts))
                .putExtra(EXTRA_KICKOFF, kickoff?.toEpochMilli() ?: -1L)
    }
}
