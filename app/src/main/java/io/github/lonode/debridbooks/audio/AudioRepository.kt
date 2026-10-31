package io.github.lonode.debridbooks.audio

import android.content.Context
import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable data class OfflinePart(val id: String, val title: String, val filename: String?, val bytes: Long)
@Serializable data class OfflineAudio(val parts: List<OfflinePart>)

/** Only encrypted identities/metadata and hashed local filenames are durable. URLs stay in memory. */
class AudioRepository(context: Context, private val db: LibraryDatabase, private val secrets: SecretCodec) {
    private val root = File(context.noBackupFilesDir, "audio-books")
    private val mutex = Mutex()
    private fun folder(account: String, item: String) = File(File(root, MobileContract.localId(account)), MobileContract.localId(item))
    suspend fun source(account: String, item: String): SearchItem = withContext(Dispatchers.IO) {
        val source = db.library().audioSource(account, item) ?: error("Saved source unavailable")
        libraryJson.decodeFromString<SearchItem>(secrets.reveal(source.payload, account))
    }
    suspend fun remember(account: String, item: SearchItem) = withContext(Dispatchers.IO) {
        db.library().rememberSource(BookEntity(account, MobileContract.localId(item.id), item.title, item.author, System.currentTimeMillis()),
            AudioSource(account, MobileContract.localId(item.id), secrets.protect(libraryJson.encodeToString(item), account)))
    }
    suspend fun queue(account: String, item: SearchItem, allowLowSpace: Boolean = false) {
        remember(account, item)
        val key = MobileContract.localId(item.id)
        db.audio().enqueue(AudioDownload(account, key, allowLowSpace = allowLowSpace))
    }
    suspend fun offline(account: String, item: String): Resolution? = withContext(Dispatchers.IO) {
        val entry = db.audio().download(account, item)?.takeIf { it.state == "COMPLETE" } ?: return@withContext null
        try {
            val manifest = libraryJson.decodeFromString<OfflineAudio>(secrets.reveal(checkNotNull(entry.manifest), account))
            require(manifest.parts.size in 1..500)
            val parts = manifest.parts.map { part ->
                require(Regex("[a-f0-9]{64}").matches(part.id) && part.bytes > 0)
                val file = File(folder(account, item), "${part.id}.audio")
                check(file.isFile && file.length() == part.bytes)
                MediaPart(part.title, android.net.Uri.fromFile(file).toString(), part.filename, part.bytes).also { require(it.id == part.id) }
            }
            Resolution(true, parts, "Offline")
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            db.audio().putDownload(entry.copy(state = "FAILED", status = "Offline files are missing or incomplete. Download again.", manifest = null))
            null
        }
    }
    suspend fun download(connection: Connection, entry: AudioDownload, network: () -> Unit = {}, progress: (Long, Long?) -> Unit = { _, _ -> }) = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(connection.accountId == entry.accountId)
            val item = source(entry.accountId, entry.itemId)
            require(MobileContract.localId(item.id) == entry.itemId)
            network()
            val api = MobileApi(connection)
            val resolved = api.resolve(item.id)
            if (!resolved.ready) {
                db.audio().state(entry.accountId, entry.itemId, "WAITING", resolved.preparationMessage())
                return@withContext
            }
            val directory = folder(entry.accountId, entry.itemId)
            val stage = File(directory.parentFile, "${directory.name}-staging")
            check(stage.parentFile!!.mkdirs() || stage.parentFile!!.isDirectory)
            if (!stage.exists() && directory.exists()) check(directory.renameTo(stage))
            check(stage.mkdirs() || stage.isDirectory)
            val estimate = resolved.streams.sizeEstimate()
            if (!entry.allowLowSpace && estimate.knownBytes > 0 && stage.usableSpace - (estimate.knownBytes - entry.bytes).coerceAtLeast(0) < 200L * 1024 * 1024) {
                db.audio().state(entry.accountId, entry.itemId, "PAUSED", "Space is limited. Free storage or choose Download anyway on the book page.")
                return@withContext
            }
            var received = 0L
            val manifest = mutableListOf<OfflinePart>()
            val previous = try { entry.manifest?.let { libraryJson.decodeFromString<OfflineAudio>(secrets.reveal(it, entry.accountId)).parts.associateBy { it.id } }.orEmpty() } catch (_: Exception) { emptyMap() }
            try {
                for (part in resolved.streams) {
                    currentCoroutineContext().ensureActive(); network()
                    val target = File(stage, "${part.id}.audio")
                    val stored = previous[part.id]
                    if (stored != null && target.isFile && target.length() == stored.bytes && stored.bytes > 0) {
                        manifest += stored; received += stored.bytes
                        require(received <= 20L * 1024 * 1024 * 1024)
                        progress(received, estimate.knownBytes.takeIf { estimate.complete }); continue
                    }
                    fun report(bytes: Long, ignored: Long?) { progress(received + bytes, estimate.knownBytes.takeIf { estimate.complete }) }
                    try { HttpBoundary.download(part.url, target, minOf(4L * 1024 * 1024 * 1024, 20L * 1024 * 1024 * 1024 - received), network, ::report) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (policy: AudioUnmeteredRequired) { throw policy }
                    catch (_: Exception) {
                        val renewed = api.resolveReady(item.id).streams.single { it.id == part.id }
                        HttpBoundary.download(renewed.url, target, minOf(4L * 1024 * 1024 * 1024, 20L * 1024 * 1024 * 1024 - received), network, ::report)
                    }
                    val prefix = target.inputStream().use { input -> val bytes = ByteArray(256); val count = input.read(bytes); if (count > 0) bytes.copyOf(count).toString(Charsets.UTF_8).trimStart().lowercase() else "" }
                    check(!prefix.startsWith("<html") && !prefix.startsWith("<!doctype") && !prefix.startsWith("{\"error\"") && !prefix.startsWith("<?xml")) { "Provider returned an error document" }
                    received += target.length()
                    require(received <= 20L * 1024 * 1024 * 1024)
                    manifest += OfflinePart(part.id, part.title, part.filename, target.length())
                    db.audio().partial(entry.accountId, entry.itemId, secrets.protect(libraryJson.encodeToString(OfflineAudio(manifest)), entry.accountId), received)
                }
                currentCoroutineContext().ensureActive()
                val allowed = manifest.map { "${it.id}.audio" }.toSet()
                stage.listFiles()?.filter { it.name !in allowed }?.forEach { check(it.deleteRecursively()) }
                if (directory.exists()) check(directory.deleteRecursively())
                check(stage.renameTo(directory))
                val sealed = secrets.protect(libraryJson.encodeToString(OfflineAudio(manifest)), entry.accountId)
                db.audio().putDownload(entry.copy(state = "COMPLETE", bytes = received, total = received, manifest = sealed, status = null,
                    updatedAt = System.currentTimeMillis()))
            } finally { /* Private completed files remain available for retry. */ }
        }
    }
    suspend fun sweepOrphans() = mutex.withLock { withContext(Dispatchers.IO) {
        val owned = db.audio().allDownloads().flatMap { entry -> val path = folder(entry.accountId, entry.itemId); listOf(path.absolutePath, File(path.parentFile, "${path.name}-staging").absolutePath) }.toSet()
        val since = System.currentTimeMillis() - 24 * 60 * 60_000L
        root.listFiles()?.filter { it.isDirectory }?.forEach { account -> account.listFiles()?.forEach { file ->
            if (file.absolutePath !in owned && file.lastModified() < since) check(file.deleteRecursively())
        } }
    } }
    suspend fun delete(account: String, item: String) = mutex.withLock { withContext(Dispatchers.IO) {
        val directory = folder(account, item)
        if (directory.exists()) check(directory.deleteRecursively())
        val stage = File(directory.parentFile, "${directory.name}-staging")
        if (stage.exists()) check(stage.deleteRecursively())
        db.audio().removeDownload(account, item)
    } }
}
