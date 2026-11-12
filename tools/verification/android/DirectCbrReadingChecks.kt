package io.github.lonode.debridbooks

import android.app.Instrumentation
import android.os.Bundle
import io.github.lonode.debridbooks.domain.SearchItem
import io.github.lonode.debridbooks.network.MediaPart
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Direct public CBR fallback; qualifies the reader, not torrent/AllDebrid availability. */
class DirectCbrReadingChecks : Instrumentation() {
    private var authorized = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        authorized = arguments?.getString("liveReading") == "yes"
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            check(authorized && targetContext.packageName.endsWith(".debug"))
            runBlocking { withTimeout(180_000) { audit() } }
            result.putString("stream", "Direct real-CBR reading checks passed.\n")
            finish(-1, result)
        } catch (failure: Exception) {
            result.putString("stream", "Direct real-CBR reading checks failed (${failure.javaClass.simpleName}).\n")
            finish(0, result)
        }
    }
    private suspend fun audit() {
        val app = targetContext.applicationContext as DebridApplication
        val connection = checkNotNull(app.connections.read())
        val root = File(targetContext.cacheDir, "comic-reading-audit")
        val input = Json.parseToJsonElement(File(root, "direct-cbr.json").readText()).jsonObject
        val name = input["name"]!!.jsonPrimitive.content
        val title = input["title"]!!.jsonPrimitive.content
        val size = input["size"]!!.jsonPrimitive.long
        val prior = File(root, "results.jsonl").readLines().lastOrNull { it.isNotBlank() }
            ?.let { Json.parseToJsonElement(it).jsonObject["mediaBytesSoFar"]!!.jsonPrimitive.long } ?: 0L
        check(name.endsWith(".cbr", true) && size in 1..(20_000_000L - prior))
        val part = MediaPart(name, input["url"]!!.jsonPrimitive.content, name, size)
        // Deliberately not a provider resolver: renewal must never add a fallback torrent.
        val item = SearchItem("direct-cbr-qualification:$title", title, type = "comic")
        var bytes = 0L
        var magic: String? = null
        val repo = app.comics
        val session = repo.open(connection, item, streams = listOf(part), networkCheck = { check(prior + bytes < 20_000_000L) }, onProgress = { progress ->
            if (!progress.extracting) bytes = progress.downloaded
            check(prior + bytes <= 20_000_000L)
            if (progress.extracting) {
                val archive = File(targetContext.cacheDir, "comic-pages").walkTopDown().first { it.name == "archive.bin" }
                magic = archive.inputStream().use { stream -> ByteArray(8).also { check(stream.read(it) == 8) }.joinToString("") { "%02x".format(it) } }
            }
        })
        check(magic?.startsWith("526172211a07") == true) // Real RAR, not a renamed ZIP.
        val pages = session.pages.mapIndexed { index, source ->
            val bitmap = repo.bitmap(source)
            val row = buildJsonObject { put("page", index + 1); put("width", bitmap.width); put("height", bitmap.height) }
            bitmap.recycle()
            repo.checkpoint(session.entry, index)
            row
        }
        val reopened = repo.open(connection, item, streams = listOf(part))
        check(reopened.entry.page == session.pages.lastIndex)
        val offline = repo.download(reopened) { _, _ -> }
        val saved = repo.openOffline(offline.entry)
        check(saved.entry.page == session.pages.lastIndex && saved.pages.size == pages.size)
        val bitmap = repo.bitmap(saved.pages.last()); bitmap.recycle()
        File(root, "direct-cbr-result.json").writeText(buildJsonObject {
            put("title", title); put("filename", name); put("transport", "Direct public archive; AllDebrid bypassed")
            put("archiveBytes", bytes); put("mediaBytesTotal", prior + bytes); put("archiveMagic", magic)
            put("pages", pages.size); put("decodedPages", JsonArray(pages)); put("reopenedAtPage", saved.entry.page + 1)
            put("offlineReopened", true)
        }.toString())
    }
}
