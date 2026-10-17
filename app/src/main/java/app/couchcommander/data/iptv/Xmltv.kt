package app.couchcommander.data.iptv

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.xmlpull.v1.XmlPullParser

/**
 * Streaming XMLTV reader. A full guide is often hundreds of MB, so it's pulled one element at a time and only
 * the wanted channels' programmes inside the time window, with a title [keepTitle] accepts, are kept.
 */
object Xmltv {
    class Result(
        /** Guide channel ids that [wantChannel] accepted, with their display names. */
        val channels: Map<String, List<String>>,
        val programmes: List<IptvProgramme>,
    )

    fun parse(
        parser: XmlPullParser,
        wantChannel: (id: String, displayNames: List<String>) -> Boolean,
        from: Instant,
        to: Instant,
        keepTitle: (String) -> Boolean,
    ): Result {
        val wanted = mutableMapOf<String, List<String>>()
        val rejected = mutableSetOf<String>()
        val programmes = mutableListOf<IptvProgramme>()
        val fromMillis = from.toEpochMilli()
        val toMillis = to.toEpochMilli()

        fun wants(id: String): Boolean {
            if (id in wanted) return true
            if (id in rejected) return false
            // A programme for a channel the guide never declared: decide on the id alone.
            return if (wantChannel(id, emptyList())) { wanted[id] = emptyList(); true } else { rejected += id; false }
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (parser.name) {
                "channel" -> {
                    val id = parser.getAttributeValue(null, "id").orEmpty()
                    val names = mutableListOf<String>()
                    readChildren(parser) { if (parser.name == "display-name") names += parser.nextText().trim() else skip(parser) }
                    if (id.isNotEmpty()) { if (wantChannel(id, names)) wanted[id] = names else rejected += id }
                }
                "programme" -> {
                    val id = parser.getAttributeValue(null, "channel").orEmpty()
                    val start = time(parser.getAttributeValue(null, "start"))
                    val stop = time(parser.getAttributeValue(null, "stop")) ?: start?.plus(2 * 3_600_000L)
                    if (start == null || stop == null || stop < fromMillis || start > toMillis || !wants(id)) skip(parser)
                    else {
                        var title: String? = null
                        readChildren(parser) { if (parser.name == "title" && title == null) title = parser.nextText().trim() else skip(parser) }
                        title?.takeIf { it.isNotEmpty() && keepTitle(it) }?.let { programmes += IptvProgramme(id, it, start, stop) }
                    }
                }
            }
            event = parser.next()
        }
        return Result(wanted, programmes)
    }

    private val COMPACT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val TIME = Regex("""^(\d{14})\s*([+-]\d{4})?""")

    /** `20261017190000 +0300`; without an offset the time is UTC, as the XMLTV spec says. */
    internal fun time(value: String?): Long? {
        val m = TIME.find(value?.trim() ?: return null) ?: return null
        return runCatching {
            val local = LocalDateTime.parse(m.groupValues[1], COMPACT)
            val offset = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { ZoneOffset.of(it.substring(0, 3) + ":" + it.substring(3)) } ?: ZoneOffset.UTC
            local.toInstant(offset).toEpochMilli()
        }.getOrNull()
    }

    /** Calls [onChild] at each child start tag of the current element; returns on its end tag. */
    private inline fun readChildren(parser: XmlPullParser, onChild: () -> Unit) {
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.END_TAG && parser.depth == depth) return
            if (event == XmlPullParser.START_TAG && parser.depth == depth + 1) onChild()
        }
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) when (parser.next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}
