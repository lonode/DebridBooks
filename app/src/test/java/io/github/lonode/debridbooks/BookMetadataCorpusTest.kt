package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.SearchItem
import io.github.lonode.debridbooks.network.standalone.*
import java.net.URI
import java.text.Normalizer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BookMetadataCorpusTest {
    @Test fun `reviewed releases preserve uncertain text and verify catalogue identities entirely offline`() = runBlocking {
        val fixture = javaClass.getResource("/torrent-book-metadata.json")!!.readText()
        val cases = Json.parseToJsonElement(fixture).jsonObject["cases"]!!.jsonArray
        assertEquals(21, cases.size)
        cases.forEachIndexed { index, value ->
            val row = value.jsonObject
            val responses = row["responses"]!!.jsonObject
            val http = DirectTransport { request ->
                val recorded = responses[URI(request.url).host]?.jsonObject
                DirectResponse(recorded?.get("status")?.jsonPrimitive?.int ?: 200,
                    (recorded?.get("body") ?: buildJsonObject {}).toString().toByteArray())
            }
            val raw = row["releaseName"]!!.jsonPrimitive.content
            val item = SearchItem("fixture-$index", raw, releaseName = raw,
                language = row["language"]!!.jsonPrimitive.content.takeIf { it.isNotBlank() }, cached = true)
            val actual = CatalogClient(http).enrich(item)
            assertEquals(raw, identity((row["expectedPreservedTitle"] ?: row["expectedTitle"])!!.jsonPrimitive.content), identity(actual.title))
            assertEquals(raw, identity((row["expectedPreservedAuthor"] ?: row["expectedAuthor"])!!.jsonPrimitive.content), identity(actual.author.orEmpty()))
            assertEquals(item.id, actual.id)
            assertEquals(raw, actual.releaseName)
            assertEquals(item.language, actual.language)
            assertTrue(actual.cached)
        }
    }

    // Compare identity, allowing editorial case/punctuation and equivalent Latin spelling.
    private fun identity(value: String) = Normalizer.normalize(value.replace("œ", "oe").replace("Œ", "OE")
        .replace("æ", "ae").replace("Æ", "AE"), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z0-9]"), "")
}
