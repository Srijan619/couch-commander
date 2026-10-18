package app.couchcommander.data.iptv

import java.io.InputStream
import java.net.URLEncoder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Xtream Codes `player_api.php`: the live categories, then every live stream in one answer, read a stream at a time
 * and kept when its category is a sports one. Asking category by category was hundreds of requests per sync: slow,
 * and what panels take for flooding.
 */
object Xtream {
    class Category(val id: String, val name: String)

    fun categoriesUrl(s: IptvSource.Xtream) = api(s) + "&action=get_live_categories"
    /** Every live stream in one response, each with its `category_id`. */
    fun streamsUrl(s: IptvSource.Xtream) = api(s) + "&action=get_live_streams"
    fun xmltvUrl(s: IptvSource.Xtream) = "${s.server}/xmltv.php?username=${enc(s.username)}&password=${enc(s.password)}"
    fun streamUrl(s: IptvSource.Xtream, streamId: String) = "${s.server}/live/${enc(s.username)}/${enc(s.password)}/$streamId.ts"

    private fun api(s: IptvSource.Xtream) = "${s.server}/player_api.php?username=${enc(s.username)}&password=${enc(s.password)}"
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private val json = Json { ignoreUnknownKeys = true }

    fun parseCategories(body: String): List<Category> = items(body).mapNotNull { o ->
        val id = o.str("category_id") ?: return@mapNotNull null
        Category(id, o.str("category_name").orEmpty())
    }

    /**
     * The streams of [categories] (any other category's are skipped), named after their category, streamed from
     * [input] so the whole list never sits in memory as JSON. A bad login's `{}` reads as none.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun parseStreams(input: InputStream, s: IptvSource.Xtream, categories: List<Category>): List<IptvChannel> {
        val names = categories.associate { it.id to it.name }
        val out = ArrayList<IptvChannel>()
        runCatching {
            for (e in json.decodeToSequence<JsonElement>(input, DecodeSequenceMode.ARRAY_WRAPPED)) {
                val o = e as? JsonObject ?: continue
                val group = names[o.str("category_id")] ?: continue
                stream(o, s, group)?.let { out += it }
            }
        }
        return out
    }

    private fun stream(o: JsonObject, s: IptvSource.Xtream, group: String): IptvChannel? {
        val id = o.str("stream_id") ?: return null
        return IptvChannel(
            name = o.str("name")?.trim().orEmpty().ifEmpty { return null },
            group = group,
            url = streamUrl(s, id),
            number = o.str("num")?.toIntOrNull(),
            epgId = o.str("epg_channel_id")?.takeIf { it.isNotBlank() },
            logo = o.str("stream_icon")?.takeIf { it.isNotBlank() },
        )
    }

    /** Panels answer a bad login or an empty category with `{}`, `null` or `[]`; all read as no items. */
    private fun items(body: String): List<JsonObject> =
        (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    /** Panels disagree on numbers vs strings (`"stream_id": 12` or `"12"`). */
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
