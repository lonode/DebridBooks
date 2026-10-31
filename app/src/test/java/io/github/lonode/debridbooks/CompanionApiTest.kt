package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CompanionApiTest {
    private data class Reply(val status: Int = 200, val body: String = "{}", val type: String = "application/json")
    private class Fixture(val handler: (String) -> Reply) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val connection = Connection("http://127.0.0.1:${server.localPort}/synthetic_private_config", "account")
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        val path = input.readLine().split(" ")[1]
                        var line = input.readLine()
                        while (!line.isNullOrEmpty()) { check(!line.startsWith("Authorization:", true)); line = input.readLine() }
                        requests += path
                        val response = handler(path)
                        val bytes = response.body.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 ${response.status} Test\r\nContent-Type: ${response.type}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                } catch (_: SocketException) { if (!server.isClosed) throw IllegalStateException("Fixture socket failed") }
            }
        }
        override fun close() { server.close(); worker.join(5000) }
    }
    private val health = """{"ok":true,"addon":"DebridBooks","torboxKeyValid":true}"""
    private val warning = SearchWarning("YggReborn", "SOURCE_UNAVAILABLE", "Synthetic source warning")
    private val book = SearchItem("tbab:synthetic_id", "Un Livre", language = "fr")
    private fun success(page: SearchPage) = SearchSession().requesting(SearchRequest("livre", refresh = true)).received(page)

    @Test fun `old server setup search audio resolution and comics omit all new fields`() = runBlocking {
        Fixture { path ->
            when {
                path.endsWith("/health") -> Reply(body = health)
                path.contains("/streams/") -> Reply(body = """{"ready":true,"streams":[{"title":"Part 1","url":"https://example.invalid/audio"}]}""")
                else -> Reply(body = """{"results":[{"id":"tbab:synthetic_id","title":"Book"}]}""")
            }
        }.use { fixture ->
            val api = MobileApi(fixture.connection)
            assertNull(api.capabilities().features)
            val audio = api.search("livre")
            val comic = api.search("comic", comic = true)
            val manga = api.search("manga", comic = true, series = true)
            assertFalse(audio.partial); assertTrue(audio.warnings.isEmpty())
            assertEquals(1, comic.results.size); assertEquals(1, manga.results.size)
            val resolution = api.resolve(audio.results.single().id)
            assertTrue(resolution.ready); assertNull(resolution.status); assertNull(resolution.streams.single().size)
        }
    }
    @Test fun `feature flags are optional and explicit provider validation wins over compatibility alias`() {
        val caps = MobileContract.parseCapabilities("""{"ok":true,"addon":"DebridBooks","provider":"alldebrid","providerKeyValid":true,"features":{"bookDetails":false,"searchWarnings":true,"streamFileSizes":true}}""")
        assertEquals(false, caps.features?.bookDetails); assertEquals(true, caps.features?.searchWarnings)
        assertThrows(IllegalArgumentException::class.java) { MobileContract.parseCapabilities(health.replace("\"torboxKeyValid\":true", "\"providerKeyValid\":false,\"torboxKeyValid\":true")) }
    }
    @Test fun `partial results stay visible with safe source warning and a manual retry`() {
        val page = MobileContract.parseSearch("""{"results":[{"id":"x","title":"Book"}],"partial":true,"warnings":[{"source":"YggReborn","code":"SOURCE_UNAVAILABLE","message":"upstream unavailable"}]}""")
        val state = success(page)
        assertEquals("Book", state.results.single().title)
        assertTrue(state.feedbackMessage!!.contains("incomplete"))
        assertTrue(state.warnings.single().displayWarning().contains("YggReborn"))
        assertEquals(SearchRequest("livre", 1), state.retry)
        assertEquals(2, state.nextPage)
    }
    @Test fun `empty incomplete search is distinct from a complete empty search`() {
        val partial = success(SearchPage(emptyList(), true, listOf(warning)))
        val complete = success(SearchPage(emptyList()))
        assertTrue(partial.feedbackMessage!!.contains("incomplete")); assertNotNull(partial.retry)
        assertTrue(complete.feedbackMessage!!.startsWith("No results")); assertNull(complete.retry)
    }
    @Test fun `outage retains previous results and failed pagination retries the same page`() = runBlocking {
        Fixture { Reply(503, """{"results":[],"partial":false,"code":"SEARCH_UNAVAILABLE","error":"secret ignored","warnings":[{"source":"Tr4ker","code":"SOURCE_UNAVAILABLE","message":"ignored"}]}""") }.use { fixture ->
            val old = success(SearchPage(listOf(book)))
            val requested = old.requesting(SearchRequest("livre", 2))
            val failure = try { MobileApi(fixture.connection).search("livre", 2); error("Expected outage") } catch (failure: ApiFailure) { failure }
            val failed = requested.failed(failure)
            assertEquals(old.results, failed.results); assertEquals(old.pages, failed.pages)
            assertEquals(SearchRequest("livre", 2), failed.retry); assertEquals(2, failed.nextPage)
            assertTrue(failed.feedbackMessage!!.contains("unavailable"))
            assertFalse(failed.partial)
        }
    }
    @Test fun `retrying an earlier partial page retains later successful pages and clears its warning`() {
        val first = success(SearchPage(listOf(book), true, listOf(warning)))
        val second = first.requesting(SearchRequest("livre", 2)).received(SearchPage(listOf(book.copy(id = "second"))))
        val retried = second.requesting(second.retry!!).received(SearchPage(listOf(book)))
        assertEquals(2, retried.results.size); assertFalse(retried.partial); assertTrue(retried.warnings.isEmpty())
        val fresh = retried.requesting(SearchRequest("other", refresh = true)).received(SearchPage(emptyList()))
        assertTrue(fresh.results.isEmpty()); assertEquals("other", fresh.query)
    }
    @Test fun `source configuration failure server failure HTML invalid and oversized errors are safe`() = runBlocking {
        val responses = listOf(
            Reply(503, """{"code":"NO_SEARCH_SOURCES"}"""),
            Reply(502, """{"code":"SEARCH_FAILED"}"""),
            Reply(503, """{"code":"SEARCH_UNAVAILABLE"}""", "text/html"),
            Reply(502, "<html>proxy error synthetic_private_config</html>"),
            Reply(503, "{invalid synthetic_private_config"),
            Reply(503, """{"code":"SEARCH_UNAVAILABLE","error":"${"x".repeat(17000)}"}"""),
            Reply(400, """{"error":"synthetic_private_config"}"""),
            Reply(403, """{"error":"synthetic_private_config"}"""),
        )
        for ((index, reply) in responses.withIndex()) Fixture { reply }.use { fixture ->
            val error = try { MobileApi(fixture.connection).search("test"); error("Expected failure") } catch (failure: ApiFailure) { failure }
            val state = SearchFailure.from(error)
            assertEquals(reply.status, error.status)
            assertFalse(error.toString().contains("synthetic_private_config")); assertNull(error.cause)
            assertFalse(state.message.contains("synthetic_private_config"))
            if (index == 0) assertTrue(state.message.contains("enabled search source"))
            if (index == 1) assertTrue(state.message.contains("server could not"))
            if (index in 2..5) assertNull(error.code)
        }
    }
    @Test fun `French details preserve paragraphs and cache keys isolate connection language and item`() = runBlocking {
        Fixture { path ->
            when {
                path.endsWith("/health") -> Reply(body = health)
                else -> Reply(body = """{"title":"Un Livre","description":"Premier paragraphe.\n\nDeuxième paragraphe.","genre":"Roman","year":2024,"author":"Alice Martin"}""")
            }
        }.use { fixture ->
            val repo = BookDetailsRepository()
            val french = repo.load(fixture.connection, book)!!
            assertEquals("Premier paragraphe.\n\nDeuxième paragraphe.", french.description)
            assertEquals(2024, french.year)
            assertEquals(french, repo.load(fixture.connection, book))
            assertEquals(1, fixture.requests.count { it.contains("/details/") })
            repo.load(fixture.connection, book.copy(language = "en"))
            repo.load(fixture.connection, book.copy(language = "multi", id = "tbab:other"))
            repo.load(Connection(fixture.connection.prefix + "2", "other"), book)
            assertEquals(4, fixture.requests.count { it.contains("/details/") })
            assertTrue(fixture.requests.any { it.endsWith("/app/details/tbab%3Asynthetic_id?language=fr") })
            assertTrue(fixture.requests.any { it.endsWith("?language=en") })
            assertTrue(fixture.requests.any { it.endsWith("/app/details/tbab%3Aother") })
            assertEquals("tbab:synthetic_id", book.id); assertEquals("fr", book.language)
        }
    }
    @Test fun `404 is remembered per connection while disabled and MangaDex details make no request`() = runBlocking {
        Fixture { path -> if (path.endsWith("/health")) Reply(body = health) else Reply(404) }.use { fixture ->
            val repo = BookDetailsRepository()
            assertNull(repo.load(fixture.connection, book))
            repo.capabilities(fixture.connection, MobileContract.parseCapabilities(health))
            assertNull(repo.load(fixture.connection, book.copy(id = "other")))
            assertEquals(1, fixture.requests.count { it.contains("/details/") })
            assertNull(repo.load(fixture.connection, book.copy(provider = "mangadex")))
            val disabled = Connection(fixture.connection.prefix + "_disabled", "disabled")
            repo.capabilities(disabled, MobileContract.parseCapabilities(health).copy(features = ServerFeatures(bookDetails = false)))
            assertNull(repo.load(disabled, book))
            assertFalse(fixture.requests.any { it.contains("_disabled") })
        }
    }
    @Test fun `null collection metadata and unsupported details leave ordinary resolution usable`() = runBlocking {
        for (detailsReply in listOf(Reply(body = """{"title":"Collection","description":null,"author":null,"genre":null,"year":null}"""), Reply(404))) {
            Fixture { path -> when {
                path.endsWith("/health") -> Reply(body = health)
                path.contains("/details/") -> detailsReply
                else -> Reply(body = """{"ready":true,"streams":[{"title":"Part","url":"https://example.invalid/audio"}]}""")
            } }.use { fixture ->
                val details = BookDetailsRepository().load(fixture.connection, book)
                assertNull(details?.description)
                assertTrue(MobileApi(fixture.connection).resolve(book.id).ready)
            }
        }
    }
    @Test fun `closing a details request cancels it without caching its response or affecting resolution`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val count = AtomicInteger()
        Fixture { path -> when {
            path.endsWith("/health") -> Reply(body = health)
            path.contains("/details/") -> {
                if (count.incrementAndGet() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                Reply(body = """{"description":"${count.get()}"}""")
            }
            else -> Reply(body = """{"ready":true,"streams":[{"title":"Part","url":"https://example.invalid/audio"}]}""")
        } }.use { fixture ->
            val repo = BookDetailsRepository()
            val request = async(Dispatchers.IO) { repo.load(fixture.connection, book) }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                request.cancel(); release.countDown(); request.join()
                assertTrue(request.isCancelled)
                assertTrue(MobileApi(fixture.connection).resolve(book.id).ready)
                assertEquals("2", repo.load(fixture.connection, book)?.description)
            } finally { release.countDown() }
        }
    }
    @Test fun `provider sizes are optional tolerant and independent of identities for both providers`() {
        for (provider in listOf("torbox", "alldebrid")) {
            val invalid = listOf("null", "0", "-1", "1.5", "1e30", "9223372036854775808", "\"123\"", "{}", "[]")
            for (size in invalid) {
                val result = MobileApi.parseResolution("""{"provider":"$provider","ready":true,"streams":[{"title":"Part","filename":"Disc 1/01.mp3","url":"https://example.invalid/audio","size":$size}]}""")
                assertNull(result.streams.single().size)
                assertFalse(result.streams.sizeEstimate().complete)
            }
            val part = MediaPart("Part", "https://example.invalid/first", "Disc 1/01.mp3", 12345678)
            assertEquals(part.id, part.copy(url = "https://example.invalid/renewed", size = null).id)
            val known = MobileApi.parseResolution("""{"ready":true,"streams":[{"title":"Part","url":"https://example.invalid/audio","size":12345678}]}""")
            assertEquals(12345678L, known.streams.single().size)
            assertTrue(known.streams.sizeEstimate().complete)
            assertFalse((known.streams + part.copy(size = null)).sizeEstimate().complete)
            assertTrue((known.streams + part.copy(size = null)).sizeEstimateLabel().contains("Total incomplete"))
            assertFalse(listOf(part.copy(size = Long.MAX_VALUE), part).sizeEstimate().complete)
        }
    }
    @Test fun `provider status is shown verbatim as text without English state branching`() {
        for (status in listOf("Queued. Waiting for a slot.", "Download paused. Resume it in your account.", "Métadonnées en cours de récupération.", "Download stalled. Check your account.")) {
            val resolution = MobileApi.parseResolution("""{"ready":false,"streams":[],"status":"$status"}""")
            assertFalse(resolution.ready); assertEquals(status, resolution.preparationMessage())
        }
    }
    @Test fun `error diagnostics and warning labels never echo sensitive server input`() = runBlocking {
        val sensitive = "https://example.invalid/synthetic_private_config/app/audio/synthetic_media_token"
        Fixture { Reply(503, """{"code":"$sensitive","error":"$sensitive","warnings":[{"source":"$sensitive","code":"$sensitive","message":"$sensitive"}]}""") }.use { fixture ->
            val error = try { MobileApi(fixture.connection).search("test"); error("Expected failure") } catch (error: ApiFailure) { error }
            assertFalse(error.stackTraceToString().contains(sensitive))
            assertFalse(error.warnings.toString().contains(sensitive))
            assertFalse(SearchWarning(sensitive, sensitive, sensitive).displayWarning().contains(sensitive))
            assertEquals("Invalid media response", assertThrows(IllegalArgumentException::class.java) { MobileApi.parseResolution("""{"ready":true,"streams":[{"url":"$sensitive","size":"invalid"}]}""") }.message)
            assertEquals("Invalid server response", assertThrows(IllegalArgumentException::class.java) { MobileContract.parseSearch("""{"results":"$sensitive"}""") }.message)
            assertEquals("Media is preparing. Try again shortly.", Resolution(false, emptyList(), sensitive).preparationMessage())
        }
    }
    @Test fun `preparing resolution is requested once and does not turn into an automatic retry`() = runBlocking {
        Fixture { Reply(body = """{"ready":false,"streams":[],"status":"Queued. Waiting for a slot."}""") }.use { fixture ->
            val failure = try { MobileApi(fixture.connection).resolveReady(book.id); error("Expected preparation") }
                catch (preparing: MediaPreparingException) { preparing }
            assertEquals("Queued. Waiting for a slot.", failure.displayStatus)
            assertEquals(1, fixture.requests.size)
            assertEquals("Media is not ready", failure.message)
        }
    }
    @Test fun `mixed file sizes preserve server order with an explicitly incomplete estimate`() {
        val files = MobileApi.parseResolution("""{"ready":true,"streams":[
            {"title":"Second","filename":"02.mp3","url":"https://example.invalid/b","size":2000000},
            {"title":"First","filename":"01.mp3","url":"https://example.invalid/a","size":null},
            {"title":"Third","filename":"03.mp3","url":"https://example.invalid/c"}]}""").streams
        assertEquals(listOf("Second", "First", "Third"), files.map { it.title })
        assertEquals(2000000L, files.sizeEstimate().knownBytes)
        assertFalse(files.sizeEstimate().complete)
        assertTrue(files.first().fileLabel().contains("2.0 MB"))
        assertTrue(files[1].fileLabel().contains("Size unknown"))
    }

}
