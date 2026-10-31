package io.github.lonode.debridbooks.domain

import io.github.lonode.debridbooks.network.ApiFailure
import io.github.lonode.debridbooks.network.safeSource

data class SearchRequest(val query: String, val number: Int = 1, val refresh: Boolean = false, val sources: Set<String> = emptySet(), val forceAbb: Boolean = false)
data class SearchFailure(val message: String, val warnings: List<SearchWarning> = emptyList()) {
    companion object {
        fun from(error: Exception): SearchFailure {
            val api = error as? ApiFailure
            val message = when {
                error is io.github.lonode.debridbooks.network.standalone.DirectFailure -> error.displayMessage()
                api?.status == 403 -> "Server access was rejected. Check the configuration link."
                api?.status == 400 -> "The server rejected this search. Check the configuration link and query."
                api?.code == "NO_SEARCH_SOURCES" -> "This content needs an enabled search source. Enable one in Settings."
                api?.code == "SEARCH_UNAVAILABLE" || api?.status == 503 -> "Search is unavailable right now. Try again later."
                api?.code == "SEARCH_FAILED" || api?.status == 502 -> "The server could not complete this search. Please retry."
                else -> "Search could not be completed. Check the connection and retry."
            }
            return SearchFailure(message, api?.warnings.orEmpty())
        }
    }
}
/** Retains successful pages on failure; replacing/retrying a page does not duplicate or exhaust it. */
data class SearchSession(
    val query: String = "", val pages: Map<Int, SearchPage> = emptyMap(),
    val attempt: SearchRequest? = null, val failure: SearchFailure? = null, val wasCancelled: Boolean = false,
    val sources: List<SearchSourceProgress> = emptyList(),
) {
    val results: List<SearchItem> = pages.toSortedMap().values.flatMap { it.results }.distinctBy { it.id }
    /** Unique retained uploads, across pages; source.count describes only the latest request. */
    fun loadedFrom(source: String): Int = results.count { (it.searchSource ?: it.tracker) == source }
    val partial: Boolean = pages.values.any { it.partial }
    val warnings: List<SearchWarning> = (pages.values.flatMap { it.warnings } + failure?.warnings.orEmpty()).distinctBy { it.source to it.code }
    val feedbackMessage: String? get() = when {
        wasCancelled -> if(pages.isEmpty()) "Search cancelled." else "Search cancelled. Previously loaded results are still shown."
        failure != null -> failure.message
        partial && results.isEmpty() -> "Search was incomplete. Available sources found no matches."
        partial -> "Some search sources did not complete. Results below may be incomplete."
        pages.isNotEmpty() && results.isEmpty() -> "No results found. Try another title or author."
        else -> null
    }
    val nextPage: Int get() = (pages.keys.maxOrNull() ?: 0) + 1
    val retry: SearchRequest? get() = if (failure != null || wasCancelled) attempt?.copy(forceAbb = false) else
        pages.entries.firstOrNull { it.value.partial || it.value.warnings.isNotEmpty() }?.let { SearchRequest(query, it.key, sources = if (sources.isEmpty()) emptySet() else it.value.warnings.map { warning -> warning.source }.filter { source -> source != "AllDebrid" }.toSet()) }
    /** Continue only when the last page contributed new results; never auto-retry a failed source. */
    fun confirmedReady(id: String) = copy(pages = pages.mapValues { (_, page) ->
        page.copy(results = page.results.map { if (it.id == id) it.copy(cached = true) else it })
    })
    fun automaticNextRequest(): SearchRequest? {
        if (wasCancelled || failure != null || pages.isEmpty() || nextPage > 10000) return null
        val number = pages.keys.maxOrNull() ?: return null
        val earlier = pages.filterKeys { it < number }.values.flatMap { it.results }.map { it.id }.toSet()
        if (pages[number]?.results.orEmpty().none { it.id !in earlier }) return null
        val successful = sources.filter { it.state == SearchSourceState.Complete && it.source !in setOf("AllDebrid", "Book details") }.map { it.source }.toSet()
        if (sources.isNotEmpty() && successful.isEmpty()) return null
        if (sources.isEmpty() && pages[number]?.partial == true) return null
        return SearchRequest(query, nextPage, sources = successful)
    }
    fun requesting(request: SearchRequest) = copy(attempt = request, failure = null, wasCancelled = false, sources = if (request.sources.isEmpty()) emptyList() else sources)
    fun received(page: SearchPage): SearchSession {
        if(wasCancelled) return this
        val request = checkNotNull(attempt)
        val previous = if (query == request.query) pages else emptyMap()
        val merged = if (request.sources.isNotEmpty()) {
            val old = previous[request.number]
            val warnings = old?.warnings.orEmpty().filterNot { it.source in request.sources || it.source == "AllDebrid" } + page.warnings
            page.copy(results = (old?.results.orEmpty().filterNot { (it.searchSource ?: it.tracker) in request.sources } + page.results).distinctBy { it.id },
                warnings = warnings, partial = warnings.isNotEmpty())
        } else page
        val updated = if (request.refresh && request.sources.isEmpty()) mapOf(request.number to merged) else previous + (request.number to merged)
        return copy(query = request.query, pages = updated, failure = null)
    }
    fun progressing(update: SearchProgress): SearchSession {
        if (wasCancelled) return this
        val updated = if (update.page.results.isNotEmpty() || update.finished) received(update.page) else this
        return updated.copy(sources = if (attempt?.sources.isNullOrEmpty()) update.sources else sources.filterNot { old -> update.sources.any { it.source == old.source } } + update.sources)
    }
    fun cancelled() = copy(wasCancelled = true, failure = null,
        sources = sources.map { if (it.state == SearchSourceState.Pending) it.copy(state = SearchSourceState.Cancelled) else it })
    fun failed(error: Exception): SearchSession {
        if (wasCancelled) return this
        val errorState = SearchFailure.from(error)
        return copy(failure = if (attempt?.sources.isNullOrEmpty()) errorState else errorState.copy(message =
            if (results.isEmpty()) "The selected source could not complete the retry." else "The selected source could not complete the retry. Previously loaded results are still shown."))
    }
}
fun SearchWarning.displayWarning(): String = "${safeSource(source)}: ${searchIssueLabel(code, source)}"

/** Safe source names, counts and reason codes only; never URLs or exception messages. */
enum class SearchSourceState { Pending, Complete, Partial, Failed, Cancelled }
data class SearchSourceProgress(val source: String, val state: SearchSourceState = SearchSourceState.Pending,
    val count: Int = 0, val issue: String? = null, val cooldownUntil: Long? = null, val cached: Boolean = false)
data class SearchProgress(val page: SearchPage, val sources: List<SearchSourceProgress>, val finished: Boolean = false)
fun searchIssueLabel(code: String?, source: String? = null): String = when (code) {
    "COOLDOWN" -> "Paused to protect your IP address."
    "LIMIT" -> "Rate limited. Wait before retrying."
    "BLOCKED" -> "Access blocked by the site. Try again later."
    "TIMEOUT" -> if (source == "AudiobookBay") "Request timed out. You may be banned, or the site may be unreachable." else "Request timed out. Try again later."
    "DNS" -> "Domain could not be reached. Check your connection."
    "TLS" -> "Secure connection failed. Try again later."
    "AUTH", "AUTH_BLOCKED" -> "Access rejected. Check this source in Settings."
    "INVALID_RESPONSE" -> "Unexpected response from the site."
    else -> "Unavailable or incomplete. Try again later."
}
