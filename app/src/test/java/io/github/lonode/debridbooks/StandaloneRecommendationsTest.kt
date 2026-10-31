package io.github.lonode.debridbooks

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import io.github.lonode.debridbooks.network.standalone.*
import java.net.URI
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StandaloneRecommendationsTest {
    private fun ok(body: String) = DirectResponse(200, body.toByteArray())
    private fun product(id: String, title: String, language: String = "french", series: String? = null, sequence: Int = 1): String =
        """{"asin":"$id","title":"$title","language":"$language","authors":[{"name":"Writer"}],"series":${series?.let { """[{"asin":"$it","title":"Wizard","sequence":"$sequence"}]""" } ?: "[]"}}"""

    @Test fun `recording language reaches direct recommendations and filters own series duplicate series and other languages`() = runBlocking {
        val current = product("B000000001", "Unique Seed", series = "B000000091")
        val other = product("B000000002", "Other Series", series = "B000000092")
        val standalone = product("B000000005", "Standalone")
        val http = DirectTransport { request ->
            val uri = URI(request.url)
            assertEquals("api.audible.fr", uri.host)
            assertFalse(request.headers.containsKey("Authorization"))
            assertFalse(request.url.contains("tbab:"))
            when {
                !uri.path.endsWith("/sims") -> ok("""{"products":[$current]}""")
                uri.query.contains("RawSimilarities") -> ok("""{"similar_products":[$current,$other,${product("B000000003", "Same Other Series", series = "B000000092")},${product("B000000004", "Wrong Language", "english")},$standalone,${product("B000000006", "Own Series", series = "B000000091")}]}""")
                else -> ok("""{"similar_products":[$standalone,${product("B000000007", "Same Author")}]}""")
            }
        }
        val id = ResolverItem(n = "Unique Seed [MP3]").encode()
        val result = StandaloneApi(StandaloneConfig("synthetic-key", languageFilter = "en"), http).similar(id, "fr")!!
        assertEquals(listOf("Other Series", "Standalone", "Same Author"), result.books.map { it.title })
        assertEquals("Unique Seed", result.because)
        assertEquals("Unique Seed [MP3]", ResolverItem.decode(id).n)
    }

    @Test fun `a verified collection seeds similar books without inventing a current volume or next book`() = runBlocking {
        val first = product("B000000001", "Wizard and the First Quest", series = "B000000091")
        val second = product("B000000002", "Wizard and the Second Quest", series = "B000000091", sequence = 2)
        val http = DirectTransport { request ->
            if (URI(request.url).path.endsWith("/sims")) {
                assertTrue(URI(request.url).path.contains("B000000001") || URI(request.url).path.contains("B000000002"))
                ok("""{"similar_products":[${product("B000000003", "Another Adventure")}]}""")
            } else ok("""{"products":[$second,$first]}""")
        }
        val client = CatalogClient(http)
        val collection = ResolverItem(n = "Wizard [FR] [INTEGRALE] [MP3]")
        assertNull(client.series(collection, "fr"))
        assertEquals("Another Adventure", client.similar(collection, "fr")!!.books.single().title)
        val ambiguous = CatalogClient(DirectTransport { ok("""{"products":[$first,${product("B000000004", "Wizard and a Different Quest", series = "B000000099")}]}""") })
        assertNull(ambiguous.similar(collection, "fr"))
    }

    @Test fun `discovery retains recommendations when series times out and retry does not cache outages as empty`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).build()
        try {
            var seriesCalls = 0; var similarCalls = 0
            val languages = mutableListOf<String?>()
            val item = SearchItem("synthetic-id", "Seed", language = "fr")
            val key = MobileContract.localId(item.id)
            db.library().upsertBook(BookEntity("account", key, item.title, null, 1))
            val connection = Connection(accountId = "account", standalone = StandaloneConfig("synthetic-key"))
            val repository = DiscoveryRepository(db.audio()) { object : DiscoveryCatalog {
                override suspend fun series(item: SearchItem): SeriesDetails? {
                    seriesCalls++
                    withTimeout(5) { delay(100) }
                    return null
                }
                override suspend fun similar(item: SearchItem): SimilarDetails {
                    similarCalls++
                    languages += item.detailsLanguage()
                    return SimilarDetails("Seed", listOf(CatalogBook("Recommendation")))
                }
            } }
            val first = repository.loadResult(connection, item, key)
            assertTrue(first.seriesFailed); assertFalse(first.similarFailed)
            assertEquals(0L, first.data.seriesAt); assertTrue(first.data.similarAt > 0)
            assertEquals("Recommendation", first.data.similar!!.books.single().title)
            repository.loadResult(connection, item, key)
            assertEquals(2, seriesCalls); assertEquals(1, similarCalls)
            repository.loadResult(connection, item, key, refreshSimilar = true)
            assertEquals(2, similarCalls)
            repository.loadResult(connection, item.copy(language = "en"), key)
            assertEquals(listOf("fr", "fr", "en"), languages)
        } finally { db.close() }
    }
}
