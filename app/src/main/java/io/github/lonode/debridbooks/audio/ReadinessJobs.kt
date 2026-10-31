package io.github.lonode.debridbooks.audio

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import io.github.lonode.debridbooks.*
import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.domain.*
import io.github.lonode.debridbooks.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ReadinessJobs {
    private val mutex = Mutex()
    fun configure(app: DebridApplication, options: AppOptions, unmetered: Boolean) {
        val jobs = app.getSystemService(JobScheduler::class.java)
        if (!options.notifyReady && !options.notifyReleases) { jobs.cancel(2102); return }
        jobs.schedule(JobInfo.Builder(2102, ComponentName(app, ReadinessJob::class.java)).setPeriodic(30 * 60_000L)
            .setRequiredNetworkType(if (unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY).setRequiresBatteryNotLow(true).build())
    }
    private fun canNotify(app: DebridApplication): Boolean =
        (android.os.Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    private fun notify(app: DebridApplication, id: Int, title: String, text: String, query: String? = null): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = app.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(NotificationChannel("book-notices", "Book notifications", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, id, Intent(app, MainActivity::class.java).apply { if (query != null) putExtra("search_query", query.take(500)) else putExtra("open_library", true) }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(id, Notification.Builder(app, "book-notices").setSmallIcon(R.drawable.ic_app).setContentTitle(title.take(100))
            .setContentText(text.take(300)).setContentIntent(open).setAutoCancel(true).build())
        return true
    }
    suspend fun check(app: DebridApplication, foreground: Boolean) = mutex.withLock {
        val preferences = app.preferences.preferences.first()
        if (!foreground && !preferences.options.notifyReady && !preferences.options.notifyReleases) return@withLock
        if (!foreground && !canNotify(app)) return@withLock
        val connection = withContext(Dispatchers.IO) { app.connections.read() } ?: return@withLock
        val dao = app.database.audio()
        if (foreground || preferences.options.notifyReady) for (entry in dao.waiting().filter { it.accountId == connection.accountId }.take(5)) {
            currentCoroutineContext().ensureActive()
            try {
                val item = app.audio.source(entry.accountId, entry.itemId)
                if (entry.state == "WAITING") {
                    val result = MobileApi(connection).resolve(item.id)
                    if (!result.ready) { dao.waitingStatus(entry.accountId, entry.itemId, result.preparationMessage()); continue }
                    val estimate = result.streams.sizeEstimate()
                    if (dao.ready(entry.accountId, entry.itemId, estimate.knownBytes.takeIf { estimate.complete }) == 0) continue
                }
                if (foreground) {
                    currentCoroutineContext().ensureActive()
                    if (preferences.unmeteredOnly && app.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered) continue
                    if (dao.startReady(entry.accountId, entry.itemId, System.currentTimeMillis()) == 0) continue
                    app.startForegroundService(Intent(app, AudioDownloadService::class.java))
                } else if (preferences.options.notifyReady && !entry.announced && notify(app, 3000 + entry.itemId.hashCode().and(65535), "Ready to download", "A saved audiobook is ready. Open DebridBooks to download it.")) {
                    dao.announced(entry.accountId, entry.itemId)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Keep waiting; never convert a failed request into a ready/empty success. */ }
        }
        if (!preferences.options.notifyReleases || !canNotify(app)) return@withLock
        val now = System.currentTimeMillis(); val today = java.time.LocalDate.now().toString()
        val progress = dao.allProgress().filter { it.accountId == connection.accountId }.sortedByDescending { it.updatedAt }.take(10)
        for (book in progress) {
            val old = dao.personal(book.accountId, book.itemId).extras()
            if (now - old.releaseCheckedAt < 20 * 60 * 60_000L) continue
            try {
                val source = app.audio.source(book.accountId, book.itemId)
                val data = app.discovery.load(connection, source, book.itemId, refreshSeries = true)
                if (data.seriesAt <= old.releaseCheckedAt) continue
                val series = data.series ?: continue
                val since = old.alertSince ?: today
                val announced = old.alerted.toMutableSet()
                for (candidate in series.books.filter { it.released != null && it.released > since && it.released <= today && !it.current }.take(30)) {
                    val key = MobileContract.localId(series.name + ":" + candidate.position + ":" + candidate.released + ":" + candidate.title)
                    if (key in announced) continue
                    val safeTitle = safeProviderStatus(candidate.title) ?: "A book in your series"
                    if (notify(app, 70000 + key.hashCode().and(65535), "New in ${safeProviderStatus(series.name) ?: "your series"}", safeTitle, candidate.search ?: candidate.title)) announced += key
                }
                dao.updatePersonal(book.accountId, book.itemId) { it.copy(alertSince = today, alerted = announced.toList().takeLast(300).toSet(), releaseCheckedAt = now) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* No fabricated announcements on missing/failed optional metadata. */ }
        }
    }
}
class ReadinessJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            try { withTimeout(120_000) { ReadinessJobs.check(application as DebridApplication, foreground = false) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
            finally { if (isActive) jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
