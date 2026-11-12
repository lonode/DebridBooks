package io.github.lonode.debridbooks

import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.standalone.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Test

/**
 * Opt-in live search audit. No resolver calls, uploads, media, or credential output.
 * Set DB_ENGLISH_BENCHMARK=1, DB_ENGLISH_KEYS to a private .env file, and
 * DB_ENGLISH_OUTPUT to a directory containing corpus.json (books: rank/query).
 * Run testDebugUnitTest --tests '*EnglishDiscoveryBenchmarkTest' with
 * -I tools/verification/book-benchmark.init.gradle. Normal CI excludes this source set.
 */
class EnglishDiscoveryBenchmarkTest {
    @Test fun englishOnlyDiscovery() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("DB_ENGLISH_BENCHMARK") == "1")
        val root = File(requireNotNull(System.getenv("DB_ENGLISH_OUTPUT")))
        val corpus = Json.parseToJsonElement(File(root, "corpus.json").readText()).jsonObject["books"]!!.jsonArray
        val keyFile = File(requireNotNull(System.getenv("DB_ENGLISH_KEYS")))
        val keys = keyFile.readLines().mapNotNull { line ->
            Regex("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*?)\\s*$").matchEntire(line)?.let {
                it.groupValues[1].lowercase() to it.groupValues[2].trim('"', '\'')
            }
        }.toMap()
        val key = keys.entries.firstOrNull { it.key in setOf("alldebrid_key", "alldebrid_api_key", "alldebrid_apikey", "alldebridapikey") }?.value
            ?: error("AllDebrid key missing (values suppressed)")
        // Both variants use only AllDebrid credentials. Knaben is an optional no-key switch.
        val config = StandaloneConfig(key, languageFilter = "en", knabenEnabled = true).validate()
        check(config.trackers().isEmpty() && config.jackettApiKey.isBlank())
        AllDebridClient(config).validate()
        // Use the same in-memory source instances across searches, retaining real cooldowns.
        // DirectHttp identity is needed by the production runtime caches.
        val api = StandaloneApi(config, DirectHttp)
        val output = File(root, "results.jsonl")
        val done = if (output.exists()) output.readLines().map { Json.parseToJsonElement(it).jsonObject["rank"]!!.jsonPrimitive.int }.toSet() else emptySet()
        for (book in corpus.map { it.jsonObject }.filter { it["rank"]!!.jsonPrimitive.int !in done }) {
            val rank = book["rank"]!!.jsonPrimitive.int
            val query = book["query"]!!.jsonPrimitive.content
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            var first: Long? = null
            var sourcesDone: Long? = null
            var latest = SearchProgress(SearchPage(emptyList()), emptyList())
            var state = "complete"
            try {
                withTimeout(180_000) {
                    api.search(query, 1, false, false) { update ->
                        latest = update
                        if (first == null && update.page.results.isNotEmpty()) first = elapsed()
                        if (sourcesDone == null && update.sources.filter { it.source != "Book details" }.all { it.state != SearchSourceState.Pending }) sourcesDone = elapsed()
                    }
                }
            } catch (_: TimeoutCancellationException) { state = "deadline_180s" }
            catch (_: Exception) { state = "failed" }
            val row = buildJsonObject {
                put("rank", rank); put("query", query); put("status", state)
                put("elapsedMs", elapsed()); put("firstResultsMs", first?.let(::JsonPrimitive) ?: JsonNull)
                put("sourcesDoneMs", sourcesDone?.let(::JsonPrimitive) ?: JsonNull)
                put("sources", JsonArray(latest.sources.map { source -> buildJsonObject {
                    put("source", source.source); put("state", source.state.name); put("count", source.count)
                    put("issue", source.issue); put("cached", source.cached)
                } }))
                put("results", JsonArray(latest.page.results.map { item -> buildJsonObject {
                    put("releaseName", item.releaseName); put("title", item.title); put("author", item.author)
                    put("source", item.searchSource); put("tracker", item.tracker); put("language", item.language)
                    put("hasCover", !item.poster.isNullOrBlank()); put("inAccount", item.cached)
                } }))
            }
            output.appendText(row.toString() + "\n")
            println("English benchmark $rank/50: ${latest.page.results.size} results, ${elapsed()}ms, $state")
            delay(10_000)
        }
    }
}
