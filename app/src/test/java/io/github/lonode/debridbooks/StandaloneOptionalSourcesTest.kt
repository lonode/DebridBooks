package io.github.lonode.debridbooks

import android.app.Application
import io.github.lonode.debridbooks.network.standalone.*
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StandaloneOptionalSourcesTest {
    // Keep these provider-specific fixtures isolated from automatic public discovery.
    private val testedSources = setOf("C411", "Tr4ker", "YggReborn", "Jackett", "AudiobookBay")
    private fun ok(body: String) = DirectResponse(200, body.toByteArray())
    private val account = """{"status":"success","data":{"magnets":[]}}"""

    @Test fun `Jackett preserves private links categories page-one behavior and account-only readiness`() = runBlocking {
        val requests = mutableListOf<DirectRequest>()
        val http = DirectTransport { request ->
            requests += request
            when (URI(request.url).host) {
                "jackett.invalid" -> {
                    assertTrue(request.headers.isEmpty())
                    assertTrue(URI(request.url).path.startsWith("/jackett/api/v2.0/indexers/all/results"))
                    ok("""{"Results":[{"Title":"Example [CBZ]","Link":"https://jackett.invalid/dl/private-torrent?apikey=synthetic-key","Size":100,"Tracker":"Custom","Language":"English"}]}""")
                }
                "api.alldebrid.com" -> ok(account)
                else -> error("Search must not fetch private torrent bytes")
            }
        }
        val search = TorrentSearch(StandaloneConfig("provider-key", abbDomain = "", jackettUrl = "https://jackett.invalid/jackett", jackettApiKey = "synthetic-key"), http)
        val comic = search.search("Example", 1, true, onlySources = testedSources).results.single()
        assertEquals("https://jackett.invalid/dl/private-torrent?apikey=synthetic-key", ResolverItem.decode(comic.id).u)
        assertEquals("en", comic.language)
        assertTrue(URLDecoder.decode(requests.first().url, "UTF-8").contains("Category[]=7030"))
        assertTrue(search.search("Example", 2, true, onlySources = testedSources).results.isEmpty())
        assertEquals(1, requests.count { URI(it.url).host == "jackett.invalid" })
        search.search("Example", 1, false, onlySources = testedSources)
        assertFalse(URLDecoder.decode(requests.last { URI(it.url).host == "jackett.invalid" }.url, "UTF-8").contains("Category[]"))
        assertTrue(requests.none { URI(it.url).path.contains("upload") || URI(it.url).path.contains("unlock") })
    }

    @Test fun `AudiobookBay list metadata supports English filtering without requesting detail pages`() = runBlocking {
        val requests = mutableListOf<DirectRequest>()
        val http = DirectTransport { request ->
            requests += request
            if (URI(request.url).host == "api.alldebrid.com") ok(account) else {
                assertEquals("/", URI(request.url).path)
                ok("""<a href="/abss/english/" rel="bookmark">English book</a>
                    <p>Language: <span itemprop="inLanguage">English</span></p>
                    <p>Format: <span itemprop="encodingFormat">M4B</span></p>
                    <table><tr><td>Combined File Size:</td><td><span>1.5</span> MBs</td></tr></table>
                    <a href="/abss/french/" rel="bookmark">French book</a><p>Language: French</p>""")
            }
        }
        val book = TorrentSearch(StandaloneConfig("key", abbDomain = "abb.invalid", languageFilter = "en"), http).search("book",1,false, onlySources = testedSources).results.single()
        assertEquals("English book",book.title); assertEquals("en",book.language); assertEquals("M4B",book.format)
        assertEquals("1.6 MB",book.sizeText)
        assertEquals("https://abb.invalid/abss/english/",ResolverItem.decode(book.id).a)
        assertNull(ResolverItem.decode(book.id).h)
        assertEquals(1,requests.count { URI(it.url).host == "abb.invalid" })
    }

    @Test fun `AudiobookBay selected details retain nested schema size and magnet metadata`() {
        val result = TorrentSearch.parseAbbDetail("Book", """
            <meta http-equiv="Content-Language" content="fr">
            <p>Language: <a><span itemprop="inLanguage">English</span></a></p>
            <p>Format: <span itemprop="encodingFormat">M4B</span></p>
            <table><tr><td>Combined File Size:</td><td><span>1.5</span> MBs</td></tr>
            <tr><td>Piece Size:</td><td>512 KBs</td></tr></table>
            Info Hash: ${"a".repeat(40)} <a href="udp://tracker.invalid/announce">Tracker</a>
        """)!!
        assertEquals("English",result.language); assertEquals("M4B",result.format); assertEquals(1_572_864L,result.size)
        assertTrue(result.magnet!!.contains("tr=udp"))
    }

    @Test fun `AudiobookBay nested labels without schema do not treat headers as recording language`() {
        val result = TorrentSearch.parseAbbDetail("Book", """
            <meta http-equiv="Content-Language" content="fr"><script language="JavaScript"></script>
            <p>Language: <a><strong>English</strong></a> Keywords: fiction</p><p>Format: <span>MP3</span></p>
            <table><tr><th>File Size:</th><td><b>2</b> GBs</td></tr></table>Info Hash: ${"a".repeat(40)}
        """)!!
        assertEquals("English",result.language); assertEquals("MP3",result.format); assertEquals(2_147_483_648L,result.size)
    }
}
