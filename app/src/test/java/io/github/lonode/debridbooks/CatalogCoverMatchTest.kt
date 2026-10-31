package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.SearchItem
import io.github.lonode.debridbooks.network.standalone.*
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Minimal public catalogue responses captured for the first coverless `test` results. */
class CatalogCoverMatchTest {
    private val rows = Json.parseToJsonElement(javaClass.getResource("/catalog-cover-regressions.json")!!.readText()).jsonArray
    private fun ok(body: String) = DirectResponse(200, body.toByteArray())
    private fun captured(query: String) = DirectTransport { request ->
        val host = URI(request.url).host
        val row = rows.map { it.jsonObject }.firstOrNull { it["query"]!!.jsonPrimitive.content == query && it["host"]!!.jsonPrimitive.content == host }
        if (row == null) ok("{}") else ok(buildJsonObject {
            put(if (host == "itunes.apple.com") "results" else "products", row["candidates"]!!)
        }.toString())
    }
    private fun item(raw: String) = SearchItem("opaque-cover-match-id", raw, releaseName = raw, language = "fr", cached = true, tracker = "Tr4ker", searchSource = "Tr4ker")

    @Test fun `Divergente first result chooses the exact French title over an expanded series label`() = runBlocking {
        val original = item("Divergente 1")
        val result = CatalogClient(captured(original.title)).enrich(original)
        assertEquals("Divergente - Tome 1", result.title)
        assertEquals("Veronica Roth", result.author)
        assertTrue(result.poster.orEmpty().contains("9791036652370"))
        assertEquals(original.id, result.id)
        assertTrue(result.cached)
        assertEquals("Tr4ker", result.searchSource)
    }

    @Test fun `Sombre Herboriste keeps artwork when catalogue moves the series and omits Tome label`() = runBlocking {
        val query = "Le Sombre Herboriste Tome 1 Testeur De Contenu"
        for (raw in listOf(query, "Michael Atamanov - $query")) {
            val result = CatalogClient(captured(query)).enrich(item(raw))
            assertEquals("Michael Atamanov", result.author)
            assertTrue(result.poster.orEmpty().contains("6fd52207-6266-709c-3bc5-cb6cf5983b78"))
            assertFalse(result.title.contains("Herboriste 1 Tome 1"))
        }
    }

    @Test fun `an author match without artwork continues to other catalogues and retries the cover later`() = runBlocking {
        var available = false
        var audibleCalls = 0
        val http = DirectTransport { request -> when (URI(request.url).host) {
            "itunes.apple.com" -> ok("""{"results":[{"collectionName":"Coverless Cedar","artistName":"Regression Writer"}]}""")
            "api.audible.fr" -> {
                audibleCalls++
                if (!available) ok("{}") else ok("""{"products":[{"asin":"B012345678","title":"Coverless Cedar","language":"french","authors":[{"name":"Regression Writer"}],"product_images":{"500":"https://images.invalid/cedar.jpg"}}]}""")
            }
            else -> ok("{}")
        } }
        val catalog = CatalogClient(http)
        val first = catalog.enrich(item("Coverless Cedar"))
        assertEquals("Regression Writer", first.author)
        assertNull(first.poster)
        available = true
        val second = catalog.enrich(item("Coverless Cedar"))
        assertEquals("https://images.invalid/cedar.jpg", second.poster)
        assertEquals(2, audibleCalls)
    }

    @Test fun `exact titles with competing authors remain ambiguous`() = runBlocking {
        val http = DirectTransport { request -> if (URI(request.url).host == "itunes.apple.com")
            ok("""{"results":[{"collectionName":"Ambiguous Cedar","artistName":"First Writer","artworkUrl100":"https://images.invalid/first.jpg"},{"collectionName":"Ambiguous Cedar","artistName":"Other Writer","artworkUrl100":"https://images.invalid/other.jpg"}]}""")
            else ok("{}") }
        assertNull(CatalogClient(http).enrich(item("Ambiguous Cedar")).poster)
    }

    @Test fun `bare decimal volume keeps its cover without adding a second volume label`() = runBlocking {
        val http = DirectTransport { request -> if (URI(request.url).host == "itunes.apple.com")
            ok("""{"results":[{"collectionName":"Decimal Cedar 1.5","artistName":"Regression Writer","artworkUrl100":"https://images.invalid/decimal.jpg"}]}""")
            else ok("{}") }
        val result = CatalogClient(http).enrich(item("Decimal Cedar Tome 1.5"))
        assertEquals("https://images.invalid/decimal.jpg", result.poster)
        assertEquals("Decimal Cedar 1.5", result.title)
    }

    @Test fun `bare wrong volume never supplies a cover even in a long otherwise matching title`() = runBlocking {
        val raw = "The Remarkable Chronicles Of The Ancient Northern Cedar Forest Tome 2"
        val http = DirectTransport { request -> if (URI(request.url).host == "itunes.apple.com")
            ok("""{"results":[{"collectionName":"The Remarkable Chronicles Of The Ancient Northern Cedar Forest 1","artistName":"Regression Writer","artworkUrl100":"https://images.invalid/wrong.jpg"}]}""")
            else ok("{}") }
        val result = CatalogClient(http).enrich(item(raw))
        assertNull(result.poster)
        assertTrue(result.title.contains("Tome 2"))
    }
}
