package io.github.lonode.debridbooks.comics

import android.app.*
import android.content.Intent
import android.net.ConnectivityManager
import android.os.IBinder
import io.github.lonode.debridbooks.DebridApplication
import io.github.lonode.debridbooks.MainActivity
import io.github.lonode.debridbooks.R
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first

/** Durable identities live in Room; fresh URLs are resolved in service memory. */
class ComicDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var running: Job? = null
    private var active: Pair<String, String>? = null
    private var latestStart = 0
    private val app get() = application as DebridApplication
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("comic-downloads", "Comic downloads", NotificationManager.IMPORTANCE_LOW))
        startForeground(2001, notification("Preparing comic downloads"))
        scope.launch {
            for (ignored in wake) {
                while (true) {
                    val entry = app.database.comics().pending().firstOrNull() ?: break
                    val identity = entry.accountId to entry.volumeId
                    active = identity
                    running = launch {
                        val dao = app.database.comics()
                        try {
                            val connection = withContext(Dispatchers.IO) { app.connections.read() } ?: error("Disconnected")
                            require(connection.accountId == entry.accountId)
                            val unmetered = app.preferences.preferences.first().unmeteredOnly
                            val connectivity = getSystemService(ConnectivityManager::class.java)
                            fun network() { if (unmetered && connectivity.isActiveNetworkMetered) throw UnmeteredRequired() }
                            network()
                            if (dao.tryStart(entry.accountId, entry.volumeId) == 0) return@launch
                            val session = app.comics.resolveSaved(connection, entry, ::network, app.preferences.preferences.first().comicSaver)
                            app.comics.download(session, ::network) { done, total ->
                                dao.downloadState(entry.accountId, entry.volumeId, "RUNNING", done)
                                if (android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    manager.notify(2001, notification("Saving comic pages: $done / $total"))
                                }
                            }
                        } catch (cancel: CancellationException) {
                            withContext(NonCancellable) { if (dao.get(entry.accountId, entry.volumeId)?.offline != true) dao.downloadState(entry.accountId, entry.volumeId, "PAUSED") }
                            throw cancel
                        } catch (_: UnmeteredRequired) {
                            dao.downloadState(entry.accountId, entry.volumeId, "PAUSED")
                        } catch (_: Exception) {
                            dao.downloadState(entry.accountId, entry.volumeId, "FAILED")
                        }
                    }
                    running?.join()
                    running = null; active = null
                }
                stopSelfResult(latestStart)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        val account = intent?.getStringExtra("account")
        val volume = intent?.getStringExtra("volume")
        if (intent?.action in listOf("pause", "cancel") && account != null && volume != null) {
            scope.launch {
                if (active == (account to volume)) { running?.cancelAndJoin() }
                app.database.comics().downloadState(account, volume, if (intent?.action == "cancel") "CANCELLED" else "PAUSED")
                wake.trySend(Unit)
            }
        } else wake.trySend(Unit)
        return START_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { running?.cancel(); stopSelf() }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 1, Intent(this, ComicDownloadService::class.java).setAction("pause")
            .putExtra("account", active?.first).putExtra("volume", active?.second), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "comic-downloads").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name)).setContentText(message).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Pause", pause).build()).build()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
private class UnmeteredRequired : IllegalStateException()
