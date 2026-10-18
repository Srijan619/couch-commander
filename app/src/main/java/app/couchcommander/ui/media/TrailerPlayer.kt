package app.couchcommander.ui.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

enum class TrailerState { Loading, Playing, Paused, Ended, Failed }

/**
 * A YouTube video playing in place, through YouTube's embedded player: no controls, and invisible
 * until it has played for a moment, so whatever sits underneath (the backdrop) shows until then and
 * the video fades in over it. Touches pass through to what's around it.
 *
 * YouTube shows the video's title, its logo and a pause button for the first seconds of play, and
 * again on every pause, restart and stall to buffer. Those are YouTube's to show, so rather than
 * covering them, the video stays hidden until they have gone: it reports [TrailerState.Loading]
 * until then, and hides again whenever YouTube would bring them back.
 *
 * [keys] are tried in order: some trailers can't be embedded, and the next one plays instead.
 * Pauses while the app isn't in the foreground. From garbled's TrailerPlayer.
 */
@Composable
fun TrailerPlayer(
    keys: List<String>,
    playing: Boolean,
    muted: Boolean,
    modifier: Modifier = Modifier,
    /** Starts over at the end; otherwise it fades back out. */
    loop: Boolean = true,
    /** Above 1, crops in: 1.34 hides the black bars of a 2.39:1 trailer filling a 16:9 view. */
    zoom: Float = 1f,
    onStateChange: (TrailerState) -> Unit = {},
) {
    var index by remember(keys) { mutableIntStateOf(0) }
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val report by rememberUpdatedState(onStateChange)

    val key = keys.getOrNull(index)
    if (key == null) {
        LaunchedEffect(keys) { report(TrailerState.Failed) }
        return
    }
    key(key) {
        OneVideo(key, playing && resumed, muted, loop, zoom, modifier) { state ->
            if (state == TrailerState.Failed && index < keys.lastIndex) index++ else report(state)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OneVideo(key: String, playing: Boolean, muted: Boolean, loop: Boolean, zoom: Float, modifier: Modifier, onStateChange: (TrailerState) -> Unit) {
    var ready by remember { mutableStateOf(false) }
    // What YouTube says, and whether its overlay has had time to go since playing (re)started.
    var player by remember { mutableStateOf(TrailerState.Loading) }
    var revealed by remember { mutableStateOf(false) }
    // After a stall YouTube's title goes as soon as the video moves again, so the wait is shorter.
    var stalled by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<WebView?>(null) }
    val looping by rememberUpdatedState(loop)
    val report by rememberUpdatedState(onStateChange)
    // Fades in gently, but hides almost at once: YouTube's title appears the moment the video stalls.
    val alpha by animateFloatAsState(if (revealed) 1f else 0f, tween(durationMillis = if (revealed) 600 else 120), label = "trailer")

    LaunchedEffect(player) {
        revealed = false
        if (player == TrailerState.Playing) {
            delay(if (stalled) AfterStallMs else OverlayGoneMs)
            stalled = false
            revealed = true
        }
    }
    val state = when {
        revealed -> TrailerState.Playing
        player == TrailerState.Playing -> TrailerState.Loading
        else -> player
    }
    LaunchedEffect(state) { report(state) }

    fun update(new: TrailerState) {
        player = new
    }

    // Silent until the picture first shows: the video waits out YouTube's overlay hidden, and its sound shouldn't
    // run ahead. After that it stays on through stalls and restarts.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(revealed) { if (revealed) shown = true }
    LaunchedEffect(ready, playing, muted, shown) {
        val v = view?.takeIf { ready } ?: return@LaunchedEffect
        v.js(if (muted || !shown) "player.mute()" else "player.unMute()")
        v.js(if (playing) "player.playVideo()" else "player.pauseVideo()")
    }

    AndroidView(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        factory = { context ->
            PassThroughWebView(context).apply {
                // AndroidView's default WRAP_CONTENT makes a WebView size its page to the content: here, zero high.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                // A 1920 px wide page (see playerHtml), scaled to fit the view: YouTube picks the quality from the
                // player's size, and at the TV's 2x density a device-width page would make it a 960 px player.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                // YouTube's mobile embed keeps its title and play button over the video whatever the
                // parameters say; the desktop one honours controls=0.
                settings.userAgentString = settings.userAgentString.replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "")
                // The page never goes anywhere; a stray link would otherwise open YouTube inside the card.
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onReady() = post { ready = true }

                        @JavascriptInterface
                        fun onState(code: Int) = post {
                            when (code) {
                                YT_PLAYING -> update(TrailerState.Playing)
                                YT_BUFFERING -> if (player == TrailerState.Playing) {
                                    // A stall before the opening title has gone still waits the full time.
                                    stalled = revealed
                                    update(TrailerState.Loading)
                                }
                                YT_PAUSED -> if (player == TrailerState.Playing) update(TrailerState.Paused)
                                YT_ENDED -> if (looping) {
                                    // Hidden again for the restart, which brings YouTube's overlay back.
                                    update(TrailerState.Loading)
                                    js("player.seekTo(0); player.playVideo()")
                                } else {
                                    update(TrailerState.Ended)
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onError(code: Int) = post { update(TrailerState.Failed) }

                        @JavascriptInterface
                        fun onQuality(quality: String, available: String) {
                            Log.i("Trailer", "$key plays at $quality of $available")
                        }
                    },
                    "Bridge",
                )
                // An https origin, so the embed sends a referrer; YouTube refuses to play without one.
                loadDataWithBaseURL(ORIGIN, playerHtml(key, zoom), "text/html", "utf-8", null)
                view = this
            }
        },
        onRelease = { it.destroy() },
    )
}

/** Only to look at: the remote stays with the Compose content around it, and touches pass through. */
private class PassThroughWebView(context: Context) : WebView(context) {
    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent) = false
}

private fun WebView.js(script: String) = evaluateJavascript("if (window.player && player.playVideo) { $script }", null)

/**
 * Whether trailers may start by themselves: not with Data Saver on, nor with animations turned off
 * in the accessibility settings. The Trailer button still plays one either way.
 */
@Composable
fun rememberAutoplayAllowed(): Boolean {
    val context = LocalContext.current
    return remember {
        val animations = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        val dataSaver = context.getSystemService(ConnectivityManager::class.java)?.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        animations && !dataSaver
    }
}

private const val ORIGIN = "https://app.couchcommander"
/** How long YouTube's title and buttons stay up once playing starts: about 2 s, longer on a slow connection. */
private const val OverlayGoneMs = 4_000L
private const val AfterStallMs = 1_000L
/** The page's width in CSS pixels: a 1080p TV's full width, one CSS pixel to a screen pixel. */
private const val PAGE_WIDTH = 1920
private const val YT_ENDED = 0
private const val YT_PLAYING = 1
private const val YT_PAUSED = 2
private const val YT_BUFFERING = 3

/**
 * The embed, sized to cover the view like ContentScale.Crop, with YouTube's IFrame API attached
 * to it for play, pause and mute. State changes and errors go back through `Bridge`.
 */
private fun playerHtml(key: String, zoom: Float) = """
<!doctype html>
<html><head>
<meta name="viewport" content="width=$PAGE_WIDTH">
<style>
  html, body { margin: 0; height: 100%; overflow: hidden; background: transparent; }
  iframe {
    position: absolute; top: 50%; left: 50%; border: 0; pointer-events: none;
    /* Zoomed by its size, not a transform: YouTube sizes the stream to the player, so a bigger one plays sharper. */
    width: calc(max(100vw, 177.78vh) * $zoom); height: calc(max(100vh, 56.25vw) * $zoom); transform: translate(-50%, -50%);
  }
</style>
</head><body>
<iframe id="player" allow="autoplay; encrypted-media" referrerpolicy="strict-origin-when-cross-origin"
  src="https://www.youtube.com/embed/$key?enablejsapi=1&mute=1&controls=0&disablekb=1&fs=0&iv_load_policy=3&cc_load_policy=0&playsinline=1&rel=0&origin=$ORIGIN"></iframe>
<script>
  var player;
  function onYouTubeIframeAPIReady() {
    player = new YT.Player('player', { events: {
      onReady: function (e) {
        // Muted trailers are scenery here; YouTube's automatic captions would only clutter them.
        try { e.target.unloadModule('captions'); } catch (_) {}
        Bridge.onReady();
      },
      onStateChange: function (e) {
        // Captions can load after onReady, so they're turned off again once playing.
        if (e.data === YT.PlayerState.PLAYING) {
          try { e.target.unloadModule('captions'); } catch (_) {}
          // Not in YouTube's documented API, but its player still takes it: the sharpest stream it has.
          try { e.target.setPlaybackQualityRange('highres', 'highres'); } catch (_) {}
          try { Bridge.onQuality(String(e.target.getPlaybackQuality()), String(e.target.getAvailableQualityLevels())); } catch (_) {}
        }
        Bridge.onState(e.data);
      },
      onError: function (e) { Bridge.onError(e.data); }
    } });
  }
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>
"""
