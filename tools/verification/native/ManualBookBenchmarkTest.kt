package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.SearchItem
import io.github.lonode.debridbooks.network.standalone.*
import java.io.File
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test

/** Opt-in audit source set; excluded from normal builds and permanent offline tests. */
class ManualBookBenchmarkTest {
    @Test fun capturePublicMetadata() = runBlocking {
        org.junit.Assume.assumeTrue("Set DB_BOOK_BENCHMARK=1 to run this manual audit", System.getenv("DB_BOOK_BENCHMARK") == "1")
        val capture = File(System.getenv("DB_BOOK_CAPTURE") ?: "/tmp/db-book-search-capture.jsonl")
        val phase = System.getenv("DB_BOOK_METADATA_PHASE") ?: "capture"
        val output = File(System.getenv("DB_BOOK_OUTPUT") ?: "/tmp/db-book-metadata-$phase.jsonl")
        val replies = File(System.getenv("DB_BOOK_REPLIES") ?: "/tmp/db-book-catalog-replies.jsonl")
        val cached = if (replies.exists()) replies.readLines().mapNotNull { line -> runCatching {
            val row = Json.parseToJsonElement(line).jsonObject
            if (row["body"] == null) null else row["url"]!!.jsonPrimitive.content to DirectResponse(row["status"]!!.jsonPrimitive.int, row["body"].toString().toByteArray())
        }.getOrNull() }.toMap() else emptyMap()
        val seen = if (output.exists()) output.readLines().map { Json.parseToJsonElement(it).jsonObject["releaseName"]!!.jsonPrimitive.content }.toMutableSet() else mutableSetOf()
        val gate = RequestGate(1000)
        val transport = DirectTransport { request ->
            val uri = URI(request.url)
            require(uri.host in setOf("itunes.apple.com", "www.googleapis.com", "openlibrary.org", "api.audible.fr", "api.audible.com"))
            val response = cached[request.url] ?: if (System.getenv("DB_BOOK_OFFLINE") == "1") DirectResponse(200, "{}".toByteArray()) else gate.run { DirectHttp.execute(request) }
            val body = runCatching {
                val root = Json.parseToJsonElement(response.text()).jsonObject
                fun minimal(rows: JsonElement?, fields: Set<String>): JsonArray = JsonArray((rows as? JsonArray).orEmpty().mapNotNull { row ->
                    (row as? JsonObject)?.let { JsonObject(it.filterKeys { key -> key in fields }) }
                })
                when (uri.host) {
                    "itunes.apple.com" -> buildJsonObject { put("results", minimal(root["results"], setOf("collectionName", "trackName", "artistName", "artworkUrl100"))) }
                    "www.googleapis.com" -> buildJsonObject { put("items", JsonArray((root["items"] as? JsonArray).orEmpty().map { row ->
                        buildJsonObject { put("volumeInfo", JsonObject(row.jsonObject["volumeInfo"]!!.jsonObject.filterKeys { it in setOf("title", "authors", "imageLinks") })) }
                    })) }
                    "api.audible.fr", "api.audible.com" -> buildJsonObject { put("products", minimal(root["products"], setOf("asin", "title", "language", "authors", "product_images"))) }
                    else -> buildJsonObject { put("docs", minimal(root["docs"], setOf("title", "author_name", "cover_i"))) }
                }
            }.getOrNull()
            if (cached[request.url] == null && System.getenv("DB_BOOK_OFFLINE") != "1") replies.appendText(buildJsonObject { put("url", request.url); put("status", response.status); if (body != null) put("body", body) }.toString() + "\n")
            response
        }
        var idle = 0
        while (idle < 120) {
            val available = capture.readLines().mapNotNull { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                .flatMap { source -> (source["releases"] as? JsonArray).orEmpty().map { source to it.jsonObject } }
                .filter { (_, row) -> row["releaseName"]!!.jsonPrimitive.content !in seen }
            if (available.isEmpty()) {
                if (File(System.getenv("DB_BOOK_COMPLETE") ?: "/tmp/db-book-search-capture.done").exists()) break
                delay(1000); idle++; continue
            }
            idle = 0
            for ((source, row) in available) {
                val raw = row["releaseName"]!!.jsonPrimitive.content
                if (!seen.add(raw)) continue
                val parsed = ReleaseName.parse(raw)
                val tracker = source["source"]!!.jsonPrimitive.content
                val language = TorrentSearch.language(TorrentResult(raw, tracker = tracker, language = row["language"]?.jsonPrimitive?.contentOrNull))
                val item = SearchItem("synthetic-opaque-id", raw, releaseName = raw, language = language)
                val result = withTimeoutOrNull(3000) { CatalogClient(transport).enrich(item) }
                output.appendText(buildJsonObject {
                    put("releaseName", raw); put("bookId", source["id"]!!); put("language", language ?: "")
                    put("parsedTitle", parsed.title); put("parsedAuthor", parsed.author ?: "")
                    put("title", result?.title ?: parsed.title); put("author", result?.author ?: parsed.author ?: "")
                    put("completed", result != null)
                }.toString() + "\n")
            }
        }
        val updated = output.readLines().map { line ->
            val row = Json.parseToJsonElement(line).jsonObject
            val item = SearchItem("synthetic-opaque-id", row["releaseName"]!!.jsonPrimitive.content,
                language = row["language"]!!.jsonPrimitive.content.takeIf { it.isNotBlank() })
            val known = CatalogClient.cachedIdentity(item)
            if (known == null) row else JsonObject(row + mapOf("title" to JsonPrimitive(known.title), "author" to JsonPrimitive(known.author.orEmpty())))
        }
        output.writeText(updated.joinToString("\n") + "\n")
    }
}
