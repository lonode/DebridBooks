package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.network.standalone.*
import io.github.lonode.debridbooks.domain.SearchProgress
import io.github.lonode.debridbooks.domain.displayMetadata
import io.github.lonode.debridbooks.ui.SetupDraft
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** API v2 field shape checked live; identifiers and URLs below are synthetic. */
class KnabenSourceTest {
    private val hash = "b".repeat(40)
    private fun ok(body: String) = DirectResponse(200, body.toByteArray())
    private fun hit(category: Int = 1003000, value: String = hash) = """{"title":"Jules Verne [English] MP3","hash":"$value","categoryId":[1000000,$category],"bytes":123456,"seeders":9,"liveSeeders":4,"cachedOrigin":"The Pirate Bay (proxy)","tracker":"thepiratebay","magnetUrl":"magnet:?xt=urn:btih:$value&tr=https://unused.invalid/announce?secret=private","link":"https://unused.invalid/private.torrent","details":"https://unused.invalid/detail"}"""
    private fun body(vararg hits: String) = """{"hits":[${hits.joinToString(",")}],"max_score":null,"total":{"relation":"eq","value":${hits.size}}}"""

    @Test fun `v2 parser preserves audiobook identity and origin but discards upstream links`() {
        val result = PublicTorrentSources.parseKnaben(body(hit()), false).single()
        assertEquals(hash, result.hash)
        assertEquals("magnet:?xt=urn:btih:$hash", result.magnet)
        assertEquals(123456L, result.size)
        assertEquals(4, result.seeders)
        assertEquals("Knaben · The Pirate Bay (proxy)", result.tracker)
        assertEquals("en", TorrentSearch.language(result))
        assertNull(result.torrentUrl)
        assertNull(result.abbPage)
        assertNull(result.detail1337x)
    }

    @Test fun `categories keep ebooks and movies out of audiobook searches and support comics`() {
        assertEquals(1, PublicTorrentSources.parseKnaben(body(hit(), hit(9001000), hit(3001000)), false).size)
        assertTrue(PublicTorrentSources.parseKnaben(body(hit()), true).isEmpty())
        assertEquals(1, PublicTorrentSources.parseKnaben(body(hit(9002000)), true).size)
        assertTrue(PublicTorrentSources.parseKnaben(body(), false).isEmpty())
    }

    @Test fun `long dotted release name stays exact through parsing metadata updates and serialization`() = runBlocking {
        val raw = "Preserved.Dotted.Release." + "Original_Segment.".repeat(24) + "[EN].MP3-GROUP"
        val row = hit().replace(JsonPrimitive("Jules Verne [English] MP3").toString(), JsonPrimitive(raw).toString())
        val updates = mutableListOf<SearchProgress>()
        val http = DirectTransport { request -> when (URI(request.url).host) {
            "api.knaben.org" -> ok(body(row))
            "api.alldebrid.com" -> ok("""{"status":"success","data":{"magnets":[]}}""")
            else -> ok("{}") // Optional catalogue misses still exercise the readable-title parser.
        } }
        val result = StandaloneApi(StandaloneConfig("synthetic", abbDomain = "", knabenEnabled = true), http)
            .search("Preserved Dotted Release", 1, false, false, onlySources = setOf("Knaben")) { updates += it }
            .results.single()
        assertTrue(result.title.startsWith("Preserved Dotted Release"))
        assertEquals(raw, result.releaseName)
        assertEquals(raw, ResolverItem.decode(result.id).n)
        assertTrue(updates.flatMap { it.page.results }.all { it.releaseName == raw })
        val restored = Json.decodeFromString<io.github.lonode.debridbooks.domain.SearchItem>(Json.encodeToString(result))
        assertEquals(raw, restored.releaseName)
    }

    @Test fun `English keeps unlabelled Divergent audiobooks and source counts without inventing their language`() = runBlocking {
        // Titles observed in the public API; identifiers are synthetic, no live service is required.
        val titles = listOf(
            "Divergent (Divergent trilogy 1) audiobook by Veronica Roth [HTD",
            "Allegiant (Divergent trilogy 3) audiobook by Veronica Roth [HTD",
            "Divergent (Audiobook) [Chapterized]", "Divergent (Audiobook)",
            "Allegiant: Divergent Trilogy, Book 3 (Audiobook) [Chapterized]",
            "Insurgent: Divergent, Book 2 (Audiobook) [Chapterized]",
            "Insurgent: Divergent, Book 2 (Audiobook)",
        )
        val rows = titles.mapIndexed { index, title ->
            hit(value = (index + 1).toString(16).padStart(40, '0'))
                .replace(JsonPrimitive("Jules Verne [English] MP3").toString(), JsonPrimitive(title).toString())
        }
        val http = DirectTransport { request -> when (URI(request.url).host) {
            "api.knaben.org" -> ok(body(*rows.toTypedArray()))
            "api.alldebrid.com" -> ok("""{"status":"success","data":{"magnets":[]}}""")
            else -> error("Unexpected destination")
        } }
        val updates = mutableListOf<SearchProgress>()
        val result = TorrentSearch(StandaloneConfig("synthetic", abbDomain = "", knabenEnabled = true, languageFilter = "en"), http)
            .search("divergent", 1, false, onlySources = setOf("Knaben")) { updates += it }
        assertEquals(titles.toSet(), result.results.map { it.releaseName }.toSet())
        assertTrue(result.results.all { it.language == null && "Language unspecified" in it.displayMetadata() })
        assertEquals(7, updates.last().sources.single { it.source == "Knaben" }.count)
        assertFalse(result.partial)
    }

    @Test fun `English excludes explicit other languages and mixed public releases without treating title words as language tags`() = runBlocking {
        val titles = listOf("Book [EN]", "Book FRENCH", "Book.FR.MP3", "Book [German]", "Book [ES]",
            "Book MULTI", "Book French English", "The Russian (Audiobook)", "Unlabelled (Audiobook)",
            "The.Russian.(Audiobook)", "Stephen.King.It.(Audiobook)", "No.Country.for.Old.Men", "Divergent - Svensk")
        val rows = titles.mapIndexed { index, title ->
            hit(value = (index + 1).toString(16).padStart(40, '0'))
                .replace(JsonPrimitive("Jules Verne [English] MP3").toString(), JsonPrimitive(title).toString())
        }
        val http = DirectTransport { request -> when (URI(request.url).host) {
            "api.knaben.org" -> ok(body(*rows.toTypedArray()))
            "api.alldebrid.com" -> ok("""{"status":"success","data":{"magnets":[]}}""")
            else -> error("Unexpected destination")
        } }
        val config = StandaloneConfig("synthetic", abbDomain = "", knabenEnabled = true, languageFilter = "en")
        val english = TorrentSearch(config, http).search("book", 1, false, onlySources = setOf("Knaben")).results
        assertEquals(setOf("Book [EN]", "The Russian (Audiobook)", "Unlabelled (Audiobook)",
            "The.Russian.(Audiobook)", "Stephen.King.It.(Audiobook)", "No.Country.for.Old.Men"), english.map { it.releaseName }.toSet())
        val all = TorrentSearch(config.withLanguageFilter(""), http).search("book", 1, false, onlySources = setOf("Knaben")).results
        assertEquals(titles.size, all.size)
    }

    @Test fun `normalizes deduplicates and validates hashes including magnet fallback`() {
        assertEquals(1, PublicTorrentSources.parseKnaben(body(hit(), hit(value = hash.uppercase())), false).size)
        val missing = hit().replace("\"hash\":\"$hash\",", "")
        assertEquals(hash, PublicTorrentSources.parseKnaben(body(missing), false).single().hash)
        val conflicting = hit().replace("\"hash\":\"$hash\"", "\"hash\":\"${"c".repeat(40)}\"")
        assertTrue(PublicTorrentSources.parseKnaben(body(conflicting), false).isEmpty())
        assertTrue(PublicTorrentSources.parseKnaben(body(hit(value = "0".repeat(40))), false).isEmpty())
    }

    @Test fun `missing hits and non-array results are errors rather than false empty success`() {
        for (invalid in listOf("{}", "[]", "<html>Unavailable</html>", """{"hits":{}}""")) {
            assertEquals("INVALID_RESPONSE", (runCatching { PublicTorrentSources.parseKnaben(invalid, false) }.exceptionOrNull() as DirectFailure).reason)
        }
    }

    @Test fun `v2 request uses correct pagination category escaped query and no secrets with cached repeats`() = runBlocking {
        val requests = mutableListOf<DirectRequest>()
        val source = PublicTorrentSources(DirectTransport { requests += it; ok(body(hit())) })
        source.search("Knaben", "Jules & Verne", 2, true)
        assertTrue(source.search("Knaben", "Jules & Verne", 2, true).cached)
        assertEquals(1, requests.size)
        val request = requests.single()
        val uri = URI(request.url)
        assertEquals("api.knaben.org", uri.host)
        assertEquals("/v2/search", uri.path)
        val params = uri.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("Jules & Verne", params["q"])
        assertEquals("9002000", params["c"])
        assertEquals("40", params["s"])
        assertEquals("40", params["f"])
        assertEquals(setOf("Accept"), request.headers.keys)
        assertNull(request.body)
    }

    @Test fun `rate limiting pauses later searches without additional requests`() = runBlocking {
        var calls = 0
        val source = PublicTorrentSources(DirectTransport { calls++; DirectResponse(429, ByteArray(0), retryAfter = "3600") })
        val first = runCatching { source.search("Knaben", "First", 1, false) }.exceptionOrNull() as DirectFailure
        assertEquals("LIMIT", first.reason)
        assertTrue(first.retryAt!! > System.currentTimeMillis() + 3_500_000)
        val second = runCatching { source.search("Knaben", "Second", 1, false) }.exceptionOrNull() as DirectFailure
        assertEquals("COOLDOWN", second.reason)
        assertEquals(1, calls)
    }

    @Test fun `cancellation reaches the active Knaben request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val source = PublicTorrentSources(DirectTransport { started.complete(Unit); try { awaitCancellation() } finally { cancelled = true } })
        val task = launch { source.search("Knaben", "Example", 1, false) }
        withTimeout(5000) { started.await() }
        task.cancelAndJoin()
        assertTrue(cancelled)
    }

    @Test fun `saved settings preserve the toggle through language edits and setup`() {
        assertFalse(Json.decodeFromString<StandaloneConfig>("""{"apiKey":"synthetic"}""").knabenEnabled)
        val config = StandaloneConfig("synthetic", abbDomain = "", bayEnabled = true, knabenEnabled = true)
        assertTrue(config.withLanguageFilter("en").knabenEnabled)
        val draft = SetupDraft().apply { load(config) }
        assertTrue(draft.config().knabenEnabled)
        assertTrue(draft.config().bayEnabled)
        assertTrue(StandaloneApi(config).capabilities().sources["comics"] == true)
    }

    @Test fun `Knaben streams with other sources deduplicates by hash and never uploads torrents during search`() = runBlocking {
        val http = DirectTransport { request ->
            assertFalse(URI(request.url).path.contains("upload"))
            when (URI(request.url).host) {
                "api.knaben.org" -> { assertFalse(request.url.contains("synthetic-secret")); assertEquals(setOf("Accept"), request.headers.keys); ok(body(hit())) }
                "1337x.to" -> ok("No results were returned.")
                "apibay.org" -> ok("""[{"id":"1","name":"Jules Verne [English] MP3","info_hash":"$hash","category":"102","seeders":"2"}]""")
                "api.alldebrid.com" -> ok("""{"status":"success","data":{"magnets":[]}}""")
                else -> ok("{}")
            }
        }
        val updates = mutableListOf<io.github.lonode.debridbooks.domain.SearchProgress>()
        val page = StandaloneApi(StandaloneConfig("synthetic-secret", abbDomain = "", bayEnabled = true, knabenEnabled = true), http)
            .search("Jules Verne", 1, false, false) { updates += it }
        assertEquals(1, page.results.size)
        assertEquals("Knaben", page.results.single().searchSource)
        assertEquals(hash, ResolverItem.decode(page.results.single().id).h)
        assertTrue(updates.any { it.page.results.isNotEmpty() })
        assertFalse(page.partial)
    }
}
