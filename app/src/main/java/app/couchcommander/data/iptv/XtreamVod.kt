package app.couchcommander.data.iptv

import java.io.InputStream
import java.net.URLEncoder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A film or series on the panel, with only what matching and playing need. [ext] is unused for a series. */
@Serializable
data class VodEntry(
    val id: String,
    val name: String,
    val ext: String = "mp4",
    /** The panel's TMDB id, when it has one: the surest match. */
    val tmdb: Int? = null,
    val year: Int? = null,
    val category: String = "",
)

/** One episode of a series on the panel. */
@Serializable
data class VodEpisode(val id: String, val season: Int, val number: Int, val title: String, val ext: String = "mp4")

/**
 * Xtream Codes `player_api.php` for films (`vod`) and series. The whole catalogue comes in one answer each, read
 * an item at a time: asking category by category is hundreds of requests, which panels take for flooding and
 * answer with 403. Stream URLs hold the login, so they are built when playing, never stored.
 */
object XtreamVod {
    fun movieCategoriesUrl(s: IptvSource.Xtream) = api(s) + "&action=get_vod_categories"
    fun moviesUrl(s: IptvSource.Xtream) = api(s) + "&action=get_vod_streams"
    fun seriesCategoriesUrl(s: IptvSource.Xtream) = api(s) + "&action=get_series_categories"
    fun seriesUrl(s: IptvSource.Xtream) = api(s) + "&action=get_series"
    fun seriesInfoUrl(s: IptvSource.Xtream, seriesId: String) = api(s) + "&action=get_series_info&series_id=" + enc(seriesId)

    fun movieStreamUrl(s: IptvSource.Xtream, e: VodEntry) = movieStreamUrl(s, e.id, e.ext)
    fun episodeStreamUrl(s: IptvSource.Xtream, e: VodEpisode) = episodeStreamUrl(s, e.id, e.ext)
    /** By stream id and container, as Continue watching keeps them. */
    fun movieStreamUrl(s: IptvSource.Xtream, id: String, ext: String) = "${s.server}/movie/${enc(s.username)}/${enc(s.password)}/$id.$ext"
    fun episodeStreamUrl(s: IptvSource.Xtream, id: String, ext: String) = "${s.server}/series/${enc(s.username)}/${enc(s.password)}/$id.$ext"

    private fun api(s: IptvSource.Xtream) = "${s.server}/player_api.php?username=${enc(s.username)}&password=${enc(s.password)}"
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Films ([series] false, keyed by `stream_id`) or series (`series_id`), streamed from [input] so the whole list
     * never sits in memory as JSON; [categories] names them by `category_id`. A bad login's `{}` reads as none.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun parseEntries(input: InputStream, categories: Map<String, String>, series: Boolean, max: Int = Int.MAX_VALUE): List<VodEntry> {
        val out = ArrayList<VodEntry>()
        runCatching {
            for (e in json.decodeToSequence<JsonElement>(input, DecodeSequenceMode.ARRAY_WRAPPED)) {
                entry(e as? JsonObject ?: continue, categories, series)?.let { out += it }
                if (out.size >= max) break
            }
        }
        return out
    }

    private fun entry(o: JsonObject, categories: Map<String, String>, series: Boolean): VodEntry? {
        val id = o.str(if (series) "series_id" else "stream_id") ?: return null
        val name = o.str("name")?.trim().orEmpty().ifEmpty { return null }
        return VodEntry(
            id = id,
            name = name,
            ext = o.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4",
            tmdb = (o.str("tmdb") ?: o.str("tmdb_id"))?.trim()?.toIntOrNull()?.takeIf { it > 0 },
            year = year(o) ?: VodNames.year(name),
            category = o.str("category_id")?.let { categories[it] }.orEmpty(),
        )
    }

    /**
     * A series' episodes from `get_series_info`. Panels give `episodes` as an object of seasons
     * (`{"1": [...]}`) or as an array of seasons; episodes without a number are numbered in order.
     */
    fun parseEpisodes(body: String): List<VodEpisode> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val seasons: List<Pair<Int?, JsonElement>> = when (val e = root["episodes"]) {
            is JsonObject -> e.entries.map { (k, v) -> k.toIntOrNull() to v }
            is JsonArray -> e.map { null to it }
            else -> emptyList()
        }
        return seasons.flatMap { (key, list) ->
            (list as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapIndexedNotNull { i, o ->
                val id = o.str("id") ?: return@mapIndexedNotNull null
                val season = o.str("season")?.toIntOrNull() ?: key ?: return@mapIndexedNotNull null
                VodEpisode(
                    id = id,
                    season = season,
                    number = o.str("episode_num")?.toIntOrNull() ?: (i + 1),
                    title = o.str("title")?.trim().orEmpty(),
                    ext = o.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4",
                )
            }
        }.distinctBy { it.season to it.number }.sortedWith(compareBy({ it.season }, { it.number }))
    }

    private fun year(o: JsonObject): Int? =
        listOf("year", "releaseDate", "release_date", "releasedate").firstNotNullOfOrNull { k -> o.str(k)?.take(4)?.toIntOrNull() }?.takeIf { it in 1880..2100 }

    private fun items(body: String): List<JsonObject> =
        (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
