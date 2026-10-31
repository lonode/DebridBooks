package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.standalone.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class RecommendationDetailsTest {
    private fun ok(body: String) = DirectResponse(200,body.toByteArray())

    @Test fun `adaptation release matches Orwell 1984 without changing resolver identity`() = runBlocking {
        val recorded=javaClass.getResource("/recommendations/1984-catalog.json")!!.readText()
        val item=ResolverItem(n="1984.Une.fiction.sonore.originale.George.Orwell.2024.FR.[MP3.96.kbps]-Notag")
        val identity=item.encode()
        var matched=false
        val client=CatalogClient(DirectTransport { request ->
            val url=URI(request.url)
            assertEquals("api.audible.fr",url.host)
            assertFalse(request.headers.containsKey("Authorization"))
            if(url.path.endsWith("/sims")) {
                matched=true
                ok("""{"similar_products":[{"asin":"B000000002","title":"Another Novel","language":"french"},{"asin":"B000000003","title":"1984 (French Edition)","language":"french","authors":[{"name":"George Orwell"},{"name":"Translator"}]}]}""")
            } else if(url.rawQuery.contains("category_id=")) {
                ok("""{"products":[]}""")
            } else {
                assertTrue(URLDecoder.decode(url.rawQuery,"UTF-8").contains("keywords=1984 Une fiction sonore originale George Orwell 2024"))
                ok(recorded)
            }
        })
        val found=client.similar(item,"fr")!!
        assertTrue(matched)
        assertEquals("1984",found.because)
        assertEquals(listOf("Another Novel"),found.books.map { it.title })
        assertEquals(identity,item.encode())
        assertEquals("1984.Une.fiction.sonore.originale.George.Orwell.2024.FR.[MP3.96.kbps]-Notag",item.n)
    }

    @Test fun `numeric titles require the known author rather than matching another work with the same name`() = runBlocking {
        val client=CatalogClient(DirectTransport { request ->
            assertFalse(URI(request.url).path.endsWith("/sims"))
            ok("""{"products":[{"asin":"B000000001","title":"1984","language":"french","authors":[{"name":"Different Writer"}]}]}""")
        })
        assertNull(client.similar(ResolverItem(n="1984 Une fiction sonore originale George Orwell 2024"),"fr"))
    }

    @Test fun `details use exact public recording identity and strip synopsis markup`() = runBlocking {
        val entry=CatalogBook("A novel",catalogId="B000000001",language="fr")
        val client=CatalogClient(DirectTransport { request ->
            assertEquals("/1.0/catalog/products/B000000001",URI(request.url).path)
            assertTrue(request.headers.isEmpty())
            ok("""{"product":{"asin":"B000000001","title":"A novel","authors":[{"name":"Writer"}],"language":"french","merchandising_summary":"<p>A story &amp; an adventure.</p>","runtime_length_min":501,"release_date":"2020-05-01","category_ladders":[{"ladder":[{"name":"Fiction"},{"name":"Fantasy"}]}]}}""")
        })
        val result=client.recommendationDetails(entry)
        assertEquals("A story & an adventure.",result.description)
        assertEquals("Writer",result.author)
        assertEquals("Fantasy",result.genre)
        assertEquals(501,result.durationMinutes)
        assertEquals("B000000001",result.catalogId)
        var called=false
        val basic=entry.copy(catalogId=null)
        assertEquals(basic,CatalogClient(DirectTransport { called=true;error("No identifier") }).recommendationDetails(basic))
        assertFalse(called)
    }

    @Test fun `details reject a different recording or language and old cached cards still decode`() = runBlocking {
        for((id,language) in listOf("B000000002" to "french","B000000001" to "english")) {
            val client=CatalogClient(DirectTransport { ok("""{"product":{"asin":"$id","title":"Another","language":"$language"}}""") })
            try { client.recommendationDetails(CatalogBook("Selected",catalogId="B000000001",language="fr"));fail("Wrong details must be rejected") }
            catch(_: IllegalArgumentException) { }
        }
        val old=Json.decodeFromString<CatalogBook>("""{"title":"Old book"}""")
        assertNull(old.description);assertNull(old.genre);assertNull(old.durationMinutes)
    }
}
