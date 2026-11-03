package io.github.lonode.debridbooks

import android.app.Application
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.standalone.*
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProgressiveCoverTest {
    // Keep these provider-specific fixtures isolated from automatic public discovery.
    private val testedSources = setOf("C411", "Tr4ker", "YggReborn", "Jackett", "AudiobookBay")
    private fun ok(body: String) = DirectResponse(200, body.toByteArray())
    private fun config(abb: Boolean = false) = StandaloneConfig("synthetic", abbDomain = if (abb) "cover-abb.invalid" else "",
        jackettUrl = "https://cover-jackett.invalid", jackettApiKey = "synthetic")
    private fun feed(titles: List<String>) = ok("""{"Results":[${titles.mapIndexed { index, title ->
        """{"Title":"$title [MP3]","InfoHash":"${(index + 1).toString(16).padStart(40, '0')}","Language":"English"}"""
    }.joinToString(",") }]}""")
    private suspend fun next(updates: Channel<SearchProgress>, predicate: (SearchProgress) -> Boolean): SearchProgress = withTimeout(10_000) {
        var update = updates.receive()
        while (!predicate(update)) update = updates.receive()
        update
    }
    private fun account() = ok("""{"status":"success","data":{"magnets":[{"hash":"${"1".padStart(40, '0')}","statusCode":4}]}}""")
    private fun term(request: DirectRequest) = URI(request.url).rawQuery.split('&').first { it.startsWith("term=") }
        .substringAfter('=').let { URLDecoder.decode(it, "UTF-8") }

    @Test fun `queued covers survive three seconds and stream before a pending source completes`() = runBlocking {
        val titles = listOf("Progressive Cedar", "Progressive Oak", "Progressive Maple", "Progressive Pine", "Progressive Birch", "Progressive Willow")
        val abb = CompletableDeferred<Unit>()
        val readiness = CompletableDeferred<Unit>()
        val updates = Channel<SearchProgress>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val http = DirectTransport { request -> when (URI(request.url).host) {
            "cover-jackett.invalid" -> feed(titles)
            "api.alldebrid.com" -> { readiness.await(); account() }
            "cover-abb.invalid" -> { abb.await(); ok("<html></html>") }
            "itunes.apple.com" -> {
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try {
                    delay(3100)
                    ok("""{"results":[{"collectionName":"${term(request)}","artistName":"Regression Writer","artworkUrl100":"https://images.invalid/100x100bb.jpg"}]}""")
                } finally { active.decrementAndGet() }
            }
            else -> error("Unexpected metadata fallback")
        } }
        val job = async { StandaloneApi(config(abb = true), http).search("Progressive", 1, false, false, onlySources = testedSources) { updates.send(it) } }
        val raw = next(updates) { it.page.results.size == 6 }
        assertTrue(raw.page.results.all { it.poster == null })
        val four = next(updates) { it.page.results.count { item -> item.poster != null } == 4 }
        assertFalse(job.isCompleted)
        assertFalse(four.finished)
        assertEquals(SearchSourceState.Pending, four.sources.single { it.source == "AudiobookBay" }.state)
        readiness.complete(Unit)
        val ready = next(updates) { it.page.results.firstOrNull()?.cached == true }
        assertTrue(ready.page.results.count { it.poster != null } >= 4)
        next(updates) { it.page.results.count { item -> item.poster != null } == 6 }
        assertEquals(4, peak.get())
        abb.complete(Unit)
        val result = job.await()
        assertEquals(titles, result.results.map { it.title })
        assertTrue(result.results.first().cached)
        assertEquals(6, result.results.map { it.id }.distinct().size)
        val finished = next(updates) { it.finished }
        assertEquals(6, finished.sources.single { it.source == "Book details" }.count)
    }

    @Test fun `timed out first catalog still permits a fallback cover`() = runBlocking {
        var timedOut = false
        val http = DirectTransport { request -> when (URI(request.url).host) {
            "cover-jackett.invalid" -> feed(listOf("Fallback Cedar"))
            "api.alldebrid.com" -> account()
            "itunes.apple.com" -> try { delay(10_000); ok("{}") } finally { timedOut = true }
            "api.audible.com" -> ok("""{"products":[{"asin":"B012345678","title":"Fallback Cedar","language":"english","authors":[{"name":"Regression Writer"}],"product_images":{"500":"https://images.invalid/fallback.jpg"}}]}""")
            else -> ok("{}")
        } }
        val result = StandaloneApi(config(), http).search("Fallback Cedar", 1, false, false, onlySources = testedSources)
        assertTrue(timedOut)
        assertEquals("https://images.invalid/fallback.jpg", result.results.single().poster)
        assertFalse(result.partial)
    }

    @Test fun `cancelled owner prevents queued covers starting before child cancellation arrives`() = runBlocking {
        val owner = Job()
        val started = AtomicInteger()
        val active = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http = DirectTransport {
            if (started.incrementAndGet() == 4) active.complete(Unit)
            release.await()
            ok("{}")
        }
        val catalog = CatalogClient(http, owner)
        // Deliberately keep these children active to reproduce the interval between
        // owner cancellation and delivery of cancellation to each child.
        val requests = (1..5).map { index -> async {
            catalog.enrich(SearchItem("owner-cancel-$index", "Owner Cancel Book $index", language = "en"))
        } }
        try {
            withTimeout(5000) { active.await() }
            assertEquals(4, started.get())
            owner.cancel()
            release.complete(Unit)
            withTimeout(5000) { requests.joinAll() }
            assertTrue(requests.all { it.isCancelled })
            assertEquals("Queued books and fallback requests must not start", 4, started.get())
        } finally {
            owner.cancel()
            requests.forEach { it.cancel() }
            requests.joinAll()
        }
    }

    @Test fun `cancelling search stops active cover requests and never starts queued books`() = runBlocking {
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val active = CompletableDeferred<Unit>()
        val updates = Channel<SearchProgress>(Channel.UNLIMITED)
        val emitted = AtomicInteger()
        val http = DirectTransport { request -> when (URI(request.url).host) {
            "cover-jackett.invalid" -> feed(listOf("Cancel Cedar", "Cancel Oak", "Cancel Maple", "Cancel Pine", "Cancel Birch"))
            "api.alldebrid.com" -> account()
            else -> {
                if (started.incrementAndGet() == 4) active.complete(Unit)
                try { awaitCancellation() } finally { cancelled.incrementAndGet() }
            }
        } }
        val job = launch { StandaloneApi(config(), http).search("Cancel", 1, false, false, onlySources = testedSources) { emitted.incrementAndGet(); updates.send(it) } }
        val raw = next(updates) { it.page.results.size == 5 }
        assertFalse(raw.finished)
        withTimeout(5000) { active.await() }
        assertEquals(4, started.get())
        job.cancelAndJoin()
        val count = emitted.get()
        assertEquals(4, cancelled.get())
        assertEquals(4, started.get())
        assertEquals(count, emitted.get())
        updates.close()
        for (update in updates) assertFalse(update.finished)
    }
}
