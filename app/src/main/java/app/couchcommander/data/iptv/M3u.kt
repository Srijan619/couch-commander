package app.couchcommander.data.iptv

/**
 * Streaming M3U parser: reads one line at a time and keeps only the channels [keep] accepts, so a playlist of
 * tens of thousands of entries never sits in memory. Handles `#EXTINF` attributes (any `key="value"`, commas in
 * values), `#EXTGRP`, `#EXTVLCOPT` user agent / referrer and the header's guide URL.
 */
object M3u {
    class Result(val epgUrl: String?, val channels: List<IptvChannel>, val total: Int)

    fun parse(lines: Sequence<String>, keep: (IptvChannel) -> Boolean): Result {
        var epgUrl: String? = null
        val channels = mutableListOf<IptvChannel>()
        var total = 0
        var info: String? = null
        var group: String? = null
        var userAgent: String? = null
        var referrer: String? = null

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    val attrs = attributes(line)
                    // Some playlists list several guides, comma-separated; the first is usually the full one.
                    epgUrl = (attrs["x-tvg-url"] ?: attrs["url-tvg"])?.split(',')?.firstOrNull { it.isNotBlank() }?.trim()
                }
                line.startsWith("#EXTINF") -> {
                    info = line; group = null; userAgent = null; referrer = null
                }
                line.startsWith("#EXTGRP:") -> group = line.removePrefix("#EXTGRP:").trim()
                line.startsWith("#EXTVLCOPT:") -> {
                    val opt = line.removePrefix("#EXTVLCOPT:")
                    when {
                        opt.startsWith("http-user-agent=") -> userAgent = opt.substringAfter('=')
                        opt.startsWith("http-referrer=") -> referrer = opt.substringAfter('=')
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    // A stream URL ends the entry; one without #EXTINF still counts towards channel numbers.
                    total++
                    val extinf = info
                    info = null
                    if (extinf == null) continue
                    val (head, title) = splitName(extinf)
                    val attrs = attributes(head)
                    val name = title.ifBlank { attrs["tvg-name"].orEmpty() }.trim()
                    if (name.isEmpty() || isDivider(name)) continue
                    val channel = IptvChannel(
                        name = name,
                        group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: group ?: "",
                        url = line,
                        number = attrs["tvg-chno"]?.toIntOrNull() ?: total,
                        epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        userAgent = userAgent,
                        referrer = referrer,
                    )
                    if (keep(channel)) channels += channel
                }
            }
        }
        return Result(epgUrl, channels, total)
    }

    private val ATTRIBUTE = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val DIVIDER = Regex("""^[-=#*_|~ ]{4,}$|^[-=#*_|~]{2,}.*[-=#*_|~]{2,}$""")

    internal fun attributes(text: String): Map<String, String> =
        ATTRIBUTE.findAll(text).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** The display name follows the first comma outside quotes: `tvg-name="A, B"` must not split. */
    internal fun splitName(extinf: String): Pair<String, String> {
        var quoted = false
        for (i in extinf.indices) {
            when (extinf[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return extinf.substring(0, i) to extinf.substring(i + 1)
            }
        }
        return extinf to ""
    }

    /** Fake "channels" that only label a section, like `##### SPORTS #####`. */
    internal fun isDivider(name: String) = DIVIDER.matches(name.trim())
}
