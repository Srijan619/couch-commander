package app.couchcommander.data.media

import app.media.Person
import app.media.PersonDetails
import app.media.SearchPage
import app.media.SearchResult
import app.media.SearchScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val scope: SearchScope = SearchScope.All,
    /** What [results] are for; they're shown only while that's still what's typed and picked. */
    val resultsFor: Pair<String, SearchScope>? = null,
    val results: List<SearchResult> = emptyList(),
    val pagesLoaded: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    /** The last request failed; Retry tries again. */
    val failed: Boolean = false,
    /** The person whose films and series show in place of the results. */
    val person: Person? = null,
    /** Credits of people opened, by id; missing while loading. */
    val personDetails: Map<Int, PersonDetails> = emptyMap(),
    /** Loading the open person's credits failed; Retry tries again. */
    val personFailed: Boolean = false,
) {
    private val current: Boolean get() = resultsFor == query.trim() to scope

    /** The results for what's typed now. */
    val shown: List<SearchResult> get() = if (current) results else emptyList()

    /** The open person's credits; null while loading. */
    val personInfo: PersonDetails? get() = person?.let { personDetails[it.id] }

    /** Typed enough to search. */
    val searchable: Boolean get() = query.trim().length >= MIN_SEARCH_LENGTH

    /** Waiting for the first page of what's typed now (including the pause before asking). */
    val firstLoading: Boolean get() = searchable && (!current || (loading && pagesLoaded == 0))

    val canLoadMore: Boolean get() = current && hasMore && !loading && !failed

    companion object {
        const val MIN_SEARCH_LENGTH = 2
    }
}

/**
 * The Movies and TV page's search: films, series and people from TMDB, as Garbled searches them. Asks a moment
 * after typing stops, a page at a time as the end of the results nears; a person opens their films and series.
 */
class MediaSearch(
    private val scope: CoroutineScope,
    private val search: suspend (query: String, scope: SearchScope, page: Int) -> SearchPage?,
    private val people: suspend (id: Int) -> PersonDetails?,
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var job: Job? = null

    /** Starts over on [scope] with nothing typed; credits already loaded are kept. */
    fun reset(scope: SearchScope) {
        job?.cancel()
        _state.update { SearchState(scope = scope, personDetails = it.personDetails) }
    }

    fun onQueryChange(query: String) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query, person = null) }
        restart(debounce = true)
    }

    fun onScopeChange(scope: SearchScope) {
        if (scope == _state.value.scope) return
        _state.update { it.copy(scope = scope, person = null) }
        // Focus opens a tab, so moving across them to the last one asks only for that one.
        restart(debounce = true)
    }

    fun onRetry() {
        val s = _state.value
        when {
            s.person != null -> onPersonOpen(s.person)
            s.pagesLoaded == 0 -> restart(debounce = false)
            else -> loadMore()
        }
    }

    /** Fetches the next page as the end of the results nears. */
    fun loadMore() {
        val s = _state.value
        if (!(s.hasMore && !s.loading) || s.resultsFor == null) return
        val (query, scope) = s.resultsFor
        _state.update { it.copy(loading = true, failed = false) }
        job = this.scope.launch { fetch(query, scope, s.pagesLoaded + 1) }
    }

    /** New words or a new scope: a different search, so start over, after a pause while typing. */
    private fun restart(debounce: Boolean) {
        job?.cancel()
        val s = _state.value
        val query = s.query.trim()
        if (query.length < SearchState.MIN_SEARCH_LENGTH) {
            _state.update { it.copy(resultsFor = null, results = emptyList(), loading = false, failed = false) }
            return
        }
        // Same search (a trailing space, say) and already have it; one cut off just now starts again.
        if (s.resultsFor == query to s.scope && !s.failed && !s.loading) return
        job = scope.launch {
            if (debounce) delay(SEARCH_DELAY_MS)
            _state.update { it.copy(loading = true, failed = false, pagesLoaded = 0, results = emptyList(), resultsFor = query to s.scope) }
            fetch(query, s.scope, 1)
        }
    }

    private suspend fun fetch(query: String, scope: SearchScope, page: Int) {
        val result = search(query, scope, page)
        _state.update {
            when {
                it.resultsFor != query to scope -> it // typed on since
                result == null -> it.copy(loading = false, failed = true)
                else -> it.copy(
                    results = (it.results + result.results).distinctBy { r -> r.key },
                    pagesLoaded = page,
                    hasMore = result.hasMore,
                    loading = false,
                    failed = false,
                )
            }
        }
    }

    /** Shows the person's best-known titles at once, then all their films and series. */
    fun onPersonOpen(person: Person) {
        _state.update { it.copy(person = person, personFailed = false) }
        if (person.id in _state.value.personDetails) return
        scope.launch {
            val details = people(person.id)
            _state.update {
                if (details == null) it.copy(personFailed = it.person?.id == person.id)
                else it.copy(personDetails = it.personDetails + (person.id to details))
            }
        }
    }

    fun onPersonClose() = _state.update { it.copy(person = null, personFailed = false) }

    private companion object {
        const val SEARCH_DELAY_MS = 350L
    }
}
