package app.couchcommander.data.iptv

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class M3uTest {
    private val playlist = """
        #EXTM3U x-tvg-url="http://epg.example/guide.xml.gz,http://epg.example/other.xml"
        #EXTINF:-1 tvg-id="" tvg-name="##### SPORTS #####" group-title="SPORTS",##### SPORTS #####
        http://host/u/p/1
        #EXTINF:-1 tvg-id="VSport1.fi" tvg-name="V Sport 1" tvg-logo="http://logo/1.png" group-title="FI| SPORTS",FI| V Sport 1 FHD
        http://host/u/p/2
        #EXTINF:-1 tvg-name="Movie, The" group-title="VOD",Movie, The (2024)
        http://host/movie/u/p/3.mkv
        #EXTINF:-1 tvg-chno="42" group-title="EVENTS",EPL 03: Arsenal v Chelsea
        #EXTVLCOPT:http-user-agent=Kodi/20
        #EXTVLCOPT:http-referrer=http://ref/
        http://host/u/p/4

        #EXTINF:-1,No group
        #EXTGRP:Football
        http://host/u/p/5
    """.trimIndent()

    @Test
    fun `parses attributes, numbers and options`() {
        val r = M3u.parse(playlist.lineSequence()) { true }
        assertEquals("http://epg.example/guide.xml.gz", r.epgUrl)
        assertEquals(5, r.total)
        assertEquals(listOf("FI| V Sport 1 FHD", "Movie, The (2024)", "EPL 03: Arsenal v Chelsea", "No group"), r.channels.map { it.name })

        val vsport = r.channels[0]
        assertEquals("VSport1.fi", vsport.epgId)
        assertEquals("FI| SPORTS", vsport.group)
        assertEquals(2, vsport.number) // playlist position: the divider before it counts, as in TiviMate
        assertEquals("http://logo/1.png", vsport.logo)

        val event = r.channels[2]
        assertEquals(42, event.number)
        assertNull(event.epgId)
        assertEquals("Kodi/20", event.userAgent)
        assertEquals("http://ref/", event.referrer)

        assertEquals("Football", r.channels[3].group)
    }

    @Test
    fun `keeps only what the filter accepts`() {
        val r = M3u.parse(playlist.lineSequence(), Sports::isLiveSports)
        assertEquals(listOf("FI| V Sport 1 FHD", "EPL 03: Arsenal v Chelsea", "No group"), r.channels.map { it.name })
    }

    @Test
    fun `commas inside quotes don't split the name`() {
        assertEquals("#EXTINF:-1 tvg-name=\"A, B\"" to "Name", M3u.splitName("#EXTINF:-1 tvg-name=\"A, B\",Name"))
    }
}
