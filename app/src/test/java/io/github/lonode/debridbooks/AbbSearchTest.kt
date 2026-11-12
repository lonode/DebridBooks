package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.network.ApiFailure
import io.github.lonode.debridbooks.network.standalone.*
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AbbSearchTest {
    private val config = StandaloneConfig("synthetic", abbDomain = "abb.invalid", languageFilter = "en")
    private fun searchPage(html: String, requests: MutableList<String> = mutableListOf()): TorrentSearch {
        val http = DirectTransport { request ->
            requests += request.url
            DirectResponse(200, (if (URI(request.url).host == "api.alldebrid.com")
                """{"status":"success","data":{"magnets":[]}}""" else html).toByteArray())
        }
        return TorrentSearch(config, http)
    }

    @Test fun `title search preserves query pagination and only publishes book bookmarks`() = runBlocking {
        val requests = mutableListOf<String>()
        val search = searchPage("""<title>Project Hail Mary Audiobook - Page 2</title>
            <div class="postTitle"><h2><a href="/abss/one/" rel="bookmark">Project Hail Mary - Andy Weir</a></h2></div>
            <p>Language: English</p><p>Format: MP3</p>
            <a href="/audio-books/type/children/">Children</a>
            <a href="/abss/sidebar/">Unrelated sidebar book</a>""", requests)
        val page = search.search("Project Hail Mary", 2, false, setOf("AudiobookBay"))
        assertEquals(listOf("Project Hail Mary - Andy Weir"), page.results.map { it.releaseName })
        assertEquals("https://abb.invalid/page/2/?s=project+hail+mary&tt=1", requests.single { URI(it).host == "abb.invalid" })
        assertTrue(requests.none { URI(it).path.startsWith("/abss/") })
    }

    @Test fun `ignored query front page fails visibly instead of returning its unrelated books`() = runBlocking {
        val search = searchPage("""<title>Unabridged Audiobooks Free Download</title>
            <a href="/abss/unrelated/" rel="bookmark">Unrelated book</a><p>Language: English</p>""")
        val failure = runCatching { search.search("Project Hail Mary", 1, false, setOf("AudiobookBay")) }.exceptionOrNull() as ApiFailure
        assertEquals("INVALID_RESPONSE", failure.warnings.single().code)
    }

    @Test fun `real empty search never turns category navigation into books`() = runBlocking {
        val search = searchPage("""<title>Nonexistent Audiobook</title><h3>Not Found</h3>
            <a href="/audio-books/type/children/">Children</a><a href="/audio-books/type/new/">New</a>""")
        val page = search.search("Nonexistent", 1, false, setOf("AudiobookBay"))
        assertTrue(page.results.isEmpty())
        assertTrue(page.warnings.isEmpty())
    }

    @Test fun `ignored ABB search preserves matching results from Knaben with a source warning`() = runBlocking {
        val http = DirectTransport { request ->
            val body = when (URI(request.url).host) {
                "api.alldebrid.com" -> """{"status":"success","data":{"magnets":[]}}"""
                "api.knaben.org" -> """{"hits":[{"title":"Project Hail Mary - Andy Weir","hash":"${"a".repeat(40)}","categoryId":[1003000]}]}"""
                else -> """<title>Unabridged Audiobooks Free Download</title><a href="/abss/unrelated/" rel="bookmark">Unrelated book</a>"""
            }
            DirectResponse(200, body.toByteArray())
        }
        val page = TorrentSearch(config, http).search("Project Hail Mary", 1, false, setOf("Knaben", "AudiobookBay"))
        assertEquals("Project Hail Mary - Andy Weir", page.results.single().releaseName)
        assertTrue(page.partial)
        assertEquals("AudiobookBay", page.warnings.single().source)
        assertEquals("INVALID_RESPONSE", page.warnings.single().code)
    }

    @Test fun `query acknowledgement tolerates case accents entities and punctuation without changing release text`() = runBlocking {
        val name = "L’Étranger - Albert Camus [MP3]"
        val page = searchPage("""<title>L&#39;étranger Audiobook</title>
            <a href="/abss/book/" rel="bookmark">$name</a><p>Language: English</p>""")
            .search("L’ÉTRANGER", 1, false, setOf("AudiobookBay"))
        assertEquals(name, page.results.single().releaseName)
    }
}
