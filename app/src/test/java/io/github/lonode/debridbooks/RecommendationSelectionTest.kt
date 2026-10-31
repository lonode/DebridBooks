package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.standalone.*
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecommendationSelectionTest {
    private fun fixture() = Json.parseToJsonElement(javaClass.getResource("/recommendations/audible-fr-catalog.json")!!.readText()).jsonObject
    private fun reply(value: JsonElement) = DirectResponse(200,value.toString().toByteArray())
    private fun params(url: String) = URI(url).rawQuery.split('&').associate {
        val pair=it.split('=',limit=2); URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair.getOrElse(1){""},"UTF-8")
    }

    @Test fun `live-shaped French catalog yields diverse novels and first volumes instead of the two bad suggestions`() = runBlocking {
        val data=fixture();val requests=mutableListOf<String>()
        val client=CatalogClient(DirectTransport { request ->
            requests+=request.url
            assertEquals("api.audible.fr",URI(request.url).host)
            assertFalse(request.headers.containsKey("Authorization"))
            val p=params(request.url)
            reply(when {
                URI(request.url).path.endsWith("/sims") -> if(p["similarity_type"]=="RawSimilarities")
                    data["rawBySeed"]!!.jsonObject[URI(request.url).path.split('/').dropLast(1).last()] ?: data["raw"]!! else data["author"]!!
                p.containsKey("category_id") -> data["category"]!!
                data["starts"]!!.jsonObject.containsKey(p["keywords"]) -> data["starts"]!!.jsonObject[p["keywords"]]!!
                else -> data["match"]!!
            })
        })
        val result=client.similar(ResolverItem(n="Harry Potter [FR] [INTEGRALE] [MP3]"),"fr")!!
        assertTrue("Expected a broader useful pool, got ${result.books.map { it.title }}",result.books.size>=6)
        assertTrue(result.books.size<=12)
        assertTrue(result.genreFallback)
        assertTrue(result.books.all { it.language=="fr" })
        assertFalse(result.books.any { it.title.contains("Wish") || it.title.contains("Archives du Monde") || it.title.contains("Harry Potter") || it.title.contains("Penford") || it.title.contains("Don Camillo") || it.title=="Histoires de Princesses" })
        assertTrue(result.companions.any { it.title=="Archives du Monde des Sorciers" })
        assertTrue(result.books.any { it.title=="Le Voleur de foudre" })
        assertFalse(result.books.any { it.title=="La Bataille du labyrinthe" || it.title=="Le Calice des dieux" })
        assertEquals(result.books.flatMap { it.seriesIds }.size,result.books.flatMap { it.seriesIds }.distinct().size)
        assertTrue(requests.size<=14)
        assertTrue(requests.none { it.contains("upload") || it.contains("unlock") })
    }

    @Test fun `library blends all seeds fairly excludes owned editions and retains distinct authors with the same title`() {
        val first=SimilarDetails("Fantasy",listOf(CatalogBook("Owned (Unabridged)",author="J.K. Writer"),
            CatalogBook("A",seriesIds=listOf("series-a")),CatalogBook("Duplicate",author="Author"),CatalogBook("Later A",seriesIds=listOf("series-a"))))
        val second=SimilarDetails("Mystery",listOf(CatalogBook("B"),CatalogBook("Duplicate",author="Author"),
            CatalogBook("Duplicate",author="Different Author"),CatalogBook("Future",upcoming=true)))
        val merged=mergeRecommendations(listOf(first,second),listOf(CatalogBook("Owned",author="J K Writer")))!!
        assertEquals(listOf("B","A","Duplicate","Duplicate"),merged.books.map { it.title })
        assertEquals("Fantasy · Mystery",merged.because)
        assertEquals(2,merged.books.count { it.title=="Duplicate" })
    }

    @Test fun `seed identities suppress recommendations for another input series and old cached records still decode`() {
        val a=SimilarDetails("A",listOf(CatalogBook("B volume 1",catalogId="B1",seriesIds=listOf("B")),CatalogBook("Independent")),seedSeriesIds=listOf("A"))
        val b=SimilarDetails("B",listOf(CatalogBook("A volume 2",seriesIds=listOf("A"))),seedSeriesIds=listOf("B"))
        assertEquals(listOf("Independent"),mergeRecommendations(listOf(a,b),emptyList())!!.books.map { it.title })
        assertEquals(listOf(CatalogBook("Old")),Json.decodeFromString<SimilarDetails>("""{"because":"Seed","books":[{"title":"Old"}]}""").books)
    }

    @Test fun `English fiction keeps provider rank while short story listeners can still receive short compilations`() = runBlocking {
        fun product(id: String,title: String,minutes: Int) = buildJsonObject {
            put("asin",id);put("title",title);put("language","english");put("runtime_length_min",minutes)
        }
        suspend fun recommendations(title: String,minutes: Int): SimilarDetails {
            val seed=product("B000000001",title,minutes)
            val suggestions=listOf(product("B000000002","Nightfall",410),product("B000000003","Reading Guide to Nightfall",100),
                product("B000000004","Nightfall Summary",40),product("B000000005","Moonlight Stories: A Compilation",50),
                product("B000000006","The Stormlight Archive",900))
            return CatalogClient(DirectTransport { request -> reply(buildJsonObject {
                put(if(URI(request.url).path.endsWith("/sims")) "similar_products" else "products",JsonArray(
                    if(URI(request.url).path.endsWith("/sims")) suggestions else listOf(seed)))
            }) }).similar(ResolverItem(n=title),"en")!!
        }
        val novel=recommendations("Crime Novel",400)
        assertEquals(listOf("Nightfall","The Stormlight Archive"),novel.books.map { it.title })
        assertEquals(listOf("Reading Guide to Nightfall"),novel.companions.map { it.title })
        val stories=recommendations("Collected Short Stories",60)
        assertTrue(stories.books.any { it.title=="Moonlight Stories: A Compilation" })
    }

    @Test fun `partial catalog failures retain good candidates and cancellation is propagated`() = runBlocking {
        val data=fixture()
        val client=CatalogClient(DirectTransport { request ->
            if(URI(request.url).path.endsWith("/sims") && params(request.url)["similarity_type"]=="RawSimilarities") reply(data["raw"]!!)
            else if(params(request.url).containsKey("keywords")) reply(data["match"]!!)
            else throw IllegalStateException("Unavailable")
        })
        val result=client.similar(ResolverItem(n="Harry Potter [FR] [INTEGRALE] [MP3]"),"fr")!!
        assertTrue(result.partial)
        assertTrue(result.companions.isNotEmpty())
        try {
            CatalogClient(DirectTransport { request ->
                if(URI(request.url).path.endsWith("/sims")) throw kotlinx.coroutines.CancellationException("Cancelled")
                reply(data["match"]!!)
            })
                .similar(ResolverItem(n="Harry Potter [FR] [INTEGRALE] [MP3]"),"fr")
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }
}
