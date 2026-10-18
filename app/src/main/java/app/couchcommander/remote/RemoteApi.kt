package app.couchcommander.remote

import app.media.MediaKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A title by its TMDB id, as Garbled knows it; [title] and [year] save the TV asking TMDB when it can match by name. */
@Serializable
data class TitleRef(val tmdb: Int, val type: String, val title: String? = null, val year: Int? = null) {
    val kind: MediaKind? get() = kindOf(type)
}

/** Play [tmdb]; a series plays [season]/[episode], else where it was left; [version] is a label from `/has`. */
@Serializable
data class PlayRequest(
    val tmdb: Int,
    val type: String,
    val title: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val version: String? = null,
    /** Where to start, in ms; null carries on from where the TV left it. */
    val startMs: Long? = null,
) {
    val ref get() = TitleRef(tmdb, type, title, year)
}

@Serializable
data class Availability(val available: Boolean, val versions: List<String> = emptyList(), val episodes: List<List<Int>>? = null)

/** One title on the TV's Continue shelf, for Garbled's own. */
@Serializable
data class ContinueItem(
    val tmdb: Int,
    val type: String,
    val title: String,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val version: String,
    val positionMs: Long,
    val durationMs: Long,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val atEpochMillis: Long,
)

/** What the TV's player has on screen, for a phone's widget: [item] is null when nothing plays; [playing] is false once paused. */
@Serializable
data class Now(val playing: Boolean = false, val item: ContinueItem? = null)

@Serializable
data class Played(val title: String, val version: String, val season: Int? = null, val episode: Int? = null, val startMs: Long = 0)

/** What the TV can do for the phone; [RemoteLibrary] in the app, a fake in tests. */
interface Library {
    suspend fun has(ref: TitleRef, withEpisodes: Boolean): Availability
    /** Starts playback, or says why it couldn't. */
    suspend fun play(request: PlayRequest): Result<Played>
    fun continueWatching(): List<ContinueItem>
    fun removeContinue(ref: TitleRef): Boolean
    /** The film or episode playing now, with its live position. */
    fun now(): Now
}

class RemoteFailure(val status: Int, message: String) : Exception(message)

/**
 * The phone's endpoints. Everything but `/info` and pairing needs a paired token.
 *
 *     GET    /info                                    → {app, version, name}
 *     POST   /pair           {name}                   → the TV shows a code
 *     POST   /pair/confirm   {code}                   → {token}
 *     GET    /has?tmdb=603&type=movie[&episodes=true] → {available, versions, episodes:[[1,1],…]}
 *     POST   /play           {tmdb, type, season?, episode?, version?, startMs?}
 *     GET    /continue                                → {items:[…]}, the latest first
 *     DELETE /continue?tmdb=603&type=movie            → off the shelf (watched, or not wanted)
 *     GET    /now                                     → {playing, item?}: what's on screen, its position live
 */
class RemoteApi(
    private val library: Library,
    private val pairing: Pairing,
    private val tokens: RemoteTokens,
    private val deviceName: () -> String,
    /** Puts the pairing code on screen. */
    private val showCode: () -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    @Serializable private data class Info(val app: String, val version: Int, val name: String)
    @Serializable private data class PairStart(val name: String = "Phone")
    @Serializable private data class PairStarted(val expiresInSeconds: Long)
    @Serializable private data class PairCode(val code: String)
    @Serializable private data class Token(val token: String)
    @Serializable private data class ContinueList(val items: List<ContinueItem>)
    @Serializable private data class Removed(val removed: Boolean)

    suspend fun handle(r: Request): Response = try {
        route(r)
    } catch (e: RemoteFailure) {
        Response.error(e.status, e.message.orEmpty())
    } catch (e: kotlinx.serialization.SerializationException) {
        Response.error(400, "Bad JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
    } catch (e: IllegalArgumentException) {
        Response.error(400, e.message.orEmpty())
    }

    private suspend fun route(r: Request): Response {
        when (r.method to r.path) {
            "GET" to "/info" -> return ok(Info.serializer(), Info("couch-commander", API_VERSION, deviceName()))
            "POST" to "/pair" -> {
                val name = parse(PairStart.serializer(), r.body.ifBlank { "{}" }).name.ifBlank { "Phone" }
                if (!pairing.start(name)) return Response.error(409, "Another phone is pairing")
                showCode()
                return Response(202, json.encodeToString(PairStarted.serializer(), PairStarted(Pairing.TTL.seconds)))
            }
            "POST" to "/pair/confirm" -> {
                val token = pairing.confirm(parse(PairCode.serializer(), r.body).code) ?: return Response.error(403, "Wrong or expired code")
                return ok(Token.serializer(), Token(token))
            }
        }
        if (!tokens.valid(r.bearer)) return Response.error(401, "Not paired")
        return when (r.method to r.path) {
            "GET" to "/has" -> ok(Availability.serializer(), library.has(queryRef(r), r.query["episodes"] == "true"))
            "POST" to "/play" -> {
                val request = parse(PlayRequest.serializer(), r.body)
                requireNotNull(request.ref.kind) { "type must be movie or tv" }
                ok(Played.serializer(), library.play(request).getOrThrow())
            }
            "GET" to "/continue" -> ok(ContinueList.serializer(), ContinueList(library.continueWatching()))
            "DELETE" to "/continue" -> ok(Removed.serializer(), Removed(library.removeContinue(queryRef(r))))
            "GET" to "/now" -> ok(Now.serializer(), library.now())
            else -> Response.error(404, "No ${r.method} ${r.path}")
        }
    }

    private fun queryRef(r: Request): TitleRef {
        val tmdb = requireNotNull(r.query["tmdb"]?.toIntOrNull()) { "tmdb is required" }
        val type = r.query["type"].orEmpty()
        requireNotNull(kindOf(type)) { "type must be movie or tv" }
        return TitleRef(tmdb, type, r.query["title"], r.query["year"]?.toIntOrNull())
    }

    private fun <T> parse(s: kotlinx.serialization.KSerializer<T>, body: String): T = json.decodeFromString(s, body)
    private fun <T> ok(s: kotlinx.serialization.KSerializer<T>, value: T) = Response(200, json.encodeToString(s, value))

    companion object {
        const val API_VERSION = 1
    }
}

fun kindOf(type: String): MediaKind? = when (type.lowercase()) {
    "movie" -> MediaKind.Movie
    "tv" -> MediaKind.Tv
    else -> null
}

fun typeOf(kind: MediaKind): String = if (kind == MediaKind.Movie) "movie" else "tv"
