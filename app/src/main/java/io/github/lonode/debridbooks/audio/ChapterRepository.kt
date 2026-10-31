package io.github.lonode.debridbooks.audio

import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.domain.MobileContract
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChapterRepository(private val dao: AudioLibraryDao, private val books: LibraryDao) {
    private val mutex = Mutex()
    private val failed = LinkedHashSet<String>()
    suspend fun chapters(account: String, item: String, part: String, uri: String): List<AudioChapter>? = mutex.withLock {
        val key = "$part:v1"
        dao.personal(account, item).extras().chapters[key]?.let { return@withLock it }
        val attempt = "$account:$item:$key:${MobileContract.localId(uri)}"
        if (attempt in failed) return@withLock null
        try {
            val chapters = withTimeoutOrNull(60_000) { withContext(Dispatchers.IO) {
                val parsed = android.net.Uri.parse(uri)
                if (parsed.scheme == "file") LocalChapterSource(checkNotNull(parsed.path)).use { EmbeddedChapters.read(it) }
                else EmbeddedChapters.read(RemoteChapterSource(uri))
            } } ?: return@withLock null
            currentCoroutineContext().ensureActive()
            if (books.audioSource(account, item) != null) dao.updatePersonal(account, item) { it.copy(chapters = it.chapters + (key to chapters)) }
            chapters
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed += attempt; while (failed.size > 128) failed.remove(failed.first()); null }
    }
}
