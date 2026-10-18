package app.couchcommander.data.media

import app.media.MediaKind
import app.media.MovieDetails
import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContinueWatchingTest {
    private val dir: File = Files.createTempDirectory("continue").toFile()

    private fun title(id: Int, name: String, kind: MediaKind = MediaKind.Movie) =
        MovieDetails(id = id, title = name, year = 1927, rating = 8.0, kind = kind, cast = listOf("Brigitte Helm"))

    private fun at(title: MovieDetails, positionMs: Long = 60_000) = Watching(title, "EN", "10", "mkv", positionMs, 6_000_000)

    @Test
    fun `the latest first, once each, and kept on disk`() {
        val played = ContinueWatching(dir)
        played.save(at(title(1, "Metropolis")))
        played.save(at(title(2, "Nosferatu")))
        played.save(at(title(1, "Metropolis"), positionMs = 120_000))
        val again = ContinueWatching(dir).of(MediaKind.Movie)
        assertEquals(listOf("Metropolis", "Nosferatu"), again.map { it.title.title })
        assertEquals(120_000, again.first().positionMs)
        assertEquals(0.02f, again.first().progress)
    }

    @Test
    fun `films and series apart, even with the same id`() {
        val played = ContinueWatching(dir)
        played.save(at(title(1, "Metropolis")))
        played.save(at(title(1, "The Twilight Zone", MediaKind.Tv)).copy(season = 1, episode = 2))
        assertEquals(listOf("Metropolis"), played.of(MediaKind.Movie).map { it.title.title })
        assertEquals(listOf("The Twilight Zone"), played.of(MediaKind.Tv).map { it.title.title })
    }

    @Test
    fun `drops the oldest past the limit, and keeps only what a poster needs`() {
        val played = ContinueWatching(dir, max = 2)
        played.save(at(title(1, "Metropolis")))
        played.save(at(title(2, "Nosferatu")))
        played.save(at(title(3, "The Kid")))
        assertEquals(listOf("The Kid", "Nosferatu"), played.of(MediaKind.Movie).map { it.title.title })
        assertEquals(emptyList(), played.of(MediaKind.Movie).first().title.cast)
    }

    @Test
    fun `remove takes it off`() {
        val played = ContinueWatching(dir)
        played.save(at(title(1, "Metropolis")))
        played.remove(title(1, "Metropolis"))
        assertNull(ContinueWatching(dir).get(title(1, "Metropolis")))
    }
}
