package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.network.Connection
import io.github.lonode.debridbooks.network.ApiFailure
import io.github.lonode.debridbooks.network.standalone.*
import io.github.lonode.debridbooks.domain.*
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StandaloneIntegrationTest {
    // Keep these provider-specific fixtures isolated from automatic public discovery.
    private val testedSources = setOf("C411", "Tr4ker", "YggReborn", "Jackett", "AudiobookBay")
    private val hash = "a".repeat(40)
    private val config = StandaloneConfig("synthetic-provider-key", abbDomain = "")
    private class FakeHttp(val handler: (DirectRequest) -> DirectResponse) : DirectTransport {
        val requests = mutableListOf<DirectRequest>()
        override suspend fun execute(request: DirectRequest): DirectResponse { requests += request; return handler(request) }
    }
    private fun ok(body: String) = DirectResponse(200, body.toByteArray())
    private fun ad(data: String) = ok("""{"status":"success","data":$data}""")
    private fun path(request: DirectRequest) = URI(request.url).path
    private fun body(request: DirectRequest) = request.body?.toString(Charsets.UTF_8).orEmpty()
    private fun params(request: DirectRequest): Map<String, String> = URI(request.url).rawQuery.orEmpty().split('&').filter { it.contains('=') }
        .associate { val pair = it.split('=', limit = 2); URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") }
    private fun xml(title: String = "Book [MP3]", torrent: String = "https://tracker.example.invalid/download?passkey=synthetic-private", extra: String = "") = """
        <rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel><item>
        <title>$title</title><enclosure url="$torrent" length="1234"/>
        <torznab:attr name="infohash" value="$hash"/><torznab:attr name="seeders" value="9"/>$extra
        </item></channel></rss>
    """.trimIndent()

    @Test fun `saved AllDebrid link migrates locally without changing library scope or retaining server secrets`() {
        val raw = """{"provider":"alldebrid","apiKey":"synthetic-provider-key","yggrebornPasskey":"synthetic-tracker-key","accessToken":"obsolete-server-token","languageFilter":"fr"}"""
        val prefix = "https://old-server.invalid/" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
        val migrated = StandaloneConfig.migrate(Connection(prefix, "existing-library-account"))
        assertEquals("existing-library-account", migrated.accountId)
        assertEquals("", migrated.prefix)
        assertEquals("synthetic-tracker-key", migrated.standalone!!.yggrebornPasskey)
        assertEquals("fr", migrated.standalone.languageFilter)
        val persisted = Json.encodeToString(migrated)
        assertFalse(persisted.contains("old-server")); assertFalse(persisted.contains("obsolete-server-token"))
        assertEquals(migrated.accountId, StandaloneConfig.connect(migrated.standalone, migrated).accountId)
        assertNotEquals(migrated.accountId, StandaloneConfig.connect(StandaloneConfig("other-provider-key"), migrated).accountId)
        assertTrue(runCatching { StandaloneConfig.migrate(Connection("https://old.invalid/synthetic", "a")) }.isFailure)
    }
    @Test fun `setup rejects malformed secrets insecure endpoints and incomplete source settings`() {
        listOf(StandaloneConfig(""), StandaloneConfig("key\r\nInjected: header"), StandaloneConfig("key", yggrebornPasskey = "pass", yggrebornUrl = "http://insecure.invalid/api"),
            StandaloneConfig("key", jackettUrl = "https://jackett.invalid"), StandaloneConfig("key", abbDomain = "example.invalid/path"),
            StandaloneConfig("key", maxSizeGB = Double.NaN), StandaloneConfig("key", languageFilter = "bogus")).forEach { assertTrue(runCatching { it.validate() }.isFailure) }
        assertEquals("key", StandaloneConfig("key").validate().apiKey)
        assertFalse(config.toString().contains(config.apiKey))
    }
    @Test fun `setup validates AllDebrid and source keys without adding any torrent`() = runBlocking {
        val cfg = StandaloneConfig("key", abbDomain = "", tr4kerApiKey = "tracker-key")
        val http = FakeHttp { request -> when (URI(request.url).host) {
            "api.alldebrid.com" -> { assertEquals("Bearer key", request.headers["Authorization"]); assertEquals("/v4/user", path(request)); ad("""{"user":{"username":"synthetic"}}""") }
            "tr4ker.net" -> { assertFalse(request.headers.containsKey("Authorization")); assertEquals("tracker-key", params(request)["apikey"]); ok("<rss><channel/></rss>") }
            else -> error("Unexpected destination")
        } }
        StandaloneApi(cfg, http).validate()
        assertEquals(2, http.requests.size)
        assertFalse(http.requests.any { path(it).contains("upload") || path(it).contains("unlock") })
    }
    @Test fun `Torznab XML and JSON preserve private torrent sources and reject hostile or malformed feeds`() {
        val item = TorrentSearch.parseTorznab(xml(), "YggReborn").single()
        assertEquals(hash, item.hash); assertTrue(item.torrentUrl!!.contains("passkey=synthetic-private")); assertEquals(1234L, item.size)
        val json = """{"rss":{"channel":{"item":{"title":"JSON book","enclosure":{"@attributes":{"url":"https://tracker.invalid/torrent","length":"456"}},"torznab:attr":[{"@attributes":{"name":"infohash","value":"$hash"}}]}}}}"""
        assertEquals(456L, TorrentSearch.parseTorznab(json, "C411").single().size)
        listOf("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss><channel/></rss>", "<rss><channel><item>", "<html>blocked</html>", "<error code='100' description='synthetic-secret'/>").forEach {
            assertTrue(runCatching { TorrentSearch.parseTorznab(it, "C411") }.isFailure)
        }
    }
    @Test fun `direct tracker paging comic categories language and ready checks never upload torrents`() = runBlocking {
        val cfg = StandaloneConfig("key", yggrebornPasskey = "pass", languageFilter = "fr")
        val http = FakeHttp { request -> when (URI(request.url).host) {
            "api.yggreborn.org" -> {
                assertEquals("40", params(request)["offset"]); assertEquals("7102,7103,7104", params(request)["cat"])
                ok(xml("Comic [CBZ]"))
            }
            "api.alldebrid.com" -> { assertEquals("/v4.1/magnet/status", path(request)); ad("""{"magnets":[{"id":1,"hash":"$hash","statusCode":4}]}""") }
            else -> error("Unexpected destination")
        } }
        val page = TorrentSearch(cfg, http).search("comic", 2, true, onlySources = testedSources)
        val item = page.results.single(); assertTrue(item.cached); assertEquals("fr", item.language); assertEquals("CBZ", item.format)
        assertEquals("c", ResolverItem.decode(item.id).t)
        assertFalse(http.requests.any { path(it).contains("upload") || path(it).contains("unlock") })
        assertFalse(page.partial)
    }
    @Test fun `one failed source retains useful results and all unavailable differs from no configured source`() = runBlocking {
        val cfg = StandaloneConfig("key", abbDomain = "", c411ApiKey = "c", tr4kerApiKey = "t")
        val http = FakeHttp { request -> when (URI(request.url).host) {
            "c411.org" -> DirectResponse(503, "synthetic-private-error".toByteArray())
            "tr4ker.net" -> ok(xml())
            else -> ad("""{"magnets":[]}""")
        } }
        val result = TorrentSearch(cfg, http).search("book", 1, false, onlySources = testedSources)
        assertEquals(1, result.results.size); assertTrue(result.partial); assertEquals("C411", result.warnings.single().source)
        val noSources = runCatching { TorrentSearch(config, http).search("book", 1, false, onlySources = testedSources) }.exceptionOrNull() as ApiFailure
        assertEquals("NO_SEARCH_SOURCES", noSources.code)
        val failed = runCatching { TorrentSearch(StandaloneConfig("key", abbDomain = "", tr4kerApiKey = "t"), FakeHttp { DirectResponse(403, byteArrayOf()) }).search("book", 1, false, onlySources = testedSources) }.exceptionOrNull() as ApiFailure
        assertEquals("SEARCH_UNAVAILABLE", failed.code)
    }
    @Test fun `English search excludes French and ambiguous results and All does not prefer French`() = runBlocking {
        val englishHash = "b".repeat(40)
        val http = FakeHttp { request -> when (URI(request.url).host) {
            "c411.org" -> ok("""{"channel":{"item":[
                {"title":"Harry Potter FRENCH MP3","guid":"$hash","seeders":99},
                {"title":"Harry Potter [EN] MP3","guid":"$englishHash"},
                {"title":"Harry Potter MULTI MP3","guid":"${"c".repeat(40)}"},
                {"title":"Harry Potter Intégrale MP3","guid":"${"d".repeat(40)}"}
            ]}}""")
            "api.alldebrid.com" -> ad("""{"magnets":[{"id":1,"hash":"$englishHash","statusCode":4}]}""")
            else -> error("Unexpected destination")
        } }
        val cfg = StandaloneConfig("key", c411ApiKey = "key", abbDomain = "", languageFilter = "en")
        val english = TorrentSearch(cfg, http).search("Harry Potter", 1, false, onlySources = testedSources).results
        assertEquals(1, english.size)
        assertEquals("en", english.single().language)
        assertTrue(english.single().releaseName!!.contains("[EN]"))
        val all = TorrentSearch(cfg.withLanguageFilter(""), http).search("Harry Potter", 1, false, onlySources = testedSources).results
        assertEquals(4, all.size)
        assertEquals("en", all.first().language)
        assertTrue(all.first().cached)
        val french = TorrentSearch(cfg.withLanguageFilter("fr"), http).search("Harry Potter", 1, false, onlySources = testedSources).results
        assertEquals(2, french.size)
        assertTrue(french.all { it.language == "fr" })
        assertFalse(http.requests.any { path(it).contains("upload") || path(it).contains("unlock") })
    }
    @Test fun `explicit release language overrides French source defaults and ambiguous language stays unknown`() {
        fun language(name: String, tracker: String = "C411", declared: String? = null) = TorrentSearch.language(TorrentResult(name, tracker = tracker, language = declared))
        assertEquals("fr", language("Book")); assertEquals("en", language("Book [EN]")); assertNull(language("Book MULTI"))
        assertEquals("en", language("Book French", declared = "English")); assertNull(language("Book", "AudiobookBay"))
        assertNull(language("Book French English")); assertNull(language("Book", declared = "Unknown"))
    }
    @Test fun `private torrent bytes upload intact and provider credentials never reach the tracker`() = runBlocking {
        val bytes = "d4:infod4:name4:bookee".toByteArray()
        val registry = MemoryMagnetRegistry()
        val http = FakeHttp { request -> when (path(request)) {
            "/v4.1/magnet/status" -> if (body(request).isEmpty()) ad("""{"magnets":[]}""") else ad("""{"magnets":{"id":42,"statusCode":1}}""")
            "/torrent" -> { assertTrue(request.headers.isEmpty()); DirectResponse(200, bytes) }
            "/v4/magnet/upload/file" -> {
                assertTrue(body(request).contains(bytes.toString(Charsets.UTF_8))); assertTrue(body(request).contains("files[]"))
                ad("""{"files":[{"id":42,"hash":"$hash"}]}""")
            }
            else -> error("Unexpected request")
        } }
        val resolution = AllDebridClient(config, http, registry).resolve(ResolverItem(hash, u = "https://tracker.invalid/torrent?passkey=synthetic", n = "Book"))
        assertFalse(resolution.ready); assertTrue(resolution.streams.isEmpty())
        assertTrue(http.requests.none { path(it) == "/v4/magnet/upload" || path(it).contains("unlock") })
        assertEquals("42", registry.entries(MobileContract.localId("alldebrid:${config.apiKey}")).single().id)
    }
    @Test fun `provider transfer mapping survives facade recreation and ready files retain natural order and sizes`() = runBlocking {
        val registry = MemoryMagnetRegistry(); val scope = MobileContract.localId("alldebrid:${config.apiKey}")
        registry.save(scope, hash, "42", hash)
        var unlocks = 0
        val http = FakeHttp { request -> when (path(request)) {
            "/v4.1/magnet/status" -> ad("""{"magnets":[{"id":42,"statusCode":4}]}""")
            "/v4/magnet/files" -> ad("""{"magnets":[{"id":42,"files":[{"n":"Book","e":[{"n":"10.mp3","l":"https://alldebrid.com/f/ten","s":100},{"n":"2.mp3","l":"https://alldebrid.com/f/two","s":200},{"n":"cover.jpg","l":"https://alldebrid.com/f/cover"}]}]}]}""")
            "/v4/link/unlock" -> { unlocks++; ad("""{"link":"https://cdn.invalid/audio-$unlocks"}""") }
            else -> error("Must not re-upload")
        } }
        val first = AllDebridClient(config, http, registry).resolve(ResolverItem(hash, n = "Book"))
        val second = AllDebridClient(config, http, registry).resolve(ResolverItem(hash, n = "Book"))
        assertEquals(listOf("Book/2.mp3", "Book/10.mp3"), first.streams.map { it.filename })
        assertEquals(listOf(200L, 100L), first.streams.map { it.size })
        assertEquals(first.streams.map { it.id }, second.streams.map { it.id }); assertNotEquals(first.streams.first().url, second.streams.first().url)
        assertTrue(hash in AllDebridClient(config, http, registry).readyHashes())
    }
    @Test fun `old account-ready restriction cannot hide search results or block explicit preparation`() = runBlocking {
        val old = StandaloneConfig("key", tr4kerApiKey = "tracker-key", abbDomain = "", instantOnly = true)
        val http = FakeHttp { request -> when (path(request)) {
            "/api/torznab/all" -> ok(xml("Book [MP3]"))
            "/v4.1/magnet/status" -> if (body(request).isEmpty()) ad("""{"magnets":[]}""") else ad("""{"magnets":[{"id":8,"statusCode":0}]}""")
            "/v4/magnet/upload" -> ad("""{"magnets":[{"id":8}]}""")
            else -> error("Unexpected request")
        } }
        val results = TorrentSearch(old, http).search("Book", 1, false, onlySources = testedSources).results
        assertEquals(1, results.size)
        assertFalse(results.single().cached)
        assertFalse(http.requests.any { path(it).contains("upload") })
        val resolution = AllDebridClient(old, http, MemoryMagnetRegistry()).resolve(ResolverItem(hash, n = "Book"))
        assertFalse(resolution.ready)
        assertTrue(resolution.status!!.contains("Preparing"))
        assertEquals(1, http.requests.count { path(it).contains("upload") })
    }
    @Test fun `unlock failure never produces an incomplete queue`() = runBlocking {
        var unlocked = false
        val failed = FakeHttp { request -> when (path(request)) {
            "/v4.1/magnet/status" -> ad("""{"magnets":[{"id":1,"hash":"$hash","statusCode":4}]}""")
            "/v4/magnet/files" -> ad("""{"magnets":[{"id":1,"files":[{"n":"1.mp3","l":"https://alldebrid.com/f/a"},{"n":"2.mp3","l":"https://alldebrid.com/f/b"}]}]}""")
            else -> if (!unlocked) { unlocked = true; ad("""{"link":"https://cdn.invalid/first"}""") } else ok("""{"status":"error","error":{"code":"synthetic-private-url"}}""")
        } }
        val error = runCatching { AllDebridClient(config, failed, MemoryMagnetRegistry()).resolve(ResolverItem(hash, n = "Book")) }.exceptionOrNull()!!
        assertTrue(error is DirectFailure); assertFalse(error.toString().contains("synthetic-private")); assertNull(error.cause)
    }
    @Test fun `torrent redirect to magnet uses a fresh provider request without forwarding credentials`() = runBlocking {
        val http = FakeHttp { request -> when (path(request)) {
            "/v4.1/magnet/status" -> if (body(request).isEmpty()) ad("""{"magnets":[]}""") else ad("""{"magnets":[{"id":3,"statusCode":0}]}""")
            "/dl" -> { assertFalse(request.headers.containsKey("Authorization")); DirectResponse(302, byteArrayOf(), "magnet:?xt=urn:btih:$hash") }
            "/v4/magnet/upload" -> { assertTrue(body(request).contains("magnets%5B%5D=")); ad("""{"magnets":[{"id":3}]}""") }
            else -> error("Unexpected request")
        } }
        assertFalse(AllDebridClient(config, http, MemoryMagnetRegistry()).resolve(ResolverItem(hash, u = "https://tracker.invalid/dl", n = "Book")).ready)
    }
    @Test fun `direct error responses and malicious URLs cannot enter diagnostics or trigger credential redirects`() = runBlocking {
        val blocked = FakeHttp { ok("""{"status":"error","error":{"code":"AUTH_BLOCKED","message":"https://secret.invalid/passkey"}}""") }
        val error = runCatching { StandaloneApi(config, blocked).validate() }.exceptionOrNull() as DirectFailure
        assertTrue(error.displayMessage().contains("approval")); assertFalse(error.toString().contains("secret.invalid")); assertNull(error.cause)
        val redirect = FakeHttp { DirectResponse(302, byteArrayOf(), "https://attacker.invalid") }
        assertTrue(runCatching { StandaloneApi(config, redirect).validate() }.isFailure); assertEquals(1, redirect.requests.size)
        val insecure = runCatching { DirectHttp.execute(DirectRequest("http://insecure.invalid/private-secret")) }.exceptionOrNull()!!
        assertFalse(insecure.toString().contains("private-secret")); assertNull(insecure.cause)
    }
    @Test fun `malformed provider account cannot pass setup or trigger a torrent upload`() = runBlocking {
        val malformed = FakeHttp { ad("{}") }
        assertTrue(runCatching { AllDebridClient(config, malformed, MemoryMagnetRegistry()).resolve(ResolverItem(hash, n = "Book")) }.isFailure)
        assertEquals(1, malformed.requests.size)
        assertTrue(malformed.requests.none { path(it).contains("upload") })
        val blankUser = FakeHttp { ad("""{"user":{}}""") }
        assertTrue(runCatching { StandaloneApi(config, blankUser).validate() }.isFailure)
    }
    @Test fun `source cancellation remains cancellation rather than a partial response`() = runBlocking {
        val cancel = DirectTransport { throw CancellationException("cancelled") }
        val failure = runCatching { TorrentSearch(StandaloneConfig("key", abbDomain = "", tr4kerApiKey = "key"), cancel).search("book", 1, false, onlySources = testedSources) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
    @Test fun `slow catalog request times out without losing completed covers or failing search`() = runBlocking {
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        val http = DirectTransport { request ->
            when (URI(request.url).host) {
                "tracker.invalid" -> ok("""{"channel":{"item":[{"title":"Quick Example [MP3]","guid":"${"b".repeat(40)}"},{"title":"Slow Example [MP3]","guid":"${"c".repeat(40)}"}]}}""")
                "api.alldebrid.com" -> ad("""{"magnets":[]}""")
                "itunes.apple.com" -> if (URI(request.url).query.contains("Quick")) {
                    ok("""{"results":[{"collectionName":"Quick Example","artistName":"Writer","artworkUrl100":"https://images.invalid/100x100bb.jpg"}]}""")
                } else {
                    try { delay(10_000); ok("{}") }
                    finally { cancelled.incrementAndGet() }
                }
                else -> ok("{}")
            }
        }
        val start = System.nanoTime()
        val page = StandaloneApi(StandaloneConfig("key", abbDomain = "", tr4kerApiKey = "key", tr4kerUrl = "https://tracker.invalid"), http)
            .search("Example", 1, false, false, onlySources = testedSources)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 6000)
        assertEquals(2, page.results.size)
        assertTrue(page.results.first { it.title == "Quick Example" }.poster!!.contains("600x600bb.jpg"))
        assertNull(page.results.first { it.title == "Slow Example" }.poster)
        assertEquals(1, cancelled.get())
        assertFalse(page.partial)
    }
    @Test fun `a later verified recording backfills earlier unresolved variants before the final search update`() = runBlocking {
        val http = DirectTransport { request -> when (URI(request.url).host) {
            "tracker.invalid" -> ok("""{"channel":{"item":[{"title":"Batch Recovery Work Batch Recovery Writer [MP3]","guid":"${"d".repeat(40)}"},{"title":"Batch Recovery Writer - Batch Recovery Work [M4B]","guid":"${"e".repeat(40)}"}]}}""")
            "api.alldebrid.com" -> ad("""{"magnets":[]}""")
            "itunes.apple.com" -> if (params(request)["term"]!!.startsWith("Batch Recovery Writer"))
                ok("""{"results":[{"collectionName":"Batch Recovery Work","artistName":"Batch Recovery Writer"}]}""")
                else { delay(150); ok("""{"results":[]}""") }
            else -> ok("{}")
        } }
        val page = StandaloneApi(StandaloneConfig("key", abbDomain = "", tr4kerApiKey = "key", tr4kerUrl = "https://tracker.invalid"), http)
            .search("Batch Recovery", 1, false, false, onlySources = testedSources)
        assertEquals(2, page.results.size)
        assertEquals(2, page.results.map { it.id }.distinct().size)
        page.results.forEach { assertEquals("Batch Recovery Work", it.title); assertEquals("Batch Recovery Writer", it.author) }
    }
    @Test fun `ready account results precede preferred format and readable comics precede unsupported formats`() = runBlocking {
        val http = DirectTransport { request ->
            when (URI(request.url).host) {
                "tracker.invalid" -> ok("""{"channel":{"item":[{"title":"Example CBR","guid":"${"b".repeat(40)}"},{"title":"Example PDF","guid":"${"c".repeat(40)}"},{"title":"Example CBZ","guid":"${"d".repeat(40)}"}]}}""")
                "api.alldebrid.com" -> ad("""{"magnets":[{"id":1,"hash":"${"b".repeat(40)}","ready":true}]}""")
                else -> ok("{}")
            }
        }
        val page = TorrentSearch(StandaloneConfig("key", abbDomain = "", tr4kerApiKey = "key", tr4kerUrl = "https://tracker.invalid"), http).search("Example", 1, true, onlySources = testedSources)
        assertEquals(listOf("CBR", "CBZ", "PDF"), page.results.map { it.format })
        assertTrue(page.results.first().cached)
    }
}
