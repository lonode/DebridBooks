package io.github.lonode.debridbooks.data

import android.util.LruCache
import io.github.lonode.debridbooks.domain.SearchItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class AudioLibraryItem(val item: SearchItem?)

/** Retain decrypted presentation data across tab switches; BookEntity versions invalidate updates. */
class AudioLibraryItems(private val dao: LibraryDao, private val secrets: ConnectionStore) {
    private val cache = LruCache<BookEntity, AudioLibraryItem>(128)
    private val json = Json { ignoreUnknownKeys = true }
    private val changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    val revision = changes as kotlinx.coroutines.flow.StateFlow<Int>
    fun clear() { cache.evictAll(); changes.value++ }
    fun cached(book: BookEntity): AudioLibraryItem? = cache.get(book)
    suspend fun item(book: BookEntity): AudioLibraryItem {
        cached(book)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val item = dao.audioSource(book.accountId, book.itemId)?.let {
                    json.decodeFromString<SearchItem>(secrets.reveal(it.payload, book.accountId))
                }
                AudioLibraryItem(item).also { cache.put(book, it) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { AudioLibraryItem(null) }
        }
    }
}
