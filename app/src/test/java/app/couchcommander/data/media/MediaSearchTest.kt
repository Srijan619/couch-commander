package app.couchcommander.data.media

import app.media.MediaKind
import app.media.MovieDetails
import app.media.Person
import app.media.PersonDetails
import app.media.SearchPage
import app.media.SearchResult
import app.media.SearchScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MediaSearchTest {
    private fun title(id: Int, name: String) = SearchResult.Title(MovieDetails(id = id, title = name, year = 1927, rating = 8.0, kind = MediaKind.Movie))

    private val asked = mutableListOf<Triple<String, SearchScope, Int>>()
    private var offline = false

    private fun TestScope.search() = MediaSearch(
        scope = this,
        search = { q, scope, page ->
            asked += Triple(q, scope, page)
            if (offline) null else SearchPage(listOf(title(page * 10 + 1, "$q $page"), title(page * 10 + 2, "$q ${page}b")), page, hasMore = page < 2, total = 4)
        },
        people = { id -> PersonDetails(id, "Brigitte Helm", "Acting", null, null, null, null, null, emptyList()) },
    )

    @Test
    fun `asks once typing stops, for what's typed last`() = runTest {
        val s = search()
        s.onQueryChange("me")
        s.onQueryChange("metro")
        s.onQueryChange("metropolis ")
        advanceUntilIdle()
        assertEquals(listOf(Triple("metropolis", SearchScope.All, 1)), asked)
        assertEquals(listOf("metropolis 1", "metropolis 1b"), s.state.value.shown.map { (it as SearchResult.Title).movie.title })
    }

    @Test
    fun `a letter isn't enough to search`() = runTest {
        val s = search()
        s.onQueryChange("m")
        advanceUntilIdle()
        assertTrue(asked.isEmpty())
        assertTrue(!s.state.value.searchable)
    }

    @Test
    fun `more pages add to the results, until there are none`() = runTest {
        val s = search()
        s.onQueryChange("nosferatu")
        advanceUntilIdle()
        s.loadMore()
        advanceUntilIdle()
        s.loadMore()
        advanceUntilIdle()
        assertEquals(4, s.state.value.shown.size)
        assertEquals(listOf(1, 2), asked.map { it.third })
    }

    @Test
    fun `a new scope searches again, and the old results aren't shown for it`() = runTest {
        val s = search()
        s.onQueryChange("dracula")
        advanceUntilIdle()
        s.onScopeChange(SearchScope.Tv)
        assertTrue(s.state.value.shown.isEmpty())
        advanceUntilIdle()
        assertEquals(SearchScope.Tv, asked.last().second)
        assertEquals(2, s.state.value.shown.size)
    }

    @Test
    fun `offline fails, and Retry asks again`() = runTest {
        val s = search()
        offline = true
        s.onQueryChange("faust")
        advanceUntilIdle()
        assertTrue(s.state.value.failed)
        offline = false
        s.onRetry()
        advanceUntilIdle()
        assertEquals(2, s.state.value.shown.size)
    }

    @Test
    fun `a person opens over the results and closes back to them`() = runTest {
        val s = search()
        s.onQueryChange("helm")
        advanceUntilIdle()
        s.onPersonOpen(Person(7, "Brigitte Helm", "Acting", null))
        advanceUntilIdle()
        assertNotNull(s.state.value.personInfo)
        s.onPersonClose()
        assertEquals(2, s.state.value.shown.size)
    }
}
