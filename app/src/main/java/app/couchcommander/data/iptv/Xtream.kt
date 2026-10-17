package app.couchcommander.data.iptv

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Xtream Codes `player_api.php`: categories first, then live streams for the sports categories only. */
object Xtream {
    class Category(val id: String, val name: String)

    fun categoriesUrl(s: IptvSource.Xtream) = api(s) + "&action=get_live_categories"
    fun streamsUrl(s: IptvSource.Xtream, categoryId: String) = api(s) + "&action=get_live_streams&category_id=" + enc(categoryId)
    fun xmltvUrl(s: IptvSource.Xtream) = "${s.server}/xmltv.php?username=${enc(s.username)}&password=${enc(s.password)}"
    fun streamUrl(s: IptvSource.Xtream, streamId: String) = "${s.server}/live/${enc(s.username)}/${enc(s.password)}/$streamId.ts"

    private fun api(s: IptvSource.Xtream) = "${s.server}/player_api.php?username=${enc(s.username)}&password=${enc(s.password)}"
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private val json = Json { ignoreUnknownKeys = true }

    fun parseCategories(body: String): List<Category> = items(body).mapNotNull { o ->
        val id = o.str("category_id") ?: return@mapNotNull null
        Category(id, o.str("category_name").orEmpty())
    }

    fun parseStreams(body: String, s: IptvSource.Xtream, category: Category): List<IptvChannel> = items(body).mapNotNull { o ->
        val id = o.str("stream_id") ?: return@mapNotNull null
        IptvChannel(
            name = o.str("name")?.trim().orEmpty().ifEmpty { return@mapNotNull null },
            group = category.name,
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
