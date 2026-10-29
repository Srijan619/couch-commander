package app.couchcommander.data.iptv

import app.media.MediaKind
import app.media.MovieDetails
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VodRepositoryTest {
    private val dir: File = Files.createTempDirectory("vod").toFile()
    private val source = IptvSource.Xtream("http://panel.test", "user", "pass")
    private val now = Instant.parse("2026-10-17T18:00:00Z")
    private val asked = mutableListOf<String>()

    private val panel = object : IptvFetch {
        override fun <T> get(url: String, maxBytes: Long, read: (InputStream) -> T): T {
            val action = url.substringAfter("action=")
            asked += action
            val body = when (action) {
                "get_vod_categories" -> """[{"category_id": "1", "category_name": "EN Movies"}]"""
                "get_vod_streams" -> """[
                    {"stream_id": 10, "name": "EN - Metropolis (1927)", "container_extension": "mkv", "tmdb": "19", "category_id": "1"},
                    {"stream_id": "11", "name": "|DE| Metropolis [4K]", "year": "1927"},
                    {"stream_id": 12, "name": "EN - Metropolis (2001)", "tmdb": "9314"},
                    {"stream_id": 13, "name": "EN - Nosferatu (2024)"}
                ]"""
                // Without category names the titles still count.
                "get_series_categories" -> throw IOException("HTTP 502")
                "get_series" -> """[{"series_id": 70, "name": "EN - The Twilight Zone (1959)", "tmdb": 6357}]"""
                "get_series_info&series_id=70" -> """{"episodes": {"1": [
                    {"id": "701", "episode_num": "2", "title": "One for the Angels", "container_extension": "mp4", "season": 1},
                    {"id": "700", "episode_num": 1, "title": "Where Is Everybody?", "container_extension": "mkv", "season": 1}
                ], "2": [{"id": "720", "episode_num": 1, "title": "King Nine Will Not Return"}]}}"""
                else -> throw IOException("HTTP 404")
            }
            return read(body.byteInputStream())
        }
    }

    private fun repository() = VodRepository(dir, panel, Dispatchers.Unconfined, Dispatchers.Unconfined)

    private fun film(id: Int, title: String, year: Int) = MovieDetails(id = id, title = title, year = year, rating = 8.0)

    @Test
    fun `finds a film by TMDB id and by name, not a remake the panel tags otherwise`() = runTest {
        repository().refresh(source, now)
        val found = repository().find(film(19, "Metropolis", 1927)) // a new one reads the file
        assertEquals(listOf("10", "11"), found.map { it.id })
        assertEquals("mkv", found.first().ext)
        assertEquals("EN Movies", found.first().category)
        assertEquals(listOf("13"), repository().find(film(426063, "Nosferatu", 2024)).map { it.id })
        assertEquals(emptyList(), repository().find(film(653, "Nosferatu", 1922)))
    }

    @Test
    fun `a series and its episodes, in order`() = runTest {
        val repo = repository()
        repo.refresh(source, now)
        val series = repo.find(MovieDetails(id = 6357, title = "The Twilight Zone", year = 1959, rating = 8.7, kind = MediaKind.Tv)).single()
        val episodes = assertNotNull(repo.episodes(source, series))
        assertEquals(listOf("700", "701", "720"), episodes.map { it.id })
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), episodes.map { it.season to it.number })
    }

    @Test
    fun `four requests in all, a recent catalogue isn't fetched again, and none is kept without a panel`() = runTest {
        val repo = repository()
        repo.refresh(source, now)
        assertEquals(4, asked.size)
        asked.clear()
        repo.refresh(source, now.plusSeconds(3600))
        assertEquals(emptyList(), asked)
        repo.refresh(null, now)
        assertEquals(emptyList(), repo.find(film(19, "Metropolis", 1927)))
        assertFalse(File(dir, "vod.json").readText().contains("Metropolis"))
    }

    @Test
    fun `deciding whether to sync doesn't read the catalogue, and its keys are saved with it`() = runTest {
        repository().refresh(source, now)
        assertEquals(listOf("metropolis"), XtreamVod.parseEntries(
            """[{"stream_id": 11, "name": "|DE| Metropolis [4K]"}]""".byteInputStream(), emptyMap(), series = false,
        ).single().keys.filter { it == "metropolis" })

        var loads = 0
        val fresh = VodRepository(dir, panel, Dispatchers.Unconfined, Dispatchers.Unconfined, onLoaded = { loads++ })
        fresh.refresh(source, now.plusSeconds(3600))
        assertEquals(0, loads)
        assertTrue(File(dir, "vod.json").readText().contains("""["metropolis","en metropolis 1927"]"""))
        assertEquals(4, fresh.status.value.movies)
        fresh.find(film(19, "Metropolis", 1927))
        assertEquals(1, loads)
    }

    @Test
    fun `a catalogue saved before the meta file still counts as synced`() = runTest {
        repository().refresh(source, now)
        File(dir, "vod-meta.json").delete()
        asked.clear()
        repository().refresh(source, now.plusSeconds(3600))
        assertEquals(emptyList(), asked)
        assertEquals(listOf("10", "11"), repository().find(film(19, "Metropolis", 1927)).map { it.id })
    }

    @Test
    fun `a bad login's answer reads as no titles`() {
        assertEquals(emptyList(), XtreamVod.parseEntries("{}".byteInputStream(), emptyMap(), series = false))
        assertEquals(emptyList(), XtreamVod.parseEntries("null".byteInputStream(), emptyMap(), series = false))
    }

    @Test
    fun `stream URLs are built when playing`() {
        assertEquals("http://panel.test/movie/user/pass/10.mkv", XtreamVod.movieStreamUrl(source, VodEntry("10", "x", ext = "mkv")))
        assertEquals("http://panel.test/series/user/pass/700.mkv", XtreamVod.episodeStreamUrl(source, VodEpisode("700", 1, 1, "x", "mkv")))
    }
}
