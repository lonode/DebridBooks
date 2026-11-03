package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.network.standalone.*
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PublicTorrentSourcesTest {
    private val hash = "a".repeat(40)
    private fun ok(text: String) = DirectResponse(200, text.toByteArray())
    private fun bay(category: Int = 102) = """[{"id":"42","name":"Jules Verne English MP3","info_hash":"$hash","size":"1048576","seeders":"12","category":"$category"}]"""
    private fun row(category: Int = 52, path: String = "/torrent/42/Jules-Verne/") = """<tr><td class="coll-1 name"><a href='/sub/$category/0/'>Audio</a><a href='$path'>Jules Verne &amp; Friends [EN]</a></td><td class="coll-2 seeds">1,234</td><td class="coll-4 size">1.5 GB<span> uploader</span></td></tr>"""

    @Test fun `API Bay validates hashes category sentinel and numeric fields`() {
        val result = PublicTorrentSources.parseBay(bay(), false).single()
        assertEquals(hash, result.hash)
        assertEquals(1048576L, result.size)
        assertEquals(12, result.seeders)
        assertEquals("en", TorrentSearch.language(result))
        assertTrue(PublicTorrentSources.parseBay(bay(201), false).isEmpty())
        assertTrue(PublicTorrentSources.parseBay("""[{"id":"0","name":"No results returned"}]""", false).isEmpty())
        assertTrue(PublicTorrentSources.parseBay(bay().replace(hash, "not-a-hash"), false).isEmpty())
        assertEquals(1, PublicTorrentSources.parseBay(bay(602), true).size)
    }

    @Test fun `1337x parsing handles category links markup size and defers magnets`() {
        val results = PublicTorrentSources.parse1337x("<table>${row()}${row(39)}${row(42)}</table>", false)
        val result = results.single()
        assertEquals("Jules Verne & Friends [EN]", result.name)
        assertEquals(1610612736L, result.size)
        assertEquals(1234, result.seeders)
        assertEquals("/torrent/42/Jules-Verne/", result.detail1337x)
        assertNull(result.hash)
        assertNull(result.magnet)
        assertEquals(1, PublicTorrentSources.parse1337x("<table>${row(39)}</table>", true).size)
        assertTrue(PublicTorrentSources.parse1337x("No results were returned.", false).isEmpty())
    }

    @Test fun `1337x search resolves hashes before publishing and selected legacy resolvers still work`() = runBlocking {
        val requests = mutableListOf<DirectRequest>()
        val source = PublicTorrentSources(DirectTransport { request ->
            requests += request
            if (URI(request.url).path.startsWith("/torrent/")) ok("""<a href="magnet:?xt=urn:btih:$hash&amp;dn=Example">Magnet</a>""")
            else ok("<table>${row()}</table>")
        })
        val result = source.search("1337x", "Jules Verne", 1, false).results.single()
        assertEquals(2, requests.size)
        assertEquals(hash, result.hash)
        assertNull(result.detail1337x)
        val saved = ResolverItem(n = result.name, x = "/torrent/42/Jules-Verne/", t = "c")
        val restored = ResolverItem.decode(saved.encode())
        assertEquals(saved.x, restored.x)
        val resolved = source.resolve(restored)
        assertEquals(hash, resolved.h)
        assertEquals("c", resolved.t)
        assertEquals(saved.n, resolved.n)
        assertEquals(2, requests.size)
        assertTrue(requests.all { URI(it.url).host == "1337x.to" && it.headers.keys.none { h -> h.equals("Authorization", true) } })
    }

    @Test fun `blocked source enters cooldown rather than making repeated requests`() = runBlocking {
        var calls = 0
        val source = PublicTorrentSources(DirectTransport { calls++; DirectResponse(403, "Access denied".toByteArray()) })
        val first = runCatching { source.search("1337x", "Example", 1, false) }.exceptionOrNull() as DirectFailure
        assertEquals("BLOCKED", first.reason)
        assertNotNull(first.retryAt)
        val second = runCatching { source.search("1337x", "Another query", 1, false) }.exceptionOrNull() as DirectFailure
        assertEquals("COOLDOWN", second.reason)
        assertEquals(1, calls)
    }

    @Test fun `Bay pages reuse a single cached response instead of repeating search requests`() = runBlocking {
        var calls = 0
        val source = PublicTorrentSources(DirectTransport { calls++; ok(bay()) })
        assertEquals(1, source.search("API Bay", "Example", 1, false).results.size)
        val page2 = source.search("API Bay", "Example", 2, false)
        assertTrue(page2.cached)
        assertTrue(page2.results.isEmpty())
        assertEquals(1, calls)
    }

    @Test fun `legacy public flags decode unchanged and automatic eligibility follows language`() {
        val legacy = Json.decodeFromString<StandaloneConfig>("""{"apiKey":"synthetic"}""")
        assertFalse(legacy.bayEnabled)
        assertFalse(legacy.x1337Enabled)
        assertTrue(legacy.publicSourcesAllowed)
        assertFalse(legacy.withLanguageFilter("fr").publicSourcesAllowed)
        val enabled = StandaloneConfig("synthetic", bayEnabled = true, x1337Enabled = true).withLanguageFilter("en")
        assertTrue(enabled.bayEnabled && enabled.x1337Enabled)
        assertNull(TorrentSearch.language(TorrentResult("Unlabelled title", tracker = "1337x")))
        assertTrue(runCatching { ResolverItem.decode(ResolverItem(n = "Bad path", x = "https://other.invalid/").encode()) }.isFailure)
    }

    @Test fun `failed public source does not hide another sources streamed results or send credentials to public hosts`() = runBlocking {
        val updates = mutableListOf<io.github.lonode.debridbooks.domain.SearchProgress>()
        val http = DirectTransport { request -> when (URI(request.url).host) {
            "apibay.org" -> { assertFalse(request.url.contains("synthetic-secret")); assertTrue(request.headers.isEmpty() || request.headers.keys.all { it == "Accept" }); ok(bay()) }
            "1337x.to" -> DirectResponse(403, "Blocked".toByteArray())
            "api.alldebrid.com" -> ok("""{"status":"success","data":{"magnets":[]}}""")
            else -> ok("{}")
        } }
        val page = StandaloneApi(StandaloneConfig("synthetic-secret", abbDomain = "", bayEnabled = true, x1337Enabled = true), http)
            .search("Jules Verne", 1, false, false) { updates += it }
        assertEquals(1, page.results.size)
        assertEquals("API Bay", page.results.single().searchSource)
        assertTrue(page.partial)
        assertTrue(page.warnings.any { it.source == "1337x" && it.code == "BLOCKED" })
        assertTrue(updates.any { it.page.results.isNotEmpty() })
    }
    @Test fun `French-only skips all three public sources for audio and comics even with legacy flags enabled`() = runBlocking {
        val requests = mutableListOf<DirectRequest>()
        val http = DirectTransport { request ->
            requests += request
            assertFalse(URI(request.url).host in setOf("apibay.org", "1337x.to", "api.knaben.org"))
            if (URI(request.url).host == "api.alldebrid.com") ok("""{"status":"success","data":{"magnets":[]}}""")
            else ok("""{"channel":{"item":[]}}""")
        }
        val config = StandaloneConfig("key", c411ApiKey = "tracker", abbDomain = "", languageFilter = "fr",
            bayEnabled = true, x1337Enabled = true, knabenEnabled = true)
        for (comic in listOf(false, true)) {
            val updates = mutableListOf<io.github.lonode.debridbooks.domain.SearchProgress>()
            TorrentSearch(config, http).search("Example", 1, comic) { updates += it }
            assertEquals(setOf("C411", "AllDebrid"), updates.first().sources.map { it.source }.toSet())
        }
        assertEquals(4, requests.size)
        assertEquals(false, StandaloneApi(config).capabilities().sources["knaben"])
        assertEquals(false, StandaloneApi(config).capabilities().sources["bay"])
        assertEquals(false, StandaloneApi(config).capabilities().sources["1337x"])
    }

    @Test fun `automatic Bay and 1337x deduplicate with Knaben by hash while streaming pending details`() = runBlocking {
        for (language in listOf("", "en")) {
            val hosts = mutableSetOf<String>()
            val http = DirectTransport { request ->
                val uri = URI(request.url)
                hosts += uri.host
                when (uri.host) {
                    "apibay.org" -> ok(bay())
                    "api.knaben.org" -> ok("""{"hits":[{"title":"Jules Verne English MP3","hash":"$hash","categoryId":[1003000]}]}""")
                    "1337x.to" -> if (uri.path.startsWith("/torrent/")) ok("""<a href="magnet:?xt=urn:btih:${hash.uppercase()}">Magnet</a>""") else ok("<table>${row()}</table>")
                    "api.alldebrid.com" -> ok("""{"status":"success","data":{"magnets":[]}}""")
                    else -> error("Unexpected destination")
                }
            }
            val config = StandaloneConfig("key", abbDomain = "", languageFilter = language, knabenEnabled = true)
            val updates = mutableListOf<io.github.lonode.debridbooks.domain.SearchProgress>()
            val page = TorrentSearch(config, http).search("Example", 1, false) { updates += it }
            assertEquals(setOf("apibay.org", "api.knaben.org", "1337x.to", "api.alldebrid.com"), hosts)
            assertEquals(1, page.results.size)
            assertEquals(hash, ResolverItem.decode(page.results.single().id).h)
            assertFalse(page.partial)
            assertTrue(updates.any { update -> update.sources.any { it.source == "1337x" && it.count == 1 && it.state == io.github.lonode.debridbooks.domain.SearchSourceState.Pending } })
            assertTrue(updates.last().finished)
        }
    }
    @Test fun `1337x detail failure retains already hashed results and reports a partial source`() = runBlocking {
        val http = DirectTransport { request ->
            when (val path = URI(request.url).path) {
                "/torrent/42/Jules-Verne/" -> ok("""<a href="magnet:?xt=urn:btih:$hash">Magnet</a>""")
                "/torrent/43/Other/" -> DirectResponse(403, ByteArray(0))
                else -> if (path.startsWith("/category-search/")) ok("<table>${row()}${row(path = "/torrent/43/Other/")}</table>")
                    else ok("""{"status":"success","data":{"magnets":[]}}""")
            }
        }
        val updates = mutableListOf<io.github.lonode.debridbooks.domain.SearchProgress>()
        val page = TorrentSearch(StandaloneConfig("key", abbDomain = ""), http)
            .search("Example", 1, false, onlySources = setOf("1337x")) { updates += it }
        assertEquals(1, page.results.size)
        assertTrue(page.partial)
        assertEquals("BLOCKED", page.warnings.single().code)
        val status = updates.last().sources.single { it.source == "1337x" }
        assertEquals(io.github.lonode.debridbooks.domain.SearchSourceState.Partial, status.state)
        assertEquals(1, status.count)
        assertNotNull(status.cooldownUntil)
    }

    @Test fun `pagination and source retries deduplicate hashes without merging distinct recordings`() {
        val first = io.github.lonode.debridbooks.domain.SearchItem("first-id", "Book", searchSource = "Knaben", infoHash = hash)
        val duplicate = first.copy(id = "other-id", title = "Different release name", searchSource = "API Bay", infoHash = hash.uppercase())
        val different = first.copy(id = "another-recording", infoHash = "b".repeat(40))
        val pages = io.github.lonode.debridbooks.domain.SearchSession(query = "Book", pages = mapOf(
            1 to io.github.lonode.debridbooks.domain.SearchPage(listOf(first)),
            2 to io.github.lonode.debridbooks.domain.SearchPage(listOf(duplicate, different))))
        assertEquals(listOf(first, different), pages.results)
        val retry = pages.requesting(io.github.lonode.debridbooks.domain.SearchRequest("Book", 1, sources = setOf("API Bay")))
            .received(io.github.lonode.debridbooks.domain.SearchPage(listOf(duplicate)))
        assertEquals(1, retry.pages.getValue(1).results.size)
        assertEquals(2, retry.results.size)
        val unknown = first.copy(id = "unknown", infoHash = "invalid")
        assertEquals(2, io.github.lonode.debridbooks.domain.SearchSession(pages = mapOf(
            1 to io.github.lonode.debridbooks.domain.SearchPage(listOf(first, unknown)))).results.size)
    }

}
