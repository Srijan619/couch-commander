package app.couchcommander

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.couchcommander.data.iptv.ChannelHealth
import app.couchcommander.domain.Accent
import app.couchcommander.player.PlayerActivity
import app.couchcommander.ui.football.FootballScreen
import app.couchcommander.ui.media.MediaScreen
import app.couchcommander.data.iptv.Names
import app.media.MediaKind
import java.time.ZoneId
import java.util.Locale
import app.couchcommander.system.HomeButtonService
import app.couchcommander.system.Screensaver
import app.couchcommander.ui.rememberFootballLines
import app.couchcommander.domain.AppOrder
import app.couchcommander.ui.settings.PlaylistPanel
import app.couchcommander.ui.settings.LeaguesPanel
import app.couchcommander.ui.settings.TeamsPanel
import app.couchcommander.ui.home.AppMenu
import app.couchcommander.ui.home.FocusRequest
import app.couchcommander.ui.home.HomeScreen
import app.couchcommander.ui.picker.AppPicker
import app.couchcommander.ui.settings.SettingsPanel
import app.couchcommander.ui.theme.CouchTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val DRIFT_IDLE_MS = 8_000L

class MainActivity : ComponentActivity() {
    /** Home pressed while the launcher is already in front. */
    private val homePresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LauncherApp).container
        setContent {
            CouchTheme { LauncherRoot(container, homePresses) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homePresses.tryEmit(Unit)
    }
}

@Composable
private fun LauncherRoot(container: AppContainer, homePresses: MutableSharedFlow<Unit>) {
    val context = LocalContext.current
    val config by container.config.config.collectAsStateWithLifecycle()
    val allApps by container.apps.apps.collectAsStateWithLifecycle()
    val frame by container.art.frame.collectAsStateWithLifecycle()
    val accent = Color(frame?.accent ?: Accent.FALLBACK)

    val visible = remember(allApps, config.apps) { AppOrder.shown(allApps, config.apps, key = { it.key }) }

    // Any key press pauses the painting's drift until the remote has been idle for a while (see ArtBackdrop).
    val keyTick = remember { mutableIntStateOf(0) }
    val idle by produceState(true) {
        snapshotFlow { keyTick.intValue }.collectLatest {
            value = false
            delay(DRIFT_IDLE_MS)
            value = true
        }
    }

    // Re-read on every resume: the user turns the Home button service on in system settings and comes back.
    var homeButton by remember { mutableStateOf(HomeButtonService.isEnabled(context)) }
    var screensaverOurs by remember { mutableStateOf(Screensaver.isOurs(context)) }
    val inputs by container.inputs.inputs.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        homeButton = HomeButtonService.isEnabled(context)
        screensaverOurs = Screensaver.isOurs(context)
        container.inputs.refresh()
        onPauseOrDispose { }
    }

    var menuFor by remember { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var choosingApps by rememberSaveable { mutableStateOf(false) }
    var teamsOpen by rememberSaveable { mutableStateOf(false) }
    var footballOpen by rememberSaveable { mutableStateOf(false) }
    var moviesOpen by rememberSaveable { mutableStateOf(false) }
    var playlistOpen by rememberSaveable { mutableStateOf(false) }
    var leaguesOpen by rememberSaveable { mutableStateOf(false) }

    val matches by container.football.matches.collectAsStateWithLifecycle()
    val leagueMatches by container.leagues.matches.collectAsStateWithLifecycle()
    val football = rememberFootballLines(matches, config.footballTeams)
    val watch by container.watch.collectAsStateWithLifecycle()
    val playlist by container.iptvSource.source.collectAsStateWithLifecycle()
    val iptvState by container.iptv.state.collectAsStateWithLifecycle()
    val syncing by container.iptv.syncing.collectAsStateWithLifecycle()
    val vodStatus by container.vod.status.collectAsStateWithLifecycle()
    val channelHealth by container.channelHealth.records.collectAsStateWithLifecycle()
    // Nothing is on home until the user has picked, so the picker opens by itself on first run.
    val pickerOpen = choosingApps || !config.onboarded
    var focusRequest by remember { mutableStateOf(FocusRequest(null, 0)) }
    fun focus(key: String?) {
        focusRequest = FocusRequest(key, focusRequest.nonce + 1)
    }

    LaunchedEffect(Unit) {
        homePresses.collect {
            menuFor = null
            settingsOpen = false
            teamsOpen = false
            footballOpen = false
            moviesOpen = false
            playlistOpen = false
            leaguesOpen = false
            if (container.config.config.value.onboarded) choosingApps = false
            focus(null)
        }
    }

    // Home never leaves the launcher; the overlays below register their own Back handlers on top of this one.
    BackHandler { }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                keyTick.intValue++
                // The Menu key, on remotes that have one, toggles settings.
                if (e.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_MENU || menuFor != null || pickerOpen || teamsOpen || footballOpen || moviesOpen || playlistOpen || leaguesOpen) return@onPreviewKeyEvent false
                if (e.nativeKeyEvent.action == KeyEvent.ACTION_UP) {
                    settingsOpen = !settingsOpen
                    if (!settingsOpen) focus(null)
                }
                true
            },
    ) {
        val footballShown = footballOpen && !teamsOpen && !pickerOpen && !playlistOpen && !leaguesOpen
        // League scores are kept live only while someone is looking at the page.
        LaunchedEffect(footballShown) { container.footballPageOpen.value = footballShown }
        // 0: the football page waits off the right edge, 1: it covers home.
        val slide by animateFloatAsState(
            if (footballShown) 1f else 0f, tween(if (footballShown) 380 else 300, easing = FastOutSlowInEasing), label = "football",
        )
        val moviesShown = moviesOpen && !pickerOpen
        LaunchedEffect(moviesShown) { container.mediaPageOpen.value = moviesShown }
        // 0: Movies and TV waits off the left edge, 1: it covers home.
        val moviesSlide by animateFloatAsState(
            if (moviesShown) 1f else 0f, tween(if (moviesShown) 380 else 300, easing = FastOutSlowInEasing), label = "movies",
        )
        // Once a page has slid fully over home, home isn't drawn at all (alpha 0 skips its layer).
        Box(Modifier.graphicsLayer { alpha = if (slide == 1f || moviesSlide == 1f) 0f else 1f }) {
        HomeScreen(
            apps = visible,
            frame = frame,
            football = football,
            // The picker and the football page cover home; panning the hidden painting would only slow them down.
            drift = idle && !pickerOpen && !footballOpen && !moviesOpen,
            interactive = menuFor == null && !settingsOpen && !pickerOpen && !teamsOpen && !footballOpen && !moviesOpen && !playlistOpen && !leaguesOpen,
            focusRequest = focusRequest,
            onLaunch = container.apps::launch,
            onAppMenu = { menuFor = it.key },
            onOpenSettings = { settingsOpen = true },
            // With no teams followed there's nothing to show yet: pick some first.
            onOpenFootball = { if (config.footballTeams.isEmpty()) teamsOpen = true else footballOpen = true },
            onOpenMovies = { moviesOpen = true },
            dockHideSeconds = config.dockHideSeconds,
            alwaysShowApps = config.alwaysShowApps,
        )
        }

        val menuApp = visible.firstOrNull { it.key == menuFor }
        if (menuApp != null) {
            val index = visible.indexOf(menuApp)
            fun close(focusKey: String?) {
                menuFor = null
                focus(focusKey)
            }
            AppMenu(
                app = menuApp,
                accent = accent,
                canMoveLeft = index > 0,
                canMoveRight = index < visible.lastIndex,
                onOpen = {
                    close(menuApp.key)
                    container.apps.launch(menuApp)
                },
                onMove = { delta ->
                    container.config.update { it.copy(apps = AppOrder.move(visible.map { a -> a.key }, menuApp.key, delta)) }
                },
                onHide = {
                    val neighbour = visible.getOrNull(index + 1) ?: visible.getOrNull(index - 1)
                    container.config.update { it.copy(apps = it.apps - menuApp.key) }
                    close(neighbour?.key)
                },
                onDismiss = { close(menuApp.key) },
            )
        }

        if (settingsOpen && !pickerOpen && !teamsOpen && !playlistOpen && !leaguesOpen) {
            SettingsPanel(
                config = config,
                inputs = inputs,
                frame = frame,
                onOpenInput = { input ->
                    settingsOpen = false
                    container.inputs.open(input)
                },
                onAndroidSettings = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                },
                appCount = visible.size,
                accent = accent,
                onUpdate = container.config::update,
                onNextPainting = container.art::next,
                onChooseApps = { choosingApps = true },
                onFootball = { teamsOpen = true },
                onLeagues = { leaguesOpen = true },
                playlist = when {
                    playlist == null -> "Not set"
                    syncing -> "Syncing…"
                    iptvState.error != null && iptvState.channels.isEmpty() -> "Failed"
                    else -> "${iptvState.sportsChannels} channels"
                },
                onPlaylist = { playlistOpen = true },
                homeButton = homeButton,
                onHomeButton = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure {
                        Toast.makeText(context, "No accessibility settings on this TV. See the README for the adb command.", Toast.LENGTH_LONG).show()
                    }
                },
                screensaverOurs = screensaverOurs,
                onScreensaver = {
                    when {
                        // Google TV's settings can't pick this one, so the first press sets it here.
                        !screensaverOurs && Screensaver.useOurs(context) -> { screensaverOurs = true }
                        !screensaverOurs -> Toast.makeText(
                            context, "Google TV hides this screensaver. Run scripts/set-home.sh once, or see the README.", Toast.LENGTH_LONG,
                        ).show()
                        // Already ours: the system page is where you switch back to Google's.
                        else -> runCatching { context.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) }.onFailure {
                            Toast.makeText(context, "No screensaver settings on this TV. See the README for the adb command.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onDismiss = {
                    settingsOpen = false
                    focus(null)
                },
            )
        }

        // Kept composed while hidden (alpha 0 isn't drawn), so opening it costs a slide, not a ~95 ms build.
        if (config.footballTeams.isNotEmpty()) Box(
            Modifier.graphicsLayer {
                translationX = size.width * (1f - slide)
                alpha = if (slide == 0f) 0f else 1f
            },
        ) {
            FootballScreen(
                active = footballShown,
                frame = frame,
                accent = accent,
                matches = matches,
                teams = config.footballTeams,
                leagues = config.footballLeagues,
                leagueMatches = leagueMatches,
                watch = watch,
                hasPlaylist = playlist != null,
                popular = iptvState.popular,
                mostWatched = remember(channelHealth, iptvState) { ChannelHealth.mostWatched(channelHealth, iptvState.popular + iptvState.channels) },
                onPlayChannel = { attempts -> PlayerActivity.start(context, attempts, null) },
                onHideChannel = container.channelHealth::hide,
                onShowHiddenChannels = container.channelHealth::showHidden,
                onAddPlaylist = { playlistOpen = true },
                onChooseLeagues = { leaguesOpen = true },
                health = channelHealth,
                onWatch = { match, attempts -> PlayerActivity.start(context, attempts, match.kickoff) },
                onClose = {
                    footballOpen = false
                    focus(null)
                },
            )
        }

        // Kept composed while hidden, like the football page, so opening it is only a slide.
        Box(
            Modifier.graphicsLayer {
                translationX = -size.width * (1f - moviesSlide)
                alpha = if (moviesSlide == 0f) 0f else 1f
            },
        ) {
            val movies by container.media.shelves(MediaKind.Movie).collectAsStateWithLifecycle()
            val tv by container.media.shelves(MediaKind.Tv).collectAsStateWithLifecycle()
            MediaScreen(
                active = moviesShown,
                frame = frame,
                accent = accent,
                movies = movies,
                tv = tv,
                media = container.media,
                account = container.tmdb,
                played = container.played,
                country = remember { Names.homeCountry(ZoneId.systemDefault().id, Locale.getDefault().country) },
                onClose = {
                    moviesOpen = false
                    focus(null)
                },
            )
        }

        if (playlistOpen) {
            PlaylistPanel(
                source = playlist,
                state = iptvState,
                syncing = syncing,
                vod = vodStatus,
                accent = accent,
                onSave = container::setPlaylist,
                onSync = {
                    container.scope.launch { container.syncPlaylist() }
                    container.scope.launch { container.vod.refresh(container.iptvSource.source.value, java.time.Instant.now(), force = true) }
                },
                onRemove = { container.setPlaylist(null) },
                hiddenChannels = channelHealth.count { it.value.hidden },
                onShowHidden = container.channelHealth::showHidden,
                onDismiss = { playlistOpen = false },
            )
        }

        if (teamsOpen) {
            TeamsPanel(
                followed = config.footballTeams,
                accent = accent,
                search = container.football::searchTeams,
                onFollow = { team -> container.config.update { it.copy(footballTeams = it.footballTeams + team) } },
                onUnfollow = { team -> container.config.update { it.copy(footballTeams = it.footballTeams.filterNot { t -> t.id == team.id }) } },
                onDismiss = { teamsOpen = false },
            )
        }

        if (leaguesOpen) {
            LeaguesPanel(
                followed = config.footballLeagues,
                accent = accent,
                search = container.leagues::searchLeagues,
                onAdd = { league -> container.config.update { it.copy(footballLeagues = it.footballLeagues + league) } },
                onRemove = { league -> container.config.update { it.copy(footballLeagues = it.footballLeagues.filterNot { l -> l.id == league.id }) } },
                onDismiss = { leaguesOpen = false },
            )
        }

        if (pickerOpen) {
            AppPicker(
                apps = allApps,
                picked = config.apps,
                accent = accent,
                firstRun = !config.onboarded,
                onToggle = { app -> container.config.update { it.copy(apps = AppOrder.toggle(it.apps, app.key)) } },
                onDone = {
                    container.config.update { it.copy(onboarded = true) }
                    choosingApps = false
                    settingsOpen = false
                    focus(null)
                },
            )
        }
    }
}
