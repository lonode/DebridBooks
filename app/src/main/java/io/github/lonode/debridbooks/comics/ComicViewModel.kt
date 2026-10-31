package io.github.lonode.debridbooks.comics

import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.lonode.debridbooks.DebridApplication
import io.github.lonode.debridbooks.data.ComicEntity
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class ComicState(
    val search: SearchSession = SearchSession(), val selected: SearchItem? = null,
    val streams: List<MediaPart> = emptyList(), val chapters: List<Chapter> = emptyList(),
    val hasMoreChapters: Boolean = false, val reader: ReadingSession? = null,
    val loadProgress: ComicLoadProgress? = null,
    val immersiveHelpDismissed: Boolean? = null,
    val page: Int = 0, val busy: Boolean = false, val searching: Boolean = false, val message: String = "", val saver: Boolean = true,
) { val results: List<SearchItem> get() = search.results }
class ComicViewModel(private val app: DebridApplication) : ViewModel() {
    val repository = app.comics
    val readingHistory = repository.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutable = MutableStateFlow(ComicState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var connection: Connection? = null
    private var query = ""
    private var series = false
    private var activeItem: SearchItem? = null
    private var activeChapter: Chapter? = null
    private var activeStreams: List<MediaPart>? = null
    private var lastRefresh = 0L
    private val writes = Channel<Pair<ComicEntity, Int>>(Channel.UNLIMITED)
    init {
        viewModelScope.launch {
            for ((entry, page) in writes) try { repository.checkpoint(entry, page) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.update { it.copy(message = "Reading progress could not be saved.") } }
        }
        viewModelScope.launch { app.preferences.preferences.catch { mutable.update { it.copy(immersiveHelpDismissed = false, message = "Reader preferences could not be loaded.") } }.collect { prefs -> mutable.update { it.copy(saver = prefs.comicSaver, immersiveHelpDismissed = prefs.immersiveHelpDismissed) } } }
    }
    fun connect(value: Connection?) {
        if (value?.accountId == connection?.accountId && value?.cacheKey == connection?.cacheKey) { connection = value; return }
        job?.cancel(); connection = value
        activeItem = null; activeChapter = null; activeStreams = null
        mutable.value = ComicState(saver = mutable.value.saver, immersiveHelpDismissed = mutable.value.immersiveHelpDismissed)
    }
    private var sourceMode: Int? = null
    fun source(mode: Int) {
        if (sourceMode != null && sourceMode != mode) resetSource()
        sourceMode = mode
    }
    fun resetSource() {
        job?.cancel(); query = ""; activeItem = null
        mutable.value = ComicState(saver = mutable.value.saver, immersiveHelpDismissed = mutable.value.immersiveHelpDismissed)
    }
    fun cancelSearch() {
        if(!mutable.value.searching) return
        job?.cancel()
        mutable.update { it.copy(busy=false,searching=false,search=it.search.cancelled(),message="") }
    }
    fun cancel() { job?.cancel(); mutable.update { it.copy(busy = false, searching=false, loadProgress = null, message = "Cancelled") } }
    private fun work(searching: Boolean = false, action: suspend () -> Unit) {
        job?.cancel()
        mutable.update { it.copy(busy = true, searching=searching, loadProgress = null, message = "") }
        job = viewModelScope.launch {
            try { action(); ensureActive() }
            catch (cancel: CancellationException) { throw cancel }
            catch (preparing: MediaPreparingException) { mutable.update { it.copy(message = preparing.displayStatus) } }
            catch (_: Exception) { mutable.update { it.copy(message = "Could not complete comic operation. Check connectivity, storage and format, then retry.") } }
            finally { if (isActive) mutable.update { it.copy(busy = false, searching=false, loadProgress = null) } }
        }
    }
    suspend fun rememberImmersiveHelp() { app.preferences.setImmersiveHelpDismissed(true) }
    fun saver(value: Boolean) = work { app.preferences.setComicSaver(value) }
    fun search(text: String, fromSeries: Boolean, more: Boolean = false) {
        val session = mutable.value.search
        val request = if (more) SearchRequest(session.query, session.nextPage) else SearchRequest(text.trim(), refresh = true)
        search(request, fromSeries)
    }
    fun retrySearch(request: SearchRequest) = search(request, series)
    private fun search(request: SearchRequest, fromSeries: Boolean) {
        val current = connection ?: return
        if (request.query.isEmpty()) return
        query = request.query; series = fromSeries
        mutable.update { it.copy(search = it.search.requesting(request)) }
        work(searching=true) {
            try {
                val api = MobileApi(current)
                if (request.number == 1) {
                    val health = api.capabilities()
                    app.bookDetails.capabilities(current, health)
                    if (health.sources[if (fromSeries) "mangadex" else "comics"] == false) {
                        throw ApiFailure(503, "NO_SEARCH_SOURCES")
                    }
                }
                val result = api.search(request.query, request.number, comic = true, series = fromSeries, onlySources = request.sources, forceAbb = request.forceAbb) { update ->
                    withContext(Dispatchers.Main.immediate) {
                        ensureActive()
                        mutable.update { it.copy(search = it.search.progressing(update)) }
                    }
                }
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(search = it.search.received(result), selected = null, streams = emptyList(), chapters = emptyList()) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { it.copy(search = it.search.failed(error)) } }
        }
    }
    fun select(item: SearchItem) {
        val current = connection ?: return
        if (mutable.value.searching) cancelSearch()
        mutable.update { it.copy(selected = item, streams = emptyList(), chapters = emptyList(), hasMoreChapters = false) }
        work {
            if (item.provider == "mangadex") {
                val page = MobileApi(current).chapters(item.id)
                mutable.update { it.copy(chapters = page.chapters, hasMoreChapters = page.hasMore) }
            } else {
                val result = MobileApi(current).resolve(item.id)
                mutable.update { it.copy(streams = result.streams, message = if (result.ready) "" else result.preparationMessage()) }
            }
        }
    }
    fun closeSelection() {
        job?.cancel()
        mutable.update { it.copy(selected = null, streams = emptyList(), chapters = emptyList(), hasMoreChapters = false, busy = false, searching=false, loadProgress = null, message = "") }
    }
    fun moreChapters() {
        val current = connection ?: return
        val item = mutable.value.selected ?: return
        work {
            val offset = mutable.value.chapters.size
            val page = MobileApi(current).chapters(item.id, offset)
            currentCoroutineContext().ensureActive()
            require(page.offset == offset)
            mutable.update { it.copy(chapters = (it.chapters + page.chapters).distinctBy { c -> c.id }, hasMoreChapters = page.hasMore) }
        }
    }
    private fun reportProgress(value: ComicLoadProgress) { mutable.update { it.copy(loadProgress = value) } }
    fun open(item: SearchItem, chapter: Chapter? = null, streams: List<MediaPart>? = null) {
        val current = connection ?: return
        work {
            val session = repository.open(current, item, chapter, streams, mutable.value.saver, onProgress = ::reportProgress)
            currentCoroutineContext().ensureActive()
            activeItem = item; activeChapter = chapter; activeStreams = streams
            mutable.update { it.copy(reader = session, page = session.entry.page.coerceIn(0, session.pages.lastIndex)) }
        }
    }
    fun history(entry: ComicEntity, fromStart: Boolean = false) = work {
        if (fromStart) repository.checkpoint(entry, 0)
        val session = if (entry.offline) repository.openOffline(if (fromStart) entry.copy(page = 0) else entry) else {
            val current = connection ?: error("Connect before reopening online history")
            require(current.accountId == entry.accountId)
            val item = SearchItem(repository.resolver(entry), entry.title, provider = if (entry.chapterId != null) "mangadex" else null, type = "comic")
            activeItem = item
            if (entry.chapterId != null) {
                activeChapter = Chapter(entry.chapterId)
                repository.open(current, item, activeChapter, saver = mutable.value.saver)
            } else {
                val result = MobileApi(current).resolve(item.id)
                if (!result.ready) throw MediaPreparingException(result.preparationMessage())
                val archive = result.streams.filter { (it.filename ?: it.title).endsWith(".cbz", true) }
                    .firstOrNull { io.github.lonode.debridbooks.domain.MobileContract.localId(entry.itemId + ":" + it.id) == entry.volumeId }
                activeStreams = archive?.let { listOf(it) }
                repository.resolveSaved(current, entry, saver = mutable.value.saver, onProgress = ::reportProgress)
            }
        }
        currentCoroutineContext().ensureActive()
        mutable.update { it.copy(reader = session, page = session.entry.page.coerceIn(0, session.pages.lastIndex)) }
    }
    fun page(index: Int) {
        val session = mutable.value.reader ?: return
        val page = index.coerceIn(0, session.pages.lastIndex)
        mutable.update { it.copy(page = page) }
        writes.trySend(session.entry to page)
    }
    fun closeReader() { job?.cancel(); mutable.update { it.copy(reader = null, busy = false, loadProgress = null) } }
    fun refresh() {
        if (System.currentTimeMillis() - lastRefresh < 30000) return
        val current = connection ?: return
        val item = activeItem ?: return
        val session = mutable.value.reader ?: return
        if (session.entry.offline) return
        lastRefresh = System.currentTimeMillis()
        work {
            // Save position before resolution so renewed URLs cannot reset it.
            repository.checkpoint(session.entry, mutable.value.page)
            val streams = if (activeChapter == null) {
                val fresh = MobileApi(current).resolve(item.id)
                if (!fresh.ready) throw MediaPreparingException(fresh.preparationMessage())
                activeStreams?.singleOrNull()?.let { old -> listOf(fresh.streams.single { it.id == old.id }) } ?: fresh.streams
            } else null
            val renewed = repository.open(current, item, activeChapter, streams, mutable.value.saver, refresh = true, onProgress = ::reportProgress)
            require(renewed.pages.size == session.pages.size)
            mutable.update { it.copy(reader = renewed) }
        }
    }
    fun download(entry: ComicEntity? = mutable.value.reader?.entry) {
        entry ?: return
        work {
            app.database.comics().enqueue(entry.accountId, entry.volumeId)
            app.startForegroundService(android.content.Intent(app, ComicDownloadService::class.java))
            mutable.update { it.copy(message = "Offline download queued. Progress is in Reading history.") }
        }
    }
    private suspend fun pauseDownload(entry: ComicEntity, cancelled: Boolean = false) {
        if (entry.downloadState in listOf("QUEUED", "RUNNING")) {
            app.startService(android.content.Intent(app, ComicDownloadService::class.java).setAction(if (cancelled) "cancel" else "pause")
                .putExtra("account", entry.accountId).putExtra("volume", entry.volumeId))
            // The service serializes cancellation; repository deletion then waits for its write lock.
            app.database.comics().downloadState(entry.accountId, entry.volumeId, if (cancelled) "CANCELLED" else "PAUSED")
        }
    }
    fun pause(entry: ComicEntity, cancelled: Boolean = false) = work { pauseDownload(entry, cancelled) }
    fun delete(entry: ComicEntity, history: Boolean = false) = work { pauseDownload(entry); repository.delete(entry, history) }
    fun export(part: MediaPart, target: android.net.Uri) = work {
        val current = connection ?: error("Disconnected")
        val item = mutable.value.selected ?: error("No selected comic")
        val unmetered = app.preferences.preferences.first().unmeteredOnly
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun network() { check(!unmetered || !connectivity.isActiveNetworkMetered) }
        withContext(Dispatchers.IO) {
            val file = java.io.File(app.cacheDir, "comic-export-${java.util.UUID.randomUUID()}")
            try {
                network()
                try { HttpBoundary.download(part.url, file, 512L * 1024 * 1024, ::network) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    val fresh = MobileApi(current).resolveReady(item.id).streams.single { it.id == part.id }
                    HttpBoundary.download(fresh.url, file, 512L * 1024 * 1024, ::network)
                }
                app.contentResolver.openOutputStream(target, "w")!!.use { output -> file.inputStream().use { input ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } }
            } catch (error: Exception) {
                runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, target) }
                throw error
            } finally { file.delete() }
        }
        mutable.update { it.copy(message = "File saved to the location you selected.") }
    }
    fun clearCache() = work { repository.clearCache(); mutable.update { it.copy(message = "Comic cache cleared") } }
}
