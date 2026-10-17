package app.couchcommander.data.iptv

import java.io.StringReader
import java.time.Instant
import org.junit.Test
import org.kxml2.io.KXmlParser
import kotlin.test.assertEquals

class XmltvTest {
    private val guide = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="vsport1.fi"><display-name lang="fi">V Sport 1 HD</display-name><icon src="x"/></channel>
          <channel id="news.fi"><display-name>News</display-name></channel>
          <programme start="20261018170000 +0300" stop="20261018193000 +0300" channel="vsport1.fi">
            <title lang="en">Premier League: Arsenal v Chelsea</title><desc>Live &amp; direct</desc>
          </programme>
          <programme start="20261018193000 +0300" stop="20261018210000 +0300" channel="vsport1.fi"><title>Golf</title></programme>
          <programme start="20261018170000 +0300" stop="20261018180000 +0300" channel="news.fi"><title>Arsenal news</title></programme>
          <programme start="20261201170000" stop="20261201180000" channel="vsport1.fi"><title>Arsenal later</title></programme>
          <programme start="20261018140000" channel="undeclared"><title>Arsenal elsewhere</title></programme>
        </tv>
    """.trimIndent()

    private fun parse(want: (String, List<String>) -> Boolean) = Xmltv.parse(
        KXmlParser().apply { setInput(StringReader(guide)) },
        want,
        from = Instant.parse("2026-10-17T00:00:00Z"), to = Instant.parse("2026-10-25T00:00:00Z"),
        keepTitle = { it.contains("Arsenal") },
    )

    @Test
    fun `keeps wanted channels, window and titles`() {
        val r = parse { id, names -> id == "vsport1.fi" || "V Sport 1 HD" in names }
        assertEquals(mapOf("vsport1.fi" to listOf("V Sport 1 HD")), r.channels)
        assertEquals(1, r.programmes.size)
        val p = r.programmes.single()
        assertEquals("Premier League: Arsenal v Chelsea", p.title)
        assertEquals(Instant.parse("2026-10-18T14:00:00Z").toEpochMilli(), p.startEpochMillis)
        assertEquals(Instant.parse("2026-10-18T16:30:00Z").toEpochMilli(), p.stopEpochMillis)
    }

    @Test
    fun `undeclared channels are decided by id, and a missing stop means two hours`() {
        val r = parse { id, _ -> id == "undeclared" }
        val p = r.programmes.single()
        assertEquals("Arsenal elsewhere", p.title)
        assertEquals(Instant.parse("2026-10-18T16:00:00Z").toEpochMilli(), p.stopEpochMillis)
    }

    @Test
    fun `times`() {
        assertEquals(Instant.parse("2026-10-18T14:00:00Z").toEpochMilli(), Xmltv.time("20261018170000 +0300"))
        assertEquals(Instant.parse("2026-10-18T17:00:00Z").toEpochMilli(), Xmltv.time("20261018170000"))
        assertEquals(null, Xmltv.time("bad"))
    }
}
