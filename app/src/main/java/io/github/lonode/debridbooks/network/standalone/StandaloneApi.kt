package io.github.lonode.debridbooks.network.standalone

import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The phone owns integrations; no local HTTP server or JavaScript runtime is embedded. */
class StandaloneApi(private val config: StandaloneConfig, private val http: DirectTransport = DirectHttp) {
    fun capabilities(): Capabilities = Capabilities(true, "DebridBooks", "alldebrid", providerKeyValid = true,
        sources = mapOf("comics" to (config.trackers().isNotEmpty() || config.jackettUrl.isNotBlank()), "mangadex" to true,
            "c411" to config.c411ApiKey.isNotBlank(), "tr4ker" to config.tr4kerApiKey.isNotBlank(), "yggreborn" to config.yggrebornPasskey.isNotBlank(),
            "audiobookbay" to config.abbDomain.isNotBlank(), "jackett" to config.jackettUrl.isNotBlank()),
        features = ServerFeatures(bookDetails = true, searchWarnings = true, streamFileSizes = true))
    suspend fun validate() = directBoundary("Setup") {
        config.validate()
        AllDebridClient(config, http).validate()
        TorrentSearch(config, http).validateSources()
    }
    suspend fun search(query: String, page: Int, comic: Boolean, series: Boolean, onlySources: Set<String> = emptySet(), forceAbb: Boolean = false, onProgress: suspend (SearchProgress) -> Unit = {}): SearchPage = directBoundary("Search") {
        require(query.isNotBlank() && query.length <= 500 && page in 1..10000)
        if (comic && series) {
            onProgress(SearchProgress(SearchPage(emptyList()), listOf(SearchSourceProgress("MangaDex"))))
            try {
                val result = MangaDexClient(http).search(query, page)
                onProgress(SearchProgress(result, listOf(SearchSourceProgress("MangaDex", SearchSourceState.Complete, result.results.size)), true))
                return@directBoundary result
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                onProgress(SearchProgress(SearchPage(emptyList()), listOf(SearchSourceProgress("MangaDex", SearchSourceState.Failed,
                    issue = (failure as? DirectFailure)?.reason ?: "SOURCE_UNAVAILABLE"))))
                throw failure
            }
        }
        enrichSearch(query, page, comic, onlySources, forceAbb, onProgress)
    }
    /** Publish optional metadata as it completes, even while another search source is pending. */
    private suspend fun enrichSearch(query: String, page: Int, comic: Boolean, onlySources: Set<String>, forceAbb: Boolean,
        onProgress: suspend (SearchProgress) -> Unit): SearchPage = coroutineScope {
        val lock = Mutex()
        val catalog = CatalogClient(http, currentCoroutineContext()[Job])
        val scheduled = mutableSetOf<String>()
        val completed = mutableMapOf<String, SearchItem>()
        val parsed = mutableMapOf<String, SearchItem>()
        val jobs = mutableListOf<Job>()
        var latest = SearchProgress(SearchPage(emptyList()), emptyList())
        fun enriched(page: SearchPage, backfill: Boolean) = page.copy(results = page.results.map { item ->
            val metadata = completed[item.id] ?: parsed[item.id] ?: item
            val current = item.copy(title = metadata.title, author = metadata.author, poster = metadata.poster)
            if (backfill) CatalogClient.cachedIdentity(current) ?: current else current
        })
        suspend fun publish(finished: Boolean = false): SearchPage {
            val snapshot = enriched(latest.page, backfill = finished)
            val details = SearchSourceProgress("Book details", if (finished) SearchSourceState.Complete else SearchSourceState.Pending,
                count = snapshot.results.count { !it.poster.isNullOrBlank() })
            onProgress(SearchProgress(snapshot, latest.sources + details, finished))
            return snapshot
        }
        val result = TorrentSearch(config, http).search(query, page, comic, onlySources, forceAbb) { update ->
            lock.withLock {
                latest = update
                update.page.results.forEach { item ->
                    if (scheduled.add(item.id)) {
                        parsed[item.id] = releaseTitle(item)
                        jobs += launch {
                            val metadata = try { catalog.enrich(item) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { releaseTitle(item) }
                            lock.withLock {
                                completed[item.id] = metadata
                                publish()
                            }
                        }
                    }
                }
                publish()
            }
        }
        // Each provider request has its own timeout. A shared page deadline used to cancel
        // the first four lookups and every queued book before any cover could arrive.
        jobs.joinAll()
        lock.withLock {
            latest = latest.copy(page = result)
            publish(finished = true)
        }
    }

    private fun releaseTitle(item: SearchItem): SearchItem = CatalogClient.cachedIdentity(item) ?: ReleaseName.parse(item.releaseName ?: item.title, item.type == "comic").let {
        item.copy(title = it.title, author = it.author ?: item.author)
    }
    suspend fun resolve(id: String, onProgress: (OpeningProgress) -> Unit = {}): Resolution = directBoundary("AllDebrid") { AllDebridClient(config, http).resolve(TorrentSearch(config, http).resolveAbb(ResolverItem.decode(id)), onProgress) }
    suspend fun chapters(id: String, offset: Int) = directBoundary("MangaDex") { MangaDexClient(http).chapters(id, offset) }
    suspend fun pages(id: String, saver: Boolean) = directBoundary("MangaDex") { MangaDexClient(http).pages(id, saver) }
    suspend fun details(id: String, language: String?): BookDetails = directBoundary("Book catalog") { CatalogClient(http).details(ResolverItem.decode(id), language) }
    suspend fun series(id: String, language: String? = null): SeriesDetails? = directBoundary("Book catalog") { CatalogClient(http).series(ResolverItem.decode(id), language ?: config.languageFilter) }
    suspend fun similar(id: String, language: String? = null): SimilarDetails? = directBoundary("Book catalog") { CatalogClient(http).similar(ResolverItem.decode(id), language ?: config.languageFilter) }
}
