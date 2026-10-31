package io.github.lonode.debridbooks.network.standalone

import io.github.lonode.debridbooks.comics.*
import io.github.lonode.debridbooks.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class MangaDexClient(private val http: DirectTransport = DirectHttp) {
    private suspend fun get(path: String, params: List<Pair<String, String>> = emptyList(), atHome: Boolean = false): JsonObject = (if (atHome) pageGate else apiGate).run {
        http.execute(DirectRequest("https://api.mangadex.org$path" + if (params.isEmpty()) "" else "?${parameters(params)}"))
            .requireSuccess("MangaDex").json().also { require(it.string("result") != "error") }
    }
    suspend fun search(query: String, page: Int): SearchPage {
        val root = get("/manga", listOf("title" to query, "limit" to "24", "offset" to ((page.toLong() - 1) * 24).toString(),
            "includes[]" to "cover_art", "includes[]" to "author", "availableTranslatedLanguage[]" to "en", "order[relevance]" to "desc") + ratings)
        require(root["data"] is JsonArray)
        val results = root.objects("data").map { manga ->
            val id = uuid(manga.string("id"))
            val attrs = manga["attributes"]!!.jsonObject
            val title = (attrs["title"] as? JsonObject)?.let { it.string("en") ?: it.values.firstOrNull()?.jsonPrimitive?.contentOrNull } ?: "Untitled"
            require(title.isNotBlank() && title.length <= 2000)
            val relations = manga.objects("relationships")
            val cover = relations.firstOrNull { it.string("type") == "cover_art" }?.get("attributes")?.jsonObject?.string("fileName")
            val author = relations.firstOrNull { it.string("type") == "author" }?.get("attributes")?.jsonObject?.string("name")
            val description = (attrs["description"] as? JsonObject)?.let { it.string("en") ?: it.values.firstOrNull()?.jsonPrimitive?.contentOrNull }
            val item = ResolverItem(n = title, t = "c", p = "m", d = id)
            SearchItem(item.encode(), title, author, provider = "mangadex", type = "comic", searchSource = "MangaDex", description = description?.take(4000),
                poster = cover?.let { "https://uploads.mangadex.org/covers/$id/${encoded(it)}.512.jpg" })
        }
        return SearchPage(results)
    }
    suspend fun chapters(itemId: String, offset: Int): ChapterPage {
        val manga = uuid(ResolverItem.decode(itemId).d)
        val chapters = feedMutex.withLock {
            val cached = feeds[manga]?.takeIf { System.nanoTime() - it.first < 600_000_000_000L }
            cached?.second ?: run {
                val all = mutableListOf<JsonObject>()
                var cursor = 0
                do {
                    val root = get("/manga/$manga/feed", listOf("limit" to "500", "offset" to "$cursor", "translatedLanguage[]" to "en", "order[chapter]" to "asc") + ratings)
                    require(root["data"] is JsonArray)
                    val batch = root.objects("data"); all += batch
                    val total = root.string("total")?.toIntOrNull() ?: throw DirectFailure("MangaDex", "INVALID_RESPONSE")
                    require(total in 0..10000 && (batch.isNotEmpty() || cursor >= total))
                    cursor += batch.size
                } while (cursor < total)
                val hosted = all.filter { row ->
                    val attrs = row["attributes"]!!.jsonObject
                    (attrs.string("pages")?.toIntOrNull() ?: 0) > 0 && attrs.string("externalUrl").isNullOrEmpty()
                }.groupBy { row -> row["attributes"]!!.jsonObject.string("chapter")?.let { "n:$it" } ?: "id:${row.string("id")}" }
                    .values.map { versions -> versions.maxBy { it["attributes"]!!.jsonObject.string("publishAt").orEmpty() } }
                    .sortedWith(compareBy<JsonObject> { it["attributes"]!!.jsonObject.string("chapter")?.toDoubleOrNull() ?: Double.MAX_VALUE }
                        .thenBy { it["attributes"]!!.jsonObject.string("publishAt").orEmpty() })
                    .map { row -> Chapter(uuid(row.string("id")), row["attributes"]!!.jsonObject.string("chapter"), row["attributes"]!!.jsonObject.string("title")) }
                if (feeds.size >= 20) feeds.remove(feeds.keys.first())
                feeds[manga] = System.nanoTime() to hosted
                hosted
            }
        }
        require(offset >= 0)
        val slice = chapters.drop(offset).take(100)
        return ChapterPage(slice, chapters.size, offset, 100, offset + slice.size < chapters.size)
    }
    suspend fun pages(chapter: String, saver: Boolean): PageResponse {
        val root = get("/at-home/server/${uuid(chapter)}", atHome = true)
        val base = root.string("baseUrl")?.trimEnd('/') ?: throw DirectFailure("MangaDex", "INVALID_RESPONSE")
        StandaloneConfig.endpoint(base)
        val details = root["chapter"]!!.jsonObject
        // The hash is a path segment, not an arbitrary URL supplied by a response.
        val pageHash = details.string("hash")?.takeIf { Regex("[a-zA-Z0-9_-]{1,200}").matches(it) } ?: throw DirectFailure("MangaDex", "INVALID_RESPONSE")
        val compressed = (details["dataSaver"] as? JsonArray).orEmpty()
        val useSaver = saver && compressed.isNotEmpty()
        val files = if (useSaver) compressed else (details["data"] as? JsonArray).orEmpty()
        val urls = files.map { "$base/${if (useSaver) "data-saver" else "data"}/$pageHash/${encoded(it.jsonPrimitive.content)}" }
        return ComicContract.pages(Json.encodeToString(PageResponse(urls, urls.size)))
    }
    companion object {
        private val apiGate = RequestGate(250)
        private val pageGate = RequestGate(1600)
        private val feedMutex = Mutex()
        private val feeds = linkedMapOf<String, Pair<Long, List<Chapter>>>()
        private val ratings = listOf("contentRating[]" to "safe", "contentRating[]" to "suggestive")
        private fun uuid(value: String?): String = value?.takeIf { Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(it) }
            ?: throw IllegalArgumentException("Invalid MangaDex identifier")
    }
}
