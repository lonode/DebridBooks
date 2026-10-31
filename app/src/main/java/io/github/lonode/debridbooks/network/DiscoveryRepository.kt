package io.github.lonode.debridbooks.network

import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface DiscoveryCatalog {
    suspend fun series(item: SearchItem): SeriesDetails?
    suspend fun similar(item: SearchItem): SimilarDetails?
}
data class DiscoveryResult(val data: DiscoveryRecord, val seriesFailed: Boolean = false, val similarFailed: Boolean = false)

/** Catalog suggestions only. Details and upload search remain separate; discovery never resolves a torrent. */
class DiscoveryRepository(private val dao: AudioLibraryDao,
    private val catalog: (Connection) -> DiscoveryCatalog = { connection -> object : DiscoveryCatalog {
        private val api = MobileApi(connection)
        override suspend fun series(item: SearchItem) = api.series(item.id, item.detailsLanguage())
        override suspend fun similar(item: SearchItem) = api.similar(item.id, item.detailsLanguage())
    } },
) {
    private val mutex = Mutex()
    private val unsupported = LinkedHashSet<String>()
    private data class Lookup<T>(val value: T?, val updated: Boolean = false, val failed: Boolean = false)
    suspend fun load(connection: Connection, item: SearchItem, savedKey: String? = null, refreshSeries: Boolean = false): DiscoveryRecord =
        loadResult(connection, item, savedKey, refreshSeries).data

    suspend fun loadResult(connection: Connection, item: SearchItem, savedKey: String? = null,
        refreshSeries: Boolean = false, refreshSimilar: Boolean = false): DiscoveryResult = mutex.withLock {
        // Invalidate old silent negative matches and keep recording languages isolated.
        val scope = connection.cacheKey + if (connection.standalone != null) ":catalog-v4:${item.detailsLanguage().orEmpty()}" else ""
        val now = System.currentTimeMillis()
        val cached = savedKey?.let { dao.personal(connection.accountId, it).extras().discovery }?.takeIf { it.connection == scope }
        val old = cached ?: DiscoveryRecord(scope)
        fun fresh(at: Long, present: Boolean) = now - at in 0 until (if (present) 3 * 24 * 60 * 60_000L else 12 * 60 * 60_000L)
        suspend fun <T> lookup(kind: String, skip: Boolean, previous: T?, block: suspend () -> T?): Lookup<T> {
            val identity = "$scope:$kind"
            if (skip || synchronized(unsupported) { identity in unsupported }) return Lookup(previous)
            return try {
                val value = block()
                currentCoroutineContext().ensureActive()
                Lookup(value, updated = true)
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                Lookup(previous, failed = true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: ApiFailure) {
                if (failure.status != 404) Lookup(previous, failed = true) else {
                    synchronized(unsupported) { unsupported += identity; while (unsupported.size > 64) unsupported.remove(unsupported.first()) }
                    Lookup(previous)
                }
            } catch (_: Exception) { Lookup(previous, failed = true) }
        }
        val api = catalog(connection)
        val result = coroutineScope {
            val series = async { lookup("series", !refreshSeries && fresh(old.seriesAt, old.series != null), old.series) { api.series(item) } }
            val similar = async { lookup("similar", !refreshSimilar && fresh(old.similarAt, old.similar != null) && (old.similar?.partial != true || now - old.similarAt < 10 * 60_000L), old.similar) { api.similar(item) } }
            val first = series.await(); val second = similar.await()
            DiscoveryResult(old.copy(series = first.value, seriesAt = if (first.updated) now else old.seriesAt,
                similar = second.value, similarAt = if (second.updated) now else old.similarAt), first.failed, second.failed)
        }
        if (savedKey != null && result.data != cached && (result.data.seriesAt > 0 || result.data.similarAt > 0)) {
            dao.updatePersonal(connection.accountId, savedKey) { it.copy(discovery = result.data) }
        }
        result
    }
}
