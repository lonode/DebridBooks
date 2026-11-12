package io.github.lonode.debridbooks.comics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ComicLoadProgress(val downloaded: Long, val total: Long?, val extracting: Boolean = false)

data class ComicLibraryItem(val item: SearchItem, val fallbackCover: String?)

class ReadingSession(val entry: ComicEntity, val pages: List<String>)
class ComicRepository(context: Context, private val dao: ComicDao, private val secrets: ConnectionStore, coverMemoryBytes: Int = 16 * 1024 * 1024) {
    private val cache = File(context.cacheDir, "comic-pages")
    private val offline = File(context.noBackupFilesDir, "comic-pages")
    private val mutex = Mutex()
    private val metadataMutex = Mutex()
    private val metadataAttempts = mutableSetOf<String>()
    private val metadataJson = Json { ignoreUnknownKeys = true }
    private val coverMemory = object : LruCache<String, Bitmap>(coverMemoryBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private data class CachedLibraryItem(val entry: ComicEntity, val presentation: ComicLibraryItem)
    private val libraryItems = LruCache<String, CachedLibraryItem>(128)
    fun cachedLibraryItem(entry: ComicEntity): ComicLibraryItem? {
        val cached = libraryItems.get("${entry.accountId}:${entry.volumeId}") ?: return null
        return cached.presentation.takeIf { cached.entry.itemId == entry.itemId && cached.entry.title == entry.title &&
            cached.entry.offline == entry.offline && cached.entry.metadata.contentEquals(entry.metadata) }
    }
    private fun coverKey(source: String, width: Int, height: Int) = "${MobileContract.localId(source)}:$width:$height"
    fun cachedCover(source: String?, width: Int, height: Int): Bitmap? = source?.let { coverMemory.get(coverKey(it, width, height)) }
    suspend fun coverBitmap(source: String, width: Int, height: Int): Bitmap {
        cachedCover(source, width, height)?.let { return it }
        return withContext(Dispatchers.IO) {
            cachedCover(source, width, height) ?: bitmap(source, width, height).also {
                it.prepareToDraw()
                coverMemory.put(coverKey(source, width, height), it)
            }
        }
    }
    private fun firstPage(dir: File): String? {
        val manifest = File(dir, "pages.txt")
        if (!manifest.isFile) return null
        val name = manifest.bufferedReader().use { it.readLine() } ?: return null
        if (!Regex("page-[0-9]+\\.img").matches(name)) return null
        return File(dir, name).takeIf { it.isFile && it.length() > 0 }?.absolutePath
    }
    suspend fun libraryItem(entry: ComicEntity): ComicLibraryItem {
        cachedLibraryItem(entry)?.let { return it }
        return withContext(Dispatchers.IO) {
            val item = try { entry.metadata?.let { metadataJson.decodeFromString<SearchItem>(secrets.reveal(it, entry.accountId)) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            val cover = firstPage(folder(offline, entry.accountId, entry.volumeId))
                ?: firstPage(folder(cache, entry.accountId, entry.volumeId))
            ComicLibraryItem(item ?: SearchItem(entry.itemId, entry.title, type = "comic"), cover).also {
                libraryItems.put("${entry.accountId}:${entry.volumeId}", CachedLibraryItem(entry, it))
            }
        }
    }
    suspend fun enrichLibraryItem(connection: Connection, entry: ComicEntity) = metadataMutex.withLock {
        require(connection.accountId == entry.accountId)
        val key = "${entry.accountId}:${entry.itemId}"
        if (key in metadataAttempts || dao.get(entry.accountId, entry.volumeId)?.metadata != null) return@withLock
        val result = MobileApi(connection).search(entry.title.substringBefore(" — "), comic = true, series = entry.chapterId != null)
        currentCoroutineContext().ensureActive()
        result.results.firstOrNull { MobileContract.localId(it.id) == entry.itemId }?.let { item ->
            dao.updateMetadata(entry.accountId, entry.itemId, secrets.protect(metadataJson.encodeToString(item), entry.accountId))
        }
        metadataAttempts += key
    }
    fun history() = dao.observeAll()
    private fun folder(root: File, account: String, volume: String): File {
        require(Regex("[a-f0-9]{64}").matches(account) && Regex("[a-f0-9]{64}").matches(volume))
        return File(File(root, account), volume)
    }
    private fun files(dir: File): List<String>? {
        val manifest = File(dir, "pages.txt")
        if (!manifest.isFile) return null
        val names = manifest.readLines()
        if (names.isEmpty() || names.size > 5000 || names.any { !Regex("page-[0-9]+\\.img").matches(it) }) return null
        return names.map { File(dir, it) }.takeIf { pages -> pages.all { it.isFile && it.length() > 0 } }?.map { it.absolutePath }
    }
    suspend fun openOffline(entry: ComicEntity): ReadingSession = withContext(Dispatchers.IO) {
        val pages = files(folder(offline, entry.accountId, entry.volumeId))
        if (pages == null) { dao.removeOffline(entry.accountId, entry.volumeId); error("Offline pages missing") }
        ReadingSession(entry, pages)
    }
    suspend fun resolver(entry: ComicEntity): String = withContext(Dispatchers.IO) { secrets.reveal(entry.resolver, entry.accountId) }
    suspend fun open(connection: Connection, item: SearchItem, chapter: Chapter? = null, streams: List<MediaPart>? = null, saver: Boolean = true, refresh: Boolean = false, networkCheck: () -> Unit = {}, onProgress: (ComicLoadProgress) -> Unit = {}): ReadingSession = mutex.withLock {
        withContext(Dispatchers.IO) {
            val itemKey = MobileContract.localId(item.id)
            val volume = MobileContract.localId(itemKey + ":" + (chapter?.id ?: streams?.singleOrNull()?.id ?: "images"))
            val previous = dao.get(connection.accountId, volume)
            val metadata = if (item.poster != null || item.author != null) secrets.protect(metadataJson.encodeToString(item), connection.accountId) else previous?.metadata
            if (metadata != null) dao.updateMetadata(connection.accountId, itemKey, metadata)
            if (previous?.offline == true) return@withContext openOffline(previous.copy(metadata = metadata))
            val cached = files(folder(cache, connection.accountId, volume))
            if (!refresh && cached != null && previous != null) return@withContext ReadingSession(previous.copy(metadata = metadata), cached)
            val api = MobileApi(connection)
            val links: List<String>
            val selected: List<MediaPart>
            if (chapter != null) {
                links = api.pages(chapter.id, saver, refresh).pages
                selected = emptyList()
            } else {
                selected = streams ?: api.resolveReady(item.id).streams
                links = selected.filter { ComicArchive.image(it.filename ?: it.title) }
                    .sortedWith { a, b -> ComicArchive.naturalOrder.compare(a.filename ?: a.title, b.filename ?: b.title) }.map { it.url }
            }
            val archive = selected.singleOrNull()?.takeIf { ComicArchive.document(it.filename ?: it.title) }
            val localPages: List<String>
            if (archive != null) {
                val parent = folder(cache, connection.accountId, volume).parentFile!!
                check(parent.mkdirs() || parent.isDirectory)
                parent.listFiles()?.filter { it.name.startsWith("staging-") }?.forEach { check(it.deleteRecursively()) }
                val staging = File(parent, "staging-${UUID.randomUUID()}")
                check(staging.mkdir())
                try {
                    val archiveFile = File(staging, "archive.bin")
                    try { HttpBoundary.download(archive.url, archiveFile, 512L * 1024 * 1024, networkCheck) { downloaded, total -> onProgress(ComicLoadProgress(downloaded, total)) } }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {
                        val renewed = api.resolveReady(item.id).streams.single { it.id == archive.id }
                        HttpBoundary.download(renewed.url, archiveFile, 512L * 1024 * 1024, networkCheck) { downloaded, total -> onProgress(ComicLoadProgress(downloaded, total)) }
                    }
                    onProgress(ComicLoadProgress(archiveFile.length(), archiveFile.length(), extracting = true))
                    val extracted = ComicArchive.extract(archiveFile, File(staging, "extracted"))
                    extracted.forEach(::checkImage)
                    val extractedDir = File(staging, "extracted")
                    File(extractedDir, "pages.txt").writeText(extracted.joinToString("\n") { it.name })
                    val final = folder(cache, connection.accountId, volume)
                    check(!final.exists() || final.deleteRecursively())
                    check(extractedDir.renameTo(final))
                    localPages = files(final) ?: error("Invalid pages")
                } finally { check(staging.deleteRecursively()) { "Could not remove temporary comic" } }
            } else {
                require(links.isNotEmpty()) { "No readable comic pages" }
                // Loose/MangaDex pages stream on demand; only downloaded pages are offline.
                localPages = links
            }
            val entry = ComicEntity(connection.accountId, volume, itemKey,
                previous?.title ?: item.title + (chapter?.let { " — Ch. ${it.num ?: "?"} ${it.title.orEmpty()}" } ?: selected.singleOrNull()?.let { " — ${it.title}" }.orEmpty()),
                chapter?.id, secrets.protect(item.id, connection.accountId),
                (previous?.page ?: 0).coerceIn(0, localPages.lastIndex), localPages.size,
                previous?.completed == true, false, System.currentTimeMillis(), previous?.downloadState ?: "NONE", previous?.downloadedPages ?: 0, metadata = metadata)
            dao.save(entry)
            ReadingSession(entry, localPages)
        }
    }
    suspend fun resolveSaved(connection: Connection, entry: ComicEntity, networkCheck: () -> Unit = {}, saver: Boolean = true, onProgress: (ComicLoadProgress) -> Unit = {}): ReadingSession {
        require(connection.accountId == entry.accountId)
        val item = SearchItem(resolver(entry), entry.title, provider = if (entry.chapterId != null) "mangadex" else null, type = "comic")
        if (entry.chapterId != null) return open(connection, item, Chapter(entry.chapterId), saver = saver, networkCheck = networkCheck)
        val result = MobileApi(connection).resolve(item.id)
        if (!result.ready) throw MediaPreparingException(result.preparationMessage())
        val archive = result.streams.firstOrNull {
            ComicArchive.document(it.filename ?: it.title) && MobileContract.localId(entry.itemId + ":" + it.id) == entry.volumeId
        }
        val streams = archive?.let { listOf(it) } ?: result.streams
        require(MobileContract.localId(entry.itemId + ":" + (streams.singleOrNull()?.id ?: "images")) == entry.volumeId)
        return open(connection, item, streams = streams, networkCheck = networkCheck, onProgress = onProgress)
    }
    suspend fun checkpoint(entry: ComicEntity, page: Int) = dao.checkpoint(entry.accountId, entry.volumeId, page)
    suspend fun download(session: ReadingSession, networkCheck: () -> Unit = {}, progress: suspend (Int, Int) -> Unit): ReadingSession = mutex.withLock {
        withContext(Dispatchers.IO) {
            val final = folder(offline, session.entry.accountId, session.entry.volumeId)
            val parent = final.parentFile!!
            check(parent.mkdirs() || parent.isDirectory)
            parent.listFiles()?.filter { it.name.startsWith("staging-") }?.forEach { check(it.deleteRecursively()) }
            val staging = File(parent, "staging-${UUID.randomUUID()}")
            check(staging.mkdir())
            try {
                var bytes = 0L
                val pages = session.pages.mapIndexed { index, source ->
                    currentCoroutineContext().ensureActive()
                    networkCheck()
                    val target = File(staging, "page-$index.img")
                    if (source.startsWith("/")) File(source).copyTo(target) else HttpBoundary.download(source, target, 64L * 1024 * 1024, networkCheck)
                    bytes += target.length()
                    check(bytes <= 1_000_000_000) { "Comic exceeds storage limit" }
                    checkImage(target)
                    progress(index + 1, session.pages.size)
                    target
                }
                File(staging, "pages.txt").writeText(pages.joinToString("\n") { it.name })
                check(!final.exists() || final.deleteRecursively())
                check(staging.renameTo(final))
                val entry = (dao.get(session.entry.accountId, session.entry.volumeId) ?: session.entry).copy(offline = true, downloadState = "COMPLETE", downloadedPages = session.pages.size)
                dao.completeOffline(entry.accountId, entry.volumeId, session.pages.size)
                ReadingSession(entry, files(final) ?: error("Incomplete download"))
            } finally { if (staging.exists()) check(staging.deleteRecursively()) }
        }
    }
    suspend fun delete(entry: ComicEntity, history: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) {
            for (root in listOf(cache, offline)) {
                val dir = folder(root, entry.accountId, entry.volumeId)
                check(!dir.exists() || dir.deleteRecursively()) { "Could not delete comic pages" }
            }
            if (history) dao.remove(entry.accountId, entry.volumeId) else dao.removeOffline(entry.accountId, entry.volumeId)
            libraryItems.evictAll()
        }
    }
    suspend fun clearCache() = mutex.withLock { withContext(Dispatchers.IO) {
        check(!cache.exists() || cache.deleteRecursively())
        coverMemory.evictAll()
        libraryItems.evictAll()
    } }
    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) { cache.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    suspend fun bitmap(source: String, maxWidth: Int = 2048, maxHeight: Int = 4096): Bitmap = withContext(Dispatchers.IO) {
        if (source.startsWith("/")) return@withContext decode(File(source), maxWidth, maxHeight)
        val images = File(cache, "images").apply { mkdirs() }
        val file = File(images, MobileContract.localId(source))
        if (!file.exists()) {
            val temp = File(images, "${UUID.randomUUID()}.tmp")
            try {
                val cached = images.listFiles().orEmpty().filter { !it.name.endsWith(".tmp") }.sortedBy { it.lastModified() }
                var size = cached.sumOf { it.length() }
                for (old in cached) {
                    if (size <= 192L * 1024 * 1024) break
                    val length = old.length()
                    if (old.delete()) size -= length
                }
                HttpBoundary.download(source, temp, 64L * 1024 * 1024)
                checkImage(temp)
                check(temp.renameTo(file))
            } finally { temp.delete() }
        }
        decode(file, maxWidth, maxHeight)
    }
    private fun checkImage(file: File) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        check(options.outWidth in 1..50000 && options.outHeight in 1..100000 && options.outWidth.toLong() * options.outHeight <= 200_000_000) { "Unsupported or oversized image" }
    }
    private fun decode(file: File, maxWidth: Int, maxHeight: Int): Bitmap {
        checkImage(file)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        var sample = 1
        while (options.outWidth / sample > maxWidth || options.outHeight / sample > maxHeight) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Unreadable page")
    }
}
