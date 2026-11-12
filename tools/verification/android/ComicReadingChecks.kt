package io.github.lonode.debridbooks

import android.app.Instrumentation
import android.os.Bundle
import io.github.lonode.debridbooks.comics.*
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.standalone.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Opt-in live audit. Debug only; bounded media, no credentials/URLs/IDs in the report. */
class ComicReadingChecks : Instrumentation() {
    private var authorized = false
    private var cbrOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        authorized = arguments?.getString("liveReading") == "yes"
        cbrOnly = arguments?.getString("cbrOnly") == "yes"
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            check(authorized && targetContext.packageName.endsWith(".debug"))
            runBlocking { audit() }
            result.putString("stream", "Bounded live comic reading audit completed. See sanitized report.\n")
            finish(-1, result)
        } catch (failure: Exception) {
            result.putString("stream", "Live reading audit stopped (${failure.javaClass.simpleName}); private details suppressed.\n")
            finish(0, result)
        }
    }
    private suspend fun audit() {
        val app = targetContext.applicationContext as DebridApplication
        val root = File(targetContext.cacheDir, "comic-reading-audit").apply { mkdirs() }
        val secret = File(root, "key.txt")
        val key = if (secret.exists()) try { secret.readText().trim() } finally { secret.delete() }
            else checkNotNull(app.connections.read()?.standalone?.apiKey)
        val config = StandaloneConfig(key, languageFilter = "en")
        AllDebridClient(config).validate()
        val connection = app.connections.candidate(config, null)
        app.connections.save(connection)
        val api = StandaloneApi(config)
        val repo = app.comics
        val output = File(root, "results.jsonl").apply { if (!exists()) writeText("") }
        val previous = output.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        fun report(row: JsonObject) {
            output.appendText(row.toString() + "\n")
            sendStatus(0, Bundle().apply { putString("stream", row.toString() + "\n") })
        }
        val corpus = Json.parseToJsonElement(File(root, "corpus.json").readText()).jsonObject["books"]!!.jsonArray
        var archiveAttempts = 0
        fun storyFile(name: String) = !Regex("(?i)cover.?only|variant covers|\\bpreview\\b").containsMatchIn(name)
        var archivesRead = previous.count { it["offlineReopened"]?.jsonPrimitive?.booleanOrNull == true && storyFile(it["filename"]?.jsonPrimitive?.content.orEmpty()) }
        var bytesUsed = previous.lastOrNull()?.get("mediaBytesSoFar")?.jsonPrimitive?.longOrNull ?: 0L
        val seen = mutableSetOf<String>()
        val fileBudget = if (cbrOnly) 20_000_000L else 12_000_000L
        for (book in corpus.map { it.jsonObject }) {
            val query = book["query"]!!.jsonPrimitive.content
            val manga = book["kind"]!!.jsonPrimitive.content == "mangadex"
            val started = System.nanoTime()
            val row = buildJsonObject {
                put("query", query); put("kind", if (manga) "mangadex" else "torrentComic")
                try {
                    val page = withTimeout(120_000) { api.search(query, 1, true, manga) }
                    put("results", page.results.size)
                    put("covers", page.results.count { !it.poster.isNullOrBlank() })
                    if (manga) {
                        fun norm(s: String) = s.lowercase().filter(Char::isLetterOrDigit)
                        val aliases = book["aliases"]!!.jsonArray.map { norm(it.jsonPrimitive.content) } + norm(query)
                        val item = page.results.firstOrNull { norm(it.title) in aliases }
                        if (item == null) put("status", "no_main_catalogue_match")
                        else {
                            put("matchedTitle", item.title)
                            val chapters = withTimeout(60_000) { api.chapters(item.id, 0) }
                            put("hostedChapters", chapters.total)
                            val chapter = chapters.chapters.firstOrNull()
                            if (chapter == null) put("status", "no_hosted_english_chapters")
                            else {
                                put("chapter", chapter.num); put("chapterTitle", chapter.title)
                                val session = withTimeout(30_000) { repo.open(connection, item, chapter, saver = true) }
                                put("pages", session.pages.size)
                                val reads = mutableListOf<JsonObject>()
                                if (query in setOf("Chainsaw Man", "Blue Lock", "One Piece")) {
                                    for ((index, url) in session.pages.take(if (query == "Blue Lock") 20 else 3).withIndex()) {
                                        if (bytesUsed >= 20_000_000) break
                                        val response = DirectHttp.execute(DirectRequest(url, limit = minOf(2_000_000L, 20_000_000L - bytesUsed).toInt()))
                                        check(response.status == 200)
                                        bytesUsed += response.bytes.size
                                        val image = File(targetContext.cacheDir, "comic-pages/images/${MobileContract.localId(url)}")
                                        image.parentFile!!.mkdirs(); image.writeBytes(response.bytes)
                                        val bitmap = repo.bitmap(url)
                                        reads += buildJsonObject { put("page", index + 1); put("width", bitmap.width); put("height", bitmap.height); put("bytes", response.bytes.size) }
                                        bitmap.recycle()
                                        repo.checkpoint(session.entry, index)
                                    }
                                }
                                put("decodedPages", JsonArray(reads))
                                val reopened = repo.open(connection, item, chapter, saver = true)
                                put("reopenedAtPage", reopened.entry.page + 1)
                                put("status", if (reads.isNotEmpty()) "pages_read_and_progress_restored" else "page_list_only")
                            }
                        }
                    } else {
                        val candidates = page.results.filter {
                            (it.releaseName ?: it.title).contains(query.removePrefix("The "), true) &&
                            !Regex("(?i)(?:\\bFR\\b|FRENCH|SPANISH|ITALIAN|ITA\\b)").containsMatchIn(it.releaseName ?: it.title)
                        }.sortedBy { item ->
                            val name = item.releaseName ?: item.title
                            val smallCbr = Regex("(?i)\\bcbr\\b").containsMatchIn(name) &&
                                Regex("(?i)^([0-9.]+) (?:M[i]?B)$").matchEntire(item.sizeText.orEmpty())?.groupValues?.get(1)?.toDoubleOrNull()?.let { it <= 19 } == true
                            (if (cbrOnly && smallCbr) -4000 else 0) +
                            (if (cbrOnly && item.cached) -2000 else 0) +
                            (if (cbrOnly && Regex("(?i)\\bcbr\\b").containsMatchIn(name)) -1000 else 0) +
                            (if (Regex("(?i)complete|collection|integral|omnibus").containsMatchIn(name)) 100 else 0) +
                            (if (Regex("(?i)\\b00[1-9]\\b|\\b01\\b").containsMatchIn(name)) 0 else 20) +
                            (if (Regex("(?i)\\bcbr\\b|\\bcbz\\b").containsMatchIn(name)) 0 else 10)
                        }
                        put("candidateReleases", JsonArray(candidates.take(3).map { JsonPrimitive(it.releaseName ?: it.title) }))
                        if (archiveAttempts < 5 && archivesRead < (if (cbrOnly) 1 else 2) && candidates.isNotEmpty()) {
                            val item = candidates.firstOrNull { seen.add(it.infoHash ?: it.id) }
                            if (item != null) {
                                archiveAttempts++
                                put("selectedRelease", item.releaseName ?: item.title)
                                put("releaseSize", item.sizeText)
                                var resolution = withTimeout(60_000) { api.resolve(item.id) }
                                if (cbrOnly) {
                                    for (attempt in 1..3) {
                                        if (resolution.ready) break
                                        delay(10_000)
                                        resolution = withTimeout(60_000) { api.resolve(item.id) }
                                    }
                                }
                                put("ready", resolution.ready)
                                put("supportedFiles", resolution.streams.size)
                                if (!resolution.ready) put("status", "provider_preparing_no_download")
                                else {
                                    val cbrFiles = resolution.streams.filter { (it.filename ?: it.title).endsWith(".cbr", true) && storyFile(it.filename ?: it.title) }
                                    put("cbrStoryFiles", cbrFiles.size)
                                    cbrFiles.mapNotNull { it.size }.minOrNull()?.let { put("smallestCbrBytes", it) }
                                    val part = resolution.streams.filter {
                                        val name = it.filename ?: it.title
                                        ComicArchive.document(name) && (!cbrOnly || name.endsWith(".cbr", true)) && storyFile(name) && it.size != null && it.size in 1..minOf(fileBudget, 20_000_000L - bytesUsed)
                                    }.sortedWith(compareBy({ !(it.filename ?: it.title).endsWith(".cbr", true) }, { it.size!! })).firstOrNull()
                                    if (part == null) put("status", "no_archive_within_bandwidth_budget")
                                    else {
                                        put("filename", part.filename ?: part.title); put("archiveBytes", part.size)
                                        var transferred = 0L
                                        var magic: String? = null
                                        val session = withTimeout(90_000) { repo.open(connection, item, streams = listOf(part), networkCheck = { check(bytesUsed < 20_000_000L) }, onProgress = {
                                            if (!it.extracting) {
                                                if (it.downloaded < transferred) transferred = 0
                                                bytesUsed += it.downloaded - transferred
                                                transferred = it.downloaded
                                            }
                                            if (it.extracting) {
                                                val archive = File(targetContext.cacheDir, "comic-pages").walkTopDown().firstOrNull { f -> f.name == "archive.bin" }
                                                magic = archive?.inputStream()?.use { stream -> ByteArray(8).also { buffer -> stream.read(buffer) }.joinToString("") { b -> "%02x".format(b) } }
                                            }
                                            check(bytesUsed <= 20_000_000L && transferred <= fileBudget)
                                        }) }
                                        put("archiveMagic", magic)
                                        put("pages", session.pages.size)
                                        val decoded = session.pages.mapIndexed { index, source ->
                                            val bitmap = repo.bitmap(source)
                                            val dimensions = buildJsonObject { put("page", index + 1); put("width", bitmap.width); put("height", bitmap.height) }
                                            bitmap.recycle(); repo.checkpoint(session.entry, index); dimensions
                                        }
                                        put("decodedPages", JsonArray(decoded))
                                        val reopened = repo.open(connection, item, streams = listOf(part))
                                        put("reopenedAtPage", reopened.entry.page + 1)
                                        val offline = repo.download(reopened) { _, _ -> }
                                        val saved = repo.openOffline(offline.entry)
                                        check(saved.pages.size == session.pages.size)
                                        put("offlineReopened", true); put("status", "archive_read_and_offline_restored")
                                        archivesRead++
                                    }
                                }
                            }
                        } else put("status", "discovery_only")
                    }
                } catch (cancel: CancellationException) {
                    if (cancel !is TimeoutCancellationException) throw cancel
                    put("status", "timeout")
                } catch (failure: Exception) {
                    put("status", "failed"); put("failureType", failure.javaClass.simpleName)
                    if (failure is DirectFailure) { put("service", failure.service); put("reason", failure.reason) }
                }
                put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                put("mediaBytesSoFar", bytesUsed)
            }
            report(row)
            delay(3000)
        }
    }
}
