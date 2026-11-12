package io.github.lonode.debridbooks

import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.os.Bundle
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.standalone.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Opt-in device audit: isolated debug app, private one-use key file, public metadata output. */
class EnglishDiscoveryChecks : Instrumentation() {
    private var authorized = false
    private var sampledPage = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        authorized = arguments?.getString("liveSearch") == "yes"
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            check(authorized && targetContext.packageName.endsWith(".debug"))
            val root = File(targetContext.cacheDir, "english-benchmark")
            val secret = File(root, "key.txt")
            val key = try { secret.readText().trim() } finally { secret.delete() }
            runBlocking { capture(root, key) }
            result.putString("stream", "Device discovery audit complete. No media or resolver operations.\n")
            finish(-1, result)
        } catch (failure: Exception) {
            result.putString("stream", "Device discovery audit failed (${failure.javaClass.simpleName}); details suppressed.\n")
            finish(0, result)
        }
    }

    private suspend fun capture(root: File, key: String) {
        val corpus = Json.parseToJsonElement(File(root, "corpus.json").readText()).jsonObject["books"]!!.jsonArray
        val config = StandaloneConfig(key, languageFilter = "en").validate()
        check(config.knabenEnabled && config.trackers().isEmpty() && config.jackettApiKey.isBlank())
        AllDebridClient(config).validate()
        val api = StandaloneApi(config)
        val output = File(root, "results.jsonl")
        val done = if (output.exists()) output.readLines().map { Json.parseToJsonElement(it).jsonObject["rank"]!!.jsonPrimitive.int }.toSet() else emptySet()
        for (book in corpus.map { it.jsonObject }.filter { it["rank"]!!.jsonPrimitive.int !in done }) {
            val rank = book["rank"]!!.jsonPrimitive.int
            val query = book["query"]!!.jsonPrimitive.content
            val kind = book["kind"]?.jsonPrimitive?.content ?: "audiobook"
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started) / 1_000_000
            var first: Long? = null
            var sourcesDone: Long? = null
            var latest = SearchProgress(SearchPage(emptyList()), emptyList())
            var state = "complete"
            try {
                withTimeout(180_000) {
                    api.search(query, 1, kind != "audiobook", kind == "mangadex") { update ->
                        latest = update
                        if (first == null && update.page.results.isNotEmpty()) first = elapsed()
                        if (sourcesDone == null && update.sources.filter { it.source != "Book details" }.all { it.state != SearchSourceState.Pending }) sourcesDone = elapsed()
                    }
                }
            } catch (_: TimeoutCancellationException) { state = "deadline_180s" }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { state = "failed" }
            val searchElapsed = elapsed()
            val manga = if (kind == "mangadex" && state == "complete") {
                withTimeoutOrNull(60_000) { auditManga(api, latest.page, book, root) }
                    ?: buildJsonObject { put("status", "chapter_deadline_60s") }
            } else null
            output.appendText(buildJsonObject {
                put("rank", rank); put("query", query); put("kind", kind); put("status", state)
                put("elapsedMs", searchElapsed); put("firstResultsMs", first?.let(::JsonPrimitive) ?: JsonNull)
                put("sourcesDoneMs", sourcesDone?.let(::JsonPrimitive) ?: JsonNull)
                if (manga != null) put("manga", manga)
                put("sources", JsonArray(latest.sources.map { source -> buildJsonObject {
                    put("source", source.source); put("state", source.state.name); put("count", source.count)
                    put("issue", source.issue); put("cached", source.cached)
                } }))
                put("results", JsonArray(latest.page.results.map { item -> buildJsonObject {
                    put("releaseName", item.releaseName); put("title", item.title); put("author", item.author)
                    put("source", item.searchSource); put("tracker", item.tracker); put("language", item.language)
                    put("format", item.format); put("sizeText", item.sizeText)
                    put("hasCover", !item.poster.isNullOrBlank()); put("inAccount", item.cached)
                } }))
            }.toString() + "\n")
            sendStatus(0, Bundle().apply { putString("stream", "Completed $rank/${corpus.size}: ${latest.page.results.size} results, ${elapsed()}ms, $state\n") })
            delay(10_000)
        }
    }

    private suspend fun auditManga(api: StandaloneApi, page: SearchPage, book: JsonObject, root: File): JsonObject {
        fun normalized(value: String) = value.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
        val aliases = (book["aliases"] as? JsonArray).orEmpty().map { normalized(it.jsonPrimitive.content) } + normalized(book["query"]!!.jsonPrimitive.content)
        val item = page.results.firstOrNull { normalized(it.title) in aliases }
            ?: return buildJsonObject { put("status", "no_exact_catalogue_match") }
        return try {
            val chapters = api.chapters(item.id, 0)
            buildJsonObject {
                put("matchedTitle", item.title); put("hasCover", !item.poster.isNullOrBlank())
                put("hostedEnglishChapters", chapters.total)
                val first = chapters.chapters.firstOrNull()
                if (first == null) { put("status", "no_hosted_english_chapters") }
                else {
                    val pages = api.pages(first.id, true)
                    put("status", "chapter_pages_available"); put("pageCount", pages.pageCount)
                    // One bounded data-saver page for the entire audit; never a full chapter/volume.
                    if (!sampledPage) {
                        sampledPage = true
                        val sample = runCatching {
                            val response = DirectHttp.execute(DirectRequest(pages.pages.first(), limit = 3_000_000))
                            check(response.status == 200)
                            val bitmap = BitmapFactory.decodeByteArray(response.bytes, 0, response.bytes.size) ?: error("Invalid image")
                            val dimensions = bitmap.width to bitmap.height; bitmap.recycle()
                            File(root, "reader-sample.image").writeBytes(response.bytes)
                            buildJsonObject { put("decoded", true); put("bytes", response.bytes.size); put("width", dimensions.first); put("height", dimensions.second) }
                        }.getOrElse { buildJsonObject { put("decoded", false) } }
                        put("readerSample", sample)
                    }
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { buildJsonObject { put("matchedTitle", item.title); put("status", "chapter_or_pages_failed") } }
    }
}
