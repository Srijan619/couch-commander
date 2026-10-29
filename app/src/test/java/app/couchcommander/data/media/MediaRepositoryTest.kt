package app.couchcommander.data.media

import app.media.MediaKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MediaRepositoryTest {
    private val dir: File = Files.createTempDirectory("media").toFile()
    private val now = Instant.parse("2026-10-17T18:00:00Z")
    private var offline = false
    private val asked = mutableListOf<String>()

    private fun page(vararg titles: Pair<Int, String>, pages: Int = 5) =
        """{"page": 1, "total_pages": $pages, "total_results": 100, "results": [${titles.joinToString { (id, t) -> """{"id": $id, "title": "$t", "release_date": "1927-01-10", "vote_average": 8.1, "vote_count": 3000}""" }}]}"""

    private fun repository() = MediaRepository(
        dir,
        HttpClient(MockEngine { request ->
            assertEquals("Bearer app-token", request.headers["Authorization"])
            val path = request.url.encodedPath.removePrefix("/3/")
            asked += path
            if (offline) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable)
            val genre = request.url.parameters["with_genres"]
            when {
                path == "trending/movie/week" && request.url.parameters["page"] == "2" ->
                    respond(page(2 to "Nosferatu", 5 to "Sunrise", pages = 2)) // Nosferatu has moved up a page since
                path == "discover/movie" && genre == "28" -> respond(page(6 to "The General"))
                path == "discover/movie" && genre != null -> respond(page())
                else -> when (path) {
                "trending/movie/week" -> respond(page(1 to "Metropolis", 2 to "Nosferatu"))
                "movie/top_rated" -> respond(page(3 to "The Kid"))
                "discover/movie" -> respond(page(4 to "Detour"))
                "movie/1" -> respond("""{"id": 1, "title": "Metropolis", "release_date": "1927-01-10", "vote_average": 8.1, "vote_count": 3000, "runtime": 153}""")
                else -> respondError(HttpStatusCode.NotFound)
                }
            }
        }) { install(HttpTimeout) },
        "app-token",
    )

    @Test
    fun `fills each shelf, ranks trending, and keeps them on disk`() = runTest {
        repository().refresh(MediaKind.Movie, now)
        val shelves = repository().shelves(MediaKind.Movie).value // a new one reads the file
        assertEquals(listOf("Metropolis", "Nosferatu"), shelves.rows[Shelf.Trending]?.map { it.title })
        assertEquals(listOf(1, 2), shelves.rows[Shelf.Trending]?.map { it.trendingRank })
        assertEquals(listOf("The Kid"), shelves.rows[Shelf.TopRated]?.map { it.title })
        assertEquals(listOf("Detour"), shelves.rows[Shelf.Newest]?.map { it.title })
        assertEquals(listOf("The General"), shelves.rows[Shelf.Action]?.map { it.title })
        assertEquals(null, shelves.rows[Shelf.Mystery]) // a series genre
        assertEquals(now, shelves.fetchedAt)
    }

    @Test
    fun `recent shelves aren't fetched again, old ones are`() = runTest {
        val repo = repository()
        repo.refresh(MediaKind.Movie, now)
        asked.clear()
        repo.refresh(MediaKind.Movie, now.plus(Duration.ofHours(1)))
        assertEquals(emptyList(), asked)
        repo.refresh(MediaKind.Movie, now.plus(MediaRepository.MAX_AGE))
        assertEquals(Shelf.of(MediaKind.Movie).size, asked.size)
    }

    @Test
    fun `a shelf grows a page at a time until the last`() = runTest {
        val repo = repository()
        repo.refresh(MediaKind.Movie, now)
        repo.more(MediaKind.Movie, Shelf.Trending)
        val shelves = repo.shelves(MediaKind.Movie).value
        assertEquals(listOf("Metropolis", "Nosferatu", "Sunrise"), shelves.rows[Shelf.Trending]?.map { it.title })
        assertEquals(false, shelves.canGrow(Shelf.Trending))
        asked.clear()
        repo.more(MediaKind.Movie, Shelf.Trending)
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `a page that fails can be asked for again`() = runTest {
        val repo = repository()
        repo.refresh(MediaKind.Movie, now)
        offline = true
        repo.more(MediaKind.Movie, Shelf.Trending)
        assertEquals(true, repo.shelves(MediaKind.Movie).value.canGrow(Shelf.Trending))
        offline = false
        repo.more(MediaKind.Movie, Shelf.Trending)
        assertEquals(3, repo.shelves(MediaKind.Movie).value.rows[Shelf.Trending]?.size)
    }

    @Test
    fun `a shelf added since the file was written is fetched at once`() = runTest {
        repository().refresh(MediaKind.Movie, now)
        File(dir, "media-movie.json").let { it.writeText(it.readText().replace("\"Action\"", "\"Gone\"")) }
        val repo = repository()
        asked.clear()
        repo.refresh(MediaKind.Movie, now.plus(Duration.ofMinutes(5)))
        assertEquals(listOf("The General"), repo.shelves(MediaKind.Movie).value.rows[Shelf.Action]?.map { it.title })
    }

    @Test
    fun `offline keeps what was there`() = runTest {
        val repo = repository()
        repo.refresh(MediaKind.Movie, now)
        offline = true
        repo.refresh(MediaKind.Movie, now.plus(Duration.ofDays(1)))
        assertEquals(now, repo.shelves(MediaKind.Movie).value.fetchedAt)
        assertEquals(2, repo.shelves(MediaKind.Movie).value.rows[Shelf.Trending]?.size)
    }

    @Test
    fun `details are asked for once`() = runTest {
        val repo = repository()
        repo.refresh(MediaKind.Movie, now)
        val metropolis = repo.shelves(MediaKind.Movie).value.rows.getValue(Shelf.Trending).first()
        assertEquals(153, assertNotNull(repo.details(metropolis)).runtimeMinutes)
        repo.details(metropolis)
        assertEquals(1, asked.count { it == "movie/1" })
    }
}
