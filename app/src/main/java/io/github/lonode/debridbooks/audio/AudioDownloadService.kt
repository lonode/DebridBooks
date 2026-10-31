package io.github.lonode.debridbooks.audio

import android.app.*
import android.content.Intent
import android.net.ConnectivityManager
import android.os.IBinder
import io.github.lonode.debridbooks.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first

class AudioDownloadService : Service() {
    private val app get() = application as DebridApplication
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var active: Pair<String, String>? = null
    private var task: Job? = null
    private var start = 0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("audio-downloads", "Audiobook downloads", NotificationManager.IMPORTANCE_LOW))
        startForeground(2100, notification("Preparing downloads"))
        scope.launch {
            app.database.audio().reconcileInterrupted(app.processOwner)
            for (ignored in wake) {
                while (true) {
                    val entry = app.database.audio().pending().firstOrNull() ?: break
                    val dao = app.database.audio()
                    active = entry.accountId to entry.itemId
                    task = launch {
                        try {
                            val connection = withContext(Dispatchers.IO) { app.connections.read() } ?: error("Disconnected")
                            require(connection.accountId == entry.accountId)
                            val unmetered = app.preferences.preferences.first().unmeteredOnly
                            val connectivity = getSystemService(ConnectivityManager::class.java)
                            fun network() { if (unmetered && connectivity.isActiveNetworkMetered) throw AudioUnmeteredRequired() }
                            network()
                            if (dao.tryStart(entry.accountId, entry.itemId, System.currentTimeMillis(), app.processOwner) == 0) return@launch
                            var last = 0L
                            app.audio.download(connection, entry, ::network) { bytes, total ->
                                val now = System.currentTimeMillis()
                                if (now - last >= 1000) {
                                    last = now
                                    scope.launch { dao.downloadProgress(entry.accountId, entry.itemId, bytes, total) }
                                    manager.notify(2100, notification("Downloading ${bytes / 1_000_000} MB" + (total?.let { " / ${it / 1_000_000} MB" } ?: "")))
                                }
                            }
                            if (dao.download(entry.accountId, entry.itemId)?.state == "COMPLETE") app.playbackRuntime.update { it.copy(message = "Audiobook downloaded. It is available offline in your library.") }
                        } catch (cancel: CancellationException) {
                            withContext(NonCancellable) { dao.state(entry.accountId, entry.itemId, "PAUSED", "Download paused. Retry to restart unfinished files.") }
                            throw cancel
                        } catch (preparing: io.github.lonode.debridbooks.network.MediaPreparingException) {
                            dao.state(entry.accountId, entry.itemId, "WAITING", preparing.displayStatus)
                        } catch (_: AudioUnmeteredRequired) { dao.state(entry.accountId, entry.itemId, "PAUSED", "An unmetered network is required. Retry when connected.") }
                        catch (_: Exception) { dao.state(entry.accountId, entry.itemId, "FAILED", "Download stopped. Check connection, available storage and network settings.") }
                    }
                    task!!.join(); task = null; active = null
                }
                stopSelfResult(start)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        start = startId
        if (intent?.action == "pause") scope.launch {
            val identity = intent.getStringExtra("account") to intent.getStringExtra("item")
            if (identity == active) task?.cancelAndJoin()
            if (identity.first != null && identity.second != null) app.database.audio().state(identity.first!!, identity.second!!, "PAUSED", "Paused")
            wake.trySend(Unit)
        } else wake.trySend(Unit)
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { task?.cancel(); stopSelf() }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 2101, Intent(this, AudioDownloadService::class.java).setAction("pause")
            .putExtra("account", active?.first).putExtra("item", active?.second), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "audio-downloads").setSmallIcon(R.drawable.ic_app).setContentTitle("DebridBooks")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Pause", pause).build()).build()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

internal class AudioUnmeteredRequired : IllegalStateException()
