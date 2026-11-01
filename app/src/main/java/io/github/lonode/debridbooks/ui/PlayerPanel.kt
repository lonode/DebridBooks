package io.github.lonode.debridbooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import io.github.lonode.debridbooks.DebridApplication
import io.github.lonode.debridbooks.data.*
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.lonode.debridbooks.R
import io.github.lonode.debridbooks.playback.*
import kotlinx.coroutines.launch
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import io.github.lonode.debridbooks.network.fileSizeLabel

internal data class PlaybackSnapshot(val title: String = "", val author: String = "", val part: String = "", val artwork: String? = null,
    val identity: String = "", val position: Long = 0, val duration: Long = 0, val playing: Boolean = false,
    val fileSize: Long? = null, val index: Int = 0, val count: Int = 0, val buffering: Boolean = false, val failed: Boolean = false, val speed: Float = 1f,
    val chapterContext: String? = null, val account: String = "", val itemId: String = "",
    val parts: List<String> = emptyList(), val durations: Map<String, Long> = emptyMap(), val ended: Boolean = false,
    val overallProgress: AudiobookProgress? = null)
private fun snapshot(player: Player?): PlaybackSnapshot {
    player ?: return PlaybackSnapshot()
    val item = player.currentMediaItem ?: return PlaybackSnapshot()
    val meta = item.mediaMetadata
    val label = chapterLabel(meta.subtitle?.toString().orEmpty(), meta.title?.toString().orEmpty(), player.currentMediaItemIndex + 1)
    return PlaybackSnapshot(meta.title?.toString().orEmpty(), meta.artist?.toString().orEmpty(), label.title,
        meta.artworkUri?.toString(), meta.extras?.getString("item").orEmpty() + item.mediaId, player.currentPosition.coerceAtLeast(0),
        player.duration.coerceAtLeast(0), player.playWhenReady, meta.extras?.getLong("fileSize", 0)?.takeIf { it > 0 }, player.currentMediaItemIndex, player.mediaItemCount,
        player.playbackState == Player.STATE_BUFFERING, player.playerError != null, player.playbackParameters.speed,
        meta.extras?.getString("chapterContext") ?: label.context,
        meta.extras?.getString("account").orEmpty(), meta.extras?.getString("item").orEmpty(),
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }, audioPartDurations(player), player.playbackState == Player.STATE_ENDED)
}
@Composable
fun PlayerPanel(controller: MediaController?, onSpeed: (Float) -> Unit, onSearch: () -> Unit) {
    val app = LocalContext.current.applicationContext as DebridApplication
    val preferences by remember(app) { app.preferences.preferences }.collectAsState(initial = ListeningPreferences())
    val runtime by app.playbackRuntime.state.collectAsState()
    val personal by remember(app) { app.database.audio().observePersonal() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var invalidTime by remember { mutableStateOf(false) }
    var embedded by remember { mutableStateOf<List<AudioChapter>>(emptyList()) }
    var state by remember(controller) { mutableStateOf(snapshot(controller)) }
    var chapters by remember(controller) { mutableStateOf(chapterQueue(controller)) }
    var showChapters by remember(controller) { mutableStateOf(false) }
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state = snapshot(player)
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) chapters = chapterQueue(player)
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    LaunchedEffect(controller) { while (true) { state = snapshot(controller); delay(500) } }
    LaunchedEffect(state.identity) {
        embedded = emptyList()
        val media = controller?.currentMediaItem ?: return@LaunchedEffect
        val extras = media.mediaMetadata.extras ?: return@LaunchedEffect
        val account = extras.getString("account") ?: return@LaunchedEffect
        val item = extras.getString("item") ?: return@LaunchedEffect
        val uri = media.localConfiguration?.uri?.toString() ?: return@LaunchedEffect
        embedded = app.chapters.chapters(account, item, media.mediaId, uri).orEmpty()
    }
    fun jump(part: String, position: Long) {
        if (app.playbackRuntime.handler != null) app.playbackRuntime.send(PlaybackAction.Jump(part, position))
        else controller?.let { player -> val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == part }; if (index != null) player.seekTo(index, position) }
    }
    val embeddedIndex = chapterAt(embedded, state.position)
    val start = if (preferences.options.chapterScrub) embedded.getOrNull(embeddedIndex)?.startMs ?: 0 else 0
    val end = if (preferences.options.chapterScrub) embedded.getOrNull(embeddedIndex + 1)?.startMs ?: state.duration else state.duration
    val chapterDuration = (end - start).coerceAtLeast(0)
    if (state.count == 0) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            Text("Nothing playing yet", style = MaterialTheme.typography.headlineSmall)
            Text("Choose an audiobook from Search or resume one from your Library.")
            Button(onClick = onSearch) { Text("Find an audiobook") }
        }
        return
    }
    val embeddedRows = embedded.mapIndexed { index, chapter -> PlayerChapter(controller?.currentMediaItem?.mediaId.orEmpty(), chapter.title, null,
        chapter.startMs, embedded.getOrNull(index + 1)?.startMs ?: state.duration.takeIf { it > 0 }, true) }
    if (showChapters) ChapterListDialog(chapters, state.index, embeddedRows, embeddedIndex,
        onClose = { showChapters = false }, onSelect = { chapter ->
            val current = controller?.currentMediaItem?.mediaId == chapter.id && (!chapter.embedded || state.position >= chapter.startMs && (chapter.endMs == null || state.position < chapter.endMs))
            if (!current) jump(chapter.id, chapter.startMs)
            showChapters = false
        })
    val actions = PlayerActions(
        tool = { tool = it; input = ""; invalidTime = false },
        chapters = { showChapters = true },
        previous = {
            val target = if (embedded.isNotEmpty()) {
                if (state.position - embedded[embeddedIndex].startMs > 3000) embeddedIndex else embeddedIndex - 1
            } else -1
            if (target >= 0) controller?.currentMediaItem?.mediaId?.let { jump(it, embedded[target].startMs) }
            else if (embedded.isEmpty() && state.position > 3000) controller?.currentMediaItem?.mediaId?.let { jump(it, 0) }
            else controller?.seekToPreviousMediaItem()
        },
        next = {
            if (embeddedIndex + 1 < embedded.size) controller?.currentMediaItem?.mediaId?.let { jump(it, embedded[embeddedIndex + 1].startMs) }
            else controller?.seekToNextMediaItem()
        },
        play = {
            if (state.failed) app.playbackRuntime.send(PlaybackAction.Retry)
            else controller?.apply {
                if (playWhenReady) pause() else { if (playbackState == Player.STATE_ENDED) seekTo(0, 0); play() }
            }
        },
        seek = { position -> controller?.currentMediaItem?.mediaId?.let { jump(it, position) } },
        jump = { seconds -> controller?.let { player -> player.currentMediaItem?.mediaId?.let {
            jump(it, shiftedPosition(player.currentPosition, player.duration, seconds * 1000L))
        } } },
        undo = { app.playbackRuntime.send(PlaybackAction.Undo) },
        range = { scope.launch { app.preferences.setOptions { it.copy(chapterScrub = !it.chapterScrub) } } },
    )
    val saved = personal.firstOrNull { it.accountId == state.account && it.itemId == state.itemId }.extras()
    val overall = audiobookProgress(state.parts, saved.audioDurationsMs + state.durations,
        controller?.currentMediaItem?.mediaId, state.position, state.ended)
    PlayerLayout(state.copy(overallProgress = overall), runtime, embedded.getOrNull(embeddedIndex)?.title ?: state.part, start, end,
        preferences.options.chapterScrub, embedded.size > 1, preferences.options.remainingTime,
        state.index > 0 || embedded.isNotEmpty() || state.position > 3000,
        state.index + 1 < state.count || embeddedIndex + 1 < embedded.size, chapters.isNotEmpty(), actions)
    if (tool == "speed") AlertDialog(onDismissRequest = { tool = "" },
        title = { Text("Playback speed") }, text = {
            BoxWithConstraints {
                val columns = if (maxWidth >= 280.dp) 5 else 3
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 3.5f).chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { speed -> FilterChip(selected = state.speed == speed, modifier = Modifier.weight(1f).height(48.dp),
                                onClick = { controller?.setPlaybackSpeed(speed); onSpeed(speed); app.playbackRuntime.send(PlaybackAction.Speed(speed)); tool = "" },
                                label = { Text(speedLabel(speed)) }) }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { tool = "" }) { Text("Close") } })
    else if (tool == "info") AlertDialog(onDismissRequest = { tool = "" }, title = { Text("Playback details") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.title, style = MaterialTheme.typography.titleMedium)
            if (state.author.isNotBlank()) Text(state.author)
            Text(state.part)
            state.chapterContext?.let { Text(it) }
            Text(stringResource(R.string.player_audio_file_position, state.index + 1, state.count))
            state.fileSize?.let { Text("File size: ${fileSizeLabel(it)}") }
            if (runtime.message.isNotEmpty()) Text(runtime.message)
        } }, confirmButton = { TextButton(onClick = { tool = "" }) { Text("Close") } })
    else if (tool == "car") Dialog(onDismissRequest = { tool = "" }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.title, style = MaterialTheme.typography.headlineSmall)
                Text(state.part)
                Button(modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), onClick = { controller?.let { if (it.playWhenReady) it.pause() else it.play() } }) { Text(if (state.playing) "Pause" else "Play", style = MaterialTheme.typography.headlineMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { controller?.currentMediaItem?.mediaId?.let { jump(it, shiftedPosition(state.position, state.duration, -30000)) } }) { Text("−30s") }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { controller?.currentMediaItem?.mediaId?.let { jump(it, shiftedPosition(state.position, state.duration, 30000)) } }) { Text("+30s") }
                }
                TextButton(onClick = { tool = "" }) { Text("Exit car mode") }
            }
        }
    } else if (tool.isNotEmpty()) AlertDialog(onDismissRequest = { tool = "" },
        title = { Text(when (tool) { "time" -> "Go to time in this file"; "bookmark" -> "Save bookmark"; else -> "Sleep timer" }) },
        text = {
            if (tool == "sleep") Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val timers = listOf("15 minutes" to PlaybackAction.Sleep(15), "30 minutes" to PlaybackAction.Sleep(30),
                    "45 minutes" to PlaybackAction.Sleep(45), "60 minutes" to PlaybackAction.Sleep(60),
                    "End of file" to PlaybackAction.Sleep(endOfPart = true), "Turn off" to PlaybackAction.Sleep())
                timers.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (label, action) -> TextButton(modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            onClick = { app.playbackRuntime.send(action); tool = "" }) {
                            Text(label, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        } }
                    }
                }
            } else Column {
                OutlinedTextField(input, { input = it.take(if (tool == "time") 12 else 2000) }, label = { Text(if (tool == "time") "hh:mm:ss or seconds" else "Optional note") }, isError = invalidTime && tool == "time")
                if (invalidTime && tool == "time") Text("Enter a valid time, for example 1:23:45.")
            }
        }, confirmButton = { if (tool != "sleep") TextButton(onClick = {
            if (tool == "time") { val target = parsePlaybackTime(input); if (target == null) invalidTime = true else { controller?.currentMediaItem?.mediaId?.let { jump(it, if (state.duration > 0) target.coerceAtMost(state.duration) else target) }; tool = "" } }
            else { app.playbackRuntime.send(PlaybackAction.Bookmark(input)); input = ""; tool = "" }
        }) { Text(if (tool == "time") "Go" else "Save") } }, dismissButton = { TextButton(onClick = { tool = ""; input = "" }) { Text("Close") } })
}
internal fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}


@Composable
private fun ChapterListDialog(chapters: List<PlayerChapter>, currentIndex: Int, embedded: List<PlayerChapter>, currentEmbedded: Int,
    onClose: () -> Unit, onSelect: (PlayerChapter) -> Unit) {
    var mode by remember { mutableStateOf(embedded.isNotEmpty()) }
    var query by remember { mutableStateOf("") }
    var scrollRequest by remember { mutableIntStateOf(0) }
    val rows = if (mode) embedded else chapters
    val current = if (mode) currentEmbedded else currentIndex
    val visible = remember(rows, query) { rows.withIndex().filter { query.isBlank() || it.value.title.contains(query, true) || it.value.context?.contains(query, true) == true || "${it.index + 1}" == query } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))
    LaunchedEffect(scrollRequest, mode) { if (rows.isNotEmpty()) listState.scrollToItem(current.coerceIn(0, rows.lastIndex)) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (mode) "Chapters in this file" else "Audio files"} (${rows.size})", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                }
                if (embedded.isNotEmpty()) Row {
                    FilterChip(selected = mode, onClick = { mode = true; query = "" }, label = { Text("Embedded chapters") })
                    FilterChip(selected = !mode, onClick = { mode = false; query = "" }, label = { Text("Audio files") })
                }
                OutlinedTextField(query, { query = it }, label = { Text("Find a chapter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = current in rows.indices, onClick = { query = ""; scrollRequest++ }) { Text(stringResource(R.string.player_current_chapter)) }
                LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(visible, key = { "${it.value.id}:${it.value.startMs}:${it.index}" }) { indexed ->
                        val index = indexed.index; val chapter = indexed.value; val selectedChapter = index == current
                        Surface(onClick = { onSelect(chapter) }, modifier = Modifier.fillMaxWidth().semantics { selected = selectedChapter }, shape = MaterialTheme.shapes.medium,
                            color = if (selectedChapter) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                            Row(Modifier.padding(12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${index + 1}", style = MaterialTheme.typography.titleMedium)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(chapter.title.ifBlank { "Chapter ${index + 1}" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    chapter.context?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    if (chapter.embedded) Text(playbackTime(chapter.startMs), style = MaterialTheme.typography.bodySmall)
                                    if (selectedChapter) Text(stringResource(R.string.player_current_chapter), style = MaterialTheme.typography.labelMedium)
                                    chapter.fileSize?.let { Text(fileSizeLabel(it), style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
