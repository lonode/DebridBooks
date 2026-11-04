package io.github.lonode.debridbooks.playback

import androidx.room.withTransaction
import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import io.github.lonode.debridbooks.*
import io.github.lonode.debridbooks.data.*
import io.github.lonode.debridbooks.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** One native owner for transport, durable checkpoints, offline browsing, sleep and recovery. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackService : MediaLibraryService() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val app get() = application as DebridApplication
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private var options = AppOptions()
    private var defaultSpeed = 1f
    private var pausedAt: Long? = null
    private var explicit = false
    private var sleepEnd = 0L
    private var sleepAtEnd = false
    private var originalVolume = 1f
    private var undo: Pair<String, Long>? = null
    private var restore: Job? = null
    private var recovery: Job? = null
    private var lastRecovery = 0L
    private val clock = ListeningClock()
    private fun identity() = player.currentMediaItem?.mediaMetadata?.extras?.let { extras ->
        val account = extras.getString("account"); val item = extras.getString("item")
        if (account != null && item != null) account to item else null
    }
    private fun checkpoint() {
        val (account, item) = identity() ?: return
        val media = player.currentMediaItem ?: return
        val extras = media.mediaMetadata.extras ?: return
        val now = System.currentTimeMillis()
        val book = BookEntity(account, item, extras.getString("title").orEmpty(), extras.getString("author"), now)
        val progress = ProgressEntity(account, item, media.mediaId, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0),
            player.playbackParameters.speed, player.playbackState == Player.STATE_ENDED, now)
        val parts = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val durations = audioPartDurations(player)
        writes.trySend {
            app.database.withTransaction {
                app.database.library().checkpoint(book, progress)
                app.database.audio().session(AudioSession(accountId = account, itemId = item))
                val saved = app.database.audio().personal(account, item).extras()
                val merged = (saved.audioDurationsMs + durations).filterKeys { it in parts }
                if (saved.audioParts != parts || saved.audioDurationsMs != merged) {
                    app.database.audio().updatePersonal(account, item) { it.copy(audioParts = parts, audioDurationsMs = merged) }
                }
            }
        }
    }
    private fun listening() {
        val id = identity()
        val values = clock.sample(id?.first, id?.second, player.currentMediaItem?.mediaId.orEmpty(), player.currentPosition,
            player.isPlaying, player.playbackParameters.speed, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        if (id != null) values.forEach { (day, ms) -> writes.trySend { app.database.audio().addListening(id.first, id.second, day, ms) } }
    }
    override fun onCreate() {
        super.onCreate()
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val sources = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, http))
        player = ExoPlayer.Builder(this).setMediaSourceFactory(sources).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true); playWhenReady = false
        }
        work.launch {
            for (write in writes) try { write() } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (session != null) player.pause(); app.playbackRuntime.update { it.copy(message = "Could not save playback data. Your previous saved place is retained.") } }
            work.cancel()
        }
        app.playbackRuntime.handler = { action -> work.launch { perform(action) } }
        work.launch { app.preferences.preferences.collectLatest { settings ->
            options = settings.options; defaultSpeed = settings.speed
            val id = identity()
            val speed = id?.let { app.database.audio().personal(it.first, it.second).extras().speed } ?: defaultSpeed
            if (identity() == id) player.setPlaybackSpeed(speed)
        } }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { listening(); checkpoint() }
            override fun onPlaybackStateChanged(playbackState: Int) {
                checkpoint()
                if (playbackState == Player.STATE_ENDED && options.autoDeleteFinished) identity()?.let { id -> work.launch {
                    if (app.database.audio().download(id.first, id.second)?.state == "COMPLETE" && identity() == id) {
                        perform(PlaybackAction.Close)
                        try { app.audio.delete(id.first, id.second) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { app.playbackRuntime.update { it.copy(message = "Book finished, but offline files could not be removed. Use Manage downloads.") } }
                    }
                } }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                clock.reset(); if (reason == Player.DISCONTINUITY_REASON_SEEK) { pausedAt = null; explicit = !player.playWhenReady }; checkpoint()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                clock.reset(); checkpoint()
                identity()?.let { id -> work.launch {
                    val saved = app.database.audio().personal(id.first, id.second).extras()
                    if (identity() == id) player.setPlaybackSpeed(saved.speed ?: defaultSpeed)
                } }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) { pausedAt = SystemClock.elapsedRealtime(); explicit = false }
                else {
                    val rewind = pausedAt?.let { smartRewindMs(SystemClock.elapsedRealtime() - it) } ?: 0
                    pausedAt = null
                    if (options.smartRewind && !explicit && rewind > 0) player.seekTo((player.currentPosition - rewind).coerceAtLeast(0))
                    explicit = false
                    if (options.bedtimeTimer && sleepEnd == 0L && !sleepAtEnd && inBedtime(java.time.LocalTime.now().hour, options.bedtimeStart)) setSleep(options.bedtimeMinutes, false)
                }
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAtEnd) triggerSleep()
                checkpoint()
            }
            override fun onPlayerError(error: PlaybackException) { checkpoint(); recover(false) }
        })
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, player, BrowserCallback()).setSessionActivity(activity).build()
        restore = work.launch { restorePaused() }
        work.launch {
            var ticks = 0
            while (true) {
                delay(250); ticks++
                if (ticks % 4 == 0) listening()
                if (ticks % 20 == 0 && player.isPlaying) checkpoint()
                if (sleepEnd > 0) {
                    val left = (sleepEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    app.playbackRuntime.update { it.copy(sleepRemainingMs = left) }
                    if (options.sleepFade && left < 30_000) player.volume = originalVolume * left / 30_000f
                    if (left == 0L) triggerSleep()
                }
            }
        }
    }
    private fun setSleep(minutes: Int, endOfPart: Boolean) {
        require(minutes in 0..480)
        player.volume = originalVolume
        originalVolume = player.volume
        sleepEnd = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0
        sleepAtEnd = endOfPart; player.pauseAtEndOfMediaItems = endOfPart
        app.playbackRuntime.update { it.copy(sleepRemainingMs = if (minutes > 0) minutes * 60_000L else 0, sleepAtEnd = endOfPart) }
    }
    private fun triggerSleep() {
        player.pause(); setSleep(0, false)
        player.seekTo((player.currentPosition - 60_000).coerceAtLeast(0))
        pausedAt = null; explicit = true; checkpoint()
        app.playbackRuntime.update { it.copy(message = "Sleep timer ended. Rewound one minute.") }
    }
    private suspend fun perform(action: PlaybackAction) {
        app.playbackRuntime.update { it.copy(message = "") }
        try {
            when (action) {
                PlaybackAction.BeginSelection -> { restore?.cancel(); recovery?.cancel() }
                PlaybackAction.Retry -> recover(true)
                PlaybackAction.Close -> {
                    restore?.cancel(); recovery?.cancel(); listening(); checkpoint(); player.pause(); player.clearMediaItems(); setSleep(0, false)
                    writes.trySend { app.database.audio().closeSession() }; undo = null
                    app.playbackRuntime.update { RuntimeState() }
                }
                is PlaybackAction.Sleep -> setSleep(action.minutes, action.endOfPart)
                is PlaybackAction.Jump -> {
                    val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == action.partId } ?: return
                    undo = player.currentMediaItem?.mediaId?.let { it to player.currentPosition }
                    explicit = true; pausedAt = null; player.seekTo(index, action.positionMs.coerceAtLeast(0)); checkpoint()
                    app.playbackRuntime.update { it.copy(undoAvailable = undo != null) }
                }
                PlaybackAction.Undo -> {
                    val previous = undo ?: return; undo = null
                    val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == previous.first } ?: return
                    explicit = true; pausedAt = null; player.seekTo(index, previous.second)
                    app.playbackRuntime.update { it.copy(undoAvailable = false) }
                }
                is PlaybackAction.Speed -> {
                    require(action.value.isFinite() && action.value in .5f..3.5f)
                    player.setPlaybackSpeed(action.value)
                    identity()?.let { app.database.audio().updatePersonal(it.first, it.second) { extras -> extras.copy(speed = action.value) } }; checkpoint()
                }
                is PlaybackAction.Bookmark -> {
                    val id = identity() ?: return
                    val mark = AudioBookmark(java.util.UUID.randomUUID().toString(), player.currentMediaItem!!.mediaId, player.currentPosition.coerceAtLeast(0),
                        player.currentMediaItem?.mediaMetadata?.subtitle?.toString().orEmpty().take(500), action.note.take(2000), System.currentTimeMillis())
                    app.database.audio().updatePersonal(id.first, id.second) { it.copy(bookmarks = it.bookmarks + mark) }
                    app.playbackRuntime.update { it.copy(message = "Bookmark saved") }
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { app.playbackRuntime.update { it.copy(message = "Could not complete playback action. Please retry.") } }
    }
    private suspend fun restorePaused() {
        if (!app.preferences.preferences.first().options.autoResume) return
        val saved = app.database.audio().session() ?: return
        try {
            val item = app.audio.source(saved.accountId, saved.itemId)
            val offline = app.audio.offline(saved.accountId, saved.itemId)
            val connection = withContext(Dispatchers.IO) { app.connections.read() }
            if (connection != null && connection.accountId != saved.accountId) return
            val resolved = offline ?: connection?.takeIf { it.accountId == saved.accountId }?.let { MobileApi(it).resolveReady(item.id) } ?: return
            val progress = app.database.library().progress(saved.accountId, saved.itemId) ?: return
            val queue = audioQueue(saved.accountId, saved.itemId, item, resolved)
            val index = queue.indexOfFirst { it.mediaId == progress.partId }; require(index >= 0)
            currentCoroutineContext().ensureActive()
            if (player.mediaItemCount != 0) return
            player.setMediaItems(queue, index, progress.positionMs); player.prepare(); player.pause(); player.setPlaybackSpeed(progress.speed)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { app.playbackRuntime.update { it.copy(message = "Saved book could not be restored. Open it from Library to retry.") } }
    }
    private fun recover(manual: Boolean) {
        if (recovery?.isActive == true || (!manual && lastRecovery != 0L && SystemClock.elapsedRealtime() - lastRecovery < 30_000)) return
        val id = identity() ?: return
        val media = player.currentMediaItem ?: return
        if (media.localConfiguration?.uri?.scheme !in setOf("http", "https")) return
        // The retry button is an explicit play request; background refresh preserves a pause.
        val part = media.mediaId; val position = player.currentPosition; val intent = manual || player.playWhenReady; val speed = player.playbackParameters.speed
        lastRecovery = SystemClock.elapsedRealtime()
        recovery = work.launch {
            app.playbackRuntime.update { it.copy(recovering = true) }
            try {
                val connection = withContext(Dispatchers.IO) { app.connections.read() } ?: error("Disconnected")
                require(connection.accountId == id.first)
                val item = app.audio.source(id.first, id.second)
                val queue = audioQueue(id.first, id.second, item, MobileApi(connection).resolveReady(item.id))
                val index = queue.indexOfFirst { it.mediaId == part }; require(index >= 0)
                ensureActive(); if (identity() != id || player.currentMediaItem?.mediaId != part) return@launch
                explicit = true; player.setMediaItems(queue, index, position); player.setPlaybackSpeed(speed); player.prepare(); player.playWhenReady = intent
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { app.playbackRuntime.update { it.copy(message = "Could not refresh this book. Retry when the connection is available.") } }
            finally { app.playbackRuntime.update { it.copy(recovering = false) } }
        }
    }
    private fun <T> future(action: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = work.launch { try { result.set(action()) } catch (cancel: CancellationException) { result.cancel(false) }
            catch (_: Exception) { result.setException(IllegalStateException("Offline library unavailable")) } }
        result.addListener({ if (result.isCancelled) job.cancel() }, java.util.concurrent.Executor { it.run() })
        return result
    }
    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder()
        .setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS).build()).build()
    private suspend fun allowedDownloads(): List<AudioDownload> {
        val connection = withContext(Dispatchers.IO) { app.connections.read() }
        return app.database.audio().completed().filter { connection == null || it.accountId == connection.accountId }
    }
    private suspend fun browse(parent: String): List<MediaItem> {
        if (parent == "root") return listOf(folder("continue", "Continue listening"), folder("downloaded", "Downloaded"))
        val available = allowedDownloads()
        if (parent in setOf("continue", "downloaded")) return available.take(100).mapNotNull { entry ->
            val progress = app.database.library().progress(entry.accountId, entry.itemId)
            if (parent == "continue" && (progress == null || progress.completed || progress.partIndex == 0 && progress.positionMs <= 5000)) return@mapNotNull null
            if (app.audio.offline(entry.accountId, entry.itemId) == null) return@mapNotNull null
            val source = app.audio.source(entry.accountId, entry.itemId)
            folder("book|${entry.accountId}|${entry.itemId}", source.title)
        }
        val keys = parent.split('|'); require(keys.size == 3 && keys[0] == "book")
        val entry = available.single { it.accountId == keys[1] && it.itemId == keys[2] }
        val resolution = app.audio.offline(entry.accountId, entry.itemId) ?: return emptyList()
        return resolution.streams.map { part -> MediaItem.Builder().setMediaId("offline|${entry.accountId}|${entry.itemId}|${part.id}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(part.title).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).build()).build() }
    }
    private suspend fun searchOffline(query: String): List<MediaItem> {
        require(query.length <= 500)
        val books = browse("downloaded").filter { it.mediaMetadata.title?.toString()?.contains(query, true) == true }
        return books.mapNotNull { book ->
            val key = book.mediaId.split('|')
            val progress = app.database.library().progress(key[1], key[2])
            val children = browse(book.mediaId)
            val part = children.firstOrNull { it.mediaId.endsWith("|${progress?.partId}") } ?: children.firstOrNull() ?: return@mapNotNull null
            part.buildUpon().setMediaMetadata(part.mediaMetadata.buildUpon().setTitle(book.mediaMetadata.title).build()).build()
        }
    }
    private inner class BrowserCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.uid != applicationInfo.uid && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS).build()
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder("root", "DebridBooks"), params))
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            require(page >= 0 && pageSize > 0 && page.toLong() * pageSize <= Int.MAX_VALUE)
            LibraryResult.ofItemList(browse(parentId).drop(page * pageSize).take(pageSize.coerceAtMost(100)), params)
        }
        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = future {
            val found = searchOffline(query)
            session.notifySearchResultChanged(browser, query, found.size, params)
            LibraryResult.ofVoid(params)
        }
        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            require(page >= 0 && pageSize > 0 && page.toLong() * pageSize <= Int.MAX_VALUE)
            LibraryResult.ofItemList(searchOffline(query).drop(page * pageSize).take(pageSize.coerceAtMost(100)), params)
        }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = future {
            val item = when {
                mediaId == "root" -> folder("root", "DebridBooks")
                mediaId in setOf("continue", "downloaded") -> browse("root").single { it.mediaId == mediaId }
                mediaId.startsWith("book|") -> browse("downloaded").single { it.mediaId == mediaId }
                mediaId.startsWith("offline|") -> { val key = mediaId.split('|'); require(key.size == 4); browse("book|${key[1]}|${key[2]}").single { it.mediaId == mediaId } }
                else -> error("Unknown library item")
            }
            LibraryResult.ofItem(item, null)
        }
        override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = Futures.immediateFuture(LibraryResult.ofVoid(params))
        override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (controller.uid == applicationInfo.uid) return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
            return future {
                require(mediaItems.size == 1)
                val keys = mediaItems.single().mediaId.split('|')
                require(keys.size == 4 && keys[0] == "offline")
                val entry = allowedDownloads().single { it.accountId == keys[1] && it.itemId == keys[2] }
                val resolution = app.audio.offline(entry.accountId, entry.itemId) ?: error("Offline unavailable")
                val item = app.audio.source(entry.accountId, entry.itemId)
                val queue = audioQueue(entry.accountId, entry.itemId, item, resolution)
                val index = queue.indexOfFirst { it.mediaId == keys[3] }; require(index >= 0)
                val progress = app.database.library().progress(entry.accountId, entry.itemId)
                MediaSession.MediaItemsWithStartPosition(queue, index, if (progress?.partId == keys[3]) (progress.positionMs - 5000).coerceAtLeast(0) else 0)
            }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onDestroy() {
        listening(); checkpoint(); writes.close(); app.playbackRuntime.handler = null
        session?.let { it.player.release(); it.release() }; session = null
        super.onDestroy()
    }
}
