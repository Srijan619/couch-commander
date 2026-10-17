package app.couchcommander.data.iptv

import app.football.FootballTeam
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.kxml2.io.KXmlParser
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IptvRepositoryTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val arsenal = listOf(FootballTeam(9825, "Arsenal"))

    private val playlist = """
        #EXTM3U url-tvg="http://epg/guide.xml.gz"
        #EXTINF:-1 group-title="FI| GENERAL",Yle TV1
        http://h/u/p/1
        #EXTINF:-1 group-title="FI| SPORTS",FI| V Sport 1 FHD
        http://h/u/p/2
    """.trimIndent()

    private val guide = """
        <tv>
          <channel id="vsport1.fi"><display-name>V Sport 1</display-name></channel>
          <channel id="yle1.fi"><display-name>Yle TV1</display-name></channel>
          <programme start="20261018170000 +0300" stop="20261018193000 +0300" channel="vsport1.fi"><title>Arsenal v Chelsea</title></programme>
          <programme start="20261018170000 +0300" stop="20261018193000 +0300" channel="vsport1.fi"><title>Golf</title></programme>
        </tv>
    """.trimIndent()

    /** Serves the playlist and a gzipped guide; counts requests. */
    private class FakeFetch(val files: Map<String, ByteArray>) : IptvFetch {
        val requests = mutableListOf<String>()
        override fun <T> get(url: String, maxBytes: Long, read: (InputStream) -> T): T {
            requests += url
            val bytes = files[url] ?: throw IOException("HTTP 404")
            val raw = bytes.inputStream().buffered()
            raw.mark(2)
            val gz = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            return read(if (gz) java.util.zip.GZIPInputStream(raw) else raw)
        }
    }

    private fun gzip(text: String) = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun repo(dir: java.io.File, fetch: IptvFetch) = IptvRepository(dir, fetch, newParser = { KXmlParser() }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun `syncs sports channels, links the guide by name and persists`() = runTest {
        val dir = Files.createTempDirectory("iptv").toFile()
        val fetch = FakeFetch(mapOf("http://pl/list.m3u" to playlist.toByteArray(), "http://epg/guide.xml.gz" to gzip(guide)))
        val source = IptvSource.M3u("http://pl/list.m3u")

        val state = repo(dir, fetch).refresh(source, arsenal, now)
        assertNull(state.error)
        assertEquals(listOf("FI| V Sport 1 FHD"), state.channels.map { it.name }) // the guide shows Arsenal on it
        assertEquals(1, state.sportsChannels)
        assertEquals("vsport1.fi", state.channels.single().epgId)
        assertEquals(listOf("Arsenal v Chelsea"), state.programmes.map { it.title })
        assertEquals(2, state.playlistSize)

        // Fresh: no second download; a new instance reads the file.
        repo(dir, fetch).refresh(source, arsenal, now.plus(Duration.ofHours(1)))
        assertEquals(2, fetch.requests.size)
        assertEquals(state.channels, repo(dir, FakeFetch(emptyMap())).state.value.channels)

        // A team change syncs again.
        repo(dir, fetch).refresh(source, arsenal + FootballTeam(1, "HJK"), now.plus(Duration.ofHours(1)))
        assertEquals(4, fetch.requests.size)
    }

    @Test
    fun `a failed guide keeps the last guide and retries in twenty minutes`() = runTest {
        val dir = Files.createTempDirectory("iptv").toFile()
        val source = IptvSource.M3u("http://pl/list.m3u")
        repo(dir, FakeFetch(mapOf("http://pl/list.m3u" to playlist.toByteArray(), "http://epg/guide.xml.gz" to gzip(guide)))).refresh(source, arsenal, now)

        val noGuide = FakeFetch(mapOf("http://pl/list.m3u" to playlist.toByteArray()))
        val r = repo(dir, noGuide)
        val state = r.refresh(source, arsenal, now.plus(Duration.ofHours(13)))
        assertNotNull(state.guideError)
        assertEquals(listOf("Arsenal v Chelsea"), state.programmes.map { it.title })
        assertEquals("vsport1.fi", state.channels.single().epgId, "the channel stays linked to the guide")

        r.refresh(source, arsenal, now.plus(Duration.ofHours(13)).plus(Duration.ofMinutes(10)))
        assertEquals(2, noGuide.requests.size, "not again within twenty minutes")
        r.refresh(source, arsenal, now.plus(Duration.ofHours(13)).plus(Duration.ofMinutes(21)))
        assertEquals(4, noGuide.requests.size)
    }

    @Test
    fun `popular channels are kept by name`() = runTest {
        val dir = Files.createTempDirectory("iptv").toFile()
        val state = repo(dir, FakeFetch(mapOf("http://pl/list.m3u" to playlist.toByteArray()))).refresh(IptvSource.M3u("http://pl/list.m3u"), emptyList(), now)
        assertEquals(listOf("FI| V Sport 1 FHD"), state.popular.map { it.name })
    }

    @Test
    fun `a failed sync keeps the last channels and waits before retrying`() = runTest {
        val dir = Files.createTempDirectory("iptv").toFile()
        val good = FakeFetch(mapOf("http://pl/list.m3u" to playlist.toByteArray()))
        val source = IptvSource.M3u("http://pl/list.m3u")
        repo(dir, good).refresh(source, emptyList(), now)

        val down = FakeFetch(emptyMap())
        val r = repo(dir, down)
        val failed = r.refresh(source, emptyList(), now.plus(Duration.ofHours(13)))
        assertEquals("HTTP 404", failed.error)
        assertEquals(1, failed.sportsChannels)
        r.refresh(source, emptyList(), now.plus(Duration.ofHours(13).plusMinutes(30)))
        assertEquals(1, down.requests.size)
    }

    @Test
    fun `xtream asks for sports categories only`() = runTest {
        val dir = Files.createTempDirectory("iptv").toFile()
        val s = IptvSource.Xtream("http://panel:8080", "user", "pa ss")
        val fetch = FakeFetch(
            mapOf(
                Xtream.categoriesUrl(s) to """[{"category_id":"1","category_name":"UK| SPORTS"},{"category_id":"2","category_name":"UK| NEWS"}]""".toByteArray(),
                Xtream.streamsUrl(s, "1") to """[{"num":101,"name":"Sky Sports PL","stream_id":55,"epg_channel_id":"skypl.uk"},{"num":102,"name":"EPL 01: Arsenal v Chelsea","stream_id":56}]""".toByteArray(),
            ),
        )
        // No guide on this panel: the event channel named after the match is kept, the plain one isn't stored.
        val state = repo(dir, fetch).refresh(s, arsenal, now)
        assertEquals(2, state.sportsChannels)
        val ch = state.channels.single()
        assertEquals(102, ch.number)
        assertEquals("http://panel:8080/live/user/pa%20ss/56.ts", ch.url)
        assertEquals(3, fetch.requests.size) // categories, one sports category, the (missing) guide
    }

    @Test
    fun `source parsing`() {
        val x = IptvSource.parse("http://panel.tv:8080/get.php?username=a%40b&password=x&type=m3u_plus&output=ts")
        assertEquals(IptvSource.Xtream("http://panel.tv:8080", "a@b", "x"), x)
        assertEquals("Xtream(<redacted>)", x.toString())
        assertNotNull(IptvSource.parse("https://example.com/list.m3u") as? IptvSource.M3u)
        assertNull(IptvSource.parse("not a url"))
    }
}
