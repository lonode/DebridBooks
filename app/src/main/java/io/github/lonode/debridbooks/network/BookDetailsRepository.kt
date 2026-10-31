package io.github.lonode.debridbooks.network

import io.github.lonode.debridbooks.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Optional page metadata only. No resolver identities or progress are ever rewritten. */
class BookDetailsRepository {
    private fun <K, V> bounded(limit: Int) = object : LinkedHashMap<K, V>(limit, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > limit
    }
    private val unsupported = bounded<String, Boolean>(32)
    private val support = bounded<String, Boolean?>(32)
    private val cache = bounded<String, BookDetails>(128)
    private fun connectionKey(connection: Connection) = connection.cacheKey + ":" + connection.accountId
    @Synchronized fun capabilities(connection: Connection, capabilities: Capabilities) {
        support[connectionKey(connection)] = capabilities.features?.bookDetails
    }
    suspend fun load(connection: Connection, item: SearchItem): BookDetails? {
        if (item.provider == "mangadex") return null
        val account = connectionKey(connection)
        val language = item.detailsLanguage()
        val key = "$account:${MobileContract.localId(item.id)}:${language.orEmpty()}"
        synchronized(this) {
            if (unsupported[account] == true || support[account] == false) return null
            cache[key]?.let { return it }
        }
        val api = MobileApi(connection)
        if (!synchronized(this) { support.containsKey(account) }) {
            val flag = try { api.capabilities().features?.bookDetails }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            currentCoroutineContext().ensureActive()
            synchronized(this) { support[account] = flag }
        }
        if (synchronized(this) { support[account] == false }) return null
        return try {
            val details = api.details(item.id, language)
            currentCoroutineContext().ensureActive()
            synchronized(this) { cache[key] = details }
            details
        } catch (failure: ApiFailure) {
            if (failure.status == 404) {
                synchronized(this) { unsupported[account] = true }
                null
            } else throw failure
        }
    }
}
