package app.couchcommander

import android.app.Application
import android.util.Xml
import app.couchcommander.data.ConfigRepository
import app.couchcommander.data.apps.AppRepository
import app.couchcommander.data.art.ArtRepository
import app.couchcommander.data.inputs.InputRepository
import app.couchcommander.data.iptv.ChannelHealthStore
import app.couchcommander.data.iptv.ChannelLogos
import app.couchcommander.data.iptv.IptvRepository
import app.couchcommander.data.iptv.IptvSource
import app.couchcommander.data.iptv.IptvState
import app.couchcommander.data.iptv.IptvSourceStore
import app.couchcommander.data.iptv.WatchFinder
import app.couchcommander.data.iptv.VodRepository
import app.couchcommander.data.iptv.WatchOption
import app.couchcommander.data.media.KeystoreSessionStore
import app.couchcommander.data.media.MediaRepository
import app.couchcommander.data.media.ContinueWatching
import app.couchcommander.data.media.RemoteImages
import app.couchcommander.data.media.TmdbAccount
import app.media.MediaKind
import app.media.TmdbAccountRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import app.football.FootballRepository
import app.football.FootballTeam
import app.football.LeagueRepository
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hand-written dependency container, shared by the launcher and the screensaver (same process). */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val config = ConfigRepository(app.filesDir, scope)
    val apps = AppRepository(app, scope)
    val art = ArtRepository(app, config.config, scope)
    val football = FootballRepository(app.filesDir)
    val leagues = LeagueRepository(app.filesDir)
    /** True while the football page is on screen: league scores are only kept live for someone looking. */
    val footballPageOpen = MutableStateFlow(false)
    val inputs = InputRepository(app)
    val iptvSource = IptvSourceStore(app.filesDir)
    val iptv = IptvRepository(app.filesDir, newParser = { Xml.newPullParser() })
    /** The panel's films and series, for Play on the Movies and TV page. */
    val vod = VodRepository(app.filesDir, onLoaded = { ms -> android.util.Log.i("Iptv", "vod catalogue loaded in $ms ms") })
    val channelHealth = ChannelHealthStore(app.filesDir)
    val logos = ChannelLogos(app.cacheDir)
    /** The proxy sleeps when idle and can take a minute to answer the first request after. */
    private val http = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
        }
    }
    val media = MediaRepository(app.filesDir, http, BuildConfig.STORE_URL)
    val images = RemoteImages(app.cacheDir)
    /** Films and series played lately, for the Movies and TV page's Continue shelf. */
    val played = ContinueWatching(app.filesDir)
    /** The user's TMDB account (watchlist, favourites, ratings), signed in from a phone. */
    val tmdb = TmdbAccount(TmdbAccountRepository(http, BuildConfig.STORE_URL), KeystoreSessionStore(app.filesDir), scope)
    /** True while the Movies and TV page is on screen: its shelves are refreshed then if they're old. */
    val mediaPageOpen = MutableStateFlow(false)

    /** Where each upcoming match of a followed team or league is on, by match id; empty without a playlist. */
    val watch: StateFlow<Map<Long, List<WatchOption>>> =
        combine(football.matches, leagues.matches, iptv.state) { matches, byLeague, state ->
            // A league page holds two months of fixtures; only the next couple of days can be in the guide's window.
            val soon = Instant.now().plus(LEAGUE_WATCH_AHEAD)
            val leagueMatches = byLeague.values.flatten().filter { it.kickoff.isBefore(soon) }
            WatchFinder.find((matches + leagueMatches).distinctBy { it.id }, state.channels, state.programmes)
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The guide is searched for the followed teams and every team in the followed leagues. */
    private val guideTeams: Flow<List<FootballTeam>> =
        combine(config.config.map { it.footballTeams }, leagues.teams) { own, inLeagues -> (own + inLeagues).distinctBy { it.id } }
            .distinctUntilChanged()

    /** Sets (or with null, forgets) the playlist and syncs straight away; from settings and the adb receiver. */
    fun setPlaylist(source: IptvSource?) {
        scope.launch {
            withContext(Dispatchers.IO) { iptvSource.set(source) }
            syncPlaylist()
        }
    }

    suspend fun syncPlaylist(): IptvState =
        iptv.refresh(iptvSource.source.value, (config.config.value.footballTeams + leagues.teams.value).distinctBy { it.id }, Instant.now(), force = true)

    init {
        // Both pages' shelves at start and each time the page opens or closes; the repository only fetches them
        // when they're more than a few hours old.
        scope.launch {
            mediaPageOpen.collect { MediaKind.entries.forEach { kind -> launch { media.refresh(kind, Instant.now()) } } }
        }
        // The account's watchlist and favourites each time the page opens while signed in (a phone or another app
        // may have changed them), and as soon as the saved session is read.
        scope.launch {
            tmdb.load()
            combine(mediaPageOpen, tmdb.session) { open, session -> open && session != null }.collectLatest { refresh ->
                if (refresh) MediaKind.entries.forEach { kind -> launch { tmdb.refreshLists(kind) } }
            }
        }
        // Checks once a minute; the repository only goes to the network when something is due (fixtures every few
        // hours, the live score each minute while a match is on), so this costs nothing between matches.
        scope.launch {
            config.config.map { it.footballTeams }.distinctUntilChanged().collectLatest { teams ->
                if (teams.isEmpty()) return@collectLatest
                while (true) {
                    football.refresh(teams, Instant.now())
                    delay(FOOTBALL_TICK_MS)
                }
            }
        }
        // League pages every 12 hours; while the football page is open, the day's scores each minute when a
        // league match is on. With no leagues, one pass forgets the old ones (and their teams in the guide).
        scope.launch {
            val followed = config.config.map { it.footballLeagues }.distinctUntilChanged()
            combine(followed, footballPageOpen) { l, open -> l to open }.collectLatest { (l, open) ->
                while (true) {
                    leagues.refresh(l, Instant.now(), ZoneId.systemDefault(), live = open)
                    if (l.isEmpty()) break
                    delay(FOOTBALL_TICK_MS)
                }
            }
        }
        // The repository syncs only when due (twice a day, or when the source or teams change); a team change
        // matters because only guide entries naming the followed teams (and the followed leagues' teams) are kept.
        scope.launch {
            combine(iptvSource.source, guideTeams) { source, t -> source to t }.collectLatest { (source, t) ->
                while (true) {
                    iptv.refresh(source, t, Instant.now())
                    if (source == null) break
                    delay(IPTV_TICK_MS)
                }
            }
        }
        // The film and series catalogue once a day; it only changes with the playlist, not with the teams.
        scope.launch {
            iptvSource.source.collectLatest { source ->
                while (true) {
                    vod.refresh(source, Instant.now())
                    if (source == null) break
                    delay(IPTV_TICK_MS)
                }
            }
        }
    }
}

private const val FOOTBALL_TICK_MS = 60_000L
private const val IPTV_TICK_MS = 30 * 60_000L
private val LEAGUE_WATCH_AHEAD: Duration = Duration.ofDays(2)

class LauncherApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
