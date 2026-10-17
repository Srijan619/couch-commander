package app.couchcommander.data.iptv

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where the user's channels come from. Holds credentials (in the URL or as fields), so it's kept out of the
 * settings file and never printed: [toString] is redacted.
 */
@Serializable
sealed interface IptvSource {
    /** A plain playlist URL; [epgUrl] overrides the guide the playlist header names. */
    @Serializable
    @SerialName("m3u")
    data class M3u(val url: String, val epgUrl: String? = null) : IptvSource {
        override fun toString() = "M3u(<redacted>)"
    }

    /** An Xtream Codes panel: asked for its sports categories only, instead of downloading the whole playlist. */
    @Serializable
    @SerialName("xtream")
    data class Xtream(val server: String, val username: String, val password: String) : IptvSource {
        override fun toString() = "Xtream(<redacted>)"
    }

    companion object {
        /** What the user pastes: an Xtream `get.php` / `player_api.php` link with username and password, or any playlist URL. */
        fun parse(text: String, epgUrl: String? = null): IptvSource? {
            val url = text.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
                val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
                k to java.net.URLDecoder.decode(v, "UTF-8")
            }.toMap()
            val user = query["username"]
            val pass = query["password"]
            val path = uri.path.orEmpty()
            if (user != null && pass != null && (path.endsWith("/get.php") || path.endsWith("/player_api.php"))) {
                val port = if (uri.port == -1) "" else ":${uri.port}"
                val base = path.substringBeforeLast('/')
                return Xtream("${uri.scheme}://${uri.host}$port$base", user, pass)
            }
            return M3u(url, epgUrl)
        }
    }
}

/** A sports channel kept from the playlist. [epgId] links it to the guide; [number] is what to type in TiviMate. */
@Serializable
data class IptvChannel(
    val name: String,
    val group: String,
    val url: String,
    /** `tvg-chno`, Xtream's `num`, or the playlist position (TiviMate numbers channels in playlist order). */
    val number: Int? = null,
    val epgId: String? = null,
    val logo: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
) {
    override fun toString() = "IptvChannel($name, $group, #$number, epg=$epgId)"
}

/** A guide entry that names a followed team; everything else in the guide is dropped while it streams past. */
@Serializable
data class IptvProgramme(val epgId: String, val title: String, val startEpochMillis: Long, val stopEpochMillis: Long)

/** One way to watch a match: [title] and [startEpochMillis] are set when it was found in the guide. */
@Serializable
data class WatchOption(
    val channel: IptvChannel,
    val title: String? = null,
    val startEpochMillis: Long? = null,
    /** 3: a channel named after the match; 2: guide names both teams; 1: guide names one team at kickoff. */
    val score: Int,
)
