package io.github.lonode.debridbooks

import android.app.Instrumentation
import android.os.Bundle
import io.github.lonode.debridbooks.network.standalone.*
import java.io.File
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Public MangaDex metadata diagnostics only; no account access or image downloads. */
class MangaDiagnosticChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName.endsWith(".debug"))
            val trace = mutableListOf<JsonObject>()
            val findings = runBlocking {
                val transport = DirectTransport { request ->
                    check(URI(request.url).host == "api.mangadex.org")
                    val response = DirectHttp.execute(request)
                    val root = runCatching { response.json() }.getOrNull()
                    trace += buildJsonObject {
                        put("endpoint", if (URI(request.url).path.endsWith("/feed")) "feed" else if (URI(request.url).path.startsWith("/at-home/")) "pages" else "catalogue")
                        put("httpStatus", response.status)
                        root?.get("total")?.let { put("total", it) }
                        (root?.get("data") as? JsonArray)?.let { put("rows", it.size) }
                        (root?.get("chapter") as? JsonObject)?.let { chapter ->
                            put("originalFiles", (chapter["data"] as? JsonArray)?.size ?: 0)
                            put("saverFiles", (chapter["dataSaver"] as? JsonArray)?.size ?: 0)
                        }
                    }
                    response
                }
                val client = MangaDexClient(transport)
                val onePiece = client.search("One Piece", 1).results.first { it.title.equals("One Piece", true) }
                val chapterResult = runCatching { client.chapters(onePiece.id, 0) }
                val first = chapterResult.getOrNull()?.chapters?.firstOrNull()
                val pageResult = first?.let { runCatching { client.pages(it.id, true) } }
                val expandedResponse = DirectHttp.execute(DirectRequest("https://api.mangadex.org/manga?title=Berserk&limit=20&order%5Brelevance%5D=desc&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive&contentRating%5B%5D=erotica"))
                check(expandedResponse.status == 200)
                val expanded = expandedResponse.json()
                val berserk = (expanded["data"] as? JsonArray).orEmpty().map { it.jsonObject }.firstOrNull {
                    val title = it["attributes"]!!.jsonObject["title"]!!.jsonObject
                    title.values.any { text -> text.jsonPrimitive.content.equals("Berserk", true) }
                }?.get("attributes")?.jsonObject
                val blueLock = client.search("Blue Lock", 1).results.first { it.title.equals("Blue Lock", true) }
                val blueChapters = client.chapters(blueLock.id, 0)
                buildJsonObject {
                    put("onePiece", buildJsonObject {
                        put("chapterCallSucceeded", chapterResult.isSuccess)
                        chapterResult.exceptionOrNull()?.let { put("failureType", it.javaClass.simpleName) }
                        chapterResult.getOrNull()?.let { put("hostedChapters", it.total) }
                        first?.let { put("chapterNumber", it.num); put("chapterTitle", it.title) }
                        if (pageResult != null) {
                            put("pagesSucceeded", pageResult.isSuccess)
                            pageResult.getOrNull()?.let { put("pages", it.pageCount) }
                            pageResult.exceptionOrNull()?.let { put("pageFailureType", it.javaClass.simpleName) }
                        }
                    })
                    put("berserk", buildJsonObject {
                        put("foundWithExpandedRatings", berserk != null)
                        berserk?.get("contentRating")?.let { put("contentRating", it) }
                        berserk?.get("availableTranslatedLanguages")?.let { put("availableLanguages", it) }
                        put("candidateTitles", JsonArray((expanded["data"] as? JsonArray).orEmpty().map { it.jsonObject["attributes"]!!.jsonObject["title"]!! }))
                    })
                    put("blueLock", buildJsonObject {
                        put("hostedChapters", blueChapters.total)
                        blueChapters.chapters.firstOrNull()?.let { put("chapterNumber", it.num); put("chapterTitle", it.title) }
                    })
                    put("requestTrace", JsonArray(trace))
                }
            }
            File(targetContext.cacheDir, "english-benchmark/manga-diagnostics.json").writeText(findings.toString())
            result.putString("stream", findings.toString() + "\n")
            finish(-1, result)
        } catch (failure: Exception) {
            result.putString("stream", "Metadata diagnostics failed (${failure.javaClass.simpleName}); no private data retained.\n")
            finish(0, result)
        }
    }
}
