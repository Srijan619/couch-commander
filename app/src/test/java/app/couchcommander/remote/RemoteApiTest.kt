package app.couchcommander.remote

import app.couchcommander.player.LivePlayback
import app.football.FootballLeague
import app.football.FootballTeam
import app.iptv.VodPlay
import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RemoteApiTest {
    private val played = mutableListOf<PlayRequest>()
    private val library = object : Library {
        override suspend fun has(ref: TitleRef, withEpisodes: Boolean) =
            if (ref.tmdb == 603) Availability(true, listOf("EN 4K", "EN")) else Availability(false)
        override suspend fun play(request: PlayRequest): Result<Played> {
            if (request.tmdb != 603) return Result.failure(RemoteFailure(404, "Not on the panel"))
            played += request
            return Result.success(Played("The Matrix", request.version ?: "EN 4K"))
        }
        override fun continueWatching() = listOf(ContinueItem(603, "movie", "The Matrix", version = "EN 4K", positionMs = 60_000, durationMs = 8_160_000, atEpochMillis = 1))
        override fun removeContinue(ref: TitleRef) = ref.tmdb == 603
        override fun now() = nowPlaying
        override fun traktUser() = traktUser
        override fun football() = picks
        override fun matchFeeds(match: Long, provider: String?) =
            MatchFeeds(if (match == 4_500_001L) listOf(ChannelTile("Sports|beIN 1", provider ?: "Ruutu FI", "FR · FHD")) else emptyList())
        override fun footballChannels() = FootballChannels(
            emptyList(),
            listOf(ChannelTile("Sports|V Sport Jalkapallo", "V Sport Jalkapallo", "FI · FHD")),
            listOf(ChannelBrand("Sky Sports", listOf(ChannelTile("UK|Sky Sports Main Event FHD", "Sky Sports Main Event", "UK · FHD", live = true)))),
        )
        override fun playChannel(request: PlayChannel): Result<PlayingChannel> =
            if (request.key == "Sports|V Sport Jalkapallo") Result.success(PlayingChannel(request.label ?: "V Sport Jalkapallo"))
            else Result.failure(RemoteFailure(404, "That channel isn't in the TV's playlist any more"))
        override fun watchMatch(request: WatchMatch): Result<WatchingMatch> {
            if (request.match != 4_500_001L) return Result.failure(RemoteFailure(404, "No channel on the TV shows this match"))
            watched += request
            return Result.success(WatchingMatch("HJK – KuPS", request.provider ?: "Ruutu FI"))
        }
    }
    private var traktUser: String? = null
    private var picks = FootballPicks(emptyList(), emptyList())
    private val watched = mutableListOf<WatchMatch>()
    private var nowPlaying = Now()
    private val tokens = RemoteTokens(Files.createTempDirectory("remote").toFile())
    private val pairing = Pairing(tokens)
    private var shown = 0
    private val api = RemoteApi(library, pairing, tokens, { "Living room" }) { shown++ }

    private suspend fun pair(): String {
        assertEquals(202, api.handle(Request("POST", "/pair", body = """{"name":"Pixel"}""")).status)
        val code = pairing.request.value!!.code
        val r = api.handle(Request("POST", "/pair/confirm", body = """{"code":"$code"}"""))
        assertEquals(200, r.status)
        return Json.parseToJsonElement(r.body).jsonObject["token"]!!.jsonPrimitive.content
    }

    @Test fun infoNeedsNoToken() = runTest {
        val r = api.handle(Request("GET", "/info"))
        assertEquals(200, r.status)
        assertTrue(r.body.contains("Living room"))
    }

    @Test fun traktGivesOnlyTheUsername() = runTest {
        assertEquals(401, api.handle(Request("GET", "/trakt")).status)
        val auth = mapOf("authorization" to "Bearer ${pair()}")
        assertEquals("{}", api.handle(Request("GET", "/trakt", headers = auth)).body)
        traktUser = "sofa_watcher"
        val r = api.handle(Request("GET", "/trakt", headers = auth))
        assertEquals(200, r.status)
        assertEquals("""{"username":"sofa_watcher"}""", r.body)
    }

    @Test fun footballGivesTheTvsPicks() = runTest {
        assertEquals(401, api.handle(Request("GET", "/football")).status)
        val auth = mapOf("authorization" to "Bearer ${pair()}")
        assertEquals("""{"teams":[],"leagues":[]}""", api.handle(Request("GET", "/football", headers = auth)).body)
        picks = FootballPicks(listOf(FootballTeam(4217, "HJK", "Veikkausliiga")), listOf(FootballLeague(47, "Premier League", "ENG")))
        val r = api.handle(Request("GET", "/football", headers = auth))
        assertEquals(200, r.status)
        assertEquals(
            """{"teams":[{"id":4217,"name":"HJK","league":"Veikkausliiga"}],"leagues":[{"id":47,"name":"Premier League","country":"ENG"}]}""",
            r.body,
        )
    }

    @Test fun footballGivesEachMatchsChannels() = runTest {
        val auth = mapOf("authorization" to "Bearer ${pair()}")
        picks = FootballPicks(emptyList(), emptyList(), mapOf(4_500_001L to listOf("Ruutu FI", "beIN")))
        assertEquals(
            """{"teams":[],"leagues":[],"channels":{"4500001":["Ruutu FI","beIN"]}}""",
            api.handle(Request("GET", "/football", headers = auth)).body,
        )
    }

    @Test fun watchesAMatchOnTheTv() = runTest {
        assertEquals(401, api.handle(Request("POST", "/football/watch", body = """{"match":4500001}""")).status)
        val auth = mapOf("authorization" to "Bearer ${pair()}")
        val r = api.handle(Request("POST", "/football/watch", headers = auth, body = """{"match":4500001,"provider":"beIN"}"""))
        assertEquals(200, r.status)
        assertEquals("""{"match":"HJK – KuPS","channel":"beIN"}""", r.body)
        assertEquals(listOf(WatchMatch(4_500_001L, "beIN")), watched)
        val missing = api.handle(Request("POST", "/football/watch", headers = auth, body = """{"match":1}"""))
        assertEquals(404, missing.status)
        assertTrue("No channel" in missing.body)
    }

    @Test fun channelsListAndPlay() = runTest {
        assertEquals(401, api.handle(Request("GET", "/football/channels")).status)
        val auth = mapOf("authorization" to "Bearer ${pair()}")
        val list = api.handle(Request("GET", "/football/channels", headers = auth))
        assertEquals(200, list.status)
        assertTrue(""""live":true""" in list.body)
        assertTrue(""""mostWatched":[{"key":"Sports|V Sport Jalkapallo""" in list.body)
        val r = api.handle(Request("POST", "/channels/play", headers = auth, body = """{"key":"Sports|V Sport Jalkapallo"}"""))
        assertEquals("""{"channel":"V Sport Jalkapallo"}""", r.body)
        assertEquals(404, api.handle(Request("POST", "/channels/play", headers = auth, body = """{"key":"gone"}""")).status)
    }

    @Test fun matchFeedsInTryOrder() = runTest {
        val auth = mapOf("authorization" to "Bearer ${pair()}")
        val r = api.handle(Request("GET", "/football/feeds", query = mapOf("match" to "4500001", "provider" to "beIN"), headers = auth))
        assertEquals("""{"channels":[{"key":"Sports|beIN 1","name":"beIN","detail":"FR · FHD"}]}""", r.body)
        assertEquals(400, api.handle(Request("GET", "/football/feeds", headers = auth)).status)
    }

    @Test fun unpairedIsRefused() = runTest {
        assertEquals(401, api.handle(Request("GET", "/continue")).status)
        assertEquals(401, api.handle(Request("GET", "/continue", headers = mapOf("authorization" to "Bearer nope"))).status)
    }

    @Test fun pairsAndPlays() = runTest {
        val auth = mapOf("authorization" to "Bearer ${pair()}")
        assertEquals(1, shown)
        assertNull(pairing.request.value)
        val has = api.handle(Request("GET", "/has", mapOf("tmdb" to "603", "type" to "movie"), auth))
        assertTrue(has.body.contains("EN 4K"))
        val play = api.handle(Request("POST", "/play", headers = auth, body = """{"tmdb":603,"type":"movie","version":"EN"}"""))
        assertEquals(200, play.status)
        assertEquals("EN", played.single().version)
        assertEquals(404, api.handle(Request("POST", "/play", headers = auth, body = """{"tmdb":1,"type":"movie"}""")).status)
        assertEquals(400, api.handle(Request("POST", "/play", headers = auth, body = """{"tmdb":603,"type":"song"}""")).status)
        assertTrue(api.handle(Request("GET", "/continue", headers = auth)).body.contains("\"positionMs\":60000"))
        assertTrue(api.handle(Request("DELETE", "/continue", mapOf("tmdb" to "603", "type" to "movie"), auth)).body.contains("true"))
    }

    @Test fun nowSaysWhatPlays() = runTest {
        val auth = mapOf("authorization" to "Bearer ${pair()}")
        assertEquals(401, api.handle(Request("GET", "/now")).status)
        assertEquals("""{"playing":false}""", api.handle(Request("GET", "/now", headers = auth)).body)
        nowPlaying = Now(true, ContinueItem(1399, "tv", "Game of Thrones", version = "EN", positionMs = 90_000, durationMs = 3_600_000, season = 1, episode = 2, atEpochMillis = 5))
        val body = Json.parseToJsonElement(api.handle(Request("GET", "/now", headers = auth)).body).jsonObject
        assertEquals("true", body["playing"]!!.jsonPrimitive.content)
        assertEquals("90000", body["item"]!!.jsonObject["positionMs"]!!.jsonPrimitive.content)
    }

    @Test fun livePositionMovesOnlyWhilePlaying() {
        val play = VodPlay(app.media.MovieDetails(603, "The Matrix", 1999, 0.0), "EN", "u", "1", "mkv")
        val s = LivePlayback.Snapshot(this, play, positionMs = 10_000, durationMs = 20_000, playing = true, advancing = true, atElapsed = 1_000)
        assertEquals(15_000, s.positionAt(6_000))
        assertEquals(20_000, s.positionAt(60_000))
        assertEquals(10_000, s.copy(advancing = false).positionAt(6_000))
    }

    @Test fun wrongCodesEndPairing() = runTest {
        api.handle(Request("POST", "/pair", body = """{"name":"Pixel"}"""))
        val code = pairing.request.value!!.code
        val wrong = if (code == "0000") "1111" else "0000"
        repeat(Pairing.MAX_ATTEMPTS) { assertEquals(403, api.handle(Request("POST", "/pair/confirm", body = """{"code":"$wrong"}""")).status) }
        assertNull(pairing.request.value)
        assertEquals(403, api.handle(Request("POST", "/pair/confirm", body = """{"code":"$code"}""")).status)
    }

    @Test fun readsARequest() {
        val raw = "POST /play?x=a%20b HTTP/1.1\r\nHost: tv\r\nAuthorization: Bearer abc\r\nContent-Length: 2\r\n\r\n{}"
        val r = MiniHttp.read(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("POST", r.method)
        assertEquals("/play", r.path)
        assertEquals("a b", r.query["x"])
        assertEquals("abc", r.bearer)
        assertEquals("{}", r.body)
    }
}
