package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.ApiFailure
import io.github.lonode.debridbooks.network.standalone.*
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class ProgressiveSearchTest {
    // Keep these provider-specific fixtures isolated from automatic public discovery.
    private val testedSources = setOf("C411", "Tr4ker", "YggReborn", "Jackett", "AudiobookBay")
    private val hash = "a".repeat(40)
    private val config = StandaloneConfig("synthetic-provider", abbDomain = "abb.invalid",
        jackettUrl = "https://jackett.invalid", jackettApiKey = "synthetic-tracker")
    private val abbOnly = StandaloneConfig("synthetic-provider", abbDomain = "abb.invalid")
    private fun ok(text: String) = DirectResponse(200, text.toByteArray())
    private fun jackett() = ok("""{"Results":[{"Title":"Example English MP3","InfoHash":"$hash","Language":"English","Tracker":"Custom"}]}""")
    private fun account() = ok("""{"status":"success","data":{"magnets":[{"hash":"$hash","statusCode":4}]}}""")

    @Test fun `fast source is published before slow source and readiness can update the same result`() = runBlocking {
        val abb = CompletableDeferred<Unit>()
        val readiness = CompletableDeferred<Unit>()
        val updates = Channel<SearchProgress>(Channel.UNLIMITED)
        val http = DirectTransport { request -> when (URI(request.url).host) {
            "jackett.invalid" -> jackett()
            "api.alldebrid.com" -> { readiness.await(); account() }
            else -> { abb.await(); DirectResponse(429, byteArrayOf()) }
        } }
        val job = async { TorrentSearch(config, http).search("Example", 1, false, onlySources = testedSources) { updates.send(it) } }
        suspend fun matching(predicate: (SearchProgress) -> Boolean): SearchProgress = withTimeout(5000) {
            var update = updates.receive()
            while (!predicate(update)) update = updates.receive()
            update
        }
        val fast = matching { it.page.results.isNotEmpty() }
        assertFalse(job.isCompleted)
        assertEquals(SearchSourceState.Pending, fast.sources.single { it.source == "AudiobookBay" }.state)
        assertFalse(fast.page.results.single().cached)
        val id = fast.page.results.single().id
        readiness.complete(Unit)
        val cached = matching { it.page.results.any { result -> result.cached } }
        assertEquals(id, cached.page.results.single().id)
        assertEquals(SearchSourceState.Pending, cached.sources.single { it.source == "AudiobookBay" }.state)
        abb.complete(Unit)
        val final = withTimeout(5000) { job.await() }
        assertEquals(id, final.results.single().id)
        assertTrue(final.partial)
        assertEquals("LIMIT", final.warnings.single().code)
        val finished = matching { it.finished }
        assertEquals("LIMIT", finished.sources.single { it.source == "AudiobookBay" }.issue)
    }

    @Test fun `cancel after streaming results keeps them and cancels the pending network operation`() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val published = CompletableDeferred<SearchProgress>()
        val stopped = CompletableDeferred<Unit>()
        val http = DirectTransport { request -> when (URI(request.url).host) {
            "jackett.invalid" -> jackett()
            "api.alldebrid.com" -> account()
            else -> try { pending.await(); ok("") } finally { stopped.complete(Unit) }
        } }
        var session = SearchSession().requesting(SearchRequest("Example", refresh = true))
        val job = launch { TorrentSearch(config, http).search("Example", 1, false, onlySources = testedSources) {
            session = session.progressing(it)
            if (it.page.results.isNotEmpty()) published.complete(it)
        } }
        withTimeout(5000) { published.await() }
        job.cancelAndJoin()
        withTimeout(5000) { stopped.await() }
        session = session.cancelled()
        assertEquals(1, session.results.size)
        assertEquals(SearchSourceState.Cancelled, session.sources.single { it.source == "AudiobookBay" }.state)
        assertEquals(SearchRequest("Example", refresh = true), session.retry)
        assertSame(session, session.progressing(SearchProgress(SearchPage(emptyList()), emptyList(), true)))
    }

    @Test fun `AudiobookBay distinguishes rate limit forbidden challenge timeout and empty success`() = runBlocking {
        for ((response, expected) in listOf(
            DirectResponse(429, byteArrayOf()) to "LIMIT",
            DirectResponse(403, byteArrayOf()) to "BLOCKED",
            ok("<title>Just a moment...</title>") to "BLOCKED",
            DirectResponse(503, "<script src='/cdn-cgi/challenge-platform/'></script>".toByteArray()) to "BLOCKED",
            null to "TIMEOUT",
        )) {
            val http = DirectTransport { request -> if (URI(request.url).host == "api.alldebrid.com") account()
                else response ?: throw DirectFailure("Service", "TIMEOUT") }
            val failure = runCatching { TorrentSearch(abbOnly, http).search("Example", 1, false, onlySources = testedSources) }.exceptionOrNull() as ApiFailure
            assertEquals(expected, failure.warnings.single { it.source == "AudiobookBay" }.code)
            assertFalse(failure.toString().contains("synthetic"))
        }
        val http = DirectTransport { request -> if (URI(request.url).host == "api.alldebrid.com") account() else ok("<title>Example Audiobook</title><h3>Not Found</h3>") }
        val empty = TorrentSearch(abbOnly, http).search("Example", 1, false, onlySources = testedSources)
        assertTrue(empty.results.isEmpty())
        assertFalse(empty.partial)
    }

    @Test fun `lazy search never fetches detail pages and a selected rate limited detail stops later requests`() = runBlocking {
        val requests = mutableListOf<String>()
        val http = DirectTransport { request ->
            requests += request.url
            when {
                URI(request.url).host == "api.alldebrid.com" -> account()
                URI(request.url).path == "/abss/limited/" -> DirectResponse(429,byteArrayOf())
                else -> ok("""<title>Example Audiobook</title><a href="/abss/good/" rel="bookmark">Good</a><a href="/abss/limited/" rel="bookmark">Limited</a>""")
            }
        }
        val search = TorrentSearch(abbOnly,http)
        val page = search.search("Example",1,false, onlySources = testedSources)
        assertEquals(2,page.results.size); assertFalse(page.partial)
        assertEquals(0,requests.count { URI(it).path.startsWith("/abss/") })
        val limited = ResolverItem.decode(page.results.single { it.title == "Limited" }.id)
        val error = runCatching { search.resolveAbb(limited) }.exceptionOrNull() as DirectFailure
        assertEquals("LIMIT",error.reason)
        val again = runCatching { search.resolveAbb(ResolverItem.decode(page.results.single { it.title == "Good" }.id)) }.exceptionOrNull() as DirectFailure
        assertEquals("COOLDOWN",again.reason)
        assertEquals(1,requests.count { URI(it).path.startsWith("/abss/") })
    }
    @Test fun `all sources failing cannot replace previously loaded pages with an empty preview`() = runBlocking {
        val previous = SearchItem("previous", "Previous book")
        var session = SearchSession().requesting(SearchRequest("previous", refresh = true)).received(SearchPage(listOf(previous)))
            .requesting(SearchRequest("new", refresh = true))
        val http = DirectTransport { request -> if (URI(request.url).host == "api.alldebrid.com") account() else DirectResponse(503, byteArrayOf()) }
        val failure = runCatching { TorrentSearch(abbOnly, http).search("new", 1, false, onlySources = testedSources) { session = session.progressing(it) } }.exceptionOrNull() as ApiFailure
        session = session.failed(failure)
        assertEquals(listOf(previous), session.results)
        assertEquals("previous", session.query)
        assertEquals(SearchRequest("new", refresh = true), session.retry)
        assertEquals(SearchSourceState.Failed, session.sources.single { it.source == "AudiobookBay" }.state)
    }

}
